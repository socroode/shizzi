package datapath

import (
    "bufio"
    "fmt"
    "io"
    "net"
    "net/http"
    "strings"
    "time"
)

const v2CaptiveLocation = "http://192.0.2.1/"

// isV2CaptiveProbe accepts only common Android, Apple, Windows and Firefox
// connectivity checks. Arbitrary browsing remains blocked before vouchers.
func isV2CaptiveProbe(r *http.Request) bool {
    if r.Method != http.MethodGet && r.Method != http.MethodHead { return false }
    host := strings.ToLower(strings.TrimSuffix(r.Host, "."))
    if index := strings.IndexByte(host, ':'); index >= 0 { host=host[:index] }
    path := r.URL.Path
    switch host {
    case "connectivitycheck.gstatic.com","connectivitycheck.android.com",
        "clients3.google.com","www.google.com","play.googleapis.com":
        return path == "/generate_204" || path == "/gen_204"
    case "captive.apple.com":
        return path == "/hotspot-detect.html"
    case "www.msftconnecttest.com":
        return path == "/connecttest.txt"
    case "www.msftncsi.com":
        return path == "/ncsi.txt"
    case "detectportal.firefox.com":
        return path == "/canonical.html"
    }
    return false
}

// serveV2CaptiveProbe never forwards the request to WAN and never accepts a
// number/PIN. It only tells supported OS connectivity checks where to open
// the PUBLIC local homepage. The browser must still authenticate via the
// verified physical hotspot listener.
func serveV2CaptiveProbe(conn net.Conn) {
    defer conn.Close()
    _ = conn.SetDeadline(time.Now().Add(3*time.Second))
    request,err:=http.ReadRequest(bufio.NewReader(io.LimitReader(conn,8192)))
    if err!=nil{return}
    defer request.Body.Close()
    if !isV2CaptiveProbe(request) { return }
    _,_=fmt.Fprintf(conn,
        "HTTP/1.1 302 Found\r\nLocation: %s\r\nContent-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
        v2CaptiveLocation)
}
