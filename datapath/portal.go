package datapath

import (
	"bufio"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"html"
	"io"
	"net"
	"net/http"
	"strings"
	"time"
)

const portalIP = "192.0.2.1"
const clientAppBridgeAddress = "127.0.0.1:8091"
const mediaBridgeAddress = "127.0.0.1:8088"
const chatBridgeAddress = "127.0.0.1:8090"

type PortalAccount struct {
	Number                         string `json:"number"`
	Name                           string `json:"name"`
	PinSalt                        string `json:"pinSalt"`
	PinHash                        string `json:"pinHash"`
	Enabled                        bool   `json:"enabled"`
	DataBalanceBytes               int64  `json:"dataBalanceBytes"`
	DataValidUntilMillis           int64  `json:"dataValidUntilMillis"`
	DataDownloadBitsPerSecond      int64  `json:"dataDownloadBps"`
	DataUploadBitsPerSecond        int64  `json:"dataUploadBps"`
	UnlimitedUntilMillis           int64  `json:"unlimitedUntilMillis"`
	UnlimitedDownloadBitsPerSecond int64  `json:"unlimitedDownloadBps"`
	UnlimitedUploadBitsPerSecond   int64  `json:"unlimitedUploadBps"`
	UnlimitedPlanName              string `json:"unlimitedPlanName"`
	TotalUpBytes                   int64  `json:"totalUpBytes"`
	TotalDownBytes                 int64  `json:"totalDownBytes"`

	// ConsumedMarkerBytes is the value of this datapath's accountUsage.DataBytes
	// that Android had already deducted when it produced DataBalanceBytes. The
	// live balance is DataBalanceBytes minus what was consumed after it.
	ConsumedMarkerBytes int64 `json:"consumedMarkerBytes"`
	// MarkerEpoch ties the marker to one datapath instance. A marker from a
	// previous instance is meaningless against fresh counters and is ignored.
	MarkerEpoch int64 `json:"markerEpoch"`
	// UsageMarkerBytes is the up+down total of this datapath's accountUsage
	// already folded into TotalUp/DownBytes by Android.
	UsageMarkerBytes int64 `json:"usageMarkerBytes"`
}

func (a PortalAccount) hasUnlimited(nowMillis int64) bool {
	return a.Enabled && a.UnlimitedUntilMillis > nowMillis
}

// dataValid reports whether the stored Data allowance is usable by date. The
// live byte balance is checked separately, against shared account usage.
func (a PortalAccount) dataValid(nowMillis int64) bool {
	return a.Enabled &&
		a.DataBalanceBytes > 0 &&
		(a.DataValidUntilMillis <= 0 || nowMillis < a.DataValidUntilMillis)
}

func (a PortalAccount) downloadBps(nowMillis int64) int64 {
	if a.hasUnlimited(nowMillis) {
		return a.UnlimitedDownloadBitsPerSecond
	}
	if a.dataValid(nowMillis) {
		return a.DataDownloadBitsPerSecond
	}
	return 0
}

func (a PortalAccount) uploadBps(nowMillis int64) int64 {
	if a.hasUnlimited(nowMillis) {
		return a.UnlimitedUploadBitsPerSecond
	}
	if a.dataValid(nowMillis) {
		return a.DataUploadBitsPerSecond
	}
	return 0
}

type remoteAdminConfig struct {
	Enabled      bool   `json:"enabled"`
	Username     string `json:"username"`
	PasswordSalt string `json:"passwordSalt"`
	PasswordHash string `json:"passwordHash"`
	DownloadBps  int64  `json:"downloadBps"`
	UploadBps    int64  `json:"uploadBps"`
}

type AdminCommand struct {
	ID              string          `json:"id"`
	IP              string          `json:"ip"`
	Action          string          `json:"action"`
	Params          json.RawMessage `json:"params"`
	CreatedAtMillis int64           `json:"createdAtMillis"`
}

type AdminCommandResult struct {
	ID      string          `json:"id"`
	Success bool            `json:"success"`
	Message string          `json:"message"`
	Payload json.RawMessage `json:"payload,omitempty"`
}

type adminSession struct {
	Token          string
	IP             string
	LastSeenMillis int64
	missingSince   time.Time
}

type adminChallenge struct {
	Nonce           string
	Username        string
	ExpiresAtMillis int64
}

type portalClientApp struct {
	Available bool   `json:"available"`
	Version   string `json:"version"`
	FileName  string `json:"fileName"`
	SizeBytes int64  `json:"sizeBytes"`
	SHA256    string `json:"sha256"`
}

type MediaDiagnostic struct {
	AtMillis             int64  `json:"atMillis"`
	ClientIP             string `json:"clientIp"`
	Path                 string `json:"path"`
	AccountAuthenticated bool   `json:"accountAuthenticated"`
	AccountNumber        string `json:"accountNumber,omitempty"`
	ProxyTarget          string `json:"proxyTarget,omitempty"`
	Backend              string `json:"backend,omitempty"`
	BackendConnected     bool   `json:"backendConnected"`
	BytesCopied          int64  `json:"bytesCopied,omitempty"`
	Result               string `json:"result"`
	Error                string `json:"error,omitempty"`
}

type portalConfig struct {
	Title        string               `json:"title"`
	Message      string               `json:"message"`
	HTML         string               `json:"html"`
	Accounts     []PortalAccount      `json:"accounts"`
	Admin        remoteAdminConfig    `json:"admin"`
	AdminState   json.RawMessage      `json:"adminState"`
	ClientApp    portalClientApp      `json:"clientApp"`
	// ClaimResults lets Android tell the portal how a voucher claim ended so
	// the client sees "accepted"/"rejected" instead of a silent drop.
	ClaimResults []PortalClaimResult   `json:"claimResults"`
	AdminResults []AdminCommandResult  `json:"adminResults"`
}

type PortalClaimResult struct {
	IP      string `json:"ip"`
	Code    string `json:"code"`
	Success bool   `json:"success"`
	Message string `json:"message"`
}

// PortalAuthorization is one logged-in session: one physical client (IP as
// resolved from Android's pre-NAT state) using one account. An account may
// have only one active device session at a time, but remains portable after
// logout, admin disconnect, or departed-client cleanup.
type PortalAuthorization struct {
	AccountNumber   string
	StartedAtMillis int64
	UpBytes         int64
	DownBytes       int64

	missingSince time.Time
}

type PortalAuthorizationStatus struct {
	IP                   string `json:"ip"`
	MAC                  string `json:"mac,omitempty"`
	AccountNumber        string `json:"accountNumber"`
	StartedAtMillis      int64  `json:"startedAtMillis"`
	SessionDataUsedBytes int64  `json:"sessionDataUsedBytes"`
	UpBytes              int64  `json:"upBytes"`
	DownBytes            int64  `json:"downBytes"`
	Authorized           bool   `json:"authorized"`
	DownloadBps          int64  `json:"downloadBps"`
	UploadBps            int64  `json:"uploadBps"`
}

type PortalRechargeClaim struct {
	IP              string `json:"ip"`
	AccountNumber   string `json:"accountNumber"`
	Code            string `json:"code"`
	ClaimedAtMillis int64  `json:"claimedAtMillis"`
}

