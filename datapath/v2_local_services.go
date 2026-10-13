package datapath

import (
    "fmt"
    "bytes"
    "io"
    "net"
    "net/http"
    "net/netip"
    "net/url"
    "strings"
    "time"

    "dev.shizzi/datapath/core"
)

// These services bind 127.0.0.1 inside the router. A remote browser cannot
// select a backend or supply a trusted account header.
type v2LocalBridge struct {
    media string
    chat string
    apps string
}

func defaultV2LocalBridge() v2LocalBridge {
    return v2LocalBridge{media: mediaBridgeAddress, chat: chatBridgeAddress, apps: clientAppBridgeAddress}
}

var v2LocalHTTPTransport = &http.Transport{
    Proxy: nil,
    DisableKeepAlives: true,
    DisableCompression: true,
    MaxIdleConns: 0,
    ResponseHeaderTimeout: 8 * time.Second,
    DialContext: (&net.Dialer{Timeout: 3 * time.Second}).DialContext,
}

func v2PhysicalClientIP(r *http.Request) (netip.Addr, bool) {
    host, _, err := net.SplitHostPort(r.RemoteAddr)
    if err != nil { return netip.Addr{}, false }
    ip, err := netip.ParseAddr(host)
    return ip, err == nil && ip.Is4() && ip.IsPrivate() && !ip.IsLoopback()
}

func v2LocalTarget(request *http.Request, prefix string) string {
    path := strings.TrimPrefix(request.URL.Path, prefix)
    if path == "" { path = "/" }
    if !strings.HasPrefix(path, "/") { path = "/" + path }
    if request.URL.RawQuery != "" { path += "?" + request.URL.RawQuery }
    return path
}

func v2ForwardRequest(r *http.Request, address, target, account, kind string) (*http.Response, error) {
    parsed, err := url.Parse("http://" + address + target)
    if err != nil { return nil, err }
    var body io.Reader
    if r.Method == http.MethodPost {
        data, readErr := io.ReadAll(io.LimitReader(r.Body, 64*1024+1))
        if readErr != nil { return nil, readErr }
        if len(data) > 64*1024 { return nil, fmt.Errorf("POST exceeds 64 KiB") }
        body = bytes.NewReader(data) // upstream requires Content-Length
    }
    upstream, err := http.NewRequestWithContext(r.Context(), r.Method, parsed.String(), body)
    if err != nil { return nil, err }
    // Do not forward a browser-supplied Shizzi account, token, Host, Cookie,
    // or X-Forwarded-* header. Trusted identity comes from Registry only.
    upstream.Host = "localhost"
    for _, name := range []string{"Range", "If-Range", "Accept", "User-Agent", "Content-Type"} {
        if value := r.Header.Get(name); value != "" { upstream.Header.Set(name, value) }
    }
    switch kind {
    case "media":
        upstream.Header.Set("X-Shizzi-Media-Account", account)
    case "chat":
        upstream.Header.Set("X-Shizzi-Chat-Account", account)
    }
    return v2LocalHTTPTransport.RoundTrip(upstream)
}

