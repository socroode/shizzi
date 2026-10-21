package datapath

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// Allow WebRTC APKs above 32 MiB; bound memory used by verified download.
const maxVerifiedClientAPKBytes = 128 * 1024 * 1024

// Validate the entire APK before sending any APK bytes to a client. A stale
// loopback server, a mismatched announcement or a truncated response must fail.
func (m *TrafficManager) serveVerifiedClientAppDownload(conn net.Conn, request *http.Request, app portalClientApp) {
	fail := func(status, message string) {
		body := []byte(message)
		_, _ = fmt.Fprintf(conn, "HTTP/1.1 %s\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Disposition: inline\r\nX-Content-Type-Options: nosniff\r\nCache-Control: no-store\r\nContent-Length: %d\r\nConnection: close\r\n\r\n", status, len(body))
		if request.Method != http.MethodHead {
			_, _ = conn.Write(body)
		}
	}
	_ = conn.SetDeadline(time.Now().Add(30 * time.Second))
	digest, err := hex.DecodeString(app.SHA256)
	if err != nil || len(digest) != sha256.Size || app.SizeBytes <= 0 || app.SizeBytes > maxVerifiedClientAPKBytes || app.BridgePort < 1 || app.BridgePort > 65535 {
		fail("503 Service Unavailable", "Métadonnées Shizzi+ indisponibles. Relancez Shizzi Hotspot.")
		return
	}
	transport := &http.Transport{DialContext: (&net.Dialer{Timeout: 3 * time.Second}).DialContext, DisableKeepAlives: true}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 15 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	response, err := client.Get("http://" + net.JoinHostPort("127.0.0.1", strconv.Itoa(app.BridgePort)) + "/shizzi-plus.apk")
	if err != nil {
		fail("503 Service Unavailable", "Téléchargement Shizzi+ indisponible. Relancez Shizzi Hotspot.")
		return
	}
	defer response.Body.Close()
	data, readErr := io.ReadAll(io.LimitReader(response.Body, app.SizeBytes+1))
	actual := sha256.Sum256(data)
	if response.StatusCode != http.StatusOK || readErr != nil || int64(len(data)) != app.SizeBytes || !strings.EqualFold(hex.EncodeToString(actual[:]), app.SHA256) {
		fail("502 Bad Gateway", "Téléchargement refusé : APK Shizzi+ non conforme. Relancez Shizzi Hotspot puis réessayez.")
		return
	}
	first, last := int64(0), int64(len(data)-1)
	status := "200 OK"
	etag := "\"" + app.SHA256 + "\""
	if raw := request.Header.Get("Range"); raw != "" && (request.Header.Get("If-Range") == "" || request.Header.Get("If-Range") == etag) {
		var valid bool
		first, last, valid = verifiedClientRange(raw, int64(len(data)))
		if !valid {
			_, _ = fmt.Fprintf(conn, "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */%d\r\nContent-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n", len(data))
			return
		}
		status = "206 Partial Content"
	}
	_, _ = fmt.Fprintf(conn, "HTTP/1.1 %s\r\nContent-Type: application/vnd.android.package-archive\r\nContent-Disposition: %s\r\nContent-Length: %d\r\nAccept-Ranges: bytes\r\nX-Shizzi-Version: %s\r\nX-Shizzi-SHA256: %s\r\nETag: %s\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n", status, mime.FormatMediaType("attachment", map[string]string{"filename": app.FileName}), last-first+1, app.Version, app.SHA256, etag)
	if status == "206 Partial Content" {
		_, _ = fmt.Fprintf(conn, "Content-Range: bytes %d-%d/%d\r\n", first, last, len(data))
	}
	_, _ = io.WriteString(conn, "\r\n")
	if request.Method != http.MethodHead {
		_, _ = conn.Write(data[first : last+1])
	}
}

func verifiedClientRange(raw string, size int64) (int64, int64, bool) {
	if size <= 0 || !strings.HasPrefix(raw, "bytes=") || strings.Contains(raw, ",") {
		return 0, 0, false
	}
	parts := strings.Split(strings.TrimSpace(strings.TrimPrefix(raw, "bytes=")), "-")
	if len(parts) != 2 { return 0, 0, false }
	if parts[0] == "" {
		suffix, err := strconv.ParseInt(parts[1], 10, 64)
		if err != nil || suffix <= 0 { return 0, 0, false }
		if suffix > size { suffix = size }
		return size - suffix, size - 1, true
	}
	first, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil || first < 0 || first >= size { return 0, 0, false }
	last := size - 1
	if parts[1] != "" {
		last, err = strconv.ParseInt(parts[1], 10, 64)
		if err != nil || last < first { return 0, 0, false }
		if last >= size { last = size - 1 }
	}
	return first, last, true
}