func (m *TrafficManager) setPortalConfig(required bool, raw string) {
	var config portalConfig
	if strings.TrimSpace(raw) != "" {
		_ = json.Unmarshal([]byte(raw), &config)
	}

	accounts := make(map[string]PortalAccount)
	for _, account := range config.Accounts {
		number := normalizeAccountNumber(account.Number)
		if number == "" {
			continue
		}
		account.Number = number
		account.PinSalt = strings.TrimSpace(account.PinSalt)
		account.PinHash = strings.ToLower(strings.TrimSpace(account.PinHash))
		accounts[number] = account
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	for index, account := range accounts {
		if account.MarkerEpoch != m.epoch {
			// Balance produced before this datapath existed: nothing counted
			// here has been deducted yet, so the marker is zero.
			account.ConsumedMarkerBytes = 0
			account.UsageMarkerBytes = 0
			accounts[index] = account
		}
	}

	m.portalRequired = required
	m.portalTitle = strings.TrimSpace(config.Title)
	if m.portalTitle == "" {
		m.portalTitle = "Shizzi Hotspot"
	}
	m.portalMessage = strings.TrimSpace(config.Message)
	if m.portalMessage == "" {
		m.portalMessage = "Connectez-vous à votre compte Shizzi."
	}
	m.portalHTML = config.HTML
	m.portalClientApp = config.ClientApp
	credentialsChanged := m.adminConfig.Username != config.Admin.Username ||
		m.adminConfig.PasswordHash != config.Admin.PasswordHash ||
		m.adminConfig.Enabled != config.Admin.Enabled
	m.adminConfig = config.Admin
	if m.adminConfig.DownloadBps < 1_000_000 {
		m.adminConfig.DownloadBps = 1_000_000
	}
	if m.adminConfig.UploadBps < 1_000_000 {
		m.adminConfig.UploadBps = 1_000_000
	}
	if len(config.AdminState) > 0 {
		m.adminState = append(json.RawMessage(nil), config.AdminState...)
	}
	if credentialsChanged || !m.adminConfig.Enabled {
		m.adminSessions = make(map[string]*adminSession)
		m.adminChallenges = make(map[string]adminChallenge)
	}
	if m.adminResults == nil {
		m.adminResults = make(map[string]AdminCommandResult)
	}
	for _, result := range config.AdminResults {
		m.adminResults[result.ID] = result
		for i := 0; i < len(m.adminCommands); {
			if m.adminCommands[i].ID == result.ID {
				m.adminCommands = append(m.adminCommands[:i], m.adminCommands[i+1:]...)
				continue
			}
			i++
		}
	}
	m.portalAccounts = accounts
	if m.portalClaimResults == nil {
		m.portalClaimResults = make(map[string]PortalClaimResult)
	}
	for _, result := range config.ClaimResults {
		m.portalClaimResults[result.IP] = result
	}

	// A deleted or suspended account ends every session using it. Nothing
	// else is touched: speeds and balance are read live from the account.
	for ip, authorization := range m.portalAuthorized {
		account, ok := accounts[authorization.AccountNumber]
		if !ok || !account.Enabled {
			delete(m.portalAuthorized, ip)
		}
	}
}

func normalizeAccountNumber(raw string) string {
	var builder strings.Builder
	for _, r := range raw {
		if r >= '0' && r <= '9' {
			builder.WriteRune(r)
		}
	}
	return builder.String()
}

func normalizeVoucherCode(raw string) string {
	return strings.ToUpper(strings.TrimSpace(raw))
}

func hashPortalPin(salt, pin string) string {
	sum := sha256.Sum256([]byte(salt + ":" + pin))
	return hex.EncodeToString(sum[:])
}

func (m *TrafficManager) portalRequiredFor(ip string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.portalRequired &&
		!m.portalAuthorizedLocked(ip, time.Now().UnixMilli()) &&
		!m.adminAuthorizedLocked(ip)
}

// remainingDataLocked is the account's live Data balance, shared by all of
// its sessions.
func (m *TrafficManager) remainingDataLocked(account PortalAccount) int64 {
	consumed := int64(0)
	if usage := m.accountUsage[account.Number]; usage != nil {
		consumed = usage.DataBytes - account.ConsumedMarkerBytes
		if consumed < 0 {
			consumed = 0
		}
	}
	remaining := account.DataBalanceBytes - consumed
	if remaining < 0 {
		return 0
	}
	return remaining
}

func (m *TrafficManager) accountHasInternetLocked(account PortalAccount, nowMillis int64) bool {
	if !account.Enabled {
		return false
	}
	if account.hasUnlimited(nowMillis) {
		return true
	}
	return account.dataValid(nowMillis) && m.remainingDataLocked(account) > 0
}

func (m *TrafficManager) portalAuthorizedLocked(ip string, nowMillis int64) bool {
	authorization := m.portalAuthorized[ip]
	if authorization == nil {
		return false
	}
	account, ok := m.portalAccounts[authorization.AccountNumber]
	if !ok {
		return false
	}
	return m.accountHasInternetLocked(account, nowMillis)
}

func (m *TrafficManager) mediaAccountIdentity(ip string) (bool, string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	authorization := m.portalAuthorized[ip]
	if authorization == nil {
		return false, ""
	}
	account, ok := m.portalAccounts[authorization.AccountNumber]
	if !ok || !account.Enabled {
		return false, ""
	}
	return true, account.Number
}

func (m *TrafficManager) mediaAccountAuthenticated(ip string) bool {
	authenticated, _ := m.mediaAccountIdentity(ip)
	return authenticated
}

func (m *TrafficManager) noteMediaDiagnostic(event MediaDiagnostic) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if event.AtMillis == 0 {
		event.AtMillis = time.Now().UnixMilli()
	}
	m.mediaDiagnostics = append(m.mediaDiagnostics, event)
	if len(m.mediaDiagnostics) > 20 {
		m.mediaDiagnostics = append([]MediaDiagnostic(nil), m.mediaDiagnostics[len(m.mediaDiagnostics)-20:]...)
	}
}

func (m *TrafficManager) submitPortalAccountLogin(
	ip, rawNumber, pin string,
) (bool, string) {
	if ip == "" {
		return false, "Appareil non identifié. Réessayez dans quelques secondes."
	}
	number := normalizeAccountNumber(rawNumber)
	now := time.Now().UnixMilli()

	m.mu.Lock()
	defer m.mu.Unlock()

	account, ok := m.portalAccounts[number]
	if !ok || !account.Enabled {
		return false, "Compte ou code incorrect."
	}
	if hashPortalPin(account.PinSalt, pin) != account.PinHash {
		return false, "Compte ou code incorrect."
	}
	// One account = one active device. The account is not permanently bound
	// to an IP/MAC: once the previous session is logged out, revoked by the
	// admin, or pruned after departure, it can be opened on another device.
	for otherIP, authorization := range m.portalAuthorized {
		if otherIP != ip && authorization.AccountNumber == number {
			return false, "Ce compte est déjà utilisé sur un autre appareil."
		}
	}
	m.portalAuthorized[ip] = &PortalAuthorization{
		AccountNumber:   number,
		StartedAtMillis: now,
	}
	delete(m.portalClaimResults, ip)
	if !m.accountHasInternetLocked(account, now) {
		return true, "Compte connecté. Aucun forfait actif : entrez un voucher."
	}
	return true, "Connexion autorisée."
}

func (m *TrafficManager) submitPortalLogout(ip string) (bool, string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.portalAuthorized[ip]; !ok {
		return false, "Aucune session ouverte sur cet appareil."
	}
	delete(m.portalAuthorized, ip)
	return true, "Session fermée sur cet appareil."
}

func (m *TrafficManager) submitPortalRecharge(ip, rawCode string) (bool, string) {
	code := normalizeVoucherCode(rawCode)
	if code == "" {
		return false, "Entrez un voucher."
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	authorization := m.portalAuthorized[ip]
	if authorization == nil {
		return false, "Connectez-vous d'abord à votre compte."
	}

	for _, claim := range m.portalRechargeClaims {
		if claim.IP == ip && claim.AccountNumber == authorization.AccountNumber &&
			claim.Code == code {
			return true, "Recharge en cours de validation…"
		}
	}

	delete(m.portalClaimResults, ip)
	m.portalRechargeClaims = append(m.portalRechargeClaims, PortalRechargeClaim{
		IP:              ip,
		AccountNumber:   authorization.AccountNumber,
		Code:            code,
		ClaimedAtMillis: time.Now().UnixMilli(),
	})
	return true, "Recharge en cours de validation…"
}

func (m *TrafficManager) clearPortalClaims() {
	m.mu.Lock()
	m.portalRechargeClaims = nil
	m.mu.Unlock()
}

// revokePortalClient ends one session (one device). The account and the other
// sessions using it are untouched.
func (m *TrafficManager) revokePortalClient(ip string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.portalAuthorized, ip)
}


func randomHex(byteCount int) string {
	bytes := make([]byte, byteCount)
	if _, err := rand.Read(bytes); err != nil {
		sum := sha256.Sum256([]byte(fmt.Sprintf("%d", time.Now().UnixNano())))
		return hex.EncodeToString(sum[:])
	}
	return hex.EncodeToString(bytes)
}

func (m *TrafficManager) adminAuthorizedLocked(ip string) bool {
	if !m.adminConfig.Enabled || ip == "" {
		return false
	}
	for _, session := range m.adminSessions {
		if session.IP == ip {
			return true
		}
	}
	return false
}

func (m *TrafficManager) requireAdminTokenLocked(request *http.Request, ip string) (*adminSession, bool) {
	token := strings.TrimSpace(request.Header.Get("X-Shizzi-Admin-Token"))
	if token == "" || !m.adminConfig.Enabled {
		return nil, false
	}
	session := m.adminSessions[token]
	if session == nil || session.IP != ip {
		return nil, false
	}
	session.LastSeenMillis = time.Now().UnixMilli()
	return session, true
}

