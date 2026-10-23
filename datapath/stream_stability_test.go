package datapath

import (
	"bytes"
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
)

func streamManager(t *testing.T, count int, confort bool) (*TrafficManager, []string) {
	t.Helper()
	m := newPortalManager(t)
	t.Cleanup(m.freeze)
	accounts := make([]PortalAccount, count)
	ips, macs := make([]string, count), make([]string, count)
	for i := range accounts {
		ips[i], macs[i] = fmt.Sprintf("192.168.7.%d", 100+i), fmt.Sprintf("02:00:00:03:00:%02x", i+1)
		accounts[i] = accountForTest(fmt.Sprintf("73%02d", i), "pass", 1_000_000)
		accounts[i].UnlimitedUntilMillis = time.Now().Add(time.Hour).UnixMilli()
		accounts[i].UnlimitedDownloadBitsPerSecond, accounts[i].UnlimitedUploadBitsPerSecond = 10_000_000, 5_000_000
		if confort {
			accounts[i].UnlimitedDownloadBitsPerSecond, accounts[i].UnlimitedUploadBitsPerSecond = 4_000_000, 2_000_000
		}
	}
	pushConfig(t, m, accounts...)
	m.setGlobalPolicy(40_000_000, 4_000_000, 0)
	m.flowAttribution.setPresenceJSON(videoPresence(ips, macs, -1))
	for i, ip := range ips {
		if ok, msg := m.submitPortalAccountLogin(ip, accounts[i].Number, "pass"); !ok {
			t.Fatal(msg)
		}
	}
	return m, ips
}

func waitPacingQueue(t *testing.T, limiter *bandwidthLimiter, count int) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for {
		limiter.mu.Lock()
		queued := len(limiter.waiters)
		limiter.mu.Unlock()
		if queued >= count {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("queued=%d, want >=%d", queued, count)
		}
		time.Sleep(time.Millisecond)
	}
}

func TestStreamCancelledTransfersDoNotDelayExistingOrNewFlow(t *testing.T) {
	for _, dir := range []direction{directionDownload, directionUpload} {
		t.Run(fmt.Sprint(dir), func(t *testing.T) {
			m, ips := streamManager(t, 1, true)
			scope, _ := m.captureFlowScope(ips[0], false, "")
			initial := m.reserveTransferForFlow(ips[0], dir, 125000, false, scope)
			if !initial.pace() {
				t.Fatal("initial transfer refused")
			}
			initial.settle(125000)
			const count = 24
			scopes := make([]*flowScope, count)
			results := make(chan bool, count)
			for i := range scopes {
				scopes[i], _ = m.captureFlowScope(ips[0], false, "")
				r := m.reserveTransferForFlow(ips[0], dir, 32768, false, scopes[i])
				go func() { ok := r.pace(); r.settle(0); results <- ok }()
				defer scopes[i].stopTransfers()
			}
			waitPacingQueue(t, initial.clientLimiter, count)
			survivorScope, _ := m.captureFlowScope(ips[0], false, "")
			survivor := m.reserveTransferForFlow(ips[0], dir, 4096, false, survivorScope)
			survivorResult := make(chan bool, 1)
			go func() { survivorResult <- survivor.pace() }()
			waitPacingQueue(t, initial.clientLimiter, count+1)
			began := time.Now()
			for _, s := range scopes {
				s.stopTransfers()
			}
			for range scopes {
				if <-results {
					t.Fatal("cancelled waiting transfer admitted")
				}
			}
			select {
			case ok := <-survivorResult:
				if !ok {
					t.Fatal("existing valid flow refused")
				}
			case <-time.After(250 * time.Millisecond):
				t.Fatal("cancelled queue delayed existing flow")
			}
			survivor.settle(4096)
			freshScope, _ := m.captureFlowScope(ips[0], false, "")
			fresh := m.reserveTransferForFlow(ips[0], dir, 4096, false, freshScope)
			if !fresh.pace() {
				t.Fatal("new flow refused")
			}
			fresh.settle(4096)
			elapsed := time.Since(began)
			if elapsed > 300*time.Millisecond {
				t.Fatalf("cancelled requests left debt: %s", elapsed)
			}
			stats := statsOf(t, m)
			if stats.SessionEnds != 0 || !m.flowAllowed(ips[0]) {
				t.Fatal("cancellation ended account")
			}
			if stats.Continuity.PacingCancelled != count {
				t.Fatalf("cancelled diagnostics=%d", stats.Continuity.PacingCancelled)
			}
			t.Logf("24 cancelled waits; existing and new 4KiB transfers completed in %s; session ends=0", elapsed)
		})
	}
}

