package datapath

import (
	"fmt"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"io"
	"net"
	"sync/atomic"
	"testing"
	"time"
)

func TestForwardingRecoversLateNATWithoutClientReconnect(t *testing.T) {
	for _, protocol := range []string{"tcp", "udp"} {
		t.Run(protocol, func(t *testing.T) {
			t.Parallel()
			m, ips := streamManager(t, 2, false)
			m.setRequireClientAttribution(true)
			var target net.Addr
			payload := []byte("internet-after-login")
			if protocol == "tcp" {
				listener, err := net.Listen("tcp4", "127.0.0.1:0")
				if err != nil {
					t.Fatal(err)
				}
				defer listener.Close()
				target = listener.Addr()
				go func() {
					c, err := listener.Accept()
					if err == nil {
						defer c.Close()
						io.Copy(c, c)
					}
				}()
			} else {
				server, err := net.ListenPacket("udp4", "127.0.0.1:0")
				if err != nil {
					t.Fatal(err)
				}
				defer server.Close()
				target = server.LocalAddr()
				go func() {
					buf := make([]byte, 4096)
					n, a, err := server.ReadFrom(buf)
					if err == nil {
						server.WriteTo(buf[:n], a)
					}
				}()
			}
			host, portString, _ := net.SplitHostPort(target.String())
			var port uint16
			fmt.Sscan(portString, &port)
			started := time.Now()
			m.flowAttribution.dumpFn = func() (string, error) {
				if time.Since(started) < 4*time.Second {
					return "IPv4 Upstream:\nIPv4 Downstream:", nil
				}
				return fmt.Sprintf("IPv4 Upstream:\n %s [aa:bb:cc:dd:ee:ff] 1(ap0) %s:50000 -> 2(testtun1) 192.0.2.2:61001 -> %s:%d\nIPv4 Downstream:", protocol, ips[0], host, port), nil
			}
			client, app := net.Pipe()
			defer app.Close()
			id := stack.TransportEndpointID{LocalAddress: tcpip.AddrFrom4Slice(net.ParseIP(host).To4()), LocalPort: port, RemoteAddress: tcpip.AddrFrom4Slice(net.IPv4(192, 0, 2, 2).To4()), RemotePort: 61001}
			done := make(chan struct{})
			go func() {
				defer close(done)
				if protocol == "tcp" {
					handleTCP(client, id, &net.Dialer{Timeout: time.Second}, m)
				} else {
					handleUDP(client, id, &net.Dialer{Timeout: time.Second}, m)
				}
			}()
			app.SetDeadline(time.Now().Add(7 * time.Second))
			if _, err := app.Write(payload); err != nil {
				t.Fatal(err)
			}
			got := make([]byte, len(payload))
			if _, err := io.ReadFull(app, got); err != nil {
				t.Fatal(err)
			}
			if string(got) != string(payload) {
				t.Fatal("payload changed")
			}
			if !m.flowAllowed(ips[0]) || !m.flowAllowed(ips[1]) {
				t.Fatal("recovery ended account")
			}
			app.Close()
			select {
			case <-done:
			case <-time.After(2 * time.Second):
				t.Fatal("handler failed to release")
			}
			t.Log("same endpoint delivered payload after 4s NAT delay; both sessions retained")
		})
	}
}

func TestForwardingRecoveryCancelsDuringBlockedDump(t *testing.T) {
	m := newTrafficManager()
	entered, release := make(chan struct{}), make(chan struct{})
	var calls atomic.Int32
	m.flowAttribution.dumpFn = func() (string, error) {
		if calls.Add(1) == 1 {
			close(entered)
		}
		<-release
		return "", nil
	}
	done := make(chan string, 1)
	go func() { done <- m.resolveForwardingClient("tcp", "192.0.2.2", 60001, "1.1.1.1", 443, true) }()
	<-entered
	m.freeze()
	select {
	case got := <-done:
		if got != "" {
			t.Fatal(got)
		}
	case <-time.After(250 * time.Millisecond):
		t.Error("shutdown blocked on dumpsys")
	}
	close(release)
}

func TestForwardingRecoveryNeverBorrowsOtherDestination(t *testing.T) {
	m, ips := streamManager(t, 2, false)
	m.flowAttribution.dumpFn = func() (string, error) { return sampleTetheringDump, nil }
	key := flowAttributionKey{"tcp", "192.0.2.2", 61001, "203.0.113.99", 443}
	if got := m.flowAttribution.resolveWithin(key, true, 150*time.Millisecond, m.stopData); got != "" {
		t.Fatalf("borrowed %s", got)
	}
	if !m.flowAllowed(ips[0]) {
		t.Fatal("lookup failure revoked account")
	}
}

func TestForwardingRecoveryIgnoresRecentMissShortcut(t *testing.T) {
	m := newTrafficManager()
	key := flowAttributionKey{"tcp", "192.0.2.2", 61001, "142.250.74.14", 443}
	m.flowAttribution.recentMisses[key] = time.Now()
	began := time.Now()
	m.flowAttribution.dumpFn = func() (string, error) {
		if time.Since(began) < 250*time.Millisecond {
			return "", nil
		}
		return sampleTetheringDump, nil
	}
	if got := m.resolveForwardingClient(key.Protocol, key.PublicIP, key.PublicPort, key.DstIP, key.DstPort, true); got != "192.168.43.20" {
		t.Fatalf("late tuple rejected: %q", got)
	}
}

func TestForwardingRecoveryBoundsPendingFlows(t *testing.T) {
	m := newTrafficManager()
	for i := 0; i < cap(m.pendingAttribution); i++ {
		m.pendingAttribution <- struct{}{}
	}
	m.flowAttribution.dumpFn = func() (string, error) { t.Error("overload queried dumpsys"); return "", nil }
	if got := m.resolveForwardingClient("udp", "192.0.2.2", 1, "1.1.1.1", 443, true); got != "" {
		t.Fatal(got)
	}
}