func adminProof(passwordHash, nonce string) string {
	mac := hmac.New(sha256.New, []byte(passwordHash))
	_, _ = mac.Write([]byte(nonce))
	return hex.EncodeToString(mac.Sum(nil))
}

func adminActionAllowed(action string) bool {
	switch action {
	case "account.create",
		"account.rename",
		"account.pin",
		"account.enable",
		"account.delete",
		"account.disconnect",
		"session.disconnect",
		"offer.upsert",
		"offer.delete",
		"voucher.generate",
		"voucher.enable",
		"portal.set",
		"admin.credentials",
		"media.enable",
		"media.folder.create",
		"media.folder.update",
		"media.folder.delete",
		"media.folder.source",
		"media.scan",
		"media.browse":
		return true
	default:
		return false
	}
}

func writeJSONStatus(conn net.Conn, status string, payload any) {
	body, _ := json.Marshal(payload)
	header := fmt.Sprintf(
		"HTTP/1.1 %s\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: %d\r\nConnection: close\r\n\r\n",
		status,
		len(body),
	)
	_, _ = conn.Write([]byte(header))
	_, _ = conn.Write(body)
}

func (m *TrafficManager) serveAdminAPI(conn net.Conn, request *http.Request, clientIP string) bool {
	path := request.URL.Path
	if !strings.HasPrefix(path, "/api/v1/admin/") {
		return false
	}

	switch {
	case request.Method == http.MethodGet && path == "/api/v1/admin/challenge":
		username := strings.TrimSpace(request.URL.Query().Get("username"))
		m.mu.Lock()
		defer m.mu.Unlock()
		if !m.adminConfig.Enabled || m.adminConfig.PasswordHash == "" {
			writeJSONStatus(conn, "503 Service Unavailable", map[string]any{
				"ok": false, "message": "Administration distante indisponible.",
			})
			return true
		}
		nonce := randomHex(24)
		m.adminChallenges[nonce] = adminChallenge{
			Nonce: nonce,
			Username: username,
			ExpiresAtMillis: time.Now().Add(60 * time.Second).UnixMilli(),
		}
		writeJSONStatus(conn, "200 OK", map[string]any{
			"ok": true,
			"nonce": nonce,
			"salt": m.adminConfig.PasswordSalt,
			"routerName": m.portalTitle,
		})
		return true

	case request.Method == http.MethodPost && path == "/api/v1/admin/login":
		_ = request.ParseForm()
		username := strings.TrimSpace(request.Form.Get("username"))
		nonce := strings.TrimSpace(request.Form.Get("nonce"))
		proof := strings.ToLower(strings.TrimSpace(request.Form.Get("proof")))

		m.mu.Lock()
		defer m.mu.Unlock()
		challenge, ok := m.adminChallenges[nonce]
		delete(m.adminChallenges, nonce)
		valid := ok &&
			challenge.ExpiresAtMillis >= time.Now().UnixMilli() &&
			m.adminConfig.Enabled &&
			username == m.adminConfig.Username &&
			challenge.Username == username &&
			hmac.Equal([]byte(proof), []byte(adminProof(m.adminConfig.PasswordHash, nonce)))
		if !valid {
			writeJSONStatus(conn, "401 Unauthorized", map[string]any{
				"ok": false, "message": "Identifiant ou mot de passe admin incorrect.",
			})
			return true
		}
		token := randomHex(32)
		m.adminSessions[token] = &adminSession{
			Token: token,
			IP: clientIP,
			LastSeenMillis: time.Now().UnixMilli(),
		}
		writeJSONStatus(conn, "200 OK", map[string]any{
			"ok": true,
			"token": token,
			"routerName": m.portalTitle,
			"downloadBps": m.adminConfig.DownloadBps,
			"uploadBps": m.adminConfig.UploadBps,
		})
		return true

	case request.Method == http.MethodPost && path == "/api/v1/admin/logout":
		m.mu.Lock()
		token := strings.TrimSpace(request.Header.Get("X-Shizzi-Admin-Token"))
		session, ok := m.requireAdminTokenLocked(request, clientIP)
		if ok {
			delete(m.adminSessions, token)
		}
		m.mu.Unlock()
		if session == nil {
			writeJSONStatus(conn, "401 Unauthorized", map[string]any{"ok": false})
		} else {
			writeJSONStatus(conn, "200 OK", map[string]any{"ok": true})
		}
		return true

	case request.Method == http.MethodGet && path == "/api/v1/admin/state":
		m.mu.Lock()
		_, ok := m.requireAdminTokenLocked(request, clientIP)
		state := append(json.RawMessage(nil), m.adminState...)
		routerName := m.portalTitle
		m.mu.Unlock()
		if !ok {
			writeJSONStatus(conn, "401 Unauthorized", map[string]any{"ok": false})
			return true
		}
		if len(state) == 0 {
			state = json.RawMessage("{}")
		}
		var stateValue any
		_ = json.Unmarshal(state, &stateValue)
		var trafficValue any
		_ = json.Unmarshal([]byte(m.statsJSON()), &trafficValue)
		writeJSONStatus(conn, "200 OK", map[string]any{
			"ok": true,
			"routerName": routerName,
			"state": stateValue,
			"traffic": trafficValue,
		})
		return true

	case request.Method == http.MethodPost && path == "/api/v1/admin/command":
		m.mu.Lock()
		_, ok := m.requireAdminTokenLocked(request, clientIP)
		m.mu.Unlock()
		if !ok {
			writeJSONStatus(conn, "401 Unauthorized", map[string]any{"ok": false})
			return true
		}
		body, err := io.ReadAll(io.LimitReader(request.Body, 128*1024))
		if err != nil {
			writeJSONStatus(conn, "400 Bad Request", map[string]any{"ok": false})
			return true
		}
		var payload struct {
			Action string          `json:"action"`
			Params json.RawMessage `json:"params"`
		}
		if json.Unmarshal(body, &payload) != nil {
			writeJSONStatus(conn, "400 Bad Request", map[string]any{"ok": false, "message": "JSON invalide."})
			return true
		}
		if !adminActionAllowed(payload.Action) {
			writeJSONStatus(conn, "403 Forbidden", map[string]any{
				"ok": false, "message": "Commande interdite.",
			})
			return true
		}
		if len(payload.Params) == 0 {
			payload.Params = json.RawMessage("{}")
		}
		id := randomHex(16)
		m.mu.Lock()
		m.adminCommands = append(m.adminCommands, AdminCommand{
			ID: id,
			IP: clientIP,
			Action: payload.Action,
			Params: payload.Params,
			CreatedAtMillis: time.Now().UnixMilli(),
		})
		m.mu.Unlock()
		writeJSONStatus(conn, "202 Accepted", map[string]any{"ok": true, "id": id, "pending": true})
		return true

	case request.Method == http.MethodGet && path == "/api/v1/admin/result":
		m.mu.Lock()
		_, ok := m.requireAdminTokenLocked(request, clientIP)
		id := strings.TrimSpace(request.URL.Query().Get("id"))
		result, found := m.adminResults[id]
		if found {
			delete(m.adminResults, id)
		}
		m.mu.Unlock()
		if !ok {
			writeJSONStatus(conn, "401 Unauthorized", map[string]any{"ok": false})
			return true
		}
		if !found {
			writeJSONStatus(conn, "200 OK", map[string]any{"ok": true, "pending": true})
			return true
		}
		var responsePayload any
		if len(result.Payload) > 0 {
			_ = json.Unmarshal(result.Payload, &responsePayload)
		}
		writeJSONStatus(conn, "200 OK", map[string]any{
			"ok": true,
			"pending": false,
			"success": result.Success,
			"message": result.Message,
			"payload": responsePayload,
		})
		return true
	}

	writeJSONStatus(conn, "404 Not Found", map[string]any{"ok": false})
	return true
}

