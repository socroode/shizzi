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
	Number                       string `json:"number"`
	Name                         string `json:"name"`
	PinSalt                      string `json:"pinSalt"`
	PinHash                      string `json:"pinHash"`
	Enabled                      bool   `json:"enabled"`
	DataBalanceBytes             int64  `json:"dataBalanceBytes"`
	DataValidUntilMillis         int64  `json:"dataValidUntilMillis"`
	DataDownloadBitsPerSecond    int64  `json:"dataDownloadBps"`
	DataUploadBitsPerSecond      int64  `json:"dataUploadBps"`
	UnlimitedUntilMillis         int64  `json:"unlimitedUntilMillis"`
	UnlimitedDownloadBitsPerSecond int64 `json:"unlimitedDownloadBps"`
	UnlimitedUploadBitsPerSecond int64  `json:"unlimitedUploadBps"`
	UnlimitedPlanName            string `json:"unlimitedPlanName"`
}

func (a PortalAccount) hasUnlimited(nowMillis int64) bool {
	return a.Enabled && a.UnlimitedUntilMillis > nowMillis
}

func (a PortalAccount) hasData(nowMillis int64) bool {
	return a.Enabled &&
		a.DataBalanceBytes > 0 &&
		(a.DataValidUntilMillis <= 0 || nowMillis < a.DataValidUntilMillis)
}

func (a PortalAccount) hasInternet(nowMillis int64) bool {
	return a.hasUnlimited(nowMillis) || a.hasData(nowMillis)
}

func (a PortalAccount) downloadBps(nowMillis int64) int64 {
	if a.hasUnlimited(nowMillis) {
		return a.UnlimitedDownloadBitsPerSecond
	}
	if a.hasData(nowMillis) {
		return a.DataDownloadBitsPerSecond
	}
	return 0
}

func (a PortalAccount) uploadBps(nowMillis int64) int64 {
	if a.hasUnlimited(nowMillis) {
		return a.UnlimitedUploadBitsPerSecond
	}
	if a.hasData(nowMillis) {
		return a.DataUploadBitsPerSecond
	}
	return 0
}

type portalConfig struct {
	Title    string          `json:"title"`
	Message  string          `json:"message"`
	Accounts []PortalAccount `json:"accounts"`
}

type PortalAuthorization struct {
	AccountNumber string `json:"accountNumber"`
	StartedAtMillis int64 `json:"startedAtMillis"`
	StartClientBytes int64 `json:"-"`
}

type PortalAuthorizationStatus struct {
	IP                   string `json:"ip"`
	AccountNumber        string `json:"accountNumber"`
	StartedAtMillis      int64  `json:"startedAtMillis"`
	SessionDataUsedBytes int64  `json:"sessionDataUsedBytes"`
}

