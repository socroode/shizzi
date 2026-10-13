package datapath

import (
    "bufio"
    "io"
    "net"
    "net/http"
    "net/netip"
    "net/url"
    "strings"
    "testing"
    "time"

    "dev.shizzi/datapath/core"
)

func TestV2DirectPINListenerRejectsWANLoopbackAndAllInterfaces(t *testing.T) {
    bridge := newV2AccountBridge(newTrafficManager())
    p := &v2PhysicalPortal{}
    for _, gateway := range []string{"0.0.0.0", "127.0.0.1", "8.8.8.8", "192.0.2.1", "::1", "not-an-ip"} {
        _, err := p.start(gateway, bridge)
        if err == nil {
            p.stop()
            t.Fatalf("unsafe gateway %q was accepted", gateway)
        }
    }
}

func TestV2CaptiveLandingSubmitsToPhysicalGatewayNotNAT(t *testing.T) {
    client, server := net.Pipe()
    defer client.Close()
    const action = "http://192.168.247.1:8093/login"
    done := make(chan struct{})
    go func() {
        servePublicPortalConn(server, action)
        close(done)
    }()
    if _, err := io.WriteString(client,
        "GET / HTTP/1.1\r\nHost: 192.0.2.1\r\nConnection: close\r\n\r\n"); err != nil { t.Fatal(err) }
    resp, err := http.ReadResponse(bufio.NewReader(client), &http.Request{Method: http.MethodGet})
    if err != nil { t.Fatal(err) }
    body, err := io.ReadAll(resp.Body)
    resp.Body.Close()
    if err != nil { t.Fatal(err) }
    if resp.StatusCode != http.StatusOK ||
        !strings.Contains(string(body), "action=\""+action+"\"") {
        t.Fatalf("TUN homepage did not point PIN at physical gateway: %d %s", resp.StatusCode, body)
    }
    if !strings.Contains(resp.Header.Get("Content-Security-Policy"), "http://192.168.247.1:8093") {
        t.Fatal("CSP blocks the genuine physical hotspot login form")
    }
    <-done
}

func TestV2PhysicalGatewayValidatesLegacyPINAndTUNOrigin(t *testing.T) {
    mgr := newTrafficManager()
    account := PortalAccount{
        Number: "1001", Enabled: true, PinSalt: "salt",
        PinHash: hashPortalPin("salt", "5280"),
    }
    mgr.portalAccounts["1001"] = account
    bridge := newV2AccountBridge(mgr)
    bridge.Sync()
    const clientIP = "192.168.247.161"
    if err := bridge.Associated("02:11:22:33:44:55", clientIP); err != nil { t.Fatal(err) }

    form := url.Values{"account": {"1001"}, "pin": {"5280"}}
    req, err := http.NewRequest(http.MethodPost,
        "http://192.168.247.1:8093/login", strings.NewReader(form.Encode()))
    if err != nil { t.Fatal(err) }
    req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
    req.Header.Set("Origin", "http://192.0.2.1")
    req.RemoteAddr = clientIP + ":50123"
    response := &physicalTestResponse{headers: make(http.Header)}
    (core.PortalEndpoint{Sessions: bridge.registry, Verifier: bridge}).ServeHTTP(response, req)
    if response.status != http.StatusOK {
        t.Fatalf("valid PIN from physical IP with TUN origin rejected: %d", response.status)
    }
    if bridge.registry.AuthorizeDirect(netip.MustParseAddr(clientIP), core.Internet,
        time.Now()) != core.DenyVoucherRequired {
        t.Fatal("physical PIN opened Internet without current voucher")
    }
}

type physicalTestResponse struct {
    headers http.Header
    status int
    body strings.Builder
}
func (p *physicalTestResponse) Header() http.Header { return p.headers }
func (p *physicalTestResponse) WriteHeader(code int) { p.status = code }
func (p *physicalTestResponse) Write(b []byte) (int, error) {
    if p.status == 0 { p.status = http.StatusOK }
    return p.body.Write(b)
}
