package datapath

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"
	"testing"
	"time"
)

type virtualShizziDevice struct {
	IP      string
	Account string
	Name    string
}

func messengerSixDeviceManagerForTest(t *testing.T) (*TrafficManager, []virtualShizziDevice) {
	t.Helper()

	// A virtual router boot starts from a clean local Messenger store.
	_ = os.Remove(messengerStatePath)
	shizziMessenger = newMessengerHub()

	devices := []virtualShizziDevice{
		{IP: "192.168.7.101", Account: "2001", Name: "Client A"},
		{IP: "192.168.7.102", Account: "2002", Name: "Client B"},
		{IP: "192.168.7.103", Account: "2003", Name: "Client C"},
		{IP: "192.168.7.104", Account: "2004", Name: "Client D"},
		{IP: "192.168.7.105", Account: "2005", Name: "Client E"},
		{IP: "192.168.7.106", Account: "2006", Name: "Client F"},
	}

	accounts := make([]PortalAccount, 0, len(devices))
	for _, device := range devices {
		salt := "six-device-" + device.Account
		accounts = append(accounts, PortalAccount{
			Number:                    device.Account,
			Name:                      device.Name,
			PinSalt:                   salt,
			PinHash:                   hashPortalPin(salt, "2468"),
			Enabled:                   true,
			DataBalanceBytes:          100_000_000,
			DataValidUntilMillis:      time.Now().Add(30 * 24 * time.Hour).UnixMilli(),
			DataDownloadBitsPerSecond: 10_000_000,
			DataUploadBitsPerSecond:   5_000_000,
		})
	}

	raw, err := json.Marshal(portalConfig{
		Title:    "Virtual Shizzi Router",
		Accounts: accounts,
	})
	if err != nil {
		t.Fatal(err)
	}

	manager := newTrafficManager()
	manager.setPortalConfig(true, string(raw))

	for _, device := range devices {
		ok, message := manager.submitPortalAccountLogin(
			device.IP,
			device.Account,
			"2468",
		)
		if !ok {
			t.Fatalf("%s login failed: %s", device.Name, message)
		}
	}

	if len(manager.portalAuthorized) != len(devices) {
		t.Fatalf("router sessions=%d, want %d", len(manager.portalAuthorized), len(devices))
	}
	return manager, devices
}

func virtualArray(t *testing.T, payload map[string]any, key string) []any {
	t.Helper()
	items, ok := payload[key].([]any)
	if !ok {
		t.Fatalf("%s is not an array: %#v", key, payload[key])
	}
	return items
}

func virtualObject(t *testing.T, value any) map[string]any {
	t.Helper()
	item, ok := value.(map[string]any)
	if !ok {
		t.Fatalf("not an object: %#v", value)
	}
	return item
}

