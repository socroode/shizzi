package datapath

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"html"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"regexp"
	"strconv"
	"strings"
	"testing"
	"time"
)

func verifiedDownloadRequest(t *testing.T, manager *TrafficManager, method, rangeHeader string, target ...string) (*http.Response, []byte) {
	t.Helper()
	server, client := net.Pipe()
	done := make(chan struct{})
	go func() {
		defer close(done)
		defer server.Close()
		url := "http://192.0.2.1/shizzi-plus.apk"
		if len(target) > 0 { url = target[0] }
		req, _ := http.NewRequest(method, url, nil)
		req.Header.Set("Range", rangeHeader)
		manager.serveClientAppDownload(server, req)
	}()
	_ = client.SetDeadline(time.Now().Add(20 * time.Second))
	response, err := http.ReadResponse(bufio.NewReader(client), &http.Request{Method: method})
	if err != nil { t.Fatal(err) }
	body, err := io.ReadAll(response.Body)
	_ = response.Body.Close()
	_ = client.Close()
	<-done
	if err != nil { t.Fatal(err) }
	return response, body
}

func verifiedAnnouncement(data []byte, port int) portalClientApp {
	digest := sha256.Sum256(data)
	return portalClientApp{Available: true, Version: "0.3.0-background-calls", FileName: "Shizzi-Plus-0.3.0-background-calls.apk", SizeBytes: int64(len(data)), SHA256: hex.EncodeToString(digest[:]), BridgePort: port}
}

func TestVerifiedDownloadRejectsWrongOrTruncatedAPKBeforeSendingBytes(t *testing.T) {
	expected := []byte("correct-new-shizzi-plus-apk")
	for _, bad := range [][]byte{[]byte("obsolete-shizzi-plus"), expected[:8], bytes.Repeat([]byte("x"), len(expected))} {
		backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write(bad) }))
		_, portText, _ := net.SplitHostPort(backend.Listener.Addr().String())
		port, _ := strconv.Atoi(portText)
		manager := newTrafficManager()
		manager.portalClientApp = verifiedAnnouncement(expected, port)
		response, body := verifiedDownloadRequest(t, manager, "GET", "")
		backend.Close()
		if response.StatusCode != 502 || bytes.Contains(body, bad) { t.Fatalf("wrong APK was not rejected: %d %s", response.StatusCode, body) }
	}
}

func TestVerifiedDownloadPreservesRangesAndHeadAfterFullVerification(t *testing.T) {
	data := []byte("0123456789")
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Range") != "" { t.Error("partial upstream response cannot prove the full APK identity") }
		_, _ = w.Write(data)
	}))
	defer backend.Close()
	_, portText, _ := net.SplitHostPort(backend.Listener.Addr().String())
	port, _ := strconv.Atoi(portText)
	manager := newTrafficManager()
	manager.portalClientApp = verifiedAnnouncement(data, port)
	for _, tc := range []struct { method, raw, body string; status int }{
		{"GET", "", "0123456789", 200}, {"GET", "bytes=2-5", "2345", 206},
		{"GET", "bytes=-3", "789", 206}, {"HEAD", "", "", 200},
		{"GET", "bytes=20-", "", 416}, {"GET", "bytes=0-1,4-5", "", 416},
	} {
		response, body := verifiedDownloadRequest(t, manager, tc.method, tc.raw)
		if response.StatusCode != tc.status || string(body) != tc.body { t.Fatalf("%s %s: %d %q", tc.method, tc.raw, response.StatusCode, body) }
	}
}

// The release gate invokes this with a live production Kotlin server and the
// real signed Shizzi+ extracted from the signed router APK. Normal Go runs use
// an in-process fixture; neither path skips verification.
func TestReleasePortalDownload(t *testing.T) {
	data := []byte("fixture-current-apk")
	port := 0
	if path := os.Getenv("SHIZZI_EXPECTED_PLUS_APK"); path != "" {
		var err error
		data, err = os.ReadFile(path)
		if err != nil { t.Fatal(err) }
		port, err = strconv.Atoi(os.Getenv("SHIZZI_DISTRIBUTION_PORT"))
		if err != nil || port <= 0 { t.Fatal("release verification needs a running Kotlin bridge") }
	} else {
		backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write(data) }))
		defer backend.Close()
		_, p, _ := net.SplitHostPort(backend.Listener.Addr().String())
		port, _ = strconv.Atoi(p)
	}
	manager := newTrafficManager()
	manager.portalClientApp = verifiedAnnouncement(data, port)
	server, client := net.Pipe()
	done := make(chan struct{})
	go func() { defer close(done); defer server.Close(); manager.servePortal(server, "192.168.7.66") }()
	_ = client.SetDeadline(time.Now().Add(5 * time.Second))
	_, _ = fmt.Fprint(client, "GET / HTTP/1.1\r\nHost: 192.0.2.1\r\nConnection: close\r\n\r\n")
	page, err := io.ReadAll(client)
	_ = client.Close()
	<-done
	if err != nil { t.Fatal(err) }
	match := regexp.MustCompile(`href="([^"]*shizzi-plus\.apk[^"]*)"`).FindStringSubmatch(string(page))
	if len(match) < 2 || !strings.Contains(match[1], "?sha256=" + manager.portalClientApp.SHA256) || !strings.Contains(string(page), "Shizzi+ 0.3.0-background-calls") {
		t.Fatalf("portal does not advertise the current version and fingerprint: %s", page)
	}
	response, body := verifiedDownloadRequest(t, manager, "GET", "", html.UnescapeString(match[1]))
	if response.StatusCode != 200 || !bytes.Equal(body, data) || response.Header.Get("X-Shizzi-Version") != "0.3.0-background-calls" || !strings.Contains(response.Header.Get("Content-Disposition"), "Shizzi-Plus-0.3.0-background-calls.apk") {
		t.Fatalf("release portal download differs from expected APK: %d, %v", response.StatusCode, response.Header)
	}
	if path := os.Getenv("SHIZZI_DOWNLOADED_PLUS_APK"); path != "" {
		if err := os.WriteFile(path, body, 0600); err != nil { t.Fatal(err) }
		fmt.Printf("Verified captive download: %d bytes, SHA-256 %s\n", len(body), response.Header.Get("X-Shizzi-SHA256"))
	}
}

func TestVerifiedDownloadRejectsObsoleteFingerprintURL(t *testing.T) {
	manager := newTrafficManager()
	manager.portalClientApp = verifiedAnnouncement([]byte("new-current-apk"), 12345)
	response, _ := verifiedDownloadRequest(t, manager, "GET", "", "http://192.0.2.1/shizzi-plus.apk?sha256=old-fingerprint")
	if response.StatusCode != 410 { t.Fatalf("obsolete download URL returned %d", response.StatusCode) }
}