func (m *TrafficManager) servePortal(conn net.Conn, clientIP string) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(15 * time.Second))

	request, err := http.ReadRequest(bufio.NewReader(conn))
	if err != nil {
		return
	}
	defer request.Body.Close()

	if m.serveAdminAPI(conn, request, clientIP) {
		return
	}

	path := request.URL.Path
	isMediaRequest := (request.Method == http.MethodGet || request.Method == http.MethodHead) &&
		(path == "/media" || strings.HasPrefix(path, "/media/"))
	isChatRequest := path == "/chat" || strings.HasPrefix(path, "/chat/")
	localAuthenticated := false
	localAccount := ""
	if isMediaRequest || isChatRequest {
		localAuthenticated, localAccount = m.mediaAccountIdentity(clientIP)
	}
	switch {
	case isChatRequest && !localAuthenticated:
		m.writeChatLoginRequired(conn, request.Method)
	case isMediaRequest && !localAuthenticated:
		m.noteMediaDiagnostic(MediaDiagnostic{
			ClientIP:             clientIP,
			Path:                 path,
			AccountAuthenticated: false,
			Result:               "account_required",
		})
		m.writeMediaLoginRequired(conn, request.Method)
	case (request.Method == http.MethodGet || request.Method == http.MethodHead) &&
		path == "/speedtest":
		_, _ = io.WriteString(
			conn,
			"HTTP/1.1 302 Found\r\nLocation: /speedtest/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
		)
	case (request.Method == http.MethodGet || request.Method == http.MethodHead) &&
		path == "/speedtest/":
		m.serveLocalSpeedtestPage(conn, request.Method)
	case (request.Method == http.MethodGet || request.Method == http.MethodHead) &&
		path == "/speedtest/ping":
		m.serveLocalSpeedtestPing(conn, request.Method)
	case (request.Method == http.MethodGet || request.Method == http.MethodHead) &&
		path == "/speedtest/download":
		m.serveLocalSpeedtestDownload(conn, request)
	case isMediaRequest:
		m.serveMediaProxy(conn, request, clientIP, localAccount)
	case isChatRequest:
		m.serveChatProxy(conn, request, localAccount)
	case (request.Method == http.MethodGet || request.Method == http.MethodHead) &&
		(path == "/download/shizzi-plus.apk" || path == "/shizzi-plus.apk"):
		m.serveClientAppDownload(conn, request)
	case request.Method == http.MethodPost && path == "/login":
		_ = request.ParseForm()
		ok, message := m.submitPortalAccountLogin(
			clientIP,
			request.Form.Get("account"),
			request.Form.Get("pin"),
		)
		m.writePortalHTML(conn, clientIP, message, !ok)
	case request.Method == http.MethodPost && path == "/recharge":
		_ = request.ParseForm()
		ok, message := m.submitPortalRecharge(clientIP, request.Form.Get("code"))
		m.writePortalHTML(conn, clientIP, message, !ok)
	case request.Method == http.MethodPost && path == "/logout":
		ok, message := m.submitPortalLogout(clientIP)
		m.writePortalHTML(conn, clientIP, message, !ok)
	case path == "/status.json":
		m.writePortalStatusJSON(conn, clientIP)
	default:
		m.writePortalHTML(conn, clientIP, "", false)
	}
}

func speedtestDownloadBytes(rawMB string) int64 {
	switch strings.TrimSpace(rawMB) {
	case "10":
		return 10 * 1024 * 1024
	case "25":
		return 25 * 1024 * 1024
	case "100":
		return 100 * 1024 * 1024
	default:
		return 50 * 1024 * 1024
	}
}

func (m *TrafficManager) serveLocalSpeedtestPing(conn net.Conn, method string) {
	body := []byte("ok")
	header := fmt.Sprintf(
		"HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: %d\r\nCache-Control: no-store, no-cache, must-revalidate\r\nPragma: no-cache\r\nConnection: close\r\n\r\n",
		len(body),
	)
	_, _ = conn.Write([]byte(header))
	if method != http.MethodHead {
		_, _ = conn.Write(body)
	}
}

func (m *TrafficManager) serveLocalSpeedtestDownload(conn net.Conn, request *http.Request) {
	size := speedtestDownloadBytes(request.URL.Query().Get("mb"))
	_ = conn.SetDeadline(time.Now().Add(5 * time.Minute))

	header := fmt.Sprintf(
		"HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: %d\r\nCache-Control: no-store, no-cache, must-revalidate\r\nPragma: no-cache\r\nX-Shizzi-Local-Speedtest: 1\r\nConnection: close\r\n\r\n",
		size,
	)
	if _, err := conn.Write([]byte(header)); err != nil || request.Method == http.MethodHead {
		return
	}

	// Generate bytes in small chunks. Nothing is read from storage and nothing
	// is fetched from the WAN: this measures the local portal -> client path.
	chunk := make([]byte, 64*1024)
	remaining := size
	for remaining > 0 {
		writeSize := int64(len(chunk))
		if remaining < writeSize {
			writeSize = remaining
		}
		written, err := conn.Write(chunk[:int(writeSize)])
		if err != nil {
			return
		}
		remaining -= int64(written)
		if written == 0 {
			return
		}
	}
}

func (m *TrafficManager) serveLocalSpeedtestPage(conn net.Conn, method string) {
	page := `<!doctype html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Test débit local · Shizzi</title>
<style>
:root{color-scheme:dark;font-family:Inter,system-ui,-apple-system,sans-serif}
*{box-sizing:border-box}body{margin:0;min-height:100vh;padding:20px;color:#f8fafc;background:linear-gradient(145deg,#07111f,#111827 55%,#0f172a)}
main{width:min(100%,620px);margin:0 auto;background:#0f172a;border:1px solid #ffffff18;border-radius:28px;padding:24px;box-shadow:0 26px 80px #0008}
a{color:#7dd3fc;text-decoration:none}.eyebrow{font-size:11px;letter-spacing:.14em;color:#7dd3fc;font-weight:800}
h1{margin:6px 0 8px;font-size:28px}.lead,.note{color:#94a3b8;line-height:1.5}
.controls{display:grid;grid-template-columns:1fr auto;gap:10px;margin:18px 0}
select,button{font:inherit;border-radius:14px;padding:14px 16px;border:0}
select{background:#1e293b;color:#fff;border:1px solid #334155}
button{background:linear-gradient(90deg,#22d3ee,#34d399);color:#06202a;font-weight:900;cursor:pointer}
button:disabled{opacity:.5;cursor:wait}
.progress{height:10px;background:#1e293b;border-radius:999px;overflow:hidden;margin:14px 0}.bar{height:100%;width:0;background:linear-gradient(90deg,#38bdf8,#34d399);transition:width .15s}
.results{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:16px}.result{padding:16px;border-radius:16px;background:#ffffff08}
.result span{display:block;color:#94a3b8;font-size:12px}.result strong{display:block;margin-top:5px;font-size:22px}
.summary{margin-top:14px;padding:16px;border-radius:16px;background:#071b2a;border:1px solid #22d3ee55;line-height:1.5}
@media(max-width:520px){.controls,.results{grid-template-columns:1fr}}
</style></head><body><main>
<a href="/">← Portail Shizzi</a>
<div class="eyebrow" style="margin-top:18px">RÉSEAU LOCAL</div>
<h1>Test de débit local</h1>
<p class="lead">Mesure le débit <strong>Reno9 → cet appareil</strong> sur le Wi-Fi Shizzi. Le test reste local : il ne télécharge rien depuis Starlink ou Internet.</p>
<div class="controls">
<select id="size" aria-label="Taille du test">
<option value="10">Rapide · 10 Mo</option>
<option value="25">Court · 25 Mo</option>
<option value="50" selected>Normal · 50 Mo</option>
<option value="100">Précis · 100 Mo</option>
</select>
<button id="start">Tester le débit local</button>
</div>
<div id="status" class="note">Prêt.</div>
<div class="progress"><div id="bar" class="bar"></div></div>
<div class="results">
<div class="result"><span>Débit moyen</span><strong id="speed">—</strong></div>
<div class="result"><span>Latence locale</span><strong id="latency">—</strong></div>
</div>
<div id="summary" class="summary">Le résultat estimera aussi la capacité pour des vidéos 1080p à 3 Mbps.</div>
<p class="note">Le débit peut varier selon la bande Wi-Fi, la distance, les interférences et les autres appareils actifs. Pour comparer plusieurs essais, reste au même endroit.</p>
</main>
<script>
(function(){
  var startButton=document.getElementById("start");
  var sizeSelect=document.getElementById("size");
  var statusEl=document.getElementById("status");
  var bar=document.getElementById("bar");
  var speedEl=document.getElementById("speed");
  var latencyEl=document.getElementById("latency");
  var summaryEl=document.getElementById("summary");

  function setStatus(text){statusEl.textContent=text;}
  function sleep(ms){return new Promise(function(resolve){setTimeout(resolve,ms);});}

  async function measureLatency(){
    var samples=[];
    for(var i=0;i<5;i++){
      var started=performance.now();
      var response=await fetch("/speedtest/ping?ts="+Date.now()+"-"+i,{cache:"no-store"});
      if(!response.ok) throw new Error("ping");
      await response.text();
      samples.push(performance.now()-started);
      await sleep(80);
    }
    samples.sort(function(a,b){return a-b;});
    return samples[Math.floor(samples.length/2)];
  }

  async function receive(url,onProgress){
    var response=await fetch(url,{cache:"no-store"});
    if(!response.ok) throw new Error("download");
    var total=Number(response.headers.get("Content-Length")||0);
    var received=0;
    var started=performance.now();

    if(response.body && response.body.getReader){
      var reader=response.body.getReader();
      while(true){
        var part=await reader.read();
        if(part.done) break;
        received+=part.value.byteLength;
        if(total>0 && onProgress) onProgress(received/total);
      }
    }else{
      var data=await response.arrayBuffer();
      received=data.byteLength;
      if(onProgress) onProgress(1);
    }
    var seconds=(performance.now()-started)/1000;
    return {bytes:received,seconds:seconds};
  }

  async function run(){
    startButton.disabled=true;
    sizeSelect.disabled=true;
    speedEl.textContent="—";
    latencyEl.textContent="—";
    summaryEl.textContent="Test en cours…";
    bar.style.width="0%";

    try{
      setStatus("Mesure de la latence locale…");
      var latency=await measureLatency();
      latencyEl.textContent=latency.toFixed(1)+" ms";

      setStatus("Préparation du lien Wi-Fi…");
      await receive("/speedtest/download?mb=10&warmup="+Date.now(),null);

      var mb=sizeSelect.value;
      setStatus("Mesure du débit Reno9 → appareil…");
      var result=await receive(
        "/speedtest/download?mb="+encodeURIComponent(mb)+"&ts="+Date.now(),
        function(progress){bar.style.width=Math.min(100,progress*100).toFixed(1)+"%";}
      );
      var mbps=(result.bytes*8/result.seconds)/1000000;
      speedEl.textContent=mbps.toFixed(1)+" Mbps";
      bar.style.width="100%";

      var conservative=Math.max(0,mbps*0.70);
      var streams=Math.floor(conservative/3);
      var oneStream=mbps>=4.5;
      summaryEl.textContent=
        "1080p à 3 Mbps : "+(oneStream?"OK":"limite")+
        " · capacité prudente ≈ "+streams+" flux simultané"+(streams===1?"":"s")+
        " à 3 Mbps (30 % de marge Wi-Fi).";
      setStatus("Test terminé. "+(result.bytes/1048576).toFixed(0)+" Mo reçus localement en "+result.seconds.toFixed(1)+" s.");
    }catch(error){
      setStatus("Le test a échoué. Vérifie que tu es toujours connecté au Wi-Fi Shizzi puis réessaie.");
      summaryEl.textContent="Aucun résultat valide.";
      bar.style.width="0%";
    }finally{
      startButton.disabled=false;
      sizeSelect.disabled=false;
    }
  }

  startButton.addEventListener("click",run);
})();
</script></body></html>`

	body := []byte(page)
	header := fmt.Sprintf(
		"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: %d\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
		len(body),
	)
	_, _ = conn.Write([]byte(header))
	if method != http.MethodHead {
		_, _ = conn.Write(body)
	}
}