// The physical listener streams media and app bytes directly. No buffering
// of multi-GB films or APK files in a ResponseRecorder.
func (b v2LocalBridge) serve(w http.ResponseWriter, r *http.Request, bridgeKind string, account string, allowed func() bool) {
    address, prefix := "", ""
    switch bridgeKind {
    case "media":
        address, prefix = b.media, "/media"
        if r.Method != http.MethodGet && r.Method != http.MethodHead {
            http.Error(w, "GET/HEAD uniquement", http.StatusMethodNotAllowed)
            return
        }
    case "chat":
        address, prefix = b.chat, "/chat"
        if r.Method != http.MethodGet && r.Method != http.MethodHead && r.Method != http.MethodPost {
            http.Error(w, "Méthode interdite", http.StatusMethodNotAllowed)
            return
        }
    case "apps":
        address = b.apps
        if !isClientAppDownloadPath(r.URL.Path) && !isAdminAppDownloadPath(r.URL.Path) {
            http.NotFound(w,r)
            return
        }
        if r.Method != http.MethodGet && r.Method != http.MethodHead {
            http.Error(w, "GET/HEAD uniquement", http.StatusMethodNotAllowed)
            return
        }
    default:
        http.NotFound(w,r)
        return
    }
    if allowed != nil && !allowed() {
        http.Error(w, "Session ou droits expirés. Reconnecte ton compte.", http.StatusForbidden)
        return
    }
    if bridgeKind == "chat" && r.Method == http.MethodPost && r.ContentLength > 64*1024 {
        http.Error(w,"Requête trop volumineuse.",http.StatusRequestEntityTooLarge)
        return
    }
    target := r.URL.RequestURI()
    if prefix != "" { target = v2LocalTarget(r, prefix) }
    if bridgeKind == "apps" {
        if isAdminAppDownloadPath(r.URL.Path) { target = "/shizzi-admin.apk" } else { target = "/shizzi-plus.apk" }
    }
    res, err := v2ForwardRequest(r, address, target, account, bridgeKind)
    if err != nil {
        http.Error(w, fmt.Sprintf("Service local %s momentanément indisponible", bridgeKind),
            http.StatusServiceUnavailable)
        return
    }
    defer res.Body.Close()
    for _, name := range []string{"Content-Type","Content-Length","Content-Range",
        "Accept-Ranges","Content-Disposition","Cache-Control","ETag","Last-Modified"} {
        if v := res.Header.Values(name); len(v)>0 {
            for _, value := range v { w.Header().Add(name,value) }
        }
    }
    w.Header().Set("X-Content-Type-Options","nosniff")
    w.Header().Set("Cache-Control","no-store")
    w.WriteHeader(res.StatusCode)
    if r.Method == http.MethodHead { return }
    chunk := make([]byte, 16384)
    for {
        if allowed != nil && !allowed() { return }
        n,readErr := res.Body.Read(chunk)
        if n > 0 {
            if allowed != nil && !allowed() { return }
            if _,err := w.Write(chunk[:n]); err != nil { return }
            if flusher,ok := w.(http.Flusher); ok { flusher.Flush() }
        }
        if readErr != nil { return }
    }
}

// The captive TUN entry point cannot know the remote station IP. Public APK
// downloads are deliberately allowed without account, but Media/Chat are NOT.
func (b v2LocalBridge) servePublicDownload(conn net.Conn, r *http.Request) {
    defer conn.Close()
    if r.Method != http.MethodGet && r.Method != http.MethodHead { return }
    target := "/shizzi-plus.apk"
    if isAdminAppDownloadPath(r.URL.Path) { target = "/shizzi-admin.apk" }
    res, err := v2ForwardRequest(r, b.apps, target, "", "apps")
    if err != nil {
        body := []byte("Application locale indisponible")
        _, _ = fmt.Fprintf(conn,
            "HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s",
            len(body), body)
        return
    }
    defer res.Body.Close()
    res.Close = true
    _ = conn.SetWriteDeadline(time.Now().Add(3*time.Minute))
    _ = res.Write(conn)
}

func (b v2LocalBridge) routePhysical(w http.ResponseWriter, r *http.Request, registry *core.Registry) bool {
    media := r.URL.Path == "/media" || strings.HasPrefix(r.URL.Path, "/media/")
    chat := r.URL.Path == "/chat" || strings.HasPrefix(r.URL.Path, "/chat/")
    app := isClientAppDownloadPath(r.URL.Path) || isAdminAppDownloadPath(r.URL.Path)
    if !media && !chat && !app { return false }

    if app {
        b.serve(w,r,"apps","",nil)
        return true
    }
    ip,found := v2PhysicalClientIP(r)
    if !found || registry == nil {
        http.Error(w,"Connexion Wi-Fi non vérifiable",http.StatusForbidden)
        return true
    }
    account,generation,valid := registry.AuthenticatedLease(ip)
    if !valid {
        http.Error(w,"Numéro de compte et PIN requis",http.StatusUnauthorized)
        return true
    }
    checkMedia := media
    check := func() bool {
        return registry.LocalLeaseStillValid(ip,account,generation,checkMedia,time.Now())
    }
    if !check() {
        http.Error(w,"Voucher Média requis ou session expirée",http.StatusForbidden)
        return true
    }
    if chat && r.Method == http.MethodPost {
        origin := r.Header.Get("Origin")
        if origin != "" && origin != "http://"+r.Host {
            http.Error(w,"Origine interdite",http.StatusForbidden)
            return true
        }
    }
    kind := "chat"
    if media { kind="media" }
    b.serve(w,r,kind,account,check)
    return true
}
