package datapath

import (
    "fmt"
    "io"
    "net"
    "strings"
    "sync"
    "sync/atomic"
    "testing"
    "time"

    "gvisor.dev/gvisor/pkg/tcpip"
    "gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
    "gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
)

// The Android Wi-Fi AP can stay connected while TUN flow attribution
// temporarily fails. In that state the local portal must explain the
// problem instead of silently resetting all TCP connections.
func TestWifiConnectedButNATUnidentifiedStillShowsRecoveryPortal(t *testing.T) {
    traffic := newPortalManager(t)
    pushConfig(t, traffic, accountForTest("1001", "pass", 100000))
    if ok, msg := traffic.submitPortalAccountLogin(phoneA, "1001", "pass"); !ok {
        t.Fatal(msg)
    }

    var mu sync.Mutex
    dump := simulatedClientList(phoneA, phoneB) +
        "IPv4 Upstream:\nIPv4 Downstream:\n"
    traffic.flowAttribution.dumpFn = func() (string, error) {
        mu.Lock()
        defer mu.Unlock()
        return dump, nil
    }

    stack := linkedStacks(t, traffic)
    dial := func(path, method, body string) (string, uint16) {
        t.Helper()
        conn, err := gonet.DialTCP(stack, tcpip.FullAddress{
            NIC: 1, Addr: tcpip.AddrFrom4([4]byte{192, 0, 2, 1}), Port: 80,
        }, ipv4.ProtocolNumber)
        if err != nil {
            t.Fatal(err)
        }
        defer conn.Close()
        sourcePort := uint16(conn.LocalAddr().(*net.TCPAddr).Port)
        conn.SetDeadline(time.Now().Add(10*time.Second))
        fmt.Fprintf(conn, "%s %s HTTP/1.1\r\nHost: 192.0.2.1\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s", method, path, len(body), body)
        data, err := io.ReadAll(conn)
        if err != nil && len(data) == 0 {
            t.Fatal(err)
        }
        return string(data), sourcePort
    }

    // Missing NAT attribution must not leak another user's account.
    response, _ := dial("/", "GET", "")
    if !strings.Contains(response, "HTTP/1.1 200 OK") ||
        !strings.Contains(response, "appareil non identifié") ||
        !strings.Contains(response, "192.0.2.1") {
        t.Fatalf("unidentified Wi-Fi client has no recovery portal: %q", response)
    }
    if strings.Contains(response, "password") || strings.Contains(response, "Numéro de compte") {
        t.Fatal("unidentified client was offered an unsafe authenticated portal")
    }
    if !strings.Contains(response, "shizzi_retry=1") || !strings.Contains(response, "http-equiv=\"refresh\"") {
        t.Fatalf("unidentified client must retry portal automatically without Wi-Fi reassociation: %q", response)
    }
    exhausted, _ := dial("/?shizzi_retry=5", "GET", "")
    if strings.Contains(exhausted, "http-equiv=\"refresh\"") {
        t.Fatal("recovery auto-refresh must stop after five attempts")
    }

    // A POST cannot invent a client IP or steal an active account.
    response, _ = dial("/login", "POST", "account=1001&pin=pass")
    if !strings.Contains(response, "appareil non identifié") {
        t.Fatalf("unattributed login bypassed safety portal: %q", response)
    }
    if len(statsOf(t, traffic).PortalAuthorizations) != 1 {
        t.Fatal("unattributed login changed account ownership")
    }
    if traffic.portalRequiredFor(phoneA) {
        t.Fatal("unknown client mistakenly evicted existing account holder")
    }

    // When Android publishes the real translation, the same Wi-Fi client
    // can reach the normal portal without reassociating to the access point.
    conn, err := gonet.DialTCP(stack, tcpip.FullAddress{
        NIC: 1, Addr: tcpip.AddrFrom4([4]byte{192, 0, 2, 1}), Port: 80,
    }, ipv4.ProtocolNumber)
    if err != nil {
        t.Fatal(err)
    }
    defer conn.Close()
    sourcePort := conn.LocalAddr().(*net.TCPAddr).Port
    mu.Lock()
    dump = fmt.Sprintf(`IPv4 Upstream:
 tcp [02:00:00:00:00:01] 47(ap0) %s:41000 -> 76(testtun0) 192.0.2.2:%d -> 192.0.2.1:80 [00:00:00:00:00:00] 1500 3ms
IPv4 Downstream:
Client Information:
{android.net.ip.IpServer@1={/%s=downstream: 41, /%s=downstream: 41}}`,
        phoneA, sourcePort, phoneA, phoneB)
    mu.Unlock()
    traffic.flowAttribution.mu.Lock()
    traffic.flowAttribution.lastRefresh = time.Now().Add(-time.Second)
    traffic.flowAttribution.mu.Unlock()

    conn.SetDeadline(time.Now().Add(8*time.Second))
    fmt.Fprint(conn, "GET / HTTP/1.1\r\nHost: 192.0.2.1\r\nConnection: close\r\n\r\n")
    body, err := io.ReadAll(conn)
    if err != nil && len(body) == 0 {
        t.Fatal(err)
    }
    if !strings.Contains(string(body), "HTTP/1.1 200 OK") ||
        !strings.Contains(string(body), "Compte") ||
        strings.Contains(string(body), "appareil non identifié") {
        t.Fatalf("normal portal did not recover once attribution returned: %q", body)
    }
}

 
// A newly connected client should not have to submit its account twice
// merely because Android publishes its NAT translation after the initial
// attribution wait. Attribution remains exact even with two clients online.
func TestPortalAttributionRetriesUntilLateExactRule(t *testing.T) {
    traffic := newPortalManager(t)
    pushConfig(t, traffic, accountForTest("1001", "pass", 100000))
    // The dump callback is installed before any forwarding goroutine starts.
    // The translated port is published atomically after DialTCP.
    var translatedPort atomic.Int64
    began := time.Now()
    traffic.flowAttribution.dumpFn = func() (string, error) {
        if time.Since(began) < 1700*time.Millisecond {
            return simulatedClientList(phoneA, phoneB) + "IPv4 Upstream:\nIPv4 Downstream:\n", nil
        }
        return fmt.Sprintf(`IPv4 Upstream:
tcp [02:00:00:00:00:01] 47(ap0) %s:41000 -> 76(testtun0) 192.0.2.2:%d -> 192.0.2.1:80 [00:00:00:00:00:00] 1500 3ms
IPv4 Downstream:
Client Information:
{android.net.ip.IpServer@1={/%s=downstream: 41, /%s=downstream: 41}}`,
            phoneB, translatedPort.Load(), phoneA, phoneB), nil
    }
    stack := linkedStacks(t, traffic)
    conn, err := gonet.DialTCP(stack, tcpip.FullAddress{
        NIC: 1, Addr: tcpip.AddrFrom4([4]byte{192, 0, 2, 1}), Port: 80,
    }, ipv4.ProtocolNumber)
    if err != nil {
        t.Fatal(err)
    }
    defer conn.Close()
    translatedPort.Store(int64(conn.LocalAddr().(*net.TCPAddr).Port))
    _ = conn.SetDeadline(time.Now().Add(10 * time.Second))
    fmt.Fprint(conn, "GET / HTTP/1.1\r\nHost: 192.0.2.1\r\nConnection: close\r\n\r\n")
    data, err := io.ReadAll(conn)
    if err != nil && len(data) == 0 {
        t.Fatal(err)
    }
    if !strings.Contains(string(data), "HTTP/1.1 200 OK") ||
        !strings.Contains(string(data), "Numéro de compte") ||
        strings.Contains(string(data), "identification en cours") {
        t.Fatalf("late exact NAT rule did not recover the original portal request: %q", data)
    }
}