func (m *TrafficManager) writeMediaLoginRequired(conn net.Conn, method string) {
	body := []byte(`<!doctype html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Compte Shizzi requis</title>
<style>
:root{color-scheme:dark;font-family:Inter,system-ui,-apple-system,sans-serif}
body{margin:0;min-height:100vh;display:grid;place-items:center;padding:24px;background:#07111f;color:#f8fafc}
main{width:min(100%,460px);padding:24px;border:1px solid #ffffff18;border-radius:24px;background:#0f172a}
h1{margin:0 0 10px}.note{color:#94a3b8;line-height:1.5}
a{display:block;margin-top:18px;padding:14px 16px;border-radius:14px;text-align:center;text-decoration:none;background:linear-gradient(90deg,#38bdf8,#34d399);color:#06202a;font-weight:900}
</style></head><body><main>
<h1>Compte Shizzi requis</h1>
<p class="note">Shizzi Media est réservé aux utilisateurs connectés à un compte Shizzi sur cet appareil.</p>
<a href="/">Ouvrir ma connexion compte</a>
</main></body></html>`)
	header := fmt.Sprintf(
		"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: %d\r\nCache-Control: no-store\r\nX-Shizzi-Media-Auth: required\r\nConnection: close\r\n\r\n",
		len(body),
	)
	_, _ = conn.Write([]byte(header))
	if method != http.MethodHead {
		_, _ = conn.Write(body)
	}
}

func mediaProxyTarget(path, rawQuery string) string {
	target := strings.TrimPrefix(path, "/media")
	if target == "" {
		target = "/"
	}
	if !strings.HasPrefix(target, "/") {
		target = "/" + target
	}
	if rawQuery != "" {
		target += "?" + rawQuery
	}
	return target
}

func (m *TrafficManager) serveMediaProxy(
	conn net.Conn,
	request *http.Request,
	clientIP string,
	accountNumber string,
) {
	if request.Method != http.MethodGet && request.Method != http.MethodHead {
		body := []byte("GET/HEAD uniquement")
		header := fmt.Sprintf(
			"HTTP/1.1 405 Method Not Allowed\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: %d\r\nConnection: close\r\n\r\n",
			len(body),
		)
		_, _ = conn.Write([]byte(header))
		_, _ = conn.Write(body)
		return
	}

	// Films can remain open for hours. Override the captive portal's short
	// request deadline while this connection is carrying local media bytes.
	deadline := time.Now().Add(6 * time.Hour)
	_ = conn.SetDeadline(deadline)

	target := mediaProxyTarget(request.URL.Path, request.URL.RawQuery)
	local, err := net.DialTimeout("tcp", mediaBridgeAddress, 3*time.Second)
	if err != nil {
		m.noteMediaDiagnostic(MediaDiagnostic{
			ClientIP:             clientIP,
			Path:                 request.URL.Path,
			AccountAuthenticated: true,
			AccountNumber:        accountNumber,
			ProxyTarget:          target,
			Backend:              mediaBridgeAddress,
			BackendConnected:     false,
			Result:               "backend_unavailable",
			Error:                err.Error(),
		})
		body := []byte("Shizzi Media indisponible. Active le serveur Media sur le routeur.")
		header := fmt.Sprintf(
			"HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: %d\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
			len(body),
		)
		_, _ = conn.Write([]byte(header))
		if request.Method != http.MethodHead {
			_, _ = conn.Write(body)
		}
		return
	}
	defer local.Close()
	_ = local.SetDeadline(deadline)

	if _, err := fmt.Fprintf(
		local,
		"%s %s HTTP/1.1\r\nHost: localhost\r\n",
		request.Method,
		target,
	); err != nil {
		return
	}

	// Range is essential for seeking in large films. Forward the small set of
	// browser headers useful to the local media server and close each upstream
	// response explicitly.
	for _, name := range []string{"Range", "If-Range", "Accept", "User-Agent"} {
		if value := request.Header.Get(name); value != "" {
			if _, err := fmt.Fprintf(local, "%s: %s\r\n", name, value); err != nil {
				return
			}
		}
	}
	if _, err := fmt.Fprintf(local, "X-Shizzi-Media-Account: %s\r\n", accountNumber); err != nil {
		return
	}
	if _, err := io.WriteString(local, "Connection: close\r\n\r\n"); err != nil {
		return
	}

	// Copy the raw upstream response so 206/Content-Range/Content-Length and
	// MIME headers reach Chrome, Edge, Firefox, Safari and Android unchanged.
	copied, copyErr := io.Copy(conn, local)
	event := MediaDiagnostic{
		ClientIP:             clientIP,
		Path:                 request.URL.Path,
		AccountAuthenticated: true,
		AccountNumber:        accountNumber,
		ProxyTarget:          target,
		Backend:              mediaBridgeAddress,
		BackendConnected:     true,
		BytesCopied:          copied,
		Result:               "proxied",
	}
	if copyErr != nil {
		event.Result = "proxy_copy_error"
		event.Error = copyErr.Error()
	}
	m.noteMediaDiagnostic(event)
}


func chatProxyTarget(path, rawQuery string) string {
	target := strings.TrimPrefix(path, "/chat")
	if target == "" {
		target = "/"
	}
	if !strings.HasPrefix(target, "/") {
		target = "/" + target
	}
	if rawQuery != "" {
		target += "?" + rawQuery
	}
	return target
}

