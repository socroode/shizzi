package datapath

import (
	"bufio"
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
	UsedBytes       int64  `json:"usedBytes"`
	ExpiresAtMillis int64  `json:"expiresAtMillis"`
	Enabled         bool   `json:"enabled"`
}

type PortalClaim struct {
	IP              string `json:"ip"`
	Code            string `json:"code"`
	ClaimedAtMillis int64  `json:"claimedAtMillis"`
}

type PortalAuthorization struct {
	Code                string
	ExpiresAtMillis     int64
	QuotaRemainingBytes int64
	StartSessionBytes   int64
	StartedAtMillis     int64
}

type portalConfigPayload struct {
	Title   string       `json:"title"`
	Message string       `json:"message"`
	HTML    string       `json:"html"`
	Passes  []PortalPass `json:"passes"`
}

type portalUsageStatus struct {
	Authorized       bool   `json:"authorized"`
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

	if !required {
		m.portalAuthorized = make(map[string]PortalAuthorization)
	}
}

func (m *TrafficManager) clearPortalClaims() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.portalClaims = nil
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
		m.portalAuthorized[ip] = existing
		return
	}

	m.portalAuthorized[ip] = PortalAuthorization{
		Code:                normalizedCode,
		ExpiresAtMillis:     expiresAtMillis,
		QuotaRemainingBytes: quotaRemainingBytes,
		StartSessionBytes:   m.clientUsedLocked(ip),
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
	if auth.ExpiresAtMillis > 0 && now >= auth.ExpiresAtMillis {
		delete(m.portalAuthorized, ip)
		return false
	}

	pass, exists := m.portalPasses[auth.Code]
	if !exists || !pass.Enabled {
		delete(m.portalAuthorized, ip)
		return false
	}

	if pass.ExpiresAtMillis > 0 && now >= pass.ExpiresAtMillis {
		delete(m.portalAuthorized, ip)
		return false
	}

	if pass.QuotaBytes > 0 {
		sessionUsed := m.clientUsedLocked(ip) - auth.StartSessionBytes
		if sessionUsed < 0 {
			sessionUsed = 0
		}
		if pass.UsedBytes+sessionUsed >= pass.QuotaBytes {
			delete(m.portalAuthorized, ip)
			return false
		}
	}
	return true
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

	sessionUsed := m.clientUsedLocked(ip) - auth.StartSessionBytes
	if sessionUsed < 0 {
		sessionUsed = 0
	}

	if pass.QuotaBytes > 0 {
		used := pass.UsedBytes + sessionUsed
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
		status.UsedBytes = sessionUsed
		status.UsedText = formatPortalBytes(sessionUsed)
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

func writePortalResponse(conn net.Conn, contentType string, body []byte) {
	headers := fmt.Sprintf(
		"HTTP/1.1 200 OK\r\nContent-Type: %s\r\nCache-Control: no-store, no-cache, must-revalidate\r\nPragma: no-cache\r\nConnection: close\r\nContent-Length: %d\r\n\r\n",
		contentType,
		len(body),
	)
	_, _ = io.WriteString(conn, headers)
	_, _ = conn.Write(body)
}

func (m *TrafficManager) servePortalStatusJSON(conn net.Conn, clientIP string) {
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
<title>Suivi consommation Shizzi</title>
<style>
:root{color-scheme:dark}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;background:#0b0b0c;color:#f7f7f8;font-family:system-ui,-apple-system,sans-serif;padding:22px}
.wrap{width:min(100%,520px);margin:0 auto}
.card{background:#1c1b1f;border-radius:24px;padding:24px;box-shadow:0 18px 60px #0008}
h1{font-size:26px;margin:0 0 4px}
.sub{color:#aaa3ad;margin:0 0 22px}
.plan{font-size:18px;font-weight:700;margin-bottom:4px}
.code{font-size:13px;color:#8d8790;margin-bottom:20px}
.row{display:flex;justify-content:space-between;gap:18px;padding:12px 0;border-bottom:1px solid #ffffff12}
.row span{color:#bbb5bd}.row strong{text-align:right}
.meter{height:14px;background:#37333a;border-radius:999px;overflow:hidden;margin:18px 0 8px}
.meter span{display:block;height:100%;width:0;background:#34d1c6;transition:width .5s ease}
.percent{text-align:right;color:#aaa3ad;font-size:13px}
.live{display:inline-flex;align-items:center;gap:7px;color:#34d1c6;font-size:13px;margin-top:18px}
.dot{width:8px;height:8px;border-radius:50%;background:#34d1c6;box-shadow:0 0 12px #34d1c6}
.offline{color:#ff9a9a}
button{width:100%;margin-top:22px;border:0;border-radius:14px;padding:14px;font:inherit;font-weight:700;background:#34d1c6;color:#07110f}
</style>
</head>
<body>
<div class="wrap">
<div class="card">
<h1>Votre consommation</h1>
<p class="sub">Mise à jour automatique en temps réel</p>
<div class="plan" id="plan">Chargement…</div>
<div class="code" id="code"></div>
<div class="meter"><span id="bar"></span></div>
<div class="percent" id="percent"></div>
<div class="row"><span>Utilisé</span><strong id="used">—</strong></div>
<div class="row"><span>Restant</span><strong id="remaining">—</strong></div>
<div class="row"><span>Validité restante</span><strong id="expires">—</strong></div>
<div class="row"><span>Débit</span><strong id="speed">—</strong></div>
<div class="live" id="live"><span class="dot"></span><span>Actualisation toutes les 2 secondes</span></div>
<button onclick="refreshNow()">Actualiser maintenant</button>
</div>
</div>
<script>
async function refreshNow(){
  try{
    const r=await fetch('/status.json?ts='+Date.now(),{cache:'no-store'});
    const s=await r.json();
    if(!s.code){
      document.getElementById('plan').textContent='Aucun voucher actif';
      document.getElementById('code').textContent='';
      document.getElementById('used').textContent='—';
      document.getElementById('remaining').textContent='—';
      document.getElementById('expires').textContent='—';
      document.getElementById('speed').textContent='—';
      document.getElementById('bar').style.width='0%';
      document.getElementById('percent').textContent='';
      return;
    }
    document.getElementById('plan').textContent=s.plan || 'Voucher';
    document.getElementById('code').textContent=s.code;
    document.getElementById('used').textContent=s.usedText || '0 MB';
    document.getElementById('remaining').textContent=s.remainingText || '—';
    document.getElementById('expires').textContent=s.expiresText || '—';
    document.getElementById('speed').textContent=s.speed || '—';
    const p=Math.max(0,Math.min(100,s.percentUsed||0));
    document.getElementById('bar').style.width=(s.limitedData?p:0)+'%';
    document.getElementById('percent').textContent=s.limitedData ? p+' % utilisé' : 'Données illimitées';
    const live=document.getElementById('live');
    if(s.authorized){
      live.className='live';
      live.innerHTML='<span class="dot"></span><span>Connexion active · mise à jour toutes les 2 secondes</span>';
    }else{
      live.className='live offline';
      live.textContent='Accès expiré ou quota épuisé';
    }
  }catch(e){
    const live=document.getElementById('live');
    live.className='live offline';
    live.textContent='Suivi temporairement indisponible';
  }
}
refreshNow();
setInterval(refreshNow,2000);
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
	if !ok || !pass.Enabled {
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
		StartSessionBytes:   m.clientUsedLocked(ip),
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

func (m *TrafficManager) servePortal(conn net.Conn, clientIP string) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(15 * time.Second))

	req, err := http.ReadRequest(bufio.NewReader(conn))
	if err != nil {
		return
	}
	defer req.Body.Close()

	switch req.URL.Path {
	case "/status.json":
		m.servePortalStatusJSON(conn, clientIP)
		return
	case "/status":
		writePortalResponse(
			conn,
			"text/html; charset=utf-8",
			[]byte(m.renderLiveStatusPage()),
		)
		return
	}

	ok := m.portalAuthorizedFor(clientIP)
	message := ""
	if ok && req.Method == http.MethodGet {
		message = "Accès actif."
	}
	if req.Method == http.MethodPost {
		body, _ := io.ReadAll(io.LimitReader(req.Body, 16*1024))
		values, _ := url.ParseQuery(string(body))
		ok, message = m.submitPortalCode(clientIP, values.Get("code"))
	}

	page := m.renderPortalPage(clientIP, ok, message)
	if ok && req.Method == http.MethodPost {
		page = injectPortalValidationRedirect(page)
	}
	writePortalResponse(conn, "text/html; charset=utf-8", []byte(page))
}

func (m *TrafficManager) renderPortalPage(clientIP string, success bool, statusMessage string) string {
	m.mu.Lock()
	title := m.portalTitle
	message := m.portalMessage
	custom := m.portalHTML

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

			sessionUsed := (m.clientUsedLocked(clientIP) - auth.StartSessionBytes)
			if sessionUsed < 0 {
				sessionUsed = 0
			}
			totalQuota := pass.QuotaBytes
			remaining := int64(0)
			if totalQuota > 0 {
				remaining = auth.QuotaRemainingBytes - sessionUsed
				if remaining < 0 {
					remaining = 0
				}
				used := totalQuota - remaining
				if used < 0 {
					used = 0
				}
				usedText = formatPortalBytes(used)
				remainingText = formatPortalBytes(remaining)
			} else {
				usedText = formatPortalBytes(sessionUsed)
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
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{{TITLE}}</title>
<style>
:root{color-scheme:light dark}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;display:grid;place-items:center;font-family:system-ui,-apple-system,sans-serif;background:#111827;color:#f9fafb;padding:24px}
.card{width:min(100%,420px);background:#1f2937;border-radius:20px;padding:28px;box-shadow:0 20px 60px #0006}
h1{margin:0 0 8px;font-size:28px}
p{color:#d1d5db;line-height:1.45}
input,button{width:100%;font:inherit;border-radius:12px;padding:14px 16px}
input{border:1px solid #4b5563;background:#111827;color:#fff;margin:14px 0}
button{border:0;background:#f9fafb;color:#111827;font-weight:700;cursor:pointer}
.status{margin:14px 0;padding:12px;border-radius:12px}
.status.error{background:#7f1d1d}
.status.success{background:#14532d}
small{display:block;margin-top:18px;color:#9ca3af}
</style>
</head>
<body>
<main class="card">
<h1>{{TITLE}}</h1>
<p>{{MESSAGE}}</p>
{{STATUS}}
<form action="{{FORM_ACTION}}" method="post">
<input name="code" autocomplete="one-time-code" autocapitalize="characters" placeholder="Access code" required>
<button type="submit">Connect</button>
</form>
<small>Powered by Shizzi</small>
</main>
</body>
</html>`