func TestStreamSlowAccountDoesNotReserveRouterBandwidth(t *testing.T) {
	m, ips := streamManager(t, 2, true)
	scope, _ := m.captureFlowScope(ips[0], false, "")
	initial := m.reserveTransferForFlow(ips[0], directionDownload, 125000, false, scope)
	if !initial.pace() {
		t.Fatal("initial transfer refused")
	}
	initial.settle(125000)
	const count = 24
	var wg sync.WaitGroup
	for range count {
		scope, _ := m.captureFlowScope(ips[0], false, "")
		r := m.reserveTransferForFlow(ips[0], directionDownload, 32768, false, scope)
		wg.Add(1)
		go func() { defer wg.Done(); r.pace(); r.settle(0) }()
		defer scope.stopTransfers()
	}
	waitPacingQueue(t, initial.clientLimiter, count)
	otherScope, _ := m.captureFlowScope(ips[1], false, "")
	other := m.reserveTransferForFlow(ips[1], directionDownload, 4096, false, otherScope)
	began := time.Now()
	if !other.pace() {
		t.Fatal("second account refused")
	}
	other.settle(4096)
	if delay := time.Since(began); delay > 100*time.Millisecond {
		t.Fatalf("slow account blocked router: %s", delay)
	}
	m.freeze()
	wg.Wait()
}

func streamTCPPair(t *testing.T) (*net.TCPConn, *net.TCPConn) {
	t.Helper()
	ln, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	a, err := net.DialTCP("tcp4", nil, ln.Addr().(*net.TCPAddr))
	if err != nil {
		t.Fatal(err)
	}
	b, err := ln.AcceptTCP()
	if err != nil {
		a.Close()
		t.Fatal(err)
	}
	t.Cleanup(func() { a.Close(); b.Close() })
	return a, b
}

func TestStreamTCPHalfCloseDrainsBothDirections(t *testing.T) {
	for _, side := range []string{"client-fin", "origin-fin"} {
		t.Run(side, func(t *testing.T) {
			m, ips := streamManager(t, 1, true)
			reader, client := streamTCPPair(t)
			upstream, origin := streamTCPPair(t)
			scope, _ := m.captureFlowScope(ips[0], false, "")
			release, ok := m.trackScopedFlow(ips[0], scope, client, upstream, false)
			if !ok {
				t.Fatal("flow refused")
			}
			done := make(chan struct{})
			go func() { defer close(done); defer release(); relay(client, upstream, ips[0], m, false, scope) }()
			t.Cleanup(func() { scope.stopTransfers(); client.Close(); upstream.Close(); <-done })
			reader.SetDeadline(time.Now().Add(5 * time.Second))
			origin.SetDeadline(time.Now().Add(5 * time.Second))
			if side == "origin-fin" {
				reader, origin = origin, reader
			}
			if _, err := reader.Write([]byte("REQ")); err != nil {
				t.Fatal(err)
			}
			if err := reader.CloseWrite(); err != nil {
				t.Fatal(err)
			}
			got, err := io.ReadAll(origin)
			if err != nil || string(got) != "REQ" {
				t.Fatalf("request=%q err=%v", got, err)
			}
			payload := bytes.Repeat([]byte("continuous-video"), 32768)
			sent := make(chan error, 1)
			go func() {
				_, err := origin.Write(payload)
				if err == nil {
					err = origin.CloseWrite()
				}
				sent <- err
			}()
			got, err = io.ReadAll(reader)
			if err != nil || !bytes.Equal(got, payload) {
				t.Fatalf("response=%d/%d err=%v", len(got), len(payload), err)
			}
			if err := <-sent; err != nil {
				t.Fatal(err)
			}
			select {
			case <-done:
			case <-time.After(time.Second):
				t.Fatal("half-closed relay did not drain")
			}
			stats := statsOf(t, m)
			if stats.SessionEnds != 0 || !m.flowAllowed(ips[0]) {
				t.Fatal("FIN ended account")
			}
			if len(stats.Continuity.Recent) != 2 {
				t.Fatal("missing direction diagnostics")
			}
			for _, event := range stats.Continuity.Recent {
				if event.Reason != "read-eof" {
					t.Fatalf("normal FIN reported as %q", event.Reason)
				}
			}
			t.Logf("%s: all %d response bytes delivered under 4/2 and 40/4; account authorized", side, len(got))
		})
	}
}

