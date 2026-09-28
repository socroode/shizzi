package datapath

import (
	"bufio"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"html"
	"net"
	"net/http"
	"strings"
	"time"
)

const portalIP = "192.0.2.1"

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

type portalConfig struct {
	Title    string          `json:"title"`
	Message  string          `json:"message"`
	Accounts []PortalAccount `json:"accounts"`
	// ClaimResults lets Android tell the portal how a voucher claim ended so
	// the client sees "accepted"/"rejected" instead of a silent drop.
	ClaimResults []PortalClaimResult `json:"claimResults"`
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
	return m.portalRequired && !m.portalAuthorizedLocked(ip, time.Now().UnixMilli())
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

func (m *TrafficManager) servePortal(conn net.Conn, clientIP string) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(15 * time.Second))

	request, err := http.ReadRequest(bufio.NewReader(conn))
	if err != nil {
		return
	}
	defer request.Body.Close()

	path := request.URL.Path
	switch {
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
		reserve := ""
		if status.Unlimited && status.StoredDataBytes > 0 {
			reserve = fmt.Sprintf(
				`<div><span>Data en réserve</span><strong>%s</strong></div>`,
				html.EscapeString(formatPortalBytes(status.StoredDataBytes)),
			)
		}
		access := `<div class="alert ok">Internet actif sur cet appareil.</div>`
		if !status.Authorized {
			access = `<div class="alert error">Pas d'Internet : aucun forfait actif. Rechargez avec un voucher.</div>`
		}
		claim := ""
		if status.ClaimMessage != "" {
			className := "error"
			if status.ClaimSuccess || status.ClaimPending {
				className = "ok"
			}
			claim = fmt.Sprintf(`<div class="alert %s">%s</div>`, className, html.EscapeString(status.ClaimMessage))
		}
		content = fmt.Sprintf(
			`%s%s<div class="account"><div class="eyebrow">COMPTE SHIZZI</div>
<h2>%s</h2><div class="muted">N° %s</div>
<div class="plan"><span>Forfait actif</span><strong>%s</strong></div>
<div class="grid"><div><span>Restant</span><strong>%s</strong></div>
<div><span>Utilisé (compte)</span><strong>%s</strong></div>
<div><span>Débit</span><strong>%s ↓ / %s ↑</strong></div>
<div><span>Validité</span><strong>%s</strong></div>%s</div>
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
</style></head><body><main class="card"><h1>%s</h1><p class="sub">%s</p>%s%s</main></body></html>`,
		refresh,
		html.EscapeString(title),
		html.EscapeString(title),
		html.EscapeString(subtitle),
		alert,
		content,
	)
	writeHTTP(conn, "text/html; charset=utf-8", []byte(page))
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
