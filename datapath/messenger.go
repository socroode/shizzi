package datapath

import (
	"encoding/json"
	"fmt"
	"html"
	"io"
	"net"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	messengerMaxMessages     = 5000
	messengerMaxEvents       = 10000
	messengerMaxGroups       = 100
	messengerMaxGroupMembers = 20
	messengerMaxGroupCall    = 6
	messengerMaxTextLength   = 2000
	messengerStatePath        = "/data/local/tmp/shizzi-messenger-v1.json"
)

type messengerIdentity struct {
	Number string `json:"number"`
	Name   string `json:"name"`
}

type messengerContact struct {
	Number string `json:"number"`
	Name   string `json:"name"`
	Online bool   `json:"online"`
	Blocked bool  `json:"blocked,omitempty"`
}

type messengerMessage struct {
	ID              int64  `json:"id"`
	From            string `json:"from"`
	To              string `json:"to,omitempty"`
	GroupID         string `json:"groupId,omitempty"`
	Text            string `json:"text"`
	CreatedAtMillis int64  `json:"createdAtMillis"`
}

type messengerGroup struct {
	ID              string
	Name            string
	Owner           string
	Admins          map[string]bool
	Members         map[string]bool
	CreatedAtMillis int64
}

type messengerRoom struct {
	CallID          string
	GroupID         string
	Media           string
	Members         map[string]bool
	CreatedAtMillis int64
}

type messengerGroupPayload struct {
	ID              string   `json:"id"`
	Name            string   `json:"name"`
	Owner           string   `json:"owner"`
	Admins          []string `json:"admins"`
	Members         []string `json:"members"`
	CreatedAtMillis int64    `json:"createdAtMillis"`
}

type messengerEvent struct {
	ID              int64  `json:"id"`
	Kind            string `json:"kind"`
	From            string `json:"from"`
	To              string `json:"to"`
	CallID          string `json:"callId,omitempty"`
	Media           string `json:"media,omitempty"`
	GroupID         string `json:"groupId,omitempty"`
	SDP             string `json:"sdp,omitempty"`
	Candidate       string `json:"candidate,omitempty"`
	SDPMid          string `json:"sdpMid,omitempty"`
	SDPMLineIndex   int    `json:"sdpMLineIndex,omitempty"`
	CreatedAtMillis int64  `json:"createdAtMillis"`
}

type messengerPersistentState struct {
	Version       int                     `json:"version"`
	NextMessageID int64                   `json:"nextMessageId"`
	Messages      []messengerMessage      `json:"messages"`
	Groups        []messengerGroupPayload `json:"groups"`
	Blocks        map[string][]string      `json:"blocks"`
}

type messengerHub struct {
	mu            sync.Mutex
	nextMessageID int64
	nextEventID   int64
	messages      []messengerMessage
	groups        map[string]*messengerGroup
	rooms         map[string]*messengerRoom
	events        []messengerEvent
	blocks        map[string]map[string]bool
	recentSends   map[string][]int64
	persistSignal  chan struct{}
}

var shizziMessenger = newMessengerHub()

func newMessengerHub() *messengerHub {
	hub := &messengerHub{
		groups:        make(map[string]*messengerGroup),
		rooms:         make(map[string]*messengerRoom),
		blocks:        make(map[string]map[string]bool),
		recentSends:   make(map[string][]int64),
		persistSignal: make(chan struct{}, 1),
	}
	hub.loadPersistentState()
	go hub.persistenceLoop()
	return hub
}

func (h *messengerHub) loadPersistentState() {
	raw, err := os.ReadFile(messengerStatePath)
	if err != nil || len(raw) == 0 {
		return
	}
	var state messengerPersistentState
	if json.Unmarshal(raw, &state) != nil || state.Version != 1 {
		return
	}

	h.nextMessageID = state.NextMessageID
	h.messages = append([]messengerMessage(nil), state.Messages...)
	if len(h.messages) > messengerMaxMessages {
		h.messages = append([]messengerMessage(nil), h.messages[len(h.messages)-messengerMaxMessages:]...)
	}
	for _, payload := range state.Groups {
		if payload.ID == "" || payload.Owner == "" {
			continue
		}
		group := &messengerGroup{
			ID: payload.ID,
			Name: payload.Name,
			Owner: payload.Owner,
			Admins: make(map[string]bool),
			Members: make(map[string]bool),
			CreatedAtMillis: payload.CreatedAtMillis,
		}
		for _, account := range payload.Admins {
			if account != "" {
				group.Admins[account] = true
			}
		}
		for _, account := range payload.Members {
			if account != "" {
				group.Members[account] = true
			}
		}
		group.Members[group.Owner] = true
		group.Admins[group.Owner] = true
		h.groups[group.ID] = group
	}
	for owner, values := range state.Blocks {
		if owner == "" {
			continue
		}
		set := make(map[string]bool)
		for _, account := range values {
			if account != "" {
				set[account] = true
			}
		}
		if len(set) > 0 {
			h.blocks[owner] = set
		}
	}
}

func (h *messengerHub) markPersistentDirtyLocked() {
	select {
	case h.persistSignal <- struct{}{}:
	default:
	}
}

func (h *messengerHub) persistenceLoop() {
	for range h.persistSignal {
		// Coalesce bursts such as group creation + several membership events.
		time.Sleep(150 * time.Millisecond)
		for {
			select {
			case <-h.persistSignal:
				continue
			default:
			}
			break
		}
		h.persistNow()
	}
}