func (m *TrafficManager) writeChatLoginRequired(conn net.Conn, method string) {
	body := []byte(`<!doctype html><html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Compte Shizzi requis</title>
<style>:root{color-scheme:dark;font-family:system-ui,sans-serif}body{margin:0;min-height:100vh;display:grid;place-items:center;padding:24px;background:#07111f;color:#f8fafc}main{width:min(100%,460px);padding:24px;border:1px solid #ffffff18;border-radius:24px;background:#0f172a}a{display:block;margin-top:18px;padding:14px;border-radius:14px;text-align:center;text-decoration:none;background:#22d3ee;color:#06202a;font-weight:900}.note{color:#94a3b8;line-height:1.5}</style>
</head><body><main><h1>Compte Shizzi requis</h1><p class="note">Ouvre d’abord ton compte Shizzi sur cet appareil pour utiliser la messagerie locale.</p><a href="/">Ouvrir ma connexion compte</a></main></body></html>`)
	header := fmt.Sprintf(
		"HTTP/1.1 200 OK\r\n"+
			"Content-Type: text/html; charset=utf-8\r\n"+
			"Content-Length: %d\r\n"+
			"Cache-Control: no-store\r\n"+
			"X-Shizzi-Chat-Auth: required\r\n"+
			"Connection: close\r\n\r\n",
		len(body),
	)
	_, _ = conn.Write([]byte(header))
	if method != http.MethodHead {
		_, _ = conn.Write(body)
	}
}

func (m *TrafficManager) serveChatProxy(
	conn net.Conn,
	request *http.Request,
	accountNumber string,
) {
	if request.Method != http.MethodGet &&
		request.Method != http.MethodHead &&
		request.Method != http.MethodPost {
		writeJSONStatus(conn, "405 Method Not Allowed", map[string]any{
			"ok": false, "message": "Méthode interdite.",
		})
		return
	}

	_ = conn.SetDeadline(time.Now().Add(30 * time.Second))
	target := chatProxyTarget(request.URL.Path, request.URL.RawQuery)
	local, err := net.DialTimeout("tcp", chatBridgeAddress, 3*time.Second)
	if err != nil {
		writeJSONStatus(conn, "503 Service Unavailable", map[string]any{
			"ok": false, "message": "Messagerie Shizzi indisponible.",
		})
		return
	}
	defer local.Close()
	_ = local.SetDeadline(time.Now().Add(30 * time.Second))

	var body []byte
	if request.Method == http.MethodPost {
		body, err = io.ReadAll(io.LimitReader(request.Body, 64*1024+1))
		if err != nil || len(body) > 64*1024 {
			writeJSONStatus(conn, "413 Payload Too Large", map[string]any{
				"ok": false, "message": "Requête trop volumineuse.",
			})
			return
		}
	}

	if _, err := fmt.Fprintf(
		local,
		"%s %s HTTP/1.1\r\nHost: localhost\r\nX-Shizzi-Chat-Account: %s\r\n",
		request.Method,
		target,
		accountNumber,
	); err != nil {
		return
	}
	if value := request.Header.Get("Content-Type"); value != "" {
		if _, err := fmt.Fprintf(local, "Content-Type: %s\r\n", value); err != nil {
			return
		}
	}
	if request.Method == http.MethodPost {
		if _, err := fmt.Fprintf(local, "Content-Length: %d\r\n", len(body)); err != nil {
			return
		}
	}
	if _, err := io.WriteString(local, "Connection: close\r\n\r\n"); err != nil {
		return
	}
	if len(body) > 0 {
		if _, err := local.Write(body); err != nil {
			return
		}
	}
	_, _ = io.Copy(conn, local)
}

func (m *TrafficManager) serveClientAppDownload(conn net.Conn, request *http.Request) {
	m.mu.Lock()
	app := m.portalClientApp
	m.mu.Unlock()

	if !app.Available {
		body := []byte("Shizzi+ indisponible")
		header := fmt.Sprintf(
			"HTTP/1.1 404 Not Found\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: %d\r\nConnection: close\r\n\r\n",
			len(body),
		)
		_, _ = conn.Write([]byte(header))
		if request.Method != http.MethodHead {
			_, _ = conn.Write(body)
		}
		return
	}

	_ = conn.SetDeadline(time.Now().Add(2 * time.Minute))
	local, err := net.DialTimeout("tcp", clientAppBridgeAddress, 3*time.Second)
	if err != nil {
		body := []byte("Téléchargement Shizzi+ momentanément indisponible.")
		header := fmt.Sprintf(
			"HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: %d\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
			len(body),
		)
		_, _ = conn.Write([]byte(header))
		if request.Method != http.MethodHead {
			_, _ = conn.Write(body)
		}
		return
	}
	defer local.Close()
	_ = local.SetDeadline(time.Now().Add(2 * time.Minute))

	if _, err := fmt.Fprintf(
		local,
		"%s /shizzi-plus.apk HTTP/1.1\r\nHost: localhost\r\n",
		request.Method,
	); err != nil {
		return
	}
	for _, name := range []string{"Range", "If-Range", "User-Agent"} {
		if value := request.Header.Get(name); value != "" {
			if _, err := fmt.Fprintf(local, "%s: %s\r\n", name, value); err != nil {
				return
			}
		}
	}
	if _, err := io.WriteString(local, "Connection: close\r\n\r\n"); err != nil {
		return
	}
	_, _ = io.Copy(conn, local)
}

func (m *TrafficManager) writePortalStatusJSON(conn net.Conn, ip string) {
	payload := m.portalStatus(ip)
	body, _ := json.Marshal(payload)
	writeHTTP(conn, "application/json; charset=utf-8", body)
}

type portalStatusPayload struct {
	Authenticated   bool   `json:"authenticated"`
	Authorized      bool   `json:"authorized"`
	AccountNumber   string `json:"accountNumber,omitempty"`
	AccountName     string `json:"accountName,omitempty"`
	Plan            string `json:"plan,omitempty"`
	RemainingBytes  int64  `json:"remainingBytes"`
	UsedBytes       int64  `json:"usedBytes"`
	SessionBytes    int64  `json:"sessionBytes"`
	ExpiresAtMillis int64  `json:"expiresAtMillis"`
	DownloadBps     int64  `json:"downloadBps"`
	UploadBps       int64  `json:"uploadBps"`
	Unlimited       bool   `json:"unlimited"`
	StoredDataBytes int64  `json:"storedDataBytes"`
	ClaimMessage    string `json:"claimMessage,omitempty"`
	ClaimSuccess    bool   `json:"claimSuccess,omitempty"`
	ClaimPending    bool   `json:"claimPending,omitempty"`
}

// portalStatus reports the account values for its single active device
// session. The account remains portable after that session ends.
func (m *TrafficManager) portalStatus(ip string) portalStatusPayload {
	now := time.Now().UnixMilli()
	m.mu.Lock()
	defer m.mu.Unlock()

	authorization := m.portalAuthorized[ip]
	if authorization == nil {
		return portalStatusPayload{}
	}
	account, ok := m.portalAccounts[authorization.AccountNumber]
	if !ok {
		return portalStatusPayload{}
	}

	// Android's totals include what it already applied from this datapath;
	// add only what was counted after that, so every device of the account
	// sees the same live total.
	usedBytes := account.TotalUpBytes + account.TotalDownBytes
	if usage := m.accountUsage[account.Number]; usage != nil {
		pending := usage.UpBytes + usage.DownBytes - account.UsageMarkerBytes
		if pending > 0 {
			usedBytes += pending
		}
	}

	unlimited := account.hasUnlimited(now)
	remaining := m.remainingDataLocked(account)
	if !account.dataValid(now) {
		remaining = 0
	}

	plan := "Aucun forfait actif"
	expires := int64(0)
	switch {
	case unlimited:
		plan = account.UnlimitedPlanName
		if strings.TrimSpace(plan) == "" {
			plan = "Illimité"
		}
		expires = account.UnlimitedUntilMillis
	case account.dataValid(now) && remaining > 0:
		plan = "Data"
		expires = account.DataValidUntilMillis
	}

	payload := portalStatusPayload{
		Authenticated:   true,
		Authorized:      m.portalAuthorizedLocked(ip, now),
		AccountNumber:   account.Number,
		AccountName:     account.Name,
		Plan:            plan,
		RemainingBytes:  remaining,
		UsedBytes:       usedBytes,
		SessionBytes:    authorization.UpBytes + authorization.DownBytes,
		ExpiresAtMillis: expires,
		DownloadBps:     account.downloadBps(now),
		UploadBps:       account.uploadBps(now),
		Unlimited:       unlimited,
		StoredDataBytes: remaining,
	}
	for _, claim := range m.portalRechargeClaims {
		if claim.IP == ip {
			payload.ClaimPending = true
			payload.ClaimMessage = "Recharge en cours de validation…"
		}
	}
	if result, ok := m.portalClaimResults[ip]; ok && !payload.ClaimPending {
		payload.ClaimSuccess = result.Success
		payload.ClaimMessage = result.Message
	}
	return payload
}

