package datapath

import (
    "errors"
    "fmt"
    "net"
    "net/http"
    "net/netip"
    "strings"
    "sync"
    "time"

    "dev.shizzi/datapath/core"
)

const v2DirectLoginPort = "8093"

// v2PhysicalPortal serves ONLY on Android's physically owned SoftAP gateway
// address (e.g. 192.168.247.1). Client TCP peer IP remains pre-NAT.
// Port 8093 is never exposed on all interfaces or on Starlink/4G upstream.
type v2PhysicalPortal struct {
    mu sync.Mutex
    server *http.Server
    listener net.Listener
    action string
}

func (p *v2PhysicalPortal) start(gateway string, bridge *v2AccountBridge) (string, error) {
    ip, err := netip.ParseAddr(strings.TrimSpace(gateway))
    if err != nil || !ip.Is4() || !ip.IsPrivate() ||
        ip.IsLoopback() || ip.IsUnspecified() {
        return "", errors.New("v2 login requires a verified private IPv4 hotspot gateway")
    }
    if bridge == nil { return "", errors.New("V2 account bridge is unavailable") }

    address := net.JoinHostPort(ip.String(), v2DirectLoginPort)
    p.mu.Lock()
    defer p.mu.Unlock()
    if p.listener != nil {
        if p.action == "http://"+address+"/login" { return p.action, nil }
        return "", errors.New("another V2 gateway is already bound")
    }
    listener, err := net.Listen("tcp4", address)
    if err != nil { return "", fmt.Errorf("V2 gateway bind %s: %w", address, err) }

    endpoint := core.PortalEndpoint{Sessions: bridge.registry, Verifier: bridge}
    handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        // Reject Host header DNS rebinding and accidental access from another
        // interface. Local TCP binding and original peer IP are independent.
        if r.Host != address {
            http.Error(w, "Hôte Shizzi invalide.", http.StatusMisdirectedRequest)
            return
        }
        if r.URL.Path != "/login" {
            http.NotFound(w, r)
            return
        }
        endpoint.ServeHTTP(w, r)
    })
    server := &http.Server{
        Handler: handler,
        ReadHeaderTimeout: 4 * time.Second,
        ReadTimeout: 12 * time.Second,
        WriteTimeout: 12 * time.Second,
        IdleTimeout: 10 * time.Second,
        MaxHeaderBytes: 8192,
    }
    p.listener = listener
    p.server = server
    p.action = "http://"+address+"/login"
    go func() { _ = server.Serve(listener) }()
    return p.action, nil
}

func (p *v2PhysicalPortal) stop() {
    p.mu.Lock()
    server := p.server
    listener := p.listener
    p.server = nil
    p.listener = nil
    p.action = ""
    p.mu.Unlock()
    if server != nil { _ = server.Close() }
    if listener != nil { _ = listener.Close() }
}

func (p *v2PhysicalPortal) loginAction() string {
    p.mu.Lock()
    defer p.mu.Unlock()
    return p.action
}
