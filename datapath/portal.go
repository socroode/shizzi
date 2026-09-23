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
	Code             string `json:"code"`
	Name             string `json:"name"`
	DownloadMbps     int64  `json:"downloadMbps"`
	UploadMbps       int64  `json:"uploadMbps"`
	QuotaBytes       int64  `json:"quotaBytes"`
	DurationMinutes  int64  `json:"durationMinutes"`
	AssignedDeviceID string `json:"assignedDeviceId"`
	Enabled          bool   `json:"enabled"`
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
}

type portalConfigPayload struct {
	Title   string       `json:"title"`
	Message string       `json:"message"`
	HTML    string       `json:"html"`
	Passes  []PortalPass `json:"passes"`
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

	m.portalAuthorized[ip] = PortalAuthorization{
		Code:                normalizePortalCode(code),
		ExpiresAtMillis:     expiresAtMillis,
		QuotaRemainingBytes: quotaRemainingBytes,
		StartSessionBytes:   m.clientUsedLocked(ip),
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

func (m *TrafficManager) portalAuthorizedLocked(ip string) bool {
	auth, ok := m.portalAuthorized[ip]
	if !ok {
		return false
	}
	if auth.ExpiresAtMillis > 0 && time.Now().UnixMilli() >= auth.ExpiresAtMillis {
		delete(m.portalAuthorized, ip)
		return false
	}
	if auth.QuotaRemainingBytes > 0 {
		used := m.clientUsedLocked(ip) - auth.StartSessionBytes
		if used >= auth.QuotaRemainingBytes {
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
	if strings.TrimSpace(pass.AssignedDeviceID) != "" {
		return false, "This access code is already in use."
	}
	for otherIP, auth := range m.portalAuthorized {
		if otherIP != ip && auth.Code == code && m.portalAuthorizedLocked(otherIP) {
			return false, "This access code is already in use."
		}
	}

	expiresAt := int64(0)
	if pass.DurationMinutes > 0 {
		expiresAt = time.Now().Add(time.Duration(pass.DurationMinutes) * time.Minute).UnixMilli()
	}

	m.portalAuthorized[ip] = PortalAuthorization{
		Code:                code,
		ExpiresAtMillis:     expiresAt,
		QuotaRemainingBytes: pass.QuotaBytes,
		StartSessionBytes:   m.clientUsedLocked(ip),
	}
	m.portalClaims = append(m.portalClaims, PortalClaim{
		IP:              ip,
		Code:            code,
		ClaimedAtMillis: time.Now().UnixMilli(),
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

	ok := false
	message := ""
	if req.Method == http.MethodPost {
		body, _ := io.ReadAll(io.LimitReader(req.Body, 16*1024))
		values, _ := url.ParseQuery(string(body))
		ok, message = m.submitPortalCode(clientIP, values.Get("code"))
	}

	page := m.renderPortalPage(ok, message)
	status := "200 OK"
	headers := fmt.Sprintf(
		"HTTP/1.1 %s\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store, no-cache, must-revalidate\r\nPragma: no-cache\r\nConnection: close\r\nContent-Length: %d\r\n\r\n",
		status,
		len([]byte(page)),
	)
	_, _ = io.WriteString(conn, headers)
	_, _ = io.WriteString(conn, page)
}

func (m *TrafficManager) renderPortalPage(success bool, statusMessage string) string {
	m.mu.Lock()
	title := m.portalTitle
	message := m.portalMessage
	custom := m.portalHTML
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

	replacer := strings.NewReplacer(
		"{{TITLE}}", html.EscapeString(title),
		"{{MESSAGE}}", html.EscapeString(message),
		"{{STATUS}}", status,
		"{{FORM_ACTION}}", "/login",
	)
	return replacer.Replace(custom)
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