type PortalRechargeClaim struct {
	IP            string `json:"ip"`
	AccountNumber string `json:"accountNumber"`
	Code          string `json:"code"`
	ClaimedAtMillis int64 `json:"claimedAtMillis"`
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

	now := time.Now().UnixMilli()
	for ip, authorization := range m.portalAuthorized {
		account, ok := accounts[authorization.AccountNumber]
		if !ok || !account.hasInternet(now) {
			delete(m.portalAuthorized, ip)
			if client := m.clients[ip]; client != nil {
				client.applyPolicy(ClientPolicy{Blocked: true})
			}
			continue
		}
		if client := m.clients[ip]; client != nil {
			client.applyPolicy(ClientPolicy{
				DownloadBitsPerSecond: account.downloadBps(now),
				UploadBitsPerSecond:   account.uploadBps(now),
			})
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

func (m *TrafficManager) portalAuthorizedLocked(ip string, nowMillis int64) bool {
	authorization, ok := m.portalAuthorized[ip]
	if !ok {
		return false
	}
	account, ok := m.portalAccounts[authorization.AccountNumber]
	if !ok || !account.hasInternet(nowMillis) {
		return false
	}
	if account.hasUnlimited(nowMillis) {
		return true
	}

	client := m.clientLocked(ip)
	sessionUsed := (client.UpBytes + client.DownBytes - authorization.StartClientBytes)
	if sessionUsed < 0 {
		sessionUsed = 0
	}
	return account.DataBalanceBytes > sessionUsed
}

func (m *TrafficManager) submitPortalAccountLogin(
	ip, rawNumber, pin string,
) (bool, string) {
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
	client := m.clientLocked(ip)
	client.applyPolicy(ClientPolicy{
		DownloadBitsPerSecond: account.downloadBps(now),
		UploadBitsPerSecond:   account.uploadBps(now),
		Blocked:               !account.hasInternet(now),
	})
	m.portalAuthorized[ip] = PortalAuthorization{
		AccountNumber:    number,
		StartedAtMillis:  now,
		StartClientBytes: client.UpBytes + client.DownBytes,
	}
	if !account.hasInternet(now) {
		return true, "Compte connecté. Recharge requise."
	}
	return true, "Connexion autorisée."
}

func (m *TrafficManager) submitPortalRecharge(ip, rawCode string) (bool, string) {
	code := normalizeVoucherCode(rawCode)
	if code == "" {
		return false, "Entrez un voucher."
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	authorization, ok := m.portalAuthorized[ip]
	if !ok {
		return false, "Connectez-vous d'abord à votre compte."
	}

	for _, claim := range m.portalRechargeClaims {
		if claim.IP == ip && claim.AccountNumber == authorization.AccountNumber &&
			claim.Code == code {
			return true, "Recharge déjà transmise."
		}
	}

	m.portalRechargeClaims = append(m.portalRechargeClaims, PortalRechargeClaim{
		IP:              ip,
		AccountNumber:   authorization.AccountNumber,
		Code:            code,
		ClaimedAtMillis: time.Now().UnixMilli(),
	})
	return true, "Recharge transmise."
}

func (m *TrafficManager) clearPortalClaims() {
	m.mu.Lock()
	m.portalRechargeClaims = nil
	m.mu.Unlock()
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
	Authenticated bool   `json:"authenticated"`
	Authorized    bool   `json:"authorized"`
	AccountNumber string `json:"accountNumber,omitempty"`
	AccountName   string `json:"accountName,omitempty"`
	Plan          string `json:"plan,omitempty"`
	RemainingBytes int64 `json:"remainingBytes"`
	UsedBytes     int64  `json:"usedBytes"`
	ExpiresAtMillis int64 `json:"expiresAtMillis"`
	DownloadBps   int64  `json:"downloadBps"`
	UploadBps     int64  `json:"uploadBps"`
	Unlimited     bool   `json:"unlimited"`
}

func (m *TrafficManager) portalStatus(ip string) portalStatusPayload {
	now := time.Now().UnixMilli()
	m.mu.Lock()
	defer m.mu.Unlock()

	authorization, ok := m.portalAuthorized[ip]
	if !ok {
		return portalStatusPayload{}
	}
	account, ok := m.portalAccounts[authorization.AccountNumber]
	if !ok {
		return portalStatusPayload{}
	}
	client := m.clientLocked(ip)
	used := client.UpBytes + client.DownBytes - authorization.StartClientBytes
	if used < 0 {
		used = 0
	}
	unlimited := account.hasUnlimited(now)
	remaining := account.DataBalanceBytes
	if !unlimited {
		remaining -= used
		if remaining < 0 {
			remaining = 0
		}
	}

	plan := "Data"
	expires := account.DataValidUntilMillis
	if unlimited {
		plan = account.UnlimitedPlanName
		if strings.TrimSpace(plan) == "" {
			plan = "Illimité"
		}
		expires = account.UnlimitedUntilMillis
	}

	return portalStatusPayload{
		Authenticated:   true,
		Authorized:      m.portalAuthorizedLocked(ip, now),
		AccountNumber:   account.Number,
		AccountName:     account.Name,
		Plan:            plan,
		RemainingBytes:  remaining,
		UsedBytes:       used,
		ExpiresAtMillis: expires,
		DownloadBps:     account.downloadBps(now),
		UploadBps:       account.uploadBps(now),
		Unlimited:       unlimited,
	}
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
		content = fmt.Sprintf(
			`<div class="account"><div class="eyebrow">COMPTE SHIZZI</div>
<h2>%s</h2><div class="muted">N° %s</div>
<div class="plan"><span>Forfait actif</span><strong>%s</strong></div>
<div class="grid"><div><span>Restant</span><strong>%s</strong></div>
<div><span>Utilisé</span><strong>%s</strong></div>
<div><span>Débit</span><strong>%s ↓ / %s ↑</strong></div>
<div><span>Validité</span><strong>%s</strong></div></div>
<form method="post" action="/recharge"><label>Recharger avec un voucher</label>
<input name="code" autocomplete="one-time-code" autocapitalize="characters" required>
<button type="submit">Recharger</button></form>
<a class="status-link" href="/status.json">Données de consommation</a></div>`,
			html.EscapeString(status.AccountName),
			html.EscapeString(status.AccountNumber),
			html.EscapeString(status.Plan),
			html.EscapeString(remaining),
			html.EscapeString(formatPortalBytes(status.UsedBytes)),
			html.EscapeString(formatPortalRate(status.DownloadBps)),
			html.EscapeString(formatPortalRate(status.UploadBps)),
			html.EscapeString(formatPortalExpiry(status.ExpiresAtMillis)),
		)
	}

	page := fmt.Sprintf(`<!doctype html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
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
</style></head><body><main class="card"><h1>%s</h1><p class="sub">%s</p>%s%s</main></body></html>`,
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