func (h *messengerHub) persistentStateLocked() messengerPersistentState {
	groups := make([]messengerGroupPayload, 0, len(h.groups))
	for _, group := range h.groups {
		groups = append(groups, messengerGroupPayloadFor(group))
	}
	sort.Slice(groups, func(i, j int) bool { return groups[i].ID < groups[j].ID })

	blocks := make(map[string][]string, len(h.blocks))
	for owner, values := range h.blocks {
		items := make([]string, 0, len(values))
		for account := range values {
			items = append(items, account)
		}
		sort.Strings(items)
		blocks[owner] = items
	}

	return messengerPersistentState{
		Version: 1,
		NextMessageID: h.nextMessageID,
		Messages: append([]messengerMessage(nil), h.messages...),
		Groups: groups,
		Blocks: blocks,
	}
}

func (h *messengerHub) persistNow() {
	h.mu.Lock()
	state := h.persistentStateLocked()
	h.mu.Unlock()

	raw, err := json.Marshal(state)
	if err != nil {
		return
	}
	temp := messengerStatePath + ".tmp"
	if err := os.WriteFile(temp, raw, 0o600); err != nil {
		return
	}
	_ = os.Rename(temp, messengerStatePath)
}

func (m *TrafficManager) messengerIdentity(ip string) (messengerIdentity, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()

	authorization := m.portalAuthorized[ip]
	if authorization == nil {
		return messengerIdentity{}, false
	}
	account, ok := m.portalAccounts[authorization.AccountNumber]
	if !ok || !account.Enabled {
		return messengerIdentity{}, false
	}
	return messengerIdentity{Number: account.Number, Name: account.Name}, true
}

func (m *TrafficManager) messengerAccountEnabled(raw string) bool {
	number := normalizeAccountNumber(raw)
	m.mu.Lock()
	defer m.mu.Unlock()
	account, ok := m.portalAccounts[number]
	return ok && account.Enabled
}

func (m *TrafficManager) messengerContacts(me string) []messengerContact {
	m.mu.Lock()
	defer m.mu.Unlock()

	online := make(map[string]bool)
	for _, authorization := range m.portalAuthorized {
		if authorization != nil {
			online[authorization.AccountNumber] = true
		}
	}

	shizziMessenger.mu.Lock()
	blockedByMe := shizziMessenger.blocks[me]
	shizziMessenger.mu.Unlock()

	result := make([]messengerContact, 0, len(m.portalAccounts))
	for number, account := range m.portalAccounts {
		if number == me || !account.Enabled {
			continue
		}
		result = append(result, messengerContact{
			Number:  number,
			Name:    account.Name,
			Online:  online[number],
			Blocked: blockedByMe != nil && blockedByMe[number],
		})
	}
	sort.Slice(result, func(i, j int) bool {
		if result[i].Online != result[j].Online {
			return result[i].Online
		}
		if result[i].Name != result[j].Name {
			return strings.ToLower(result[i].Name) < strings.ToLower(result[j].Name)
		}
		return result[i].Number < result[j].Number
	})
	return result
}

func (h *messengerHub) blockedLocked(a, b string) bool {
	if h.blocks[a] != nil && h.blocks[a][b] {
		return true
	}
	return h.blocks[b] != nil && h.blocks[b][a]
}

func (h *messengerHub) allowSendLocked(account string, now int64) bool {
	cutoff := now - 60_000
	items := h.recentSends[account]
	write := items[:0]
	for _, stamp := range items {
		if stamp >= cutoff {
			write = append(write, stamp)
		}
	}
	items = write
	if len(items) >= 30 {
		h.recentSends[account] = items
		return false
	}
	h.recentSends[account] = append(items, now)
	return true
}

func (h *messengerHub) appendMessageLocked(message messengerMessage) messengerMessage {
	h.nextMessageID++
	message.ID = h.nextMessageID
	h.messages = append(h.messages, message)
	if len(h.messages) > messengerMaxMessages {
		h.messages = append([]messengerMessage(nil), h.messages[len(h.messages)-messengerMaxMessages:]...)
	}
	h.markPersistentDirtyLocked()
	return message
}

func (h *messengerHub) appendEventLocked(event messengerEvent) messengerEvent {
	h.nextEventID++
	event.ID = h.nextEventID
	h.events = append(h.events, event)
	if len(h.events) > messengerMaxEvents {
		h.events = append([]messengerEvent(nil), h.events[len(h.events)-messengerMaxEvents:]...)
	}
	return event
}

func messengerGroupPayloadFor(group *messengerGroup) messengerGroupPayload {
	admins := make([]string, 0, len(group.Admins))
	for account := range group.Admins {
		admins = append(admins, account)
	}
	members := make([]string, 0, len(group.Members))
	for account := range group.Members {
		members = append(members, account)
	}
	sort.Strings(admins)
	sort.Strings(members)
	return messengerGroupPayload{
		ID:              group.ID,
		Name:            group.Name,
		Owner:           group.Owner,
		Admins:          admins,
		Members:         members,
		CreatedAtMillis: group.CreatedAtMillis,
	}
}

func decodeMessengerBody(request *http.Request, target any) error {
	decoder := json.NewDecoder(io.LimitReader(request.Body, 128*1024))
	return decoder.Decode(target)
}

