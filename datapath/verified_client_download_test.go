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


func TestVerifiedDownloadAcceptsLargeWebRTCAPK(t *testing.T) {
 // The real signed Shizzi+Cam is ~48 MB. The historical 32 MiB limit
 // made the browser save a 61-byte error as an APK file.
 data := bytes.Repeat([]byte{0x5a}, 48_216_718)
 backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
  w.Header().Set("Content-Length",strconv.Itoa(len(data)))
  _,_ = w.Write(data)
 }))
 defer backend.Close()
 _, p, _ := net.SplitHostPort(backend.Listener.Addr().String())
 port, _ := strconv.Atoi(p)
 m := newTrafficManager()
 m.portalClientApp = verifiedAnnouncement(data,port)
 response, bytes := verifiedDownloadRequest(t,m,http.MethodHead,"")
 if response.StatusCode != 200 || len(bytes) != 0 {
  t.Fatalf("large verified APK HEAD failed: status=%d body=%q",response.StatusCode,bytes)
 }
 if response.ContentLength != int64(len(data)) ||
    response.Header.Get("X-Shizzi-SHA256") != m.portalClientApp.SHA256 {
  t.Fatalf("large APK metadata wrong: size=%d header=%v",response.ContentLength,response.Header)
 }
}

func TestTinyServerErrorNeverBecomesAPKOnEitherDownload(t *testing.T) {
    upstream:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
        w.WriteHeader(http.StatusServiceUnavailable)
        _,_=w.Write(bytes.Repeat([]byte("x"),61))
    }))
    defer upstream.Close()
    _,p,_:=net.SplitHostPort(upstream.Listener.Addr().String())
    port,_:=strconv.Atoi(p)
    expected:=bytes.Repeat([]byte("P"),4096)
    manager:=newTrafficManager()
    manager.portalClientApp=verifiedAnnouncement(expected,port)
    manager.portalAdminApp=verifiedAnnouncement(expected,port)
    manager.portalAdminApp.FileName="Shizzi-Admin-real.apk"
    for _,path:=range []string{"/shizzi-plus.apk","/shizzi-admin.apk"} {
        for _,method:=range []string{"GET","HEAD"} {
            response,body:=verifiedDownloadRequest(t,manager,method,"","http://192.0.2.1"+path)
            if response.StatusCode!=502 || strings.Contains(response.Header.Get("Content-Type"),"package-archive") ||
                strings.Contains(response.Header.Get("Content-Disposition"),"attachment") ||
                response.Header.Get("X-Shizzi-Download-Error")!="true" {
                t.Fatalf("%s %s delivered bad APK: status=%d headers=%v",method,path,response.StatusCode,response.Header)
            }
            if method=="HEAD" && len(body)!=0 {t.Fatalf("HEAD %s returned a body",path)}
            if method=="GET" && bytes.Equal(body,bytes.Repeat([]byte("x"),61)) {
                t.Fatalf("download error masqueraded as APK: %s",path)
            }
        }
    }
}

func TestViewerVerifiedDownloadRejectsTinyError(t *testing.T) {
 source:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
   w.WriteHeader(503)
   _,_=w.Write(bytes.Repeat([]byte("e"),61))
 }))
 defer source.Close()
 _,p,_:=net.SplitHostPort(source.Listener.Addr().String())
 port,_:=strconv.Atoi(p)
 m:=newTrafficManager()
 m.portalCamViewerApp=verifiedAnnouncement(bytes.Repeat([]byte{0x41},3000),port)
 for _,method:=range []string{"GET","HEAD"} {
   resp,_:=verifiedDownloadRequest(t,m,method,"","http://192.0.2.1/shizzicam-admin.apk")
   if resp.StatusCode!=502 || resp.Header.Get("X-Shizzi-Download-Error")!="true" ||
     strings.Contains(resp.Header.Get("Content-Type"),"package-archive") {
      t.Fatalf("viewer error saved as apk: %s %d",method,resp.StatusCode)
   }
 }
}