func (m *TrafficManager) writePortalHTML(
	conn net.Conn,
	ip, message string,
	isError bool,
) {
	status := m.portalStatus(ip)

	m.mu.Lock()
	title := m.portalTitle
	subtitle := m.portalMessage
	custom := m.portalHTML
	clientApp := m.portalClientApp
	m.mu.Unlock()

	alert := ""
	if message != "" {
		className := "ok"
		if isError {
			className = "error"
		}
		alert = fmt.Sprintf(
			`<div class="alert %s">%s</div>`,
			className,
			html.EscapeString(message),
		)
	}

	content := ""
	if !status.Authenticated {
		content = `<form method="post" action="/login">
<label>Numéro de compte</label>
<input name="account" inputmode="numeric" autocomplete="username" required>
<label>Mot de passe</label>
<input name="pin" type="password" autocomplete="current-password" required>
<button type="submit">Ouvrir la connexion compte</button>
</form>`
	} else {
		remaining := formatPortalBytes(status.RemainingBytes)
		if status.Unlimited {
			remaining = "Illimité"
		}
		reserveStyle := " style=\"display:none\""
		if status.Unlimited && status.StoredDataBytes > 0 {
			reserveStyle = ""
		}
		reserve := fmt.Sprintf(
			`<div id="shizzi-reserve-wrap"%s><span>Data en réserve</span><strong id="shizzi-reserve">%s</strong></div>`,
			reserveStyle,
			html.EscapeString(formatPortalBytes(status.StoredDataBytes)),
		)
		access := `<div id="shizzi-access" class="alert ok">Internet actif sur cet appareil.</div>`
		if !status.Authorized {
			access = `<div id="shizzi-access" class="alert error">Pas d'Internet : aucun forfait actif. Rechargez avec un voucher.</div>`
		}
		claimClass := "error"
		if status.ClaimSuccess || status.ClaimPending {
			claimClass = "ok"
		}
		claimStyle := ` style="display:none"`
		if status.ClaimMessage != "" {
			claimStyle = ""
		}
		claim := fmt.Sprintf(
			`<div id="shizzi-claim" class="alert %s"%s>%s</div>`,
			claimClass,
			claimStyle,
			html.EscapeString(status.ClaimMessage),
		)
		content = fmt.Sprintf(
			`%s%s<div id="shizzi-account" class="account"><div class="eyebrow">COMPTE SHIZZI</div>
<h2 id="shizzi-account-name">%s</h2><div class="muted">N° <span id="shizzi-account-number">%s</span></div>
<div class="plan"><span>Forfait actif</span><strong id="shizzi-plan">%s</strong></div>
<div class="grid"><div><span>Restant</span><strong id="shizzi-remaining">%s</strong></div>
<div><span>Utilisé (compte)</span><strong id="shizzi-used">%s</strong></div>
<div><span>Débit</span><strong id="shizzi-rate">%s ↓ / %s ↑</strong></div>
<div><span>Validité</span><strong id="shizzi-expiry">%s</strong></div>%s</div>
<form method="post" action="/recharge"><label>Recharger avec un voucher</label>
<input name="code" autocomplete="one-time-code" autocapitalize="characters" required>
<button type="submit">Recharger</button></form>
<form method="post" action="/logout"><button class="secondary" type="submit">Fermer la session sur cet appareil</button></form>
<a class="status-link" href="http://192.0.2.1/">Mon compte : http://192.0.2.1/</a></div>`,
			access,
			claim,
			html.EscapeString(status.AccountName),
			html.EscapeString(status.AccountNumber),
			html.EscapeString(status.Plan),
			html.EscapeString(remaining),
			html.EscapeString(formatPortalBytes(status.UsedBytes)),
			html.EscapeString(formatPortalRate(status.DownloadBps)),
			html.EscapeString(formatPortalRate(status.UploadBps)),
			html.EscapeString(formatPortalExpiry(status.ExpiresAtMillis)),
			reserve,
		)
	}

	if status.Authenticated {
	content += `<section class="media-link"><div class="eyebrow">MEDIA LOCAL</div>
<strong>Shizzi Media</strong>
<p>Films, séries et musique disponibles dans le navigateur sur ce Wi-Fi, sans utiliser Internet ni le quota Data.</p>
<a class="media-button" href="/media/">Ouvrir Shizzi Media</a>
<div class="media-note">Compatible PC, téléphone et tablette · lecture locale</div></section>
<section class="media-link"><div class="eyebrow">MESSAGERIE LOCALE</div>
<strong>Messagerie Shizzi</strong>
<p>Messages privés et groupes entre comptes Shizzi connectés, avec historique stocké sur le routeur.</p>
<a class="media-button" href="/chat/">Ouvrir la messagerie</a>
<div class="media-note">Trafic local · ne consomme pas le quota Internet</div></section>`
	}

	content += `<section class="speedtest-link"><div class="eyebrow">RÉSEAU LOCAL</div>
<strong>Test de débit Shizzi</strong>
<p>Mesurez la vitesse réelle du Reno9 vers cet appareil, sans utiliser Internet.</p>
<a class="speedtest-button" href="/speedtest/">Tester le débit local</a>
<div class="speedtest-note">Navigateur uniquement · aucun Termux nécessaire</div></section>`

	if clientApp.Available {
		shortSHA := clientApp.SHA256
		if len(shortSHA) > 12 {
			shortSHA = shortSHA[:12]
		}
		content += fmt.Sprintf(
			`<section class="app-download"><div class="eyebrow">APPLICATION CLIENT</div>
<strong>Shizzi+ %s</strong>
<p>Installez Shizzi+ directement depuis ce Wi-Fi. Aucun Internet ni quota Data n'est utilisé.</p>
<a class="download-button" href="http://192.0.2.1/shizzi-plus.apk" download="%s" target="_blank" rel="noopener">Télécharger Shizzi+</a>
<div id="shizzi-download-status" class="app-note" aria-live="polite"></div>
<div class="app-meta">%s · SHA-256 %s…</div>
<div class="app-note">Lien direct : http://192.0.2.1/shizzi-plus.apk</div>
<div class="app-note">Android peut demander d'autoriser l'installation depuis cette source.</div></section>`,
			html.EscapeString(clientApp.Version),
			html.EscapeString(clientApp.FileName),
			html.EscapeString(formatPortalFileSize(clientApp.SizeBytes)),
			html.EscapeString(shortSHA),
		)
	}

	refresh := ""
	if status.ClaimPending {
		refresh = `<meta http-equiv="refresh" content="3;url=/">`
	}

	page := fmt.Sprintf(`<!doctype html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">%s
<title>%s</title>
<style>
:root{color-scheme:dark;font-family:Inter,system-ui,-apple-system,sans-serif}
*{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;padding:20px;color:#f8fafc;background:linear-gradient(145deg,#07111f,#111827 55%%,#0f172a)}
.card{width:min(100%%,460px);background:#0f172a;border:1px solid #ffffff18;border-radius:28px;padding:24px;box-shadow:0 26px 80px #0008}
h1{margin:0;font-size:28px}.sub,.muted{color:#94a3b8}.sub{margin:6px 0 20px}.eyebrow{font-size:11px;letter-spacing:.14em;color:#7dd3fc;font-weight:800}
h2{margin:5px 0 3px}.alert{padding:12px 14px;border-radius:14px;margin:12px 0;font-weight:700}.alert.ok{background:#064e3b}.alert.error{background:#7f1d1d}
label{display:block;color:#cbd5e1;font-size:12px;font-weight:700;margin:14px 0 6px}input,button{width:100%%;font:inherit;border-radius:14px;padding:14px 16px}
input{border:1px solid #334155;background:#0b1220;color:#fff;outline:none}button{border:0;background:linear-gradient(90deg,#22d3ee,#34d399);color:#06202a;font-weight:900;margin-top:12px}
.plan{margin-top:18px;padding:16px;border-radius:18px;background:#ffffff08}.plan span,.grid span{display:block;color:#94a3b8;font-size:11px}.plan strong{display:block;margin-top:4px;font-size:18px}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:12px}.grid>div{padding:12px;border-radius:14px;background:#ffffff08}.grid strong{display:block;margin-top:4px;font-size:13px}
.status-link{display:block;text-align:center;margin-top:16px;color:#7dd3fc;text-decoration:none;font-weight:700;font-size:13px}
button.secondary{background:#1e293b;color:#e2e8f0}
.media-link,.speedtest-link,.app-download{margin-top:20px;padding:16px;border:1px solid #22d3ee55;border-radius:18px;background:#071b2a}
.media-link>strong,.speedtest-link>strong,.app-download>strong{display:block;margin:5px 0 6px;font-size:18px}.media-link p,.speedtest-link p,.app-download p{margin:0 0 12px;color:#cbd5e1;font-size:13px;line-height:1.45}
.media-button,.speedtest-button,.download-button{display:block;width:100%%;border-radius:14px;padding:14px 16px;text-align:center;text-decoration:none;background:linear-gradient(90deg,#38bdf8,#34d399);color:#06202a;font-weight:900}
.media-note,.speedtest-note,.app-meta,.app-note{margin-top:9px;color:#94a3b8;font-size:11px;word-break:break-word}
</style></head><body><main class="card"><h1>%s</h1><p class="sub">%s</p>%s%s</main></body></html>`,
		refresh,
		html.EscapeString(title),
		html.EscapeString(title),
		html.EscapeString(subtitle),
		alert,
		content,
	)

	if strings.TrimSpace(custom) != "" {
		page = applyPortalCustomization(custom, title, subtitle, alert, content)
		if refresh != "" {
			lower := strings.ToLower(page)
			if index := strings.LastIndex(lower, "</head>"); index >= 0 {
				page = page[:index] + refresh + page[index:]
			} else {
				page = refresh + page
			}
		}
	}

	page = injectPortalAutoRefresh(page)
	page = injectClientAppDownload(page)
	writeHTTP(conn, "text/html; charset=utf-8", []byte(page))
}