func messengerBadRequest(conn net.Conn, message string) {
	writeJSONStatus(conn, "400 Bad Request", map[string]any{"ok": false, "message": message})
}

func messengerForbidden(conn net.Conn, message string) {
	writeJSONStatus(conn, "403 Forbidden", map[string]any{"ok": false, "message": message})
}

func messengerNotFound(conn net.Conn, message string) {
	writeJSONStatus(conn, "404 Not Found", map[string]any{"ok": false, "message": message})
}

func (m *TrafficManager) serveMessengerAPI(
	conn net.Conn,
	request *http.Request,
	clientIP string,
) bool {
	if !strings.HasPrefix(request.URL.Path, "/api/v1/messenger/") {
		return false
	}
	if !m.messengerModuleEnabled() {
		writeJSONStatus(conn, "404 Not Found", map[string]any{
			"ok": false, "message": "Shizzi Messenger est désactivé sur ce routeur.",
		})
		return true
	}

	me, ok := m.messengerIdentity(clientIP)
	if !ok {
		writeJSONStatus(conn, "401 Unauthorized", map[string]any{
			"ok": false, "message": "Compte Shizzi requis.",
		})
		return true
	}

	path := strings.TrimPrefix(request.URL.Path, "/api/v1/messenger/")
	path = strings.Trim(path, "/")

	switch {
	case request.Method == http.MethodGet && path == "me":
		writeJSONStatus(conn, "200 OK", map[string]any{"ok": true, "account": me})
		return true

	case request.Method == http.MethodGet && path == "contacts":
		writeJSONStatus(conn, "200 OK", map[string]any{
			"ok": true, "contacts": m.messengerContacts(me.Number),
		})
		return true

	case path == "messages" && request.Method == http.MethodGet:
		peer := normalizeAccountNumber(request.URL.Query().Get("peer"))
		after, _ := strconv.ParseInt(request.URL.Query().Get("after"), 10, 64)
		if peer == "" {
			messengerBadRequest(conn, "Contact manquant.")
			return true
		}
		shizziMessenger.mu.Lock()
		items := make([]messengerMessage, 0)
		for _, message := range shizziMessenger.messages {
			if message.ID <= after || message.GroupID != "" {
				continue
			}
			if (message.From == me.Number && message.To == peer) ||
				(message.From == peer && message.To == me.Number) {
				items = append(items, message)
			}
		}
		shizziMessenger.mu.Unlock()
		writeJSONStatus(conn, "200 OK", map[string]any{"ok": true, "messages": items})
		return true

	case path == "messages" && request.Method == http.MethodPost:
		var body struct {
			To   string `json:"to"`
			Text string `json:"text"`
		}
		if err := decodeMessengerBody(request, &body); err != nil {
			messengerBadRequest(conn, "Message invalide.")
			return true
		}
		to := normalizeAccountNumber(body.To)
		text := strings.TrimSpace(body.Text)
		if to == "" || to == me.Number || !m.messengerAccountEnabled(to) {
			messengerBadRequest(conn, "Destinataire invalide.")
			return true
		}
		if text == "" || len([]rune(text)) > messengerMaxTextLength {
			messengerBadRequest(conn, "Le message doit contenir entre 1 et 2000 caractères.")
			return true
		}
		now := time.Now().UnixMilli()
		shizziMessenger.mu.Lock()
		if shizziMessenger.blockedLocked(me.Number, to) {
			shizziMessenger.mu.Unlock()
			messengerForbidden(conn, "Communication bloquée entre ces comptes.")
			return true
		}
		if !shizziMessenger.allowSendLocked(me.Number, now) {
			shizziMessenger.mu.Unlock()
			writeJSONStatus(conn, "429 Too Many Requests", map[string]any{
				"ok": false, "message": "Trop de messages. Réessayez dans une minute.",
			})
			return true
		}
		message := shizziMessenger.appendMessageLocked(messengerMessage{
			From: me.Number, To: to, Text: text, CreatedAtMillis: now,
		})
		shizziMessenger.appendEventLocked(messengerEvent{
			Kind: "message", From: me.Number, To: to, CreatedAtMillis: now,
		})
		shizziMessenger.mu.Unlock()
		writeJSONStatus(conn, "200 OK", map[string]any{"ok": true, "message": message})
		return true

	case path == "groups" && request.Method == http.MethodGet:
		shizziMessenger.mu.Lock()
		groups := make([]messengerGroupPayload, 0)
		for _, group := range shizziMessenger.groups {
			if group.Members[me.Number] {
				groups = append(groups, messengerGroupPayloadFor(group))
			}
		}
		shizziMessenger.mu.Unlock()
		sort.Slice(groups, func(i, j int) bool {
			return strings.ToLower(groups[i].Name) < strings.ToLower(groups[j].Name)
		})
		writeJSONStatus(conn, "200 OK", map[string]any{"ok": true, "groups": groups})
		return true

	case path == "groups" && request.Method == http.MethodPost:
		var body struct {
			Name    string   `json:"name"`
			Members []string `json:"members"`
		}
		if err := decodeMessengerBody(request, &body); err != nil {
			messengerBadRequest(conn, "Groupe invalide.")
			return true
		}
		name := strings.TrimSpace(body.Name)
		if name == "" || len([]rune(name)) > 64 {
			messengerBadRequest(conn, "Nom de groupe invalide.")
			return true
		}
		members := map[string]bool{me.Number: true}
		for _, raw := range body.Members {
			number := normalizeAccountNumber(raw)
			if number != "" && m.messengerAccountEnabled(number) {
				members[number] = true
			}
		}
		if len(members) > messengerMaxGroupMembers {
			messengerBadRequest(conn, "20 membres maximum par groupe.")
			return true
		}
		shizziMessenger.mu.Lock()
		if len(shizziMessenger.groups) >= messengerMaxGroups {
			shizziMessenger.mu.Unlock()
			messengerBadRequest(conn, "Nombre maximum de groupes atteint.")
			return true
		}
		id := randomHex(6)
		group := &messengerGroup{
			ID: id, Name: name, Owner: me.Number,
			Admins: map[string]bool{me.Number: true},
			Members: members, CreatedAtMillis: time.Now().UnixMilli(),
		}
		shizziMessenger.groups[id] = group
		shizziMessenger.markPersistentDirtyLocked()
		payload := messengerGroupPayloadFor(group)
		now := time.Now().UnixMilli()
		for member := range members {
			if member == me.Number {
				continue
			}
			shizziMessenger.appendEventLocked(messengerEvent{
				Kind: "group-update", From: me.Number, To: member,
				GroupID: id, CreatedAtMillis: now,
			})
		}
		shizziMessenger.mu.Unlock()
		writeJSONStatus(conn, "200 OK", map[string]any{"ok": true, "group": payload})
		return true

	case strings.HasPrefix(path, "groups/"):
		parts := strings.Split(path, "/")
		if len(parts) < 3 {
			messengerNotFound(conn, "Route groupe inconnue.")
			return true
		}
		groupID := strings.TrimSpace(parts[1])
		action := parts[2]

		if action == "messages" && request.Method == http.MethodGet {
			after, _ := strconv.ParseInt(request.URL.Query().Get("after"), 10, 64)
			shizziMessenger.mu.Lock()
			group := shizziMessenger.groups[groupID]
			if group == nil || !group.Members[me.Number] {
				shizziMessenger.mu.Unlock()
				messengerForbidden(conn, "Vous n'êtes pas membre de ce groupe.")
				return true
			}
			items := make([]messengerMessage, 0)
			for _, message := range shizziMessenger.messages {
				if message.ID > after && message.GroupID == groupID {
					items = append(items, message)
				}
			}
			shizziMessenger.mu.Unlock()
			writeJSONStatus(conn, "200 OK", map[string]any{"ok": true, "messages": items})
			return true
		}

		if action == "messages" && request.Method == http.MethodPost {
			var body struct {
				Text string `json:"text"`
			}
			if err := decodeMessengerBody(request, &body); err != nil {
				messengerBadRequest(conn, "Message invalide.")
				return true
			}
			text := strings.TrimSpace(body.Text)
			if text == "" || len([]rune(text)) > messengerMaxTextLength {
				messengerBadRequest(conn, "Le message doit contenir entre 1 et 2000 caractères.")
				return true
			}
			now := time.Now().UnixMilli()
			shizziMessenger.mu.Lock()
			group := shizziMessenger.groups[groupID]
			if group == nil || !group.Members[me.Number] {
				shizziMessenger.mu.Unlock()
				messengerForbidden(conn, "Vous n'êtes pas membre de ce groupe.")
				return true
			}
			if !shizziMessenger.allowSendLocked(me.Number, now) {
				shizziMessenger.mu.Unlock()
				writeJSONStatus(conn, "429 Too Many Requests", map[string]any{
					"ok": false, "message": "Trop de messages. Réessayez dans une minute.",
				})
				return true
			}
			message := shizziMessenger.appendMessageLocked(messengerMessage{
				From: me.Number, GroupID: groupID, Text: text, CreatedAtMillis: now,
			})
			for member := range group.Members {
				if member == me.Number {
					continue
				}
				shizziMessenger.appendEventLocked(messengerEvent{
					Kind: "group-message", From: me.Number, To: member,
					GroupID: groupID, CreatedAtMillis: now,
				})
			}
			shizziMessenger.mu.Unlock()
			writeJSONStatus(conn, "200 OK", map[string]any{"ok": true, "message": message})
			return true
		}

		if action == "members" && request.Method == http.MethodPost {
			var body struct {
				Account string `json:"account"`
				Action  string `json:"action"`
			}
			if err := decodeMessengerBody(request, &body); err != nil {
				messengerBadRequest(conn, "Modification invalide.")
				return true
			}
			target := normalizeAccountNumber(body.Account)
			shizziMessenger.mu.Lock()
			group := shizziMessenger.groups[groupID]
			if group == nil {
				shizziMessenger.mu.Unlock()
				messengerNotFound(conn, "Groupe introuvable.")
				return true
			}
			if group.Owner != me.Number && !group.Admins[me.Number] {
				shizziMessenger.mu.Unlock()
				messengerForbidden(conn, "Droits administrateur requis.")
				return true
			}
			if body.Action == "remove" {
				if target == group.Owner {
					shizziMessenger.mu.Unlock()
					messengerBadRequest(conn, "Le propriétaire ne peut pas être retiré.")
					return true
				}
				delete(group.Members, target)
				delete(group.Admins, target)
			} else {
				if !m.messengerAccountEnabled(target) {
					shizziMessenger.mu.Unlock()
					messengerBadRequest(conn, "Compte introuvable.")
					return true
				}
				if len(group.Members) >= messengerMaxGroupMembers {
					shizziMessenger.mu.Unlock()
					messengerBadRequest(conn, "20 membres maximum par groupe.")
					return true
				}
				group.Members[target] = true
			}
			shizziMessenger.markPersistentDirtyLocked()
			payload := messengerGroupPayloadFor(group)
			shizziMessenger.appendEventLocked(messengerEvent{
				Kind: "group-update", From: me.Number, To: target,
				GroupID: groupID, CreatedAtMillis: time.Now().UnixMilli(),
			})
			shizziMessenger.mu.Unlock()
			writeJSONStatus(conn, "200 OK", map[string]any{"ok": true, "group": payload})
			return true
		}

		messengerNotFound(conn, "Action groupe inconnue.")
		return true

	case path == "blocks" && request.Method == http.MethodGet:
		shizziMessenger.mu.Lock()
		numbers := make([]string, 0)
		for number := range shizziMessenger.blocks[me.Number] {
			numbers = append(numbers, number)
		}
		shizziMessenger.mu.Unlock()
		sort.Strings(numbers)
		writeJSONStatus(conn, "200 OK", map[string]any{"ok": true, "blocked": numbers})
		return true

	case path == "block" && request.Method == http.MethodPost:
		var body struct {
			Account string `json:"account"`
			Blocked bool   `json:"blocked"`
		}
		if err := decodeMessengerBody(request, &body); err != nil {
			messengerBadRequest(conn, "Blocage invalide.")
			return true
		}
		target := normalizeAccountNumber(body.Account)
		if target == "" || target == me.Number || !m.messengerAccountEnabled(target) {
			messengerBadRequest(conn, "Compte invalide.")
			return true
		}
		shizziMessenger.mu.Lock()
		if shizziMessenger.blocks[me.Number] == nil {
			shizziMessenger.blocks[me.Number] = make(map[string]bool)
		}
		if body.Blocked {
			shizziMessenger.blocks[me.Number][target] = true
		} else {
			delete(shizziMessenger.blocks[me.Number], target)
		}
		shizziMessenger.markPersistentDirtyLocked()
		shizziMessenger.mu.Unlock()
		writeJSONStatus(conn, "200 OK", map[string]any{"ok": true})
		return true

	case path == "events" && request.Method == http.MethodGet:
		after, _ := strconv.ParseInt(request.URL.Query().Get("after"), 10, 64)
		now := time.Now().UnixMilli()
		shizziMessenger.mu.Lock()
		items := make([]messengerEvent, 0)
		for _, event := range shizziMessenger.events {
			if event.ID <= after || event.To != me.Number {
				continue
			}
			if now-event.CreatedAtMillis > 10*60_000 {
				continue
			}
			items = append(items, event)
		}
		shizziMessenger.mu.Unlock()
		writeJSONStatus(conn, "200 OK", map[string]any{"ok": true, "events": items})
		return true

	case path == "room" && request.Method == http.MethodPost:
		var body struct {
			Action  string `json:"action"`
			CallID  string `json:"callId"`
			GroupID string `json:"groupId"`
			Media   string `json:"media"`
		}
		if err := decodeMessengerBody(request, &body); err != nil {
			messengerBadRequest(conn, "Salon d'appel invalide.")
			return true
		}
		action := strings.ToLower(strings.TrimSpace(body.Action))
		callID := strings.TrimSpace(body.CallID)
		groupID := strings.TrimSpace(body.GroupID)
		media := strings.ToLower(strings.TrimSpace(body.Media))
		if callID == "" || len(callID) > 96 || groupID == "" {
			messengerBadRequest(conn, "Identifiant d'appel de groupe invalide.")
			return true
		}
		if media != "video" {
			media = "audio"
		}
		shizziMessenger.mu.Lock()
		group := shizziMessenger.groups[groupID]
		if group == nil || !group.Members[me.Number] {
			shizziMessenger.mu.Unlock()
			messengerForbidden(conn, "Vous n'êtes pas membre de ce groupe.")
			return true
		}
		if action == "leave" {
			room := shizziMessenger.rooms[callID]
			if room != nil {
				delete(room.Members, me.Number)
				now := time.Now().UnixMilli()
				for member := range room.Members {
					shizziMessenger.appendEventLocked(messengerEvent{
						Kind: "room-leave", From: me.Number, To: member,
						CallID: callID, Media: room.Media, GroupID: room.GroupID,
						CreatedAtMillis: now,
					})
				}
				if len(room.Members) == 0 {
					delete(shizziMessenger.rooms, callID)
				}
			}
			shizziMessenger.mu.Unlock()
			writeJSONStatus(conn, "200 OK", map[string]any{"ok": true})
			return true
		}
		if action != "join" {
			shizziMessenger.mu.Unlock()
			messengerBadRequest(conn, "Action de salon inconnue.")
			return true
		}
		room := shizziMessenger.rooms[callID]
		if room == nil {
			room = &messengerRoom{
				CallID: callID, GroupID: groupID, Media: media,
				Members: make(map[string]bool), CreatedAtMillis: time.Now().UnixMilli(),
			}
			shizziMessenger.rooms[callID] = room
		}
		if room.GroupID != groupID {
			shizziMessenger.mu.Unlock()
			messengerForbidden(conn, "Ce salon appartient à un autre groupe.")
			return true
		}
		if !room.Members[me.Number] && len(room.Members) >= messengerMaxGroupCall {
			shizziMessenger.mu.Unlock()
			messengerBadRequest(conn, "Cet appel de groupe a déjà 6 participants.")
			return true
		}
		existing := make([]string, 0, len(room.Members))
		for member := range room.Members {
			if member != me.Number {
				existing = append(existing, member)
			}
		}
		sort.Strings(existing)
		room.Members[me.Number] = true
		now := time.Now().UnixMilli()
		for _, member := range existing {
			shizziMessenger.appendEventLocked(messengerEvent{
				Kind: "room-join", From: me.Number, To: member,
				CallID: callID, Media: room.Media, GroupID: groupID,
				CreatedAtMillis: now,
			})
		}
		shizziMessenger.mu.Unlock()
		writeJSONStatus(conn, "200 OK", map[string]any{
			"ok": true, "members": existing, "media": room.Media,
		})
		return true

	case path == "signal" && request.Method == http.MethodPost:
		var body struct {
			To            string `json:"to"`
			Kind          string `json:"kind"`
			CallID        string `json:"callId"`
			Media         string `json:"media"`
			GroupID       string `json:"groupId"`
			SDP           string `json:"sdp"`
			Candidate     string `json:"candidate"`
			SDPMid        string `json:"sdpMid"`
			SDPMLineIndex int    `json:"sdpMLineIndex"`
		}
		if err := decodeMessengerBody(request, &body); err != nil {
			messengerBadRequest(conn, "Signal invalide.")
			return true
		}
		to := normalizeAccountNumber(body.To)
		kind := strings.ToLower(strings.TrimSpace(body.Kind))
		callID := strings.TrimSpace(body.CallID)
		media := strings.ToLower(strings.TrimSpace(body.Media))
		if to == "" || to == me.Number || !m.messengerAccountEnabled(to) {
			messengerBadRequest(conn, "Destinataire invalide.")
			return true
		}
		switch kind {
		case "offer", "answer", "ice", "hangup", "decline":
		default:
			messengerBadRequest(conn, "Type de signal inconnu.")
			return true
		}
		if callID == "" || len(callID) > 96 {
			messengerBadRequest(conn, "Identifiant d'appel invalide.")
			return true
		}
		if media != "audio" && media != "video" {
			media = "audio"
		}
		shizziMessenger.mu.Lock()
		if shizziMessenger.blockedLocked(me.Number, to) {
			shizziMessenger.mu.Unlock()
			messengerForbidden(conn, "Communication bloquée entre ces comptes.")
			return true
		}
		if body.GroupID != "" {
			group := shizziMessenger.groups[body.GroupID]
			if group == nil || !group.Members[me.Number] || !group.Members[to] {
				shizziMessenger.mu.Unlock()
				messengerForbidden(conn, "Appel de groupe non autorisé.")
				return true
			}
		}
		event := shizziMessenger.appendEventLocked(messengerEvent{
			Kind: kind, From: me.Number, To: to, CallID: callID, Media: media,
			GroupID: strings.TrimSpace(body.GroupID), SDP: body.SDP,
			Candidate: body.Candidate, SDPMid: body.SDPMid,
			SDPMLineIndex: body.SDPMLineIndex, CreatedAtMillis: time.Now().UnixMilli(),
		})
		shizziMessenger.mu.Unlock()
		writeJSONStatus(conn, "200 OK", map[string]any{"ok": true, "event": event})
		return true
	}

	messengerNotFound(conn, "Route Messenger inconnue.")
	return true
}