func TestStreamGVisorHalfCloseKeepsResponse(t *testing.T) {
	m, ips := streamManager(t, 2, true)
	// An external NIC must not receive 127/8 packets. Bind a local unicast
	// host address so this exercises production martian filtering unchanged.
	addresses, err := net.InterfaceAddrs()
	if err != nil {
		t.Fatal(err)
	}
	var hostIPv4 net.IP
	for _, address := range addresses {
		if network, ok := address.(*net.IPNet); ok {
			if ip := network.IP.To4(); ip != nil && !ip.IsLoopback() && ip.IsGlobalUnicast() {
				hostIPv4 = ip
				break
			}
		}
	}
	if hostIPv4 == nil {
		t.Fatal("gVisor integration test requires a local unicast IPv4 interface")
	}
	ln, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: hostIPv4})
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	var mu sync.Mutex
	rule := ""
	m.flowAttribution.dumpFn = func() (string, error) {
		mu.Lock()
		defer mu.Unlock()
		return "IPv4 Upstream:\n" + rule + "\nIPv4 Downstream:\n", nil
	}
	payload := bytes.Repeat([]byte("gvisor-video"), 32768)
	sent := make(chan error, 1)
	go func() {
		c, err := ln.AcceptTCP()
		if err != nil {
			sent <- err
			return
		}
		defer c.Close()
		c.SetDeadline(time.Now().Add(8 * time.Second))
		request, err := io.ReadAll(c)
		if err == nil && string(request) != "REQ" {
			err = fmt.Errorf("request=%q", request)
		}
		if err == nil {
			_, err = c.Write(payload)
		}
		if err == nil {
			err = c.CloseWrite()
		}
		sent <- err
	}()
	clientStack := linkedStacks(t, m)
	port := uint16(ln.Addr().(*net.TCPAddr).Port)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, err := gonet.DialContextTCP(ctx, clientStack, tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{hostIPv4[0], hostIPv4[1], hostIPv4[2], hostIPv4[3]}), Port: port}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	mu.Lock()
	rule = fmt.Sprintf(" tcp [02:00:00:03:00:01] 47(ap0) %s:41000 -> 76(testtun0) 192.0.2.2:%d -> %s:%d [00:00:00:00:00:00] 1500 3ms", ips[0], conn.LocalAddr().(*net.TCPAddr).Port, hostIPv4.String(), port)
	mu.Unlock()
	conn.SetDeadline(time.Now().Add(8 * time.Second))
	if _, err = conn.Write([]byte("REQ")); err != nil {
		t.Fatal(err)
	}
	if err = conn.CloseWrite(); err != nil {
		t.Fatal(err)
	}
	got, err := io.ReadAll(conn)
	if err != nil || !bytes.Equal(got, payload) {
		t.Fatalf("gVisor response=%d/%d err=%v", len(got), len(payload), err)
	}
	if err := <-sent; err != nil {
		t.Fatal(err)
	}
	if !m.flowAllowed(ips[0]) {
		t.Fatal("gVisor FIN ended account")
	}
	t.Logf("production gVisor forwarder, exact simulated NAT, CloseWrite: delivered %d response bytes", len(got))
}

func TestStreamHalfClosedConnectionStillHonorsLogout(t *testing.T) {
	m, ips := streamManager(t, 1, true)
	reader, client := streamTCPPair(t)
	upstream, origin := streamTCPPair(t)
	scope, _ := m.captureFlowScope(ips[0], false, "")
	release, ok := m.trackScopedFlow(ips[0], scope, client, upstream, false)
	if !ok {
		t.Fatal("flow refused")
	}
	done := make(chan struct{})
	go func() { defer close(done); defer release(); relay(client, upstream, ips[0], m, false, scope) }()
	t.Cleanup(func() { scope.stopTransfers(); client.Close(); upstream.Close(); <-done })
	reader.CloseWrite()
	origin.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := io.ReadAll(origin); err != nil {
		t.Fatal(err)
	}
	if ok, _ := m.submitPortalLogout(ips[0]); !ok {
		t.Fatal("logout failed")
	}
	select {
	case <-done:
	case <-time.After(500 * time.Millisecond):
		t.Fatal("half-close prevented revocation")
	}
	snapshot := statsOf(t, m)
	found := false
	for _, e := range snapshot.Continuity.Recent {
		if e.Reason == "authorization-ended" {
			found = true
		}
	}
	if !found {
		t.Fatal("logout reason missing from flow diagnostics")
	}
}