func TestVirtualRouterWithSixAndroidClients(t *testing.T) {
	manager, devices := messengerSixDeviceManagerForTest(t)

	t.Run("six independent account sessions", func(t *testing.T) {
		for _, device := range devices {
			status, payload := messengerRequest(
				t,
				manager,
				device.IP,
				http.MethodGet,
				"/api/v1/messenger/me",
				"",
			)
			if !strings.Contains(status, "200") {
				t.Fatalf("%s /me status=%s", device.Name, status)
			}
			account := virtualObject(t, payload["account"])
			if got := account["number"]; got != device.Account {
				t.Fatalf("%s inherited account %v, want %s", device.Name, got, device.Account)
			}

			portal := manager.portalStatus(device.IP)
			if portal.AccountNumber != device.Account {
				t.Fatalf("%s portal account=%s, want %s", device.Name, portal.AccountNumber, device.Account)
			}
		}
	})

	t.Run("all six see five online contacts", func(t *testing.T) {
		for _, device := range devices {
			status, payload := messengerRequest(
				t,
				manager,
				device.IP,
				http.MethodGet,
				"/api/v1/messenger/contacts",
				"",
			)
			if !strings.Contains(status, "200") {
				t.Fatalf("%s contacts status=%s", device.Name, status)
			}
			contacts := virtualArray(t, payload, "contacts")
			if len(contacts) != 5 {
				t.Fatalf("%s contacts=%d, want 5", device.Name, len(contacts))
			}
			for _, raw := range contacts {
				contact := virtualObject(t, raw)
				if online, _ := contact["online"].(bool); !online {
					t.Fatalf("%s sees offline contact during six-device session: %#v", device.Name, contact)
				}
			}
		}
	})

	t.Run("private messages never leak to another account", func(t *testing.T) {
		status, payload := messengerRequest(
			t,
			manager,
			devices[0].IP,
			http.MethodPost,
			"/api/v1/messenger/messages",
			`{"to":"2002","text":"message privé A vers B"}`,
		)
		if !strings.Contains(status, "200") || payload["ok"] != true {
			t.Fatalf("private send failed: %s %#v", status, payload)
		}

		_, inboxB := messengerRequest(
			t,
			manager,
			devices[1].IP,
			http.MethodGet,
			"/api/v1/messenger/messages?peer=2001&after=0",
			"",
		)
		if got := len(virtualArray(t, inboxB, "messages")); got != 1 {
			t.Fatalf("B private messages=%d, want 1", got)
		}

		for _, device := range devices[2:] {
			_, other := messengerRequest(
				t,
				manager,
				device.IP,
				http.MethodGet,
				"/api/v1/messenger/messages?peer=2001&after=0",
				"",
			)
			if got := len(virtualArray(t, other, "messages")); got != 0 {
				t.Fatalf("%s leaked A↔B private messages: %d", device.Name, got)
			}
		}
	})

	var groupID string
	t.Run("six-member discussion group", func(t *testing.T) {
		status, created := messengerRequest(
			t,
			manager,
			devices[0].IP,
			http.MethodPost,
			"/api/v1/messenger/groups",
			`{"name":"Groupe 6 appareils","members":["2002","2003","2004","2005","2006"]}`,
		)
		if !strings.Contains(status, "200") || created["ok"] != true {
			t.Fatalf("group create failed: %s %#v", status, created)
		}

		group := virtualObject(t, created["group"])
		groupID, _ = group["id"].(string)
		if groupID == "" {
			t.Fatal("group id missing")
		}
		if got := len(virtualArray(t, group, "members")); got != 6 {
			t.Fatalf("group members=%d, want 6", got)
		}

		for index, device := range devices {
			body := fmt.Sprintf(`{"text":"message groupe appareil %d"}`, index+1)
			status, response := messengerRequest(
				t,
				manager,
				device.IP,
				http.MethodPost,
				"/api/v1/messenger/groups/"+groupID+"/messages",
				body,
			)
			if !strings.Contains(status, "200") || response["ok"] != true {
				t.Fatalf("%s group send failed: %s %#v", device.Name, status, response)
			}
		}

		for _, device := range devices {
			_, response := messengerRequest(
				t,
				manager,
				device.IP,
				http.MethodGet,
				"/api/v1/messenger/groups/"+groupID+"/messages?after=0",
				"",
			)
			if got := len(virtualArray(t, response, "messages")); got != 6 {
				t.Fatalf("%s group history=%d, want 6", device.Name, got)
			}
		}
	})

	t.Run("six clients join one local video-call room", func(t *testing.T) {
		callID := "virtual-router-six-video"

		for index, device := range devices {
			body := fmt.Sprintf(
				`{"action":"join","callId":"%s","groupId":"%s","media":"video"}`,
				callID,
				groupID,
			)
			status, response := messengerRequest(
				t,
				manager,
				device.IP,
				http.MethodPost,
				"/api/v1/messenger/room",
				body,
			)
			if !strings.Contains(status, "200") || response["ok"] != true {
				t.Fatalf("%s room join failed: %s %#v", device.Name, status, response)
			}
			if got := len(virtualArray(t, response, "members")); got != index {
				t.Fatalf("%s saw %d existing room members, want %d", device.Name, got, index)
			}
		}

		// A six-way WebRTC mesh has 15 peer pairs. Simulate one offer and one
		// answer per pair through the router signalling mailbox.
		pairs := 0
		for left := 0; left < len(devices); left++ {
			for right := left + 1; right < len(devices); right++ {
				pairs++
				offer := fmt.Sprintf(
					`{"to":"%s","kind":"offer","callId":"%s","media":"video","groupId":"%s","sdp":"offer-%d-%d"}`,
					devices[right].Account,
					callID,
					groupID,
					left,
					right,
				)
				status, _ := messengerRequest(
					t,
					manager,
					devices[left].IP,
					http.MethodPost,
					"/api/v1/messenger/signal",
					offer,
				)
				if !strings.Contains(status, "200") {
					t.Fatalf("offer %d→%d failed: %s", left, right, status)
				}

				answer := fmt.Sprintf(
					`{"to":"%s","kind":"answer","callId":"%s","media":"video","groupId":"%s","sdp":"answer-%d-%d"}`,
					devices[left].Account,
					callID,
					groupID,
					right,
					left,
				)
				status, _ = messengerRequest(
					t,
					manager,
					devices[right].IP,
					http.MethodPost,
					"/api/v1/messenger/signal",
					answer,
				)
				if !strings.Contains(status, "200") {
					t.Fatalf("answer %d→%d failed: %s", right, left, status)
				}
			}
		}
		if pairs != 15 {
			t.Fatalf("mesh pairs=%d, want 15", pairs)
		}

		totalOffers := 0
		totalAnswers := 0
		for _, device := range devices {
			_, response := messengerRequest(
				t,
				manager,
				device.IP,
				http.MethodGet,
				"/api/v1/messenger/events?after=0",
				"",
			)
			for _, raw := range virtualArray(t, response, "events") {
				event := virtualObject(t, raw)
				if event["callId"] != callID {
					continue
				}
				switch event["kind"] {
				case "offer":
					totalOffers++
				case "answer":
					totalAnswers++
				}
			}
		}
		if totalOffers != 15 || totalAnswers != 15 {
			t.Fatalf("mesh signalling offers=%d answers=%d, want 15/15", totalOffers, totalAnswers)
		}
	})

	t.Run("local Messenger does not consume Internet Data quota", func(t *testing.T) {
		raw := manager.statsJSON()
		var snapshot trafficStatsSnapshot
		if err := json.Unmarshal([]byte(raw), &snapshot); err != nil {
			t.Fatal(err)
		}
		for _, usage := range snapshot.AccountUsage {
			if usage.DataBytes != 0 {
				t.Fatalf("account %s Messenger consumed %d Internet Data bytes", usage.AccountNumber, usage.DataBytes)
			}
		}
	})

	t.Run("one client leaves while five continue independently", func(t *testing.T) {
		if ok, message := manager.submitPortalLogout(devices[5].IP); !ok {
			t.Fatalf("F logout failed: %s", message)
		}

		status, _ := messengerRequest(
			t,
			manager,
			devices[5].IP,
			http.MethodGet,
			"/api/v1/messenger/me",
			"",
		)
		if !strings.Contains(status, "401") {
			t.Fatalf("logged-out F status=%s, want 401", status)
		}

		for _, device := range devices[:5] {
			status, payload := messengerRequest(
				t,
				manager,
				device.IP,
				http.MethodGet,
				"/api/v1/messenger/me",
				"",
			)
			if !strings.Contains(status, "200") || payload["ok"] != true {
				t.Fatalf("%s stopped after F logout: %s %#v", device.Name, status, payload)
			}
		}

		_, contacts := messengerRequest(
			t,
			manager,
			devices[0].IP,
			http.MethodGet,
			"/api/v1/messenger/contacts",
			"",
		)
		foundF := false
		for _, raw := range virtualArray(t, contacts, "contacts") {
			contact := virtualObject(t, raw)
			if contact["number"] == devices[5].Account {
				foundF = true
				if online, _ := contact["online"].(bool); online {
					t.Fatal("F still reported online after logout")
				}
			}
		}
		if !foundF {
			t.Fatal("F missing from contact list after logout")
		}

		// The remaining five still exchange a group message.
		status, response := messengerRequest(
			t,
			manager,
			devices[1].IP,
			http.MethodPost,
			"/api/v1/messenger/groups/"+groupID+"/messages",
			`{"text":"les cinq autres continuent"}`,
		)
		if !strings.Contains(status, "200") || response["ok"] != true {
			t.Fatalf("remaining clients group send failed: %s %#v", status, response)
		}
	})

	t.Run("sixth client can reconnect to its own account and recover history", func(t *testing.T) {
		ok, message := manager.submitPortalAccountLogin(
			devices[5].IP,
			devices[5].Account,
			"2468",
		)
		if !ok {
			t.Fatalf("F reconnect failed: %s", message)
		}

		_, response := messengerRequest(
			t,
			manager,
			devices[5].IP,
			http.MethodGet,
			"/api/v1/messenger/groups/"+groupID+"/messages?after=0",
			"",
		)
		// Six initial group messages + one sent while F was logged out.
		if got := len(virtualArray(t, response, "messages")); got != 7 {
			t.Fatalf("F recovered group history=%d, want 7", got)
		}
	})
}
