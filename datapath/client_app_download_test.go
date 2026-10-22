package datapath

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"strconv"
	"io"
	"net"
	"strings"
	"testing"
	"time"
)

func TestClientAppDownloadUsesNativeBrowserStreaming(t *testing.T) {
    page:=injectClientAppDownload(`<html><body><a class="download-button" href="http://192.0.2.1/shizzi-plus.apk?sha256=some" data-size="100" data-sha256="abc">Télécharger</a><div id="shizzi-download-status"></div></body></html>`)
    for _,expected:=range []string{"shizziNativeDownload","method:\"HEAD\"","Content-Length","X-Shizzi-SHA256","APK indisponible","link.addEventListener"} {
        if !strings.Contains(page,expected) {t.Fatalf("missing safe download preflight %q",expected)}
    }
    for _,forbidden:=range []string{"response.blob()","URL.createObjectURL","download=\"Shizzi-Plus-0.2.10-test.apk\""} {
        if strings.Contains(page,forbidden) {t.Fatalf("download must never buffer and save an unverified APK: %q",forbidden)}
    }
}


func TestClientAppDownloadScriptIsNotInjectedWithoutDownloadLink(t *testing.T) {
	page := `<html><body>Portail sans application</body></html>`
	if actual := injectClientAppDownload(page); actual != page {
		t.Fatal("download script injected when Shizzi+ is unavailable")
	}
}


func TestPortalShowsDirect192DownloadLink(t *testing.T) {
	manager := newTrafficManager()
	manager.mu.Lock()
	manager.portalClientApp = portalClientApp{
		Available: true,
		Version:   "0.2.10-test",
		FileName:  "Shizzi-Plus-0.2.10-test.apk",
		SizeBytes: 123456,
		SHA256:    strings.Repeat("a",64),
        BridgePort:8091,
	}
	manager.mu.Unlock()

	server, client := net.Pipe()
	done := make(chan struct{})
	go func() {
		defer close(done)
		manager.servePortal(server, "192.168.7.66")
	}()

	_ = client.SetDeadline(time.Now().Add(3 * time.Second))
	_, _ = fmt.Fprint(client, "GET / HTTP/1.1\r\nHost: 192.0.2.1\r\nConnection: close\r\n\r\n")
	response, err := io.ReadAll(client)
	_ = client.Close()
	if err != nil {
		t.Fatalf("read portal: %v", err)
	}
	<-done

	page := string(response)
	for _, expected := range []string{
		`http://192.0.2.1/shizzi-plus.apk`,
		`Shizzi+ 0.2.10-test`,
		`Shizzi-Plus-0.2.10-test.apk`,
	} {
		if !strings.Contains(page, expected) {
			t.Fatalf("portal direct download missing %q: %s", expected, page)
		}
	}
	if strings.Contains(page, `target="_blank"`) {
		t.Fatal("Shizzi+ download must stay in the current browser context")
	}
}

func TestShizziPlusDownloadPathVariants(t *testing.T) {
	valid := []string{
		"/shizzi-plus.apk",
		"/shizzi-plus.apk/",
		"/shizzi-plus.apk///",
		"/download/shizzi-plus.apk",
		"/download/shizzi-plus.apk/",
		"/SHIZZI-PLUS.APK/",
	}
	for _, path := range valid {
		if !isClientAppDownloadPath(path) {
			t.Fatalf("valid Shizzi+ path rejected: %q", path)
		}
	}

	for _, path := range []string{
		"/",
		"/shizzi-plus",
		"/shizzi-plus.apk.html",
		"/media/shizzi-plus.apk",
		"/download/",
	} {
		if isClientAppDownloadPath(path) {
			t.Fatalf("non-download path accepted: %q", path)
		}
	}
}

func TestTrailingSlashShizziPlusDownloadNeverFallsBackToPortalHTML(t *testing.T) {
    m:=newTrafficManager()
    m.portalClientApp=portalClientApp{Available:true,Version:"test",FileName:"client.apk",SizeBytes:100,SHA256:strings.Repeat("a",64)}
    response,body:=verifiedDownloadRequest(t,m,"GET","","http://192.0.2.1/shizzi-plus.apk/")
    if response.StatusCode!=503 || strings.Contains(response.Header.Get("Content-Type"),"package-archive") ||
       strings.Contains(response.Header.Get("Content-Disposition"),"attachment") {
       t.Fatalf("stale fixed-port download was not blocked: %d %q",response.StatusCode,body)
    }
}


func TestDirectShizziPlusAliasForwardsRangeAndStreamsResponse(t *testing.T) {
    data:=[]byte("0123456789abcdef")
    upstream:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
       _,_=w.Write(data)
    }))
    defer upstream.Close()
    _,portRaw,_:=net.SplitHostPort(upstream.Listener.Addr().String())
    port,_:=strconv.Atoi(portRaw)
    m:=newTrafficManager()
    m.portalClientApp=verifiedAnnouncement(data,port)
    response,body:=verifiedDownloadRequest(t,m,"GET","bytes=5-","http://192.0.2.1/download/shizzi-plus.apk")
    if response.StatusCode!=206 || string(body)!="56789abcdef" || response.Header.Get("X-Shizzi-SHA256")!=m.portalClientApp.SHA256 ||
       !strings.Contains(response.Header.Get("Content-Disposition"),"attachment") {
       t.Fatalf("verified HTTP range broken: %d %q",response.StatusCode,body)
    }
}