type streamSample struct {
	ip       string
	protocol string
	dir      direction
	bytes    int64
	maxGap   time.Duration
	err      error
}

func streamTransfer(sender, receiver net.Conn, payload []byte, datagram bool) error {
	sender.SetWriteDeadline(time.Now().Add(6 * time.Second))
	receiver.SetReadDeadline(time.Now().Add(6 * time.Second))
	sent := make(chan error, 1)
	go func() { _, err := sender.Write(payload); sent <- err }()
	got := make([]byte, len(payload))
	var err error
	if datagram {
		var n int
		n, err = receiver.Read(got)
		if err == nil && n != len(payload) {
			err = io.ErrUnexpectedEOF
		}
	} else {
		_, err = io.ReadFull(receiver, got)
	}
	if err != nil {
		return err
	}
	if err = <-sent; err != nil {
		return err
	}
	if !bytes.Equal(got, payload) {
		return fmt.Errorf("payload corrupted or datagram sequence lost")
	}
	return nil
}

// These are socket-level simulations, not Android/radio emulators or tests of
// third-party apps. Every client has concurrent bidirectional TCP and UDP.
func TestStreamContinuityConfortMatrix(t *testing.T) {
	if testing.Short() {
		t.Skip("prolonged shaped streams")
	}
	t.Run("oneway_udp_past_60s", func(t *testing.T) {
		t.Parallel()
		streamOneWayUDPConfort(t)
	})
	for _, count := range []int{1, 2, 6, 8} {
		t.Run(fmt.Sprintf("%d_clients", count), func(t *testing.T) {
			t.Parallel()
			m, ips := streamManager(t, count, true)
			began := time.Now()
			duration := 12 * time.Second
			if count == 8 {
				duration = 70 * time.Second
			}
			until := began.Add(duration)
			results := make(chan streamSample, 4*count)
			for _, ip := range ips {
				for _, datagram := range []bool{false, true} {
					v := startVideoRelay(t, m, ip, datagram)
					for _, dir := range []direction{directionUpload, directionDownload} {
						go func(ip string, datagram bool, dir direction, v videoRelay) {
							sample := streamSample{ip: ip, dir: dir, protocol: "tcp"}
							size := 16 * 1024
							if datagram {
								size = 1200
								sample.protocol = "udp"
							}
							payload := bytes.Repeat([]byte{byte(dir) + 1}, size)
							last := time.Now()
							sender, receiver := v.reader, v.writer
							if dir == directionDownload {
								sender, receiver = v.writer, v.reader
							}
							for sequence := uint64(0); time.Now().Before(until); sequence++ {
								binary.BigEndian.PutUint64(payload, sequence)
								if err := streamTransfer(sender, receiver, payload, datagram); err != nil {
									sample.err = err
									break
								}
								now := time.Now()
								sample.maxGap = max(sample.maxGap, now.Sub(last))
								last = now
								sample.bytes += int64(size)
							}
							results <- sample
						}(ip, datagram, dir, v)
					}
				}
			}
			all := make([]streamSample, 0, 4*count)
			ticker := time.NewTicker(time.Second)
			defer ticker.Stop()
			timeout := time.NewTimer(duration + 20*time.Second)
			defer timeout.Stop()
			for len(all) < 4*count {
				select {
				case sample := <-results:
					if sample.err != nil || sample.bytes == 0 {
						t.Fatalf("%s %s dir=%d bytes=%d err=%v", sample.ip, sample.protocol, sample.dir, sample.bytes, sample.err)
					}
					if sample.maxGap > 3*time.Second {
						t.Fatalf("unbounded stream pause %s: %s", sample.ip, sample.maxGap)
					}
					all = append(all, sample)
				case <-ticker.C:
					if ok, msg := m.submitPortalAccountLogin(ips[0], "7300", "pass"); !ok {
						t.Fatal(msg)
					}
					stats := statsOf(t, m)
					if stats.SessionEnds != 0 || len(stats.PortalAuthorizations) != count {
						t.Fatal("shaping or repeated login ended a valid account")
					}
				case <-timeout.C:
					t.Fatal("continuity matrix exceeded deadline")
				}
			}
			elapsed := time.Since(began)
			var up, down int64
			maxGap := time.Duration(0)
			for _, sample := range all {
				if sample.dir == directionUpload {
					up += sample.bytes
				} else {
					down += sample.bytes
				}
				maxGap = max(maxGap, sample.maxGap)
			}
			allowed := func(bps int64) int64 {
				return int64(float64(bps)/8*(elapsed.Seconds()+defaultBurstWindow.Seconds())) + 32768
			}
			if down > allowed(40_000_000) || up > allowed(4_000_000) {
				t.Fatalf("router ceiling exceeded: up=%d down=%d duration=%s", up, down, elapsed)
			}
			for _, ip := range ips {
				var clientUp, clientDown int64
				for _, sample := range all {
					if sample.ip == ip {
						if sample.dir == directionUpload {
							clientUp += sample.bytes
						} else {
							clientDown += sample.bytes
						}
					}
				}
				if clientDown > allowed(4_000_000) || clientUp > allowed(2_000_000) {
					t.Fatalf("Confort ceiling exceeded by %s", ip)
				}
				if !m.flowAllowed(ip) {
					t.Fatalf("valid account %s ended", ip)
				}
			}
			deadline := time.Now().Add(time.Second)
			for {
				stats := statsOf(t, m)
				if stats.TotalUpBytes == up && stats.TotalDownBytes == down {
					break
				}
				if time.Now().After(deadline) {
					t.Fatal("delivered bytes not settled exactly")
				}
				time.Sleep(time.Millisecond)
			}
			stats := statsOf(t, m)
			t.Logf("%d accounts; %d bidirectional TCP/UDP flows; duration=%s up=%d down=%d max inter-block gap=%s session ends=%d; payloads intact and ceilings respected", count, 2*count, elapsed, up, down, maxGap, stats.SessionEnds)
		})
	}
}

