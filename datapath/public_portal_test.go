package datapath

import (
    "bufio"
    "io"
    "net"
    "net/http"
    "strings"
    "testing"
    "time"

    "gvisor.dev/gvisor/pkg/tcpip"
    "gvisor.dev/gvisor/pkg/tcpip/stack"
)

func TestPublicPortalInterceptsOnlyLocalHTTP(t *testing.T) {
    requestID := stack.TransportEndpointID{
        LocalAddress: tcpip.AddrFrom4([4]byte{192, 0, 2, 1}),
        LocalPort: 80,
    }
    if !isPublicPortalFlow(requestID) { t.Fatal("local HTTP not intercepted") }

    requestID.LocalAddress = tcpip.AddrFrom4([4]byte{8, 8, 8, 8})
    if isPublicPortalFlow(requestID) { t.Fatal("external HTTP bypassed account policy") }
    requestID.LocalAddress = tcpip.AddrFrom4([4]byte{192, 0, 2, 1})
    requestID.LocalPort = 443
    if isPublicPortalFlow(requestID) { t.Fatal("non-HTTP portal port bypassed policy") }
}

func TestPortalOverRawTranslatedTCPBeforeAuthentication(t *testing.T) {
    client, server := net.Pipe()
    defer client.Close()
    done := make(chan struct{})
    go func() {
        servePublicPortalConn(server, "")
        close(done)
    }()

    _ = client.SetDeadline(time.Now().Add(5 * time.Second))
    _, err := io.WriteString(client, "GET / HTTP/1.1\r\nHost: 192.0.2.1\r\nConnection: close\r\n\r\n")
    if err != nil { t.Fatal(err) }
    response, err := http.ReadResponse(bufio.NewReader(client), &http.Request{Method: http.MethodGet})
    if err != nil { t.Fatal(err) }
    defer response.Body.Close()
    body, err := io.ReadAll(response.Body)
    if err != nil { t.Fatal(err) }
    if response.StatusCode != http.StatusOK ||
        !strings.Contains(string(body), "Numéro de compte") {
        t.Fatalf("public portal not readable over TUN: %d %s", response.StatusCode, body)
    }
    <-done
}

func TestPortalDoesNotAcceptPINFromUnverifiedTUNConnection(t *testing.T) {
    client, server := net.Pipe()
    defer client.Close()
    go servePublicPortalConn(server, "")

    form := "account=1001&pin=123456"
    raw := "POST /login HTTP/1.1\r\nHost: 192.0.2.1\r\n" +
        "Content-Type: application/x-www-form-urlencoded\r\n" +
        "Content-Length: 23\r\nConnection: close\r\n\r\n" + form
    _ = client.SetDeadline(time.Now().Add(5 * time.Second))
    if _, err := io.WriteString(client, raw); err != nil { t.Fatal(err) }
    response, err := http.ReadResponse(bufio.NewReader(client), &http.Request{Method: http.MethodPost})
    if err != nil { t.Fatal(err) }
    defer response.Body.Close()
    if response.StatusCode != http.StatusForbidden {
        t.Fatalf("login bypass on unidentified TUN: %d", response.StatusCode)
    }
}