func TestPortalShowsDirectShizziAdminDownloadLink(t *testing.T) {
	manager := newTrafficManager()
	manager.mu.Lock()
	manager.portalClientApp = portalClientApp{
		Available: true,
		Version:   "0.2.10-test",
		FileName:  "Shizzi-Plus-0.2.10-test.apk",
		SizeBytes: 123456,
		SHA256:    "0123456789abcdef",
	}
	manager.portalAdminApp = portalClientApp{
        Available:true,Version:"0.3.0-test",FileName:"Shizzi-Admin-test.apk",
        SizeBytes:234567,SHA256:strings.Repeat("b",64),BridgePort:8091,
    }
	manager.mu.Unlock()

	server, client := net.Pipe()
	done := make(chan struct{})
	go func() {
		defer close(done)
		manager.servePortal(server, "192.168.7.66")
	}()

	_ = client.SetDeadline(time.Now().Add(3 * time.Second))
	_, _ = fmt.Fprint(client, "GET / HTTP/1.1\r\nHost: 192.0.2.1\r\nConnection: close\r\n\r\n")
	response, err := io.ReadAll(client)
	_ = client.Close()
	if err != nil {
		t.Fatalf("read portal: %v", err)
	}
	<-done

	page := string(response)
	for _, expected := range []string{
		`http://192.0.2.1/shizzi-admin.apk`,
		`Télécharger Shizzi Admin`,
		`APPLICATION ADMIN`,
	} {
		if !strings.Contains(page, expected) {
			t.Fatalf("admin direct download missing %q: %s", expected, page)
		}
	}
	if strings.Contains(page, `href="http://192.0.2.1/shizzi-admin.apk" target="_blank"`) {
		t.Fatal("Shizzi Admin download must stay in the current browser context")
	}
}

func TestShizziAdminDownloadPathVariants(t *testing.T) {
	valid := []string{
		"/shizzi-admin.apk",
		"/shizzi-admin.apk/",
		"/shizzi-admin.apk///",
		"/download/shizzi-admin.apk",
		"/download/shizzi-admin.apk/",
		"/SHIZZI-ADMIN.APK/",
	}
	for _, path := range valid {
		if !isAdminAppDownloadPath(path) {
			t.Fatalf("valid Shizzi Admin path rejected: %q", path)
		}
	}

	for _, path := range []string{
		"/",
		"/shizzi-admin",
		"/shizzi-admin.apk.html",
		"/media/shizzi-admin.apk",
		"/download/",
	} {
		if isAdminAppDownloadPath(path) {
			t.Fatalf("non-admin-download path accepted: %q", path)
		}
	}
}

func TestDirectShizziAdminAliasStreamsResponse(t *testing.T) {
    data:=[]byte("verified-admin-apk")
    upstream:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
        if r.URL.Path!="/shizzi-admin.apk" {w.WriteHeader(404);return}
        _,_=w.Write(data)
    }))
    defer upstream.Close()
    _,portRaw,_:=net.SplitHostPort(upstream.Listener.Addr().String())
    port,_:=strconv.Atoi(portRaw)
    m:=newTrafficManager()
    m.portalAdminApp=verifiedAnnouncement(data,port)
    m.portalAdminApp.FileName="Shizzi-Admin-test.apk"
    response,body:=verifiedDownloadRequest(t,m,"GET","","http://192.0.2.1/shizzi-admin.apk")
    if response.StatusCode!=200 || string(body)!=string(data) ||
        response.Header.Get("X-Shizzi-SHA256")!=m.portalAdminApp.SHA256 ||
        !strings.Contains(response.Header.Get("Content-Disposition"),"Shizzi-Admin-test.apk"){
        t.Fatalf("admin verification failed: status=%d body=%q",response.StatusCode,body)
    }
}


func TestViewerAPKDownloadAndPortalLink(t *testing.T) {
 data:=[]byte("verified-visionneuse-apk")
 upstream:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
  if r.URL.Path!="/shizzicam-admin.apk" {w.WriteHeader(404);return}
  _,_=w.Write(data)
 }))
 defer upstream.Close()
 _,p,_:=net.SplitHostPort(upstream.Listener.Addr().String())
 port,_:=strconv.Atoi(p)
 m:=newTrafficManager()
 m.portalCamAdminApp=verifiedAnnouncement(data,port)
 m.portalCamAdminApp.Version="0.3.1-relay-controls"
 m.portalCamAdminApp.FileName="ShizziCam-Admin-0.3.1.apk"
 response,body:=verifiedDownloadRequest(t,m,"GET","","http://192.0.2.1/shizzicam-admin.apk")
 if response.StatusCode!=200 || string(body)!=string(data) || response.Header.Get("X-Shizzi-SHA256")!=m.portalCamAdminApp.SHA256 {
  t.Fatalf("viewer verified APK download failed: %d %q",response.StatusCode,body)
 }
 if !isCamAdminAppDownloadPath("/download/shizzicam-admin.apk") {t.Fatal("viewer alias missing")}
}