func TestStreamGlobalCeilingsUnderConcurrentAccounts(t *testing.T) {
	for _, dir := range []direction{directionDownload, directionUpload} {
		t.Run(fmt.Sprint(dir), func(t *testing.T) {
			m, ips := streamManager(t, 8, false)
			began := time.Now()
			results := make(chan error, len(ips))
			const bytesPerClient = 256 * 1024
			for _, ip := range ips {
				v := startVideoRelay(t, m, ip, false)
				go func() {
					sender, receiver := v.reader, v.writer
					if dir == directionDownload {
						sender, receiver = v.writer, v.reader
					}
					payload := bytes.Repeat([]byte("rate"), 4096)
					for n := 0; n < bytesPerClient/len(payload); n++ {
						if err := streamTransfer(sender, receiver, payload, false); err != nil {
							results <- err
							return
						}
					}
					results <- nil
				}()
			}
			for range ips {
				if err := <-results; err != nil {
					t.Fatal(err)
				}
			}
			elapsed := time.Since(began)
			bps := int64(40_000_000)
			if dir == directionUpload {
				bps = 4_000_000
			}
			delivered := int64(bytesPerClient * len(ips))
			ceiling := float64(bps)/8*(elapsed.Seconds()+defaultBurstWindow.Seconds()) + 32768
			if float64(delivered) > ceiling {
				t.Fatalf("aggregate %d bit/s ceiling exceeded: %d bytes in %s", bps, delivered, elapsed)
			}
			if statsOf(t, m).SessionEnds != 0 {
				t.Fatal("saturation ended an account")
			}
			t.Logf("8 accounts offered > global cap; dir=%d bytes=%d duration=%s cap=%d bit/s with 250ms burst", dir, delivered, elapsed, bps)
		})
	}
}

func TestStreamCancelledUnlimitedWaitNeverAdmits(t *testing.T) {
	cancel := make(chan struct{})
	close(cancel)
	if newBandwidthLimiter(0).waitCancelledUntil(1200, nil, nil, cancel) {
		t.Fatal("already cancelled unlimited flow admitted")
	}
}

