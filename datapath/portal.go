package datapath

import (
	"bufio"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"html"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type PortalPass struct {
	Code            string `json:"code"`
	Name            string `json:"name"`
	DownloadBps     int64  `json:"downloadBps"`
	UploadBps       int64  `json:"uploadBps"`
	DownloadUnit    string `json:"downloadUnit"`
	UploadUnit      string `json:"uploadUnit"`
	QuotaBytes      int64  `json:"quotaBytes"`
	QuotaUnit       string `json:"quotaUnit"`
	DurationMinutes int64  `json:"durationMinutes"`
	DurationUnit    string `json:"durationUnit"`
	ActivatedAtMillis     int64  `json:"activatedAtMillis"`
	UsedBytes             int64  `json:"usedBytes"`
	ExpiresAtMillis       int64  `json:"expiresAtMillis"`
	RedeemedAccountNumber string `json:"redeemedAccountNumber"`
	RedeemedAtMillis      int64  `json:"redeemedAtMillis"`
	Enabled               bool   `json:"enabled"`
}

type PortalAccount struct {
	Number               string `json:"number"`
	Pin                  string `json:"pin"`
	Name                 string `json:"name"`
	Enabled              bool   `json:"enabled"`
	DataBalanceBytes     int64  `json:"dataBalanceBytes"`
	DataExpiresAtMillis  int64  `json:"dataExpiresAtMillis"`
	DataDownloadBps      int64  `json:"dataDownloadBps"`
	DataUploadBps        int64  `json:"dataUploadBps"`
	UnlimitedUntilMillis int64  `json:"unlimitedUntilMillis"`
	UnlimitedDownloadBps int64  `json:"unlimitedDownloadBps"`
	UnlimitedUploadBps   int64  `json:"unlimitedUploadBps"`
	UnlimitedPlanName    string `json:"unlimitedPlanName"`
}

type PortalRechargeClaim struct {
	IP              string `json:"ip"`
	AccountNumber   string `json:"accountNumber"`
	Code            string `json:"code"`
	ClaimedAtMillis int64  `json:"claimedAtMillis"`
}

type PortalClaim struct {
	IP              string `json:"ip"`
	Code            string `json:"code"`
	ClaimedAtMillis int64  `json:"claimedAtMillis"`
}

type PortalAuthorization struct {
	Code                      string
	AccountNumber             string
	SessionToken              string
	ExpiresAtMillis           int64
	QuotaRemainingBytes       int64
	StartedAtMillis           int64
	SessionUsedBytes          int64
	AccountedSessionBytes     int64
	SessionDataUsedBytes      int64
	AccountedSessionDataBytes int64
}

type PortalAccountSession struct {
	Token           string
	AccountNumber   string
	CreatedAtMillis int64
	LastSeenMillis  int64
}

const portalSessionCookieName = "shizzi_session"

type portalConfigPayload struct {
	Title   string       `json:"title"`
	Message string       `json:"message"`
	HTML    string       `json:"html"`
	Passes   []PortalPass    `json:"passes"`
	Accounts []PortalAccount `json:"accounts"`
}

type portalUsageStatus struct {
	Authorized       bool   `json:"authorized"`
	Authenticated    bool   `json:"authenticated"`
	AccountNumber    string `json:"accountNumber"`
	AccountName      string `json:"accountName"`
	Code             string `json:"code"`
	Plan             string `json:"plan"`
	Speed            string `json:"speed"`
	QuotaBytes       int64  `json:"quotaBytes"`
	UsedBytes        int64  `json:"usedBytes"`
	RemainingBytes   int64  `json:"remainingBytes"`
	UsedText         string `json:"usedText"`
	RemainingText    string `json:"remainingText"`
	PercentUsed      int64  `json:"percentUsed"`
	ExpiresAtMillis  int64  `json:"expiresAtMillis"`
	ExpiresText      string `json:"expiresText"`
	LimitedData      bool   `json:"limitedData"`
}

func (m *TrafficManager) setPortalConfig(required bool, raw string) {
	var payload portalConfigPayload
	if strings.TrimSpace(raw) != "" {
		_ = json.Unmarshal([]byte(raw), &payload)
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	m.portalRequired = required
	m.portalTitle = strings.TrimSpace(payload.Title)
	m.portalMessage = strings.TrimSpace(payload.Message)
	m.portalHTML = payload.HTML

	nextPasses := make(map[string]PortalPass)
	for _, pass := range payload.Passes {
		code := normalizePortalCode(pass.Code)
		if code == "" {
			continue
		}
		pass.Code = code
		nextPasses[code] = pass
	}
	m.portalPasses = nextPasses

	nextAccounts := make(map[string]PortalAccount)
	for _, account := range payload.Accounts {
		number := normalizePortalAccountNumber(account.Number)
		if number == "" {
			continue
		}
		account.Number = number
		account.Pin = strings.TrimSpace(account.Pin)
		nextAccounts[number] = account
	}
	m.portalAccounts = nextAccounts
	for token, session := range m.portalAccountSessions {
		account, exists := nextAccounts[session.AccountNumber]
		if !exists || !account.Enabled {
			delete(m.portalAccountSessions, token)
		}
	}
	for ip, auth := range m.portalAuthorized {
		if auth.AccountNumber == "" {
			continue
		}
		account, exists := nextAccounts[auth.AccountNumber]
		if !exists || !account.Enabled {
			delete(m.portalAuthorized, ip)
			continue
		}
		if auth.SessionToken != "" {
			if _, exists := m.portalAccountSessions[auth.SessionToken]; !exists {
				delete(m.portalAuthorized, ip)
				continue
			}
		}
		auth.AccountedSessionDataBytes = auth.SessionDataUsedBytes
		m.portalAuthorized[ip] = auth
	}

	if !required {
		m.portalAuthorized = make(map[string]PortalAuthorization)
		m.portalAccountSessions = make(map[string]PortalAccountSession)
	}
}

func (m *TrafficManager) clearPortalClaims() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.portalClaims = nil
	m.portalRechargeClaims = nil
}

func (m *TrafficManager) setPortalClientAccess(
	ip, code string,
	expiresAtMillis, quotaRemainingBytes int64,
	allowed bool,
) {
	if ip == "" {
		return
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if !allowed {
		delete(m.portalAuthorized, ip)
		return
	}

	normalizedCode := normalizePortalCode(code)
	if existing, ok := m.portalAuthorized[ip]; ok && existing.Code == normalizedCode {
		existing.ExpiresAtMillis = expiresAtMillis
		existing.QuotaRemainingBytes = quotaRemainingBytes
		existing.AccountedSessionBytes = existing.SessionUsedBytes
		m.portalAuthorized[ip] = existing
		return
	}

	m.portalAuthorized[ip] = PortalAuthorization{
		Code:                normalizedCode,
		ExpiresAtMillis:     expiresAtMillis,
		QuotaRemainingBytes: quotaRemainingBytes,
		StartedAtMillis:     time.Now().UnixMilli(),
	}
}

func (m *TrafficManager) portalRequiredFor(ip string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()

	if !m.portalRequired {
		return false
	}
	return !m.portalAuthorizedLocked(ip)
}

func (m *TrafficManager) portalAuthorizedFor(ip string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.portalAuthorizedLocked(ip)
}

func (m *TrafficManager) portalAuthorizedLocked(ip string) bool {
	auth, ok := m.portalAuthorized[ip]
	if !ok {
		return false
	}

	now := time.Now().UnixMilli()
	if auth.AccountNumber != "" {
		return m.portalAccountInternetAllowedLocked(auth, now)
	}
	if auth.ExpiresAtMillis > 0 && now >= auth.ExpiresAtMillis {
		// Keep the record for the local status page. Returning false is enough
		// to block ordinary Internet traffic.
		return false
	}

	pass, exists := m.portalPasses[auth.Code]
	if !exists || !pass.Enabled {
		delete(m.portalAuthorized, ip)
		return false
	}

	if pass.ExpiresAtMillis > 0 && now >= pass.ExpiresAtMillis {
		// Preserve the pass association so the client can still see that the
		// voucher expired on the local usage page.
		return false
	}

	if pass.QuotaBytes > 0 {
		unpersisted := auth.SessionUsedBytes - auth.AccountedSessionBytes
		if unpersisted < 0 {
			unpersisted = 0
		}
		if pass.UsedBytes+unpersisted >= pass.QuotaBytes {
			// Preserve the exhausted voucher for read-only status display.
			// portalAuthorizedLocked still returns false, so Internet access
			// remains blocked immediately at the quota boundary.
			return false
		}
	}
	return true
}


const portalForeverMillis int64 = 2305843009213693951

func normalizePortalAccountNumber(raw string) string {
	return strings.Map(func(r rune) rune {
		if r >= '0' && r <= '9' {
			return r
		}
		return -1
	}, raw)
}

func safePortalAdd(base, delta int64) int64 {
	if base >= portalForeverMillis || delta >= portalForeverMillis {
		return portalForeverMillis
	}
	if delta > 0 && base > portalForeverMillis-delta {
		return portalForeverMillis
	}
	return base + delta
}

func (m *TrafficManager) portalAccountStateLocked(
	auth PortalAuthorization,
	now int64,
) (PortalAccount, string, int64, bool) {
	account, ok := m.portalAccounts[auth.AccountNumber]
	if !ok || !account.Enabled {
		return PortalAccount{}, "", 0, false
	}
	if account.UnlimitedUntilMillis > now {
		return account, "unlimited", 0, true
	}
	unpersisted := auth.SessionDataUsedBytes - auth.AccountedSessionDataBytes
	if unpersisted < 0 {
		unpersisted = 0
	}
	remaining := account.DataBalanceBytes - unpersisted
	if remaining < 0 {
		remaining = 0
	}
	if remaining > 0 &&
		(account.DataExpiresAtMillis <= 0 || now < account.DataExpiresAtMillis) {
		return account, "data", remaining, true
	}
	return account, "", remaining, true
}

func (m *TrafficManager) portalAccountInternetAllowedLocked(
	auth PortalAuthorization,
	now int64,
) bool {
	_, kind, _, exists := m.portalAccountStateLocked(auth, now)
	return exists && kind != ""
}

func (m *TrafficManager) portalAccountUsesMeteredDataLocked(
	auth PortalAuthorization,
	now int64,
) bool {
	_, kind, remaining, exists := m.portalAccountStateLocked(auth, now)
	return exists && kind == "data" && remaining > 0
}

func applyPortalRecharge(account PortalAccount, pass PortalPass, now int64) PortalAccount {
	unlimitedActive := account.UnlimitedUntilMillis > now
	dataExpired := account.DataExpiresAtMillis > 0 && now >= account.DataExpiresAtMillis
	if dataExpired && !unlimitedActive {
		account.DataBalanceBytes = 0
		account.DataExpiresAtMillis = 0
	}

	durationMillis := int64(0)
	if pass.DurationMinutes > 0 {
		if pass.DurationMinutes > portalForeverMillis/60000 {
			durationMillis = portalForeverMillis
		} else {
			durationMillis = pass.DurationMinutes * 60000
		}
	}

	if pass.QuotaBytes > 0 {
		account.DataBalanceBytes = safePortalAdd(account.DataBalanceBytes, pass.QuotaBytes)
		base := now
		if unlimitedActive {
			base = account.UnlimitedUntilMillis
		}
		if durationMillis <= 0 {
			account.DataExpiresAtMillis = 0
		} else {
			account.DataExpiresAtMillis = safePortalAdd(base, durationMillis)
		}
		account.DataDownloadBps = pass.DownloadBps
		account.DataUploadBps = pass.UploadBps
		return account
	}

	base := now
	if unlimitedActive {
		base = account.UnlimitedUntilMillis
	}
	if durationMillis <= 0 {
		account.UnlimitedUntilMillis = portalForeverMillis
	} else {
		account.UnlimitedUntilMillis = safePortalAdd(base, durationMillis)
		if account.DataBalanceBytes > 0 && account.DataExpiresAtMillis > 0 {
			account.DataExpiresAtMillis = safePortalAdd(account.DataExpiresAtMillis, durationMillis)
		}
	}
	account.UnlimitedDownloadBps = pass.DownloadBps
	account.UnlimitedUploadBps = pass.UploadBps
	account.UnlimitedPlanName = pass.Name
	return account
}

func (m *TrafficManager) clientUsedLocked(ip string) int64 {
	if isSharedTunnelAddress(ip) {
		return m.sharedUpBytes + m.sharedDownBytes
	}
	client := m.clientLocked(ip)
	return client.UpBytes + client.DownBytes
}

func (m *TrafficManager) portalUsageStatusFor(ip string) portalUsageStatus {
	m.mu.Lock()
	defer m.mu.Unlock()

	status := portalUsageStatus{}
	auth, ok := m.portalAuthorized[ip]
	if !ok {
		return status
	}
	if auth.AccountNumber != "" {
		account, kind, remaining, exists := m.portalAccountStateLocked(auth, time.Now().UnixMilli())
		if !exists {
			return status
		}
		status.Authenticated = true
		status.AccountNumber = account.Number
		status.AccountName = account.Name
		status.Authorized = kind != ""
		status.Plan = "Compte Shizzi"
		switch kind {
		case "unlimited":
			if account.UnlimitedPlanName != "" {
				status.Plan = account.UnlimitedPlanName
			}
			status.Speed = fmt.Sprintf(
				"%s / %s",
				formatPortalRate(account.UnlimitedDownloadBps, ""),
				formatPortalRate(account.UnlimitedUploadBps, ""),
			)
			status.RemainingText = "Illimité"
			status.ExpiresAtMillis = account.UnlimitedUntilMillis
		case "data":
			status.Plan = "Forfait Data"
			status.Speed = fmt.Sprintf(
				"%s / %s",
				formatPortalRate(account.DataDownloadBps, ""),
				formatPortalRate(account.DataUploadBps, ""),
			)
			status.LimitedData = true
			status.RemainingBytes = remaining
			status.RemainingText = formatPortalBytes(remaining)
			status.UsedBytes = auth.SessionDataUsedBytes
			status.UsedText = formatPortalBytes(auth.SessionDataUsedBytes)
			status.ExpiresAtMillis = account.DataExpiresAtMillis
		default:
			status.Plan = "Compte sans forfait"
			status.RemainingText = "0 MB"
			status.UsedText = "0 MB"
			status.ExpiresText = "Recharge requise"
		}
		if status.ExpiresText == "" && status.ExpiresAtMillis > 0 {
			status.ExpiresText = formatPortalTimeRemaining(status.ExpiresAtMillis - time.Now().UnixMilli())
		} else if status.ExpiresText == "" {
			status.ExpiresText = "Sans expiration"
		}
		return status
	}

	pass, exists := m.portalPasses[auth.Code]
	if !exists {
		return status
	}

	status.Authorized = m.portalAuthorizedLocked(ip)
	status.Code = auth.Code
	status.Plan = pass.Name
	status.Speed = fmt.Sprintf(
		"%s / %s",
		formatPortalRate(pass.DownloadBps, pass.DownloadUnit),
		formatPortalRate(pass.UploadBps, pass.UploadUnit),
	)
	status.QuotaBytes = pass.QuotaBytes
	status.LimitedData = pass.QuotaBytes > 0
	status.ExpiresAtMillis = auth.ExpiresAtMillis

	unpersisted := auth.SessionUsedBytes - auth.AccountedSessionBytes
	if unpersisted < 0 {
		unpersisted = 0
	}

	if pass.QuotaBytes > 0 {
		used := pass.UsedBytes + unpersisted
		if used < 0 {
			used = 0
		}
		if used > pass.QuotaBytes {
			used = pass.QuotaBytes
		}
		remaining := pass.QuotaBytes - used
		if remaining < 0 {
			remaining = 0
		}
		status.UsedBytes = used
		status.RemainingBytes = remaining
		status.UsedText = formatPortalBytes(used)
		status.RemainingText = formatPortalBytes(remaining)
		status.PercentUsed = used * 100 / pass.QuotaBytes
	} else {
		used := pass.UsedBytes + unpersisted
		status.UsedBytes = used
		status.UsedText = formatPortalBytes(used)
		status.RemainingText = "Illimité"
	}

	if auth.ExpiresAtMillis > 0 {
		status.ExpiresText = formatPortalTimeRemaining(
			auth.ExpiresAtMillis - time.Now().UnixMilli(),
		)
	} else {
		status.ExpiresText = "Sans expiration"
	}
	return status
}

func writePortalResponseWithHeaders(
	conn net.Conn,
	contentType string,
	body []byte,
	extraHeaders []string,
) {
	headers := fmt.Sprintf(
		"HTTP/1.1 200 OK\r\nContent-Type: %s\r\nCache-Control: no-store, no-cache, must-revalidate\r\nPragma: no-cache\r\nConnection: close\r\nContent-Length: %d\r\n",
		contentType,
		len(body),
	)
	for _, header := range extraHeaders {
		if strings.TrimSpace(header) != "" {
			headers += header + "\r\n"
		}
	}
	headers += "\r\n"
	_, _ = io.WriteString(conn, headers)
	_, _ = conn.Write(body)
}

func writePortalResponse(conn net.Conn, contentType string, body []byte) {
	writePortalResponseWithHeaders(conn, contentType, body, nil)
}

func (m *TrafficManager) servePortalStatusJSON(conn net.Conn, clientIP, sessionToken string) {
	m.bindPortalAccountSession(clientIP, sessionToken)
	status := m.portalUsageStatusFor(clientIP)
	body, err := json.Marshal(status)
	if err != nil {
		body = []byte("{}")
	}
	writePortalResponse(conn, "application/json; charset=utf-8", body)
}

func (m *TrafficManager) renderLiveStatusPage() string {
	return `<!doctype html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Ma consommation Shizzi</title>
<style>
:root{color-scheme:dark;font-family:Inter,system-ui,-apple-system,sans-serif}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;color:#f8fafc;background:radial-gradient(circle at 15% 5%,#0ea5e955,transparent 34%),radial-gradient(circle at 90% 10%,#8b5cf655,transparent 32%),linear-gradient(145deg,#07111f,#111827 52%,#0f172a);padding:20px}
.wrap{width:min(100%,540px);margin:0 auto}
.hero{margin:10px 0 16px}.eyebrow{font-size:12px;letter-spacing:.15em;color:#7dd3fc;font-weight:800}.hero h1{font-size:30px;margin:6px 0 3px}.sub{color:#94a3b8;margin:0}
.card{background:#0f172ae8;border:1px solid #ffffff18;border-radius:26px;padding:22px;box-shadow:0 24px 70px #0007;backdrop-filter:blur(12px)}
.account{display:flex;justify-content:space-between;gap:14px;align-items:flex-start}.account-name{font-size:22px;font-weight:800}.account-number{color:#94a3b8;font-size:13px;margin-top:3px}.pill{padding:7px 11px;border-radius:999px;background:#10b98122;color:#6ee7b7;border:1px solid #10b98155;font-size:12px;font-weight:800}
.plan-card{margin-top:18px;padding:18px;border-radius:20px;background:linear-gradient(135deg,#0ea5e922,#8b5cf622);border:1px solid #7dd3fc35}.plan-label{color:#94a3b8;font-size:12px}.plan{font-size:18px;font-weight:800;margin-top:3px}.remaining-label{color:#cbd5e1;font-size:12px;margin-top:18px}.remaining{font-size:34px;font-weight:900;line-height:1.05;margin-top:4px;color:#f0fdfa}
.meter{height:12px;background:#ffffff12;border-radius:999px;overflow:hidden;margin:16px 0 6px}.meter span{display:block;height:100%;width:0;background:linear-gradient(90deg,#22d3ee,#34d399,#a78bfa);transition:width .5s ease}.percent{text-align:right;color:#94a3b8;font-size:12px}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:16px}.metric{padding:14px;border-radius:16px;background:#ffffff08;border:1px solid #ffffff10}.metric span{display:block;color:#94a3b8;font-size:12px}.metric strong{display:block;margin-top:5px;font-size:15px}.wide{grid-column:1/-1}
.live{display:flex;align-items:center;gap:8px;color:#6ee7b7;font-size:13px;margin-top:18px}.dot{width:8px;height:8px;border-radius:50%;background:#34d399;box-shadow:0 0 14px #34d399}.offline{color:#fda4af}.offline .dot{background:#fb7185;box-shadow:0 0 14px #fb7185}
button{width:100%;margin-top:20px;border:0;border-radius:15px;padding:14px 16px;font:inherit;font-weight:800;background:linear-gradient(90deg,#22d3ee,#34d399);color:#06202a}
</style>
</head>
<body>
<div class="wrap">
<div class="hero"><div class="eyebrow">SHIZZI CONSO</div><h1>Ma consommation</h1><p class="sub">Mise à jour automatique toutes les 2 secondes</p></div>
<div class="card">
<div class="account"><div><div class="account-name" id="accountName">Chargement…</div><div class="account-number" id="accountNumber"></div></div><span class="pill" id="state">En ligne</span></div>
<div class="plan-card">
<div class="plan-label">Forfait actif</div><div class="plan" id="plan">—</div>
<div class="remaining-label">Données restantes</div><div class="remaining" id="remaining">—</div>
<div class="meter"><span id="bar"></span></div><div class="percent" id="percent"></div>
<div class="grid">
<div class="metric"><span>Utilisé</span><strong id="used">—</strong></div>
<div class="metric"><span>Débit ↓ / ↑</span><strong id="speed">—</strong></div>
<div class="metric wide"><span>Validité restante</span><strong id="expires">—</strong></div>
</div>
</div>
<div class="live" id="live"><span class="dot"></span><span>Actualisation en cours</span></div>
<button onclick="refreshNow()">Actualiser maintenant</button>
</div>
</div>
<script>
async function refreshNow(){
  try{
    const r=await fetch('/status.json?ts='+Date.now(),{cache:'no-store'});
    const s=await r.json();
    const hasIdentity=!!(s.code||s.accountNumber);
    document.getElementById('accountName').textContent=s.accountName|| (hasIdentity?'Compte Shizzi':'Aucun compte connecté');
    document.getElementById('accountNumber').textContent=s.accountNumber?'Compte '+s.accountNumber:(s.code||'');
    document.getElementById('plan').textContent=s.plan||'Aucun forfait actif';
    document.getElementById('used').textContent=s.usedText||'0 MB';
    document.getElementById('remaining').textContent=s.remainingText||'0 MB';
    document.getElementById('expires').textContent=s.expiresText||'Recharge requise';
    document.getElementById('speed').textContent=s.speed||'—';
    const p=Math.max(0,Math.min(100,s.percentUsed||0));
    document.getElementById('bar').style.width=(s.limitedData?p:0)+'%';
    document.getElementById('percent').textContent=s.limitedData&&p>0?p+' % utilisé':(s.limitedData?'Solde Data actif':'Données illimitées');
    const live=document.getElementById('live');
    const state=document.getElementById('state');
    if(s.authorized){
      live.className='live'; live.innerHTML='<span class="dot"></span><span>Connexion Internet active</span>';
      state.textContent='Actif';
    }else{
      live.className='live offline'; live.innerHTML='<span class="dot"></span><span>Internet bloqué · recharge disponible localement</span>';
      state.textContent=s.authenticated?'Recharge requise':'Hors ligne';
    }
  }catch(e){
    const live=document.getElementById('live');
    live.className='live offline'; live.innerHTML='<span class="dot"></span><span>Suivi temporairement indisponible</span>';
  }
}
refreshNow(); setInterval(refreshNow,2000);
</script>
</body>
</html>`
}

func (m *TrafficManager) submitPortalCode(ip, rawCode string) (bool, string) {
	code := normalizePortalCode(rawCode)
	if code == "" {
		return false, "Enter an access code."
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	pass, ok := m.portalPasses[code]
	if !ok || !pass.Enabled || pass.RedeemedAccountNumber != "" {
		return false, "Invalid access code."
	}

	now := time.Now()
	nowMillis := now.UnixMilli()
	if pass.ExpiresAtMillis > 0 && nowMillis >= pass.ExpiresAtMillis {
		return false, "This access code has expired."
	}
	if pass.QuotaBytes > 0 && pass.UsedBytes >= pass.QuotaBytes {
		return false, "This access code has no data remaining."
	}

	// The prepaid balance belongs to the code, not to a MAC/device identity.
	// Re-entering it on another client transfers the live authorization.
	for otherIP, auth := range m.portalAuthorized {
		if otherIP != ip && auth.Code == code {
			delete(m.portalAuthorized, otherIP)
		}
	}

	expiresAt := pass.ExpiresAtMillis
	if expiresAt <= 0 && pass.DurationMinutes > 0 {
		expiresAt = now.Add(time.Duration(pass.DurationMinutes) * time.Minute).UnixMilli()
	}

	quotaRemaining := int64(0)
	if pass.QuotaBytes > 0 {
		quotaRemaining = pass.QuotaBytes - pass.UsedBytes
		if quotaRemaining < 0 {
			quotaRemaining = 0
		}
	}

	m.portalAuthorized[ip] = PortalAuthorization{
		Code:                code,
		ExpiresAtMillis:     expiresAt,
		QuotaRemainingBytes: quotaRemaining,
		StartedAtMillis:     nowMillis,
	}
	m.portalClaims = append(m.portalClaims, PortalClaim{
		IP:              ip,
		Code:            code,
		ClaimedAtMillis: nowMillis,
	})
	if len(m.portalClaims) > 100 {
		m.portalClaims = append([]PortalClaim(nil), m.portalClaims[len(m.portalClaims)-100:]...)
	}

	return true, "Access granted. You can now use the Internet."
}


func newPortalSessionToken() string {
	raw := make([]byte, 24)
	if _, err := rand.Read(raw); err != nil {
		return ""
	}
	return hex.EncodeToString(raw)
}

func portalSessionTokenFromRequest(req *http.Request) string {
	if req == nil {
		return ""
	}
	if token := strings.TrimSpace(req.URL.Query().Get("session")); token != "" {
		return token
	}
	if cookie, err := req.Cookie(portalSessionCookieName); err == nil {
		return strings.TrimSpace(cookie.Value)
	}
	return ""
}

func (m *TrafficManager) portalAuthorizationForSessionLocked(
	ip, rawToken string,
) (PortalAuthorization, bool) {
	token := strings.TrimSpace(rawToken)
	if auth, ok := m.portalAuthorized[ip]; ok && auth.AccountNumber != "" {
		if token == "" || auth.SessionToken == "" || auth.SessionToken == token {
			if auth.SessionToken != "" {
				if session, exists := m.portalAccountSessions[auth.SessionToken]; exists {
					session.LastSeenMillis = time.Now().UnixMilli()
					m.portalAccountSessions[auth.SessionToken] = session
				}
			}
			return auth, true
		}
	}
	if token == "" {
		return PortalAuthorization{}, false
	}
	session, ok := m.portalAccountSessions[token]
	if !ok {
		return PortalAuthorization{}, false
	}
	account, ok := m.portalAccounts[session.AccountNumber]
	if !ok || !account.Enabled {
		delete(m.portalAccountSessions, token)
		return PortalAuthorization{}, false
	}
	now := time.Now().UnixMilli()
	session.LastSeenMillis = now
	m.portalAccountSessions[token] = session

	auth := PortalAuthorization{
		AccountNumber: session.AccountNumber,
		SessionToken:  token,
		StartedAtMillis: session.CreatedAtMillis,
	}
	m.portalAuthorized[ip] = auth
	return auth, true
}

func (m *TrafficManager) bindPortalAccountSession(ip, rawToken string) string {
	m.mu.Lock()
	defer m.mu.Unlock()
	auth, ok := m.portalAuthorizationForSessionLocked(ip, rawToken)
	if !ok {
		return strings.TrimSpace(rawToken)
	}
	return auth.SessionToken
}

func (m *TrafficManager) submitPortalAccountLoginWithSession(
	ip, rawNumber, rawPin string,
) (bool, string, string) {
	number := normalizePortalAccountNumber(rawNumber)
	pin := strings.TrimSpace(rawPin)
	if number == "" || pin == "" {
		return false, "Numéro de compte et PIN requis.", ""
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	account, ok := m.portalAccounts[number]
	if !ok || !account.Enabled || account.Pin != pin {
		return false, "Compte ou PIN invalide.", ""
	}

	// A fresh login is the authority for the one-active-device rule.
	// Invalidate previous browser/captive sessions for this account.
	for token, session := range m.portalAccountSessions {
		if session.AccountNumber == number {
			delete(m.portalAccountSessions, token)
		}
	}
	for otherIP, auth := range m.portalAuthorized {
		if auth.AccountNumber == number {
			delete(m.portalAuthorized, otherIP)
		}
	}

	token := newPortalSessionToken()
	if token == "" {
		return false, "Impossible de créer la session du compte.", ""
	}
	now := time.Now().UnixMilli()
	m.portalAccountSessions[token] = PortalAccountSession{
		Token:           token,
		AccountNumber:   number,
		CreatedAtMillis: now,
		LastSeenMillis:  now,
	}
	m.portalAuthorized[ip] = PortalAuthorization{
		AccountNumber: number,
		SessionToken:  token,
		StartedAtMillis: now,
	}
	if account.UnlimitedUntilMillis > now ||
		(account.DataBalanceBytes > 0 &&
			(account.DataExpiresAtMillis <= 0 || now < account.DataExpiresAtMillis)) {
		return true, "Compte connecté. Accès Internet actif.", token
	}
	return true, "Compte connecté. Rechargez votre compte pour accéder à Internet.", token
}

func (m *TrafficManager) submitPortalAccountLogin(ip, rawNumber, rawPin string) (bool, string) {
	ok, message, _ := m.submitPortalAccountLoginWithSession(ip, rawNumber, rawPin)
	return ok, message
}

func (m *TrafficManager) submitPortalRechargeWithSession(
	ip, rawCode, rawSession string,
) (bool, string) {
	code := normalizePortalCode(rawCode)
	if code == "" {
		return false, "Code de recharge requis."
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	auth, ok := m.portalAuthorizationForSessionLocked(ip, rawSession)
	if !ok || auth.AccountNumber == "" {
		return false, "Connectez d'abord votre compte Shizzi."
	}
	account, ok := m.portalAccounts[auth.AccountNumber]
	if !ok || !account.Enabled {
		return false, "Compte indisponible."
	}
	pass, ok := m.portalPasses[code]
	if !ok || !pass.Enabled || pass.RedeemedAccountNumber != "" ||
		pass.ActivatedAtMillis > 0 || pass.UsedBytes > 0 {
		return false, "Coupon invalide ou déjà utilisé."
	}

	unpersisted := auth.SessionDataUsedBytes - auth.AccountedSessionDataBytes
	if unpersisted > 0 {
		account.DataBalanceBytes -= unpersisted
		if account.DataBalanceBytes < 0 {
			account.DataBalanceBytes = 0
		}
		auth.AccountedSessionDataBytes = auth.SessionDataUsedBytes
	}

	now := time.Now().UnixMilli()
	account = applyPortalRecharge(account, pass, now)
	pass.RedeemedAccountNumber = account.Number
	pass.RedeemedAtMillis = now
	m.portalAccounts[account.Number] = account
	m.portalPasses[code] = pass
	m.portalAuthorized[ip] = auth

	m.portalRechargeClaims = append(m.portalRechargeClaims, PortalRechargeClaim{
		IP:              ip,
		AccountNumber:   account.Number,
		Code:            code,
		ClaimedAtMillis: now,
	})
	if len(m.portalRechargeClaims) > 100 {
		m.portalRechargeClaims = append(
			[]PortalRechargeClaim(nil),
			m.portalRechargeClaims[len(m.portalRechargeClaims)-100:]...,
		)
	}

	if pass.QuotaBytes > 0 {
		return true, fmt.Sprintf(
			"Recharge acceptée. Nouveau solde: %s.",
			formatPortalBytes(account.DataBalanceBytes),
		)
	}
	return true, "Recharge illimitée acceptée."
}

func (m *TrafficManager) submitPortalRecharge(ip, rawCode string) (bool, string) {
	return m.submitPortalRechargeWithSession(ip, rawCode, "")
}

func (m *TrafficManager) portalAccountPanelLocked(clientIP, sessionToken string) string {
	if len(m.portalAccounts) == 0 {
		return ""
	}
	auth, ok := m.portalAuthorizationForSessionLocked(clientIP, sessionToken)
	if !ok || auth.AccountNumber == "" {
		return "<section class=\"account-box login-box\"><div class=\"eyebrow\">COMPTE PRÉPAYÉ</div><h2>Connexion client</h2>" +
			"<p class=\"muted\">Entrez votre numéro de compte et votre PIN.</p>" +
			"<form action=\"/account/login\" method=\"post\">" +
			"<label>Numéro de compte</label><input name=\"account\" inputmode=\"numeric\" autocomplete=\"username\" placeholder=\"Ex. 25494159\" required>" +
			"<label>PIN</label><input name=\"pin\" inputmode=\"numeric\" autocomplete=\"current-password\" placeholder=\"6 chiffres\" required>" +
			"<button type=\"submit\">Se connecter</button></form></section>"
	}
	account, exists := m.portalAccounts[auth.AccountNumber]
	if !exists {
		return ""
	}
	now := time.Now().UnixMilli()
	_, kind, remaining, _ := m.portalAccountStateLocked(auth, now)
	displayName := account.Name
	if displayName == "" {
		displayName = "Compte Shizzi"
	}

	plan := "Aucun forfait actif"
	stateClass := "warning"
	stateText := "Recharge requise"
	remainingText := "0 MB"
	expiresText := "Recharge requise"
	downText := "—"
	upText := "—"

	switch kind {
	case "unlimited":
		plan = account.UnlimitedPlanName
		if plan == "" {
			plan = "Illimité"
		}
		stateClass = "active"
		stateText = "Actif"
		remainingText = "Illimité"
		expiresText = formatPortalTimeRemaining(account.UnlimitedUntilMillis - now)
		downText = formatPortalRate(account.UnlimitedDownloadBps, "")
		upText = formatPortalRate(account.UnlimitedUploadBps, "")
	case "data":
		plan = "Forfait Data"
		stateClass = "active"
		stateText = "Actif"
		remainingText = formatPortalBytes(remaining)
		expiresText = formatPortalTimeRemaining(account.DataExpiresAtMillis - now)
		downText = formatPortalRate(account.DataDownloadBps, "")
		upText = formatPortalRate(account.DataUploadBps, "")
	}

	return fmt.Sprintf(
		"<section class=\"account-box account-active\">"+
			"<div class=\"account-head\"><div><div class=\"eyebrow\">COMPTE PRÉPAYÉ</div><h2>%s</h2><div class=\"account-number\">Compte %s</div></div><span class=\"state-pill %s\">%s</span></div>"+
			"<div class=\"plan-card\"><div class=\"plan-label\">Forfait actif</div><div class=\"plan-name\">%s</div>"+
			"<div class=\"remaining-big\">%s</div>"+
			"<div class=\"metric-grid\"><div><span>Débit ↓</span><strong>%s</strong></div><div><span>Débit ↑</span><strong>%s</strong></div><div class=\"wide\"><span>Validité restante</span><strong>%s</strong></div></div></div>"+
			"<form action=\"/account/recharge\" method=\"post\" class=\"recharge-form\"><label>Code de recharge</label>"+
			"<input type=\"hidden\" name=\"session\" value=\"%s\">"+
			"<input name=\"code\" autocomplete=\"one-time-code\" autocapitalize=\"characters\" placeholder=\"Saisir le coupon\" required>"+
			"<button type=\"submit\">Recharger mon compte</button></form>"+
			"<a class=\"status-link\" href=\"/status\">Voir ma consommation en direct</a></section>",
		html.EscapeString(displayName),
		html.EscapeString(account.Number),
		stateClass,
		stateText,
		html.EscapeString(plan),
		html.EscapeString(remainingText),
		html.EscapeString(downText),
		html.EscapeString(upText),
		html.EscapeString(expiresText),
		html.EscapeString(auth.SessionToken),
	)
}

func (m *TrafficManager) servePortal(conn net.Conn, clientIP string) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(15 * time.Second))

	req, err := http.ReadRequest(bufio.NewReader(conn))
	if err != nil {
		return
	}
	defer req.Body.Close()

	sessionToken := portalSessionTokenFromRequest(req)
	sessionToken = m.bindPortalAccountSession(clientIP, sessionToken)

	switch req.URL.Path {
	case "/status.json":
		m.servePortalStatusJSON(conn, clientIP, sessionToken)
		return
	case "/status":
		writePortalResponse(
			conn,
			"text/html; charset=utf-8",
			[]byte(m.renderLiveStatusPage()),
		)
		return
	}

	internetOK := m.portalAuthorizedFor(clientIP)
	actionOK := false
	message := ""
	extraHeaders := []string{}
	if internetOK && req.Method == http.MethodGet {
		message = "Accès actif."
	}
	if req.Method == http.MethodPost {
		body, _ := io.ReadAll(io.LimitReader(req.Body, 16*1024))
		values, _ := url.ParseQuery(string(body))
		if posted := strings.TrimSpace(values.Get("session")); posted != "" {
			sessionToken = posted
		}
		switch req.URL.Path {
		case "/account/login":
			var freshToken string
			actionOK, message, freshToken = m.submitPortalAccountLoginWithSession(
				clientIP,
				values.Get("account"),
				values.Get("pin"),
			)
			if freshToken != "" {
				sessionToken = freshToken
				extraHeaders = append(extraHeaders, fmt.Sprintf(
					"Set-Cookie: %s=%s; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax",
					portalSessionCookieName,
					freshToken,
				))
			}
		case "/account/recharge":
			actionOK, message = m.submitPortalRechargeWithSession(
				clientIP,
				values.Get("code"),
				sessionToken,
			)
		default:
			actionOK, message = m.submitPortalCode(clientIP, values.Get("code"))
		}
		internetOK = m.portalAuthorizedFor(clientIP)
	}

	page := m.renderPortalPage(clientIP, sessionToken, internetOK || actionOK, message)
	if internetOK && req.Method == http.MethodPost {
		page = injectPortalValidationRedirect(page)
	}
	writePortalResponseWithHeaders(
		conn,
		"text/html; charset=utf-8",
		[]byte(page),
		extraHeaders,
	)
}

func (m *TrafficManager) renderPortalPage(
	clientIP, sessionToken string,
	success bool,
	statusMessage string,
) string {
	m.mu.Lock()
	title := m.portalTitle
	message := m.portalMessage
	custom := m.portalHTML
	accountPanel := m.portalAccountPanelLocked(clientIP, sessionToken)
	accountMode := len(m.portalAccounts) > 0

	planName := ""
	speedText := ""
	usedText := ""
	remainingText := ""
	expiresText := ""
	if auth, ok := m.portalAuthorized[clientIP]; ok {
		if pass, exists := m.portalPasses[auth.Code]; exists {
			planName = pass.Name
			speedText = fmt.Sprintf(
				"%s / %s",
				formatPortalRate(pass.DownloadBps, pass.DownloadUnit),
				formatPortalRate(pass.UploadBps, pass.UploadUnit),
			)

			unpersisted := auth.SessionUsedBytes - auth.AccountedSessionBytes
			if unpersisted < 0 {
				unpersisted = 0
			}
			totalQuota := pass.QuotaBytes
			remaining := int64(0)
			if totalQuota > 0 {
				used := pass.UsedBytes + unpersisted
				if used < 0 {
					used = 0
				}
				if used > totalQuota {
					used = totalQuota
				}
				remaining = totalQuota - used
				usedText = formatPortalBytes(used)
				remainingText = formatPortalBytes(remaining)
			} else {
				used := pass.UsedBytes + unpersisted
				usedText = formatPortalBytes(used)
				remainingText = "Illimité"
			}

			if auth.ExpiresAtMillis > 0 {
				expiresText = formatPortalTimeRemaining(auth.ExpiresAtMillis - time.Now().UnixMilli())
			} else {
				expiresText = "Sans expiration"
			}
		}
	}
	m.mu.Unlock()

	if title == "" {
		title = "Shizzi Hotspot"
	}
	if message == "" {
		message = "Enter your access code to go online."
	}

	statusClass := "error"
	if success {
		statusClass = "success"
	}
	status := ""
	if statusMessage != "" {
		status = fmt.Sprintf(
			`<div class="status %s">%s</div>`,
			statusClass,
			html.EscapeString(statusMessage),
		)
	}

	if strings.TrimSpace(custom) == "" {
		custom = defaultPortalHTML
	}

	usagePopup := ""
	if success && (usedText != "" || remainingText != "") {
		usagePopup = portalUsagePopup(
			planName,
			speedText,
			usedText,
			remainingText,
			expiresText,
		)
	}

	replacer := strings.NewReplacer(
		"{{TITLE}}", html.EscapeString(title),
		"{{MESSAGE}}", html.EscapeString(message),
		"{{STATUS}}", status,
		"{{FORM_ACTION}}", "/login",
		"{{PLAN}}", html.EscapeString(planName),
		"{{SPEED}}", html.EscapeString(speedText),
		"{{USED}}", html.EscapeString(usedText),
		"{{REMAINING}}", html.EscapeString(remainingText),
		"{{EXPIRES}}", html.EscapeString(expiresText),
		"{{USAGE_POPUP}}", usagePopup,
	)
	rendered := replacer.Replace(custom)
	voucherForm := ""
	if !accountMode {
		voucherForm = "<form action=\"/login\" method=\"post\">" +
			"<input name=\"code\" autocomplete=\"one-time-code\" autocapitalize=\"characters\" placeholder=\"Access code\" required>" +
			"<button type=\"submit\">Connect</button></form>"
	}
	rendered = strings.ReplaceAll(rendered, "{{ACCOUNT_PANEL}}", accountPanel)
	rendered = strings.ReplaceAll(rendered, "{{VOUCHER_FORM}}", voucherForm)
	if accountPanel != "" && !strings.Contains(custom, "{{ACCOUNT_PANEL}}") {
		if index := strings.LastIndex(strings.ToLower(rendered), "</body>"); index >= 0 {
			rendered = rendered[:index] + accountPanel + rendered[index:]
		} else {
			rendered += accountPanel
		}
	}

	if usagePopup != "" && !strings.Contains(custom, "{{USAGE_POPUP}}") {
		if strings.Contains(strings.ToLower(rendered), "</body>") {
			index := strings.LastIndex(strings.ToLower(rendered), "</body>")
			rendered = rendered[:index] + usagePopup + rendered[index:]
		} else {
			rendered += usagePopup
		}
	}
	return rendered
}

func formatPortalRate(bps int64, preferredUnit string) string {
	if bps <= 0 {
		return "Illimité"
	}
	switch strings.ToUpper(strings.TrimSpace(preferredUnit)) {
	case "KBPS":
		return fmt.Sprintf("%d kbps", bps/1_000)
	case "MBPS":
		return fmt.Sprintf("%.2f Mbps", float64(bps)/1_000_000.0)
	default:
		if bps < 1_000_000 {
			return fmt.Sprintf("%d kbps", bps/1_000)
		}
		return fmt.Sprintf("%.2f Mbps", float64(bps)/1_000_000.0)
	}
}

func formatPortalBytes(value int64) string {
	if value <= 0 {
		return "0 MB"
	}
	const (
		mb = int64(1_000_000)
		gb = int64(1_000_000_000)
	)
	if value < gb {
		return fmt.Sprintf("%.0f MB", float64(value)/float64(mb))
	}
	return fmt.Sprintf("%.2f GB", float64(value)/float64(gb))
}

func formatPortalTimeRemaining(remainingMillis int64) string {
	if remainingMillis <= 0 {
		return "Expiré"
	}
	totalMinutes := remainingMillis / 60_000
	days := totalMinutes / 1_440
	hours := (totalMinutes % 1_440) / 60
	minutes := totalMinutes % 60
	switch {
	case days > 0:
		return fmt.Sprintf("%d j %d h", days, hours)
	case hours > 0:
		return fmt.Sprintf("%d h %d min", hours, minutes)
	default:
		return fmt.Sprintf("%d min", minutes)
	}
}

func portalUsagePopup(planName, speedText, usedText, remainingText, expiresText string) string {
	plan := html.EscapeString(planName)
	speed := html.EscapeString(speedText)
	used := html.EscapeString(usedText)
	remaining := html.EscapeString(remainingText)
	expires := html.EscapeString(expiresText)

	return fmt.Sprintf(`
<style>
#shizzi-usage-backdrop{position:fixed;inset:0;background:#0009;display:flex;align-items:center;justify-content:center;padding:20px;z-index:99999}
#shizzi-usage-card{width:min(100%%,420px);background:#111827;color:#f9fafb;border-radius:22px;padding:24px;box-shadow:0 24px 70px #0008;font-family:system-ui,-apple-system,sans-serif}
#shizzi-usage-card h2{margin:0 0 8px;font-size:24px}
#shizzi-usage-card .plan{color:#9ca3af;margin-bottom:18px}
#shizzi-usage-card .meter{height:12px;background:#374151;border-radius:999px;overflow:hidden;margin:8px 0 18px}
#shizzi-usage-card .meter span{display:block;height:100%%;background:#f9fafb;width:var(--used)}
#shizzi-usage-card .row{display:flex;justify-content:space-between;gap:16px;padding:7px 0;border-bottom:1px solid #ffffff14}
#shizzi-usage-card button{margin-top:20px;width:100%%;border:0;border-radius:12px;padding:13px 16px;background:#f9fafb;color:#111827;font-weight:700}
</style>
<div id="shizzi-usage-backdrop" role="dialog" aria-modal="true" aria-label="Consommation Internet">
  <div id="shizzi-usage-card">
    <h2>Votre consommation</h2>
    <div class="plan">%s</div>
    <div class="row"><span>Utilisé</span><strong>%s</strong></div>
    <div class="row"><span>Restant</span><strong>%s</strong></div>
    <div class="row"><span>Validité restante</span><strong>%s</strong></div>
    <div class="row"><span>Débit</span><strong>%s</strong></div>
    <a href="http://192.0.2.1/status" target="_blank" rel="noopener" style="display:block;margin-top:18px;text-align:center;color:#d1d5db;text-decoration:none">Ouvrir le suivi en direct</a>
    <button type="button" onclick="if(window.shizziFinishLogin){window.shizziFinishLogin()}else{window.location.replace('http://connectivitycheck.gstatic.com/generate_204')}">Continuer</button>
  </div>
</div>`, plan, used, remaining, expires, speed)
}

const portalValidationURL = "http://connectivitycheck.gstatic.com/generate_204"

func injectPortalValidationRedirect(page string) string {
	bridge := fmt.Sprintf(`
<script>
(function(){
  var target=%q;
  window.shizziFinishLogin=function(){
    window.location.replace(target);
  };
})();
</script>
<noscript><p style="text-align:center"><a href="%s">Continuer vers Internet</a></p></noscript>`,
		portalValidationURL,
		html.EscapeString(portalValidationURL),
	)

	lower := strings.ToLower(page)
	if index := strings.LastIndex(lower, "</body>"); index >= 0 {
		return page[:index] + bridge + page[index:]
	}
	return page + bridge
}

func normalizePortalCode(raw string) string {
	return strings.ToUpper(strings.TrimSpace(raw))
}

const defaultPortalHTML = `<!doctype html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{{TITLE}}</title>
<style>
:root{color-scheme:dark;font-family:Inter,system-ui,-apple-system,sans-serif}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;display:grid;place-items:center;color:#f8fafc;background:radial-gradient(circle at 15% 5%,#0ea5e955,transparent 34%),radial-gradient(circle at 90% 10%,#8b5cf655,transparent 32%),linear-gradient(145deg,#07111f,#111827 52%,#0f172a);padding:20px}
.card{width:min(100%,460px);background:#0f172ae8;border:1px solid #ffffff18;border-radius:28px;padding:24px;box-shadow:0 26px 80px #0008;backdrop-filter:blur(12px)}
.brand{display:flex;align-items:center;gap:12px;margin-bottom:18px}.logo{width:42px;height:42px;border-radius:14px;background:linear-gradient(135deg,#22d3ee,#34d399,#8b5cf6);box-shadow:0 8px 24px #22d3ee33}.brand h1{font-size:25px;margin:0}.brand p{margin:2px 0 0;color:#94a3b8;font-size:13px}
.status{margin:14px 0;padding:12px 14px;border-radius:14px;font-weight:700}.status.error{background:#7f1d1d88;border:1px solid #fb718555;color:#fecdd3}.status.success{background:#064e3b99;border:1px solid #34d39955;color:#a7f3d0}
.account-box{margin-top:8px}.eyebrow{font-size:11px;letter-spacing:.14em;color:#7dd3fc;font-weight:800}.account-box h2{font-size:23px;margin:5px 0 4px}.muted,.account-number{color:#94a3b8}
.account-head{display:flex;justify-content:space-between;gap:14px;align-items:flex-start}.state-pill{font-size:11px;font-weight:800;padding:6px 10px;border-radius:999px}.state-pill.active{background:#10b98122;color:#6ee7b7;border:1px solid #10b98155}.state-pill.warning{background:#f59e0b22;color:#fcd34d;border:1px solid #f59e0b55}
.plan-card{margin-top:18px;padding:18px;border-radius:20px;background:linear-gradient(135deg,#0ea5e922,#8b5cf622);border:1px solid #7dd3fc35}.plan-label{color:#94a3b8;font-size:12px}.plan-name{font-size:18px;font-weight:800;margin-top:3px}.remaining-big{font-size:34px;font-weight:900;color:#f0fdfa;margin-top:14px}
.metric-grid{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:14px}.metric-grid>div{padding:12px;border-radius:14px;background:#ffffff08}.metric-grid span{display:block;color:#94a3b8;font-size:11px}.metric-grid strong{display:block;margin-top:4px;font-size:14px}.metric-grid .wide{grid-column:1/-1}
label{display:block;color:#cbd5e1;font-size:12px;font-weight:700;margin:14px 0 6px}input,button{width:100%;font:inherit;border-radius:14px;padding:14px 16px}input{border:1px solid #334155;background:#0b1220;color:#fff;outline:none;margin:0 0 8px}input:focus{border-color:#22d3ee;box-shadow:0 0 0 3px #22d3ee22}button{border:0;background:linear-gradient(90deg,#22d3ee,#34d399);color:#06202a;font-weight:900;cursor:pointer;margin-top:6px}.status-link{display:block;text-align:center;margin-top:16px;color:#7dd3fc;text-decoration:none;font-weight:700;font-size:13px}
small.footer{display:block;margin-top:20px;color:#64748b;text-align:center}
</style>
</head>
<body>
<main class="card">
<div class="brand"><div class="logo"></div><div><h1>{{TITLE}}</h1><p>{{MESSAGE}}</p></div></div>
{{STATUS}}
{{ACCOUNT_PANEL}}
{{VOUCHER_FORM}}
<small class="footer">Propulsé par Shizzi</small>
</main>
</body>
</html>`