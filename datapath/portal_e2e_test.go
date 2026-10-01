package datapath

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

// linkedStacks builds a "hotspot side" stack (addressed 192.0.2.2, like the
// NAT-ed traffic Android puts on the TUN) wired to a datapath stack running
// Shizzi's forwarders.
func linkedStacks(t *testing.T, traffic *TrafficManager) *stack.Stack {
	t.Helper()
	newStack := func() *stack.Stack {
		s := stack.New(stack.Options{
			NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
			TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
		})
		t.Cleanup(s.Close)
		return s
	}
	client, server := newStack(), newStack()
	clientLink := channel.New(256, 1500, "")
	serverLink := channel.New(256, 1500, "")
	if err := client.CreateNIC(1, clientLink); err != nil {
		t.Fatal(err)
	}
	if err := server.CreateNIC(1, serverLink); err != nil {
		t.Fatal(err)
	}
	client.AddProtocolAddress(1, tcpip.ProtocolAddress{
		Protocol:          ipv4.ProtocolNumber,
		AddressWithPrefix: tcpip.AddrFrom4([4]byte{192, 0, 2, 2}).WithPrefix(),
	}, stack.AddressProperties{})
	for _, s := range []*stack.Stack{client, server} {
		s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}})
	}
	server.SetPromiscuousMode(1, true)
	server.SetSpoofing(1, true)
	installForwarders(server, &net.Dialer{Timeout: time.Second}, traffic)

	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	pump := func(from, to *channel.Endpoint) {
		for {
			packet := from.ReadContext(ctx)
			if packet == nil {
				return
			}
			buffer := packet.ToBuffer()
			packet.DecRef()
			inbound := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer})
			to.InjectInbound(ipv4.ProtocolNumber, inbound)
			inbound.DecRef()
		}
	}
	go pump(clientLink, serverLink)
	go pump(serverLink, clientLink)
	return client
}

// The rule only appears once the TCP connection exists (as with Android's
// BPF offload); the portal must still be served to the right phone.
func TestPortalServedWhenRuleAppearsAfterHandshake(t *testing.T) {
	traffic := newTrafficManager()
	var mu sync.Mutex
	dump := "IPv4 Upstream:\nIPv4 Downstream:\n{/192.168.7.66=downstream: 41, /192.168.7.77=downstream: 41}"
	traffic.flowAttribution.dumpFn = func() (string, error) {
		mu.Lock()
		defer mu.Unlock()
		return dump, nil
	}
	pushConfig(t, traffic, accountForTest("1001", "aaaa", 1_000_000))
	traffic.setRequireClientAttribution(true)

	client := linkedStacks(t, traffic)
	target := tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{203, 0, 113, 10}), Port: 80}
	conn, err := gonet.DialTCP(client, target, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatalf("handshake did not complete before attribution: %v", err)
	}
	defer conn.Close()

	port := conn.LocalAddr().(*net.TCPAddr).Port
	mu.Lock()
	dump = fmt.Sprintf(`IPv4 Upstream:
 tcp [aa:aa:aa:aa:aa:01] 47(wlan0) 192.168.7.66:41000 -> 76(testtun0) 192.0.2.2:%d -> 203.0.113.10:80 [00:00:00:00:00:00] 1500 3ms
IPv4 Downstream:
{/192.168.7.66=downstream: 41, /192.168.7.77=downstream: 41}`, port)
	mu.Unlock()

	conn.SetDeadline(time.Now().Add(5 * time.Second))
	fmt.Fprint(conn, "GET /generate_204 HTTP/1.1\r\nHost: connectivitycheck.gstatic.com\r\n\r\n")
	response, err := io.ReadAll(bufio.NewReader(conn))
	if err != nil && len(response) == 0 {
		t.Fatalf("read: %v", err)
	}
	if !strings.Contains(string(response), "Numéro de compte") {
		t.Fatalf("portal not served: %q", string(response))
	}
}

// An unresolved UDP flow must not freeze the netstack for everyone else.
func TestUnresolvedUDPDoesNotStallOtherFlows(t *testing.T) {
	traffic := newTrafficManager()
	traffic.flowAttribution.dumpFn = func() (string, error) {
		return "IPv4 Upstream:\nIPv4 Downstream:\n{/192.168.7.66=downstream: 41, /192.168.7.77=downstream: 41}", nil
	}
	pushConfig(t, traffic, accountForTest("1001", "aaaa", 1_000_000))
	traffic.setRequireClientAttribution(true)
	client := linkedStacks(t, traffic)

	for i := 0; i < 5; i++ {
		udpConn, err := gonet.DialUDP(client, nil, &tcpip.FullAddress{
			NIC: 1, Addr: tcpip.AddrFrom4([4]byte{203, 0, 113, 20}), Port: uint16(443 + i),
		}, ipv4.ProtocolNumber)
		if err != nil {
			t.Fatal(err)
		}
		udpConn.Write([]byte("quic"))
		defer udpConn.Close()
	}

	started := time.Now()
	conn, err := gonet.DialTCP(client, tcpip.FullAddress{
		NIC: 1, Addr: tcpip.AddrFrom4([4]byte{203, 0, 113, 10}), Port: 443,
	}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	conn.Close()
	if elapsed := time.Since(started); elapsed > time.Second {
		t.Fatalf("TCP handshake stalled %v behind unresolved UDP", elapsed)
	}
}


func TestAuthenticatedMediaRequestReachesLoopbackBackend(t *testing.T) {
	backend, err := net.Listen("tcp", mediaBridgeAddress)
	if err != nil {
		t.Fatalf("listen Media backend: %v", err)
	}
	t.Cleanup(func() { _ = backend.Close() })

	backendBody := "<html><body>Shizzi Media E2E</body></html>"
	backendDone := make(chan struct{})
	go func() {
		defer close(backendDone)
		conn, acceptErr := backend.Accept()
		if acceptErr != nil {
			return
		}
		defer conn.Close()

		reader := bufio.NewReader(conn)
		for {
			line, readErr := reader.ReadString('\n')
			if readErr != nil {
				return
			}
			if line == "\r\n" {
				break
			}
		}

		_, _ = fmt.Fprintf(
			conn,
			"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s",
			len(backendBody),
			backendBody,
		)
	}()

	manager := newTrafficManager()
	manager.setPortalConfig(true, portalConfigForTest(t))
	clientIP := "192.168.7.66"
	ok, message := manager.submitPortalAccountLogin(clientIP, "1001", "1234")
	if !ok {
		t.Fatalf("login failed: %s", message)
	}

	server, client := net.Pipe()
	responseDone := make(chan struct{})
	go func() {
		defer close(responseDone)
		manager.servePortal(server, clientIP)
	}()

	_ = client.SetDeadline(time.Now().Add(5 * time.Second))
	_, err = fmt.Fprint(
		client,
		"GET /media/ HTTP/1.1\r\nHost: 192.0.2.1\r\nConnection: close\r\n\r\n",
	)
	if err != nil {
		t.Fatalf("write Media request: %v", err)
	}

	response, readErr := io.ReadAll(client)
	_ = client.Close()
	if readErr != nil {
		t.Fatalf("read Media response: %v", readErr)
	}
	<-responseDone
	<-backendDone

	if !strings.Contains(string(response), "200 OK") {
		t.Fatalf("Media response was not 200: %q", string(response))
	}
	if !strings.Contains(string(response), "Shizzi Media E2E") {
		t.Fatalf("Media backend body was not proxied: %q", string(response))
	}

	stats := manager.statsJSON()
	for _, expected := range []string{
		`"path":"/media/"`,
		`"accountAuthenticated":true`,
		`"backendConnected":true`,
		`"result":"proxied"`,
	} {
		if !strings.Contains(stats, expected) {
			t.Fatalf("Media diagnostic missing %s: %s", expected, stats)
		}
	}
}