func (m *TrafficManager) serveMessengerPage(
	conn net.Conn,
	method string,
	clientIP string,
) {
	me, ok := m.messengerIdentity(clientIP)
	if !ok {
		body := []byte(`<!doctype html><html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Shizzi Messenger</title></head><body style="font-family:system-ui;background:#07111f;color:white;padding:24px"><h1>Compte Shizzi requis</h1><p>Connectez d'abord votre compte sur le portail.</p><a style="color:#67e8f9" href="/">Ouvrir le portail Shizzi</a></body></html>`)
		header := fmt.Sprintf("HTTP/1.1 401 Unauthorized\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: %d\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n", len(body))
		_, _ = conn.Write([]byte(header))
		if method != http.MethodHead {
			_, _ = conn.Write(body)
		}
		return
	}

	page := fmt.Sprintf(`<!doctype html>
<html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Shizzi Messenger</title>
<style>
:root{color-scheme:dark;font-family:Inter,system-ui,-apple-system,sans-serif}*{box-sizing:border-box}
body{margin:0;background:#07111f;color:#f8fafc}header{position:sticky;top:0;background:#0f172a;padding:14px 16px;border-bottom:1px solid #ffffff18;z-index:2}
header strong{font-size:20px}header small{display:block;color:#94a3b8;margin-top:2px}
main{max-width:760px;margin:auto;padding:14px}.tabs{display:flex;gap:8px;margin-bottom:12px}.tabs button{flex:1}
button,input{font:inherit;border-radius:12px;border:0;padding:12px}button{background:#22d3ee;color:#06202a;font-weight:800}button.secondary{background:#1e293b;color:#e2e8f0}
.panel{background:#0f172a;border:1px solid #ffffff18;border-radius:18px;padding:14px;margin-bottom:12px}.hidden{display:none!important}
.list{display:grid;gap:8px}.item{padding:12px;background:#ffffff08;border-radius:14px;display:flex;align-items:center;gap:10px}.item .grow{flex:1}
.dot{width:9px;height:9px;border-radius:50%%;background:#475569}.dot.on{background:#34d399}
.chat{min-height:240px;max-height:52vh;overflow:auto;padding:8px;background:#020617;border-radius:14px}.msg{max-width:82%%;margin:7px 0;padding:9px 11px;border-radius:12px;background:#1e293b}.msg.mine{margin-left:auto;background:#164e63}
.msg small{display:block;color:#94a3b8;margin-top:4px}.composer{display:grid;grid-template-columns:1fr auto;gap:8px;margin-top:8px}.composer input{background:#111827;color:white;border:1px solid #334155}
.actions{display:flex;gap:7px;flex-wrap:wrap}.actions button{padding:9px 11px}.notice{padding:10px;border-radius:12px;background:#172554;color:#bfdbfe;margin-bottom:10px}
.call{border-color:#34d39955;background:#052e2b}.danger{background:#7f1d1d!important;color:white!important}
</style></head><body>
<header><strong>Shizzi Messenger</strong><small>%s · compte %s · réseau local</small></header>
<main>
<div id="incoming" class="panel call hidden"></div>
<div class="tabs"><button onclick="showTab('contacts')">Messages</button><button class="secondary" onclick="showTab('groups')">Groupes</button></div>
<section id="contacts" class="panel"><div class="notice">Messages et appels restent sur le Wi-Fi Shizzi. Appels vidéo 1↔1 en 480p, groupes en 360p (6 participants max en version test).</div><div id="contactList" class="list">Chargement…</div></section>
<section id="groups" class="panel hidden">
<div class="actions"><input id="groupName" placeholder="Nom du groupe" style="flex:1;background:#111827;color:white;border:1px solid #334155"><button onclick="createGroup()">Créer</button></div>
<div id="groupList" class="list" style="margin-top:12px">Chargement…</div></section>
<section id="conversation" class="panel hidden">
<div class="actions"><button class="secondary" onclick="closeConversation()">← Retour</button><strong id="conversationTitle" style="align-self:center"></strong><span style="flex:1"></span><button id="audioCall">☎ Audio</button><button id="videoCall">▣ 480p</button></div>
<div id="chat" class="chat"></div><div class="composer"><input id="messageText" maxlength="2000" placeholder="Votre message…"><button onclick="sendMessage()">Envoyer</button></div>
</section>
<section id="groupConversation" class="panel hidden">
<div class="actions"><button class="secondary" onclick="closeGroup()">← Retour</button><strong id="groupTitle" style="align-self:center"></strong><span style="flex:1"></span><button id="groupAudio">☎ Groupe</button><button id="groupVideo">▣ 360p</button></div>
<div id="groupChat" class="chat"></div><div class="composer"><input id="groupMessageText" maxlength="2000" placeholder="Message au groupe…"><button onclick="sendGroupMessage()">Envoyer</button></div>
</section>
</main>
<script>
const ME=%q; let currentPeer=null,currentGroup=null,lastDirect=0,lastGroup=0,lastEvent=0;
function showTab(id){for(const x of ['contacts','groups','conversation','groupConversation'])document.getElementById(x).classList.toggle('hidden',x!==id);if(id==='contacts')loadContacts();if(id==='groups')loadGroups();}
async function api(path,options){const r=await fetch(path,Object.assign({cache:'no-store'},options||{}));const j=await r.json().catch(()=>({ok:false,message:'Réponse invalide'}));if(!r.ok||j.ok===false)throw new Error(j.message||('HTTP '+r.status));return j;}
function esc(s){const d=document.createElement('div');d.textContent=s||'';return d.innerHTML}
async function loadContacts(){try{const j=await api('/api/v1/messenger/contacts');const root=document.getElementById('contactList');root.innerHTML='';j.contacts.forEach(c=>{const d=document.createElement('div');d.className='item';d.innerHTML='<span class="dot '+(c.online?'on':'')+'"></span><div class="grow"><strong>'+esc(c.name||('Compte '+c.number))+'</strong><small> '+esc(c.number)+(c.online?' · en ligne':' · hors ligne')+'</small></div>';const b=document.createElement('button');b.textContent='Ouvrir';b.onclick=()=>openConversation(c);d.appendChild(b);root.appendChild(d)});if(!j.contacts.length)root.textContent='Aucun autre compte.'}catch(e){document.getElementById('contactList').textContent=e.message}}
function openConversation(c){currentPeer=c;lastDirect=0;document.getElementById('conversationTitle').textContent=c.name||c.number;document.getElementById('audioCall').onclick=()=>startCall(c.number,false,'');document.getElementById('videoCall').onclick=()=>startCall(c.number,true,'');showTab('conversation');loadMessages();}
function closeConversation(){currentPeer=null;showTab('contacts')}
async function loadMessages(){if(!currentPeer)return;try{const j=await api('/api/v1/messenger/messages?peer='+encodeURIComponent(currentPeer.number)+'&after='+lastDirect);const root=document.getElementById('chat');j.messages.forEach(x=>{lastDirect=Math.max(lastDirect,x.id);const d=document.createElement('div');d.className='msg '+(x.from===ME?'mine':'');d.innerHTML='<div>'+esc(x.text)+'</div><small>'+new Date(x.createdAtMillis).toLocaleTimeString()+'</small>';root.appendChild(d)});if(j.messages.length)root.scrollTop=root.scrollHeight}catch(e){}}
async function sendMessage(){if(!currentPeer)return;const f=document.getElementById('messageText'),text=f.value.trim();if(!text)return;try{await api('/api/v1/messenger/messages',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({to:currentPeer.number,text})});f.value='';await loadMessages()}catch(e){alert(e.message)}}
async function loadGroups(){try{const j=await api('/api/v1/messenger/groups');const root=document.getElementById('groupList');root.innerHTML='';j.groups.forEach(g=>{const d=document.createElement('div');d.className='item';d.innerHTML='<div class="grow"><strong>'+esc(g.name)+'</strong><small>'+g.members.length+' membre(s)</small></div>';const b=document.createElement('button');b.textContent='Ouvrir';b.onclick=()=>openGroup(g);d.appendChild(b);root.appendChild(d)});if(!j.groups.length)root.textContent='Aucun groupe.'}catch(e){document.getElementById('groupList').textContent=e.message}}
async function createGroup(){const name=document.getElementById('groupName').value.trim();if(!name)return;const raw=prompt('Numéros de comptes à ajouter, séparés par des virgules (facultatif)','')||'';const members=raw.split(',').map(x=>x.trim()).filter(Boolean);try{await api('/api/v1/messenger/groups',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name,members})});document.getElementById('groupName').value='';loadGroups()}catch(e){alert(e.message)}}
function openGroup(g){currentGroup=g;lastGroup=0;document.getElementById('groupTitle').textContent=g.name;document.getElementById('groupAudio').onclick=()=>startGroupCall(g,false);document.getElementById('groupVideo').onclick=()=>startGroupCall(g,true);showTab('groupConversation');loadGroupMessages();}
function closeGroup(){currentGroup=null;showTab('groups')}
async function loadGroupMessages(){if(!currentGroup)return;try{const j=await api('/api/v1/messenger/groups/'+encodeURIComponent(currentGroup.id)+'/messages?after='+lastGroup);const root=document.getElementById('groupChat');j.messages.forEach(x=>{lastGroup=Math.max(lastGroup,x.id);const d=document.createElement('div');d.className='msg '+(x.from===ME?'mine':'');d.innerHTML='<strong>'+esc(x.from)+'</strong><div>'+esc(x.text)+'</div><small>'+new Date(x.createdAtMillis).toLocaleTimeString()+'</small>';root.appendChild(d)});if(j.messages.length)root.scrollTop=root.scrollHeight}catch(e){}}
async function sendGroupMessage(){if(!currentGroup)return;const f=document.getElementById('groupMessageText'),text=f.value.trim();if(!text)return;try{await api('/api/v1/messenger/groups/'+encodeURIComponent(currentGroup.id)+'/messages',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({text})});f.value='';await loadGroupMessages()}catch(e){alert(e.message)}}
function startCall(to,video,group){location.href='shizzi-call://start?to='+encodeURIComponent(to)+'&video='+(video?'1':'0')+'&group='+encodeURIComponent(group||'')}
function startGroupCall(g,video){location.href='shizzi-call://group?group='+encodeURIComponent(g.id)+'&video='+(video?'1':'0')}
async function pollEvents(){try{const j=await api('/api/v1/messenger/events?after='+lastEvent);for(const e of j.events){lastEvent=Math.max(lastEvent,e.id);if(e.kind==='offer'){showIncoming(e)}else if(e.kind==='message'&&currentPeer&&e.from===currentPeer.number){loadMessages()}else if(e.kind==='group-message'&&currentGroup&&e.groupId===currentGroup.id){loadGroupMessages()}else if(e.kind==='group-update'){loadGroups()}}}catch(e){}setTimeout(pollEvents,900)}
function showIncoming(e){const box=document.getElementById('incoming');box.classList.remove('hidden');box.innerHTML='<strong>Appel '+(e.media==='video'?'vidéo':'audio')+' entrant</strong><div>Compte '+esc(e.from)+(e.groupId?' · groupe':'')+'</div><div class="actions" style="margin-top:10px"><button id="acceptCall">Accepter</button><button id="declineCall" class="danger">Refuser</button></div>';document.getElementById('acceptCall').onclick=()=>{location.href='shizzi-call://incoming?callId='+encodeURIComponent(e.callId)+'&from='+encodeURIComponent(e.from)+'&video='+(e.media==='video'?'1':'0')+'&group='+encodeURIComponent(e.groupId||'')};document.getElementById('declineCall').onclick=async()=>{await api('/api/v1/messenger/signal',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({to:e.from,kind:'decline',callId:e.callId,media:e.media,groupId:e.groupId||''})});box.classList.add('hidden')}}
setInterval(()=>{if(currentPeer)loadMessages();if(currentGroup)loadGroupMessages()},1800);loadContacts();loadGroups();pollEvents();
</script></body></html>`,
		html.EscapeString(me.Name),
		html.EscapeString(me.Number),
		me.Number,
	)

	writeMessengerHTML(conn, method, []byte(page))
}

func writeMessengerHTML(conn net.Conn, method string, body []byte) {
	header := fmt.Sprintf(
		"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: %d\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
		len(body),
	)
	_, _ = conn.Write([]byte(header))
	if method != http.MethodHead {
		_, _ = conn.Write(body)
	}
}