func TestStreamDiagnosticHistoryIsBoundedAndCopied(t *testing.T) {
	m, ips := streamManager(t, 1, true)
	for i := 0; i < 40; i++ {
		m.noteFlowEnd("udp", ips[0], directionDownload, flowCopyResult{"udp-idle", int64(i)}, nil)
	}
	first := statsOf(t, m)
	if len(first.Continuity.Recent) != 32 || first.Continuity.Recent[0].Bytes != 8 {
		t.Fatal("flow history not bounded")
	}
	if !strings.Contains(m.statsJSON(), "\"udp-idle\"") {
		t.Fatal("reason missing from exported diagnostics")
	}
	m.noteFlowEnd("tcp", ips[0], directionUpload, flowCopyResult{"read-eof", 999}, nil)
	if first.Continuity.Recent[0].Bytes != 8 {
		t.Fatal("snapshot aliased mutable history")
	}
}

func TestStreamGlobalCancellationWakesAnotherAccount(t *testing.T) {
	m, ips := streamManager(t, 9, false)
	// Consume the real router burst, leaving account buckets untouched.
	m.globalUploadLimiter.wait(125000)
	const count = 8
	scopes := make([]*flowScope, count)
	results := make(chan bool, count)
	for i := range scopes {
		scopes[i], _ = m.captureFlowScope(ips[i], false, "")
		r := m.reserveTransferForFlow(ips[i], directionUpload, 32768, false, scopes[i])
		go func() { ok := r.pace(); r.settle(0); results <- ok }()
		defer scopes[i].stopTransfers()
	}
	waitPacingQueue(t, m.globalUploadLimiter, count)
	survivorScope, _ := m.captureFlowScope(ips[count], false, "")
	survivor := m.reserveTransferForFlow(ips[count], directionUpload, 4096, false, survivorScope)
	survivorResult := make(chan bool, 1)
	go func() { survivorResult <- survivor.pace() }()
	waitPacingQueue(t, m.globalUploadLimiter, count+1)
	began := time.Now()
	for _, scope := range scopes { scope.stopTransfers() }
	for range scopes { if <-results { t.Fatal("cancelled router request admitted") } }
	select {
	case ok := <-survivorResult:
		if !ok { t.Fatal("other valid account refused") }
	case <-time.After(200*time.Millisecond):
		t.Fatal("cancelled router queue delayed another account")
	}
	survivor.settle(4096)
	if statsOf(t,m).SessionEnds != 0 { t.Fatal("router cancellation ended account") }
	t.Logf("8 router waits cancelled; another account admitted in %s without session loss", time.Since(began))
}

func TestStreamCancellationDoesNotRefillSpentCredit(t *testing.T) {
	l := newBandwidthLimiter(8_000_000)
	if !l.waitCancelled(250000,nil,nil) { t.Fatal("initial transfer refused") }
	cancel := make(chan struct{})
	result := make(chan bool,1)
	go func() { result <- l.waitCancelledUntil(125000,nil,nil,cancel) }()
	waitPacingQueue(t,l,1)
	close(cancel)
	if <-result { t.Fatal("cancelled transfer admitted") }
	began:=time.Now()
	if !l.waitCancelled(125000,nil,nil) { t.Fatal("next transfer refused") }
	if elapsed:=time.Since(began);elapsed<100*time.Millisecond { t.Fatalf("cancellation refilled spent bandwidth: %s",elapsed) }
}

func streamOneWayUDPConfort(t *testing.T) {
	m, ips := streamManager(t, 1, true)
	v := startVideoRelay(t, m, ips[0], true)
	began := time.Now()
	until := began.Add(65 * time.Second)
	payload := bytes.Repeat([]byte("one-way-video"), 90)
	var delivered int64
	var maxGap time.Duration
	last := time.Now()
	for time.Now().Before(until) {
		if err := streamTransfer(v.writer, v.reader, payload, true); err != nil {
			t.Fatal(err)
		}
		now := time.Now()
		maxGap = max(maxGap, now.Sub(last))
		last = now
		delivered += int64(len(payload))
	}
	if maxGap > 3 * time.Second {
		t.Fatalf("one-way UDP stalled: %s", maxGap)
	}
	stats := statsOf(t, m)
	if stats.SessionEnds != 0 || stats.TotalUpBytes != 0 || !m.flowAllowed(ips[0]) {
		t.Fatal("silent uplink ended or polluted active downlink")
	}
	t.Logf("one-way shaped UDP active beyond 60s idle timeout: duration=%s down=%d max-gap=%s account authorized", time.Since(began), delivered, maxGap)
}