func applyPortalCustomization(
	custom, title, subtitle, status, content string,
) string {
	hasFunctionalPlaceholder :=
		strings.Contains(custom, "{{CONTENT}}") ||
			strings.Contains(custom, "{{ACCOUNT_PANEL}}") ||
			strings.Contains(custom, "{{LOGIN_FORM}}")

	functional := status + content
	rendered := strings.NewReplacer(
		"{{TITLE}}", html.EscapeString(title),
		"{{MESSAGE}}", html.EscapeString(subtitle),
		"{{STATUS}}", status,
		"{{CONTENT}}", functional,
		"{{ACCOUNT_PANEL}}", functional,
		"{{LOGIN_FORM}}", functional,
		"{{FORM_ACTION}}", "/login",
	).Replace(custom)

	if hasFunctionalPlaceholder {
		return rendered
	}

	lower := strings.ToLower(rendered)
	if index := strings.LastIndex(lower, "</body>"); index >= 0 {
		return rendered[:index] + functional + rendered[index:]
	}
	return rendered + functional
}

func injectClientAppDownload(page string) string {
	if !strings.Contains(page, "shizzi-plus.apk") {
		return page
	}

	// Do not fetch the APK into JavaScript first. On Android captive portals that
	// extra fetch/blob/object-URL hop can delay the DownloadManager hand-off by
	// tens of seconds even for a tiny local file. Keep the native <a download>
	// navigation intact so the browser starts streaming from Shizzi immediately.
	script := `<script>
(function(){
  var link=document.querySelector('a.download-button[href*="shizzi-plus.apk"]');
  if(!link || link.dataset.shizziNativeDownload==="1") return;
  link.dataset.shizziNativeDownload="1";

  var status=document.getElementById("shizzi-download-status");
  function setStatus(message){ if(status) status.textContent=message; }

  link.addEventListener("click",function(){
    link.textContent="Téléchargement lancé…";
    setStatus("Téléchargement direct depuis le routeur Shizzi…");
    setTimeout(function(){
      link.textContent="Télécharger Shizzi+";
    },1500);
  });
})();
</script>`

	lower := strings.ToLower(page)
	if index := strings.LastIndex(lower, "</body>"); index >= 0 {
		return page[:index] + script + page[index:]
	}
	return page + script
}

func injectPortalAutoRefresh(page string) string {
	script := `<script>
(function(){
  function byId(id){ return document.getElementById(id); }
  function setText(id, value){ var el=byId(id); if(el){ el.textContent=value; } }
  function bytes(value){
    value=Number(value||0);
    if(value<=0) return "0 Mo";
    if(value>=1000000000) return (value/1000000000).toFixed(2)+" Go";
    return (value/1000000).toFixed(1)+" Mo";
  }
  function rate(value){
    value=Number(value||0);
    if(value<=0) return "0 Mbps";
    return (value/1000000).toFixed(1)+" Mbps";
  }
  function expiry(epoch){
    epoch=Number(epoch||0);
    if(epoch<=0) return "—";
    var remaining=epoch-Date.now();
    if(remaining<=0) return "Expiré";
    var totalMinutes=Math.floor(remaining/60000);
    var days=Math.floor(totalMinutes/1440);
    var hours=Math.floor((totalMinutes%1440)/60);
    if(days>0) return days+" j "+hours+" h";
    return hours+" h";
  }
  async function shizziRefresh(){
    try{
      var response=await fetch("/status.json?ts="+Date.now(),{cache:"no-store"});
      if(!response.ok) return;
      var s=await response.json();
      if(!s.authenticated){
        if(byId("shizzi-account")) location.reload();
        return;
      }
      setText("shizzi-account-name",s.accountName||"");
      setText("shizzi-account-number",s.accountNumber||"");
      setText("shizzi-plan",s.plan||"Aucun forfait actif");
      setText("shizzi-remaining",s.unlimited ? "Illimité" : bytes(s.remainingBytes));
      setText("shizzi-used",bytes(s.usedBytes));
      setText("shizzi-rate",rate(s.downloadBps)+" ↓ / "+rate(s.uploadBps)+" ↑");
      setText("shizzi-expiry",expiry(s.expiresAtMillis));

      var access=byId("shizzi-access");
      if(access){
        access.className=s.authorized ? "alert ok" : "alert error";
        access.textContent=s.authorized
          ? "Internet actif sur cet appareil."
          : "Pas d'Internet : aucun forfait actif. Rechargez avec un voucher.";
      }

      var reserveWrap=byId("shizzi-reserve-wrap");
      if(reserveWrap){
        var showReserve=Boolean(s.unlimited && Number(s.storedDataBytes||0)>0);
        reserveWrap.style.display=showReserve ? "" : "none";
        setText("shizzi-reserve",bytes(s.storedDataBytes));
      }

      var claim=byId("shizzi-claim");
      if(claim){
        if(s.claimMessage){
          claim.style.display="";
          claim.className=(s.claimSuccess||s.claimPending) ? "alert ok" : "alert error";
          claim.textContent=s.claimMessage;
        }else{
          claim.style.display="none";
          claim.textContent="";
        }
      }
    }catch(_){}
  }
  window.shizziRefresh=shizziRefresh;
  shizziRefresh();
  setInterval(shizziRefresh,2000);
})();
</script>`
	lower := strings.ToLower(page)
	if index := strings.LastIndex(lower, "</body>"); index >= 0 {
		return page[:index] + script + page[index:]
	}
	return page + script
}

func writeHTTP(conn net.Conn, contentType string, body []byte) {
	header := fmt.Sprintf(
		"HTTP/1.1 200 OK\r\nContent-Type: %s\r\nCache-Control: no-store\r\nContent-Length: %d\r\nConnection: close\r\n\r\n",
		contentType,
		len(body),
	)
	_, _ = conn.Write([]byte(header))
	_, _ = conn.Write(body)
}

func formatPortalFileSize(value int64) string {
	if value <= 0 {
		return "taille inconnue"
	}
	if value < 1_000_000 {
		return fmt.Sprintf("%.0f Ko", float64(value)/1_000.0)
	}
	return fmt.Sprintf("%.1f Mo", float64(value)/1_000_000.0)
}

func formatPortalBytes(value int64) string {
	if value <= 0 {
		return "0 Mo"
	}
	if value >= 1_000_000_000 {
		return fmt.Sprintf("%.2f Go", float64(value)/1_000_000_000.0)
	}
	return fmt.Sprintf("%.1f Mo", float64(value)/1_000_000.0)
}

func formatPortalRate(value int64) string {
	if value <= 0 {
		return "0 Mbps"
	}
	return fmt.Sprintf("%.1f Mbps", float64(value)/1_000_000.0)
}

func formatPortalExpiry(epochMillis int64) string {
	if epochMillis <= 0 {
		return "—"
	}
	remaining := epochMillis - time.Now().UnixMilli()
	if remaining <= 0 {
		return "Expiré"
	}
	totalMinutes := remaining / 60_000
	days := totalMinutes / 1440
	hours := (totalMinutes % 1440) / 60
	if days > 0 {
		return fmt.Sprintf("%d j %d h", days, hours)
	}
	return fmt.Sprintf("%d h", hours)
}
