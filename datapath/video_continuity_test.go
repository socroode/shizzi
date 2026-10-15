package datapath

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func videoPresence(ips, macs []string, omitLease int) string {
	leases := []AssociatedClient{}
	for i, ip := range ips {
		if i != omitLease {
			leases = append(leases, AssociatedClient{ip, macs[i]})
		}
	}
	raw, _ := json.Marshal(map[string]any{"androidTimezone": "Pacific/Tahiti", "wifiKnown": true, "leasesKnown": true, "associatedMacs": macs, "leases": leases})
	return string(raw)
}

func videoManager(t *testing.T) *TrafficManager {
	t.Helper()
	m := newPortalManager(t)
	pushConfig(t, m, accountForTest("1001", "pass", 1_000_000), accountForTest("1002", "other", 1_000_000))
	m.flowAttribution.setPresenceJSON(videoPresence([]string{phoneA, phoneB}, []string{"02:00:00:00:00:01", "02:00:00:00:00:02"}, -1))
	if ok, msg := m.submitPortalAccountLogin(phoneA, "1001", "pass"); !ok {
		t.Fatal(msg)
	}
	t.Cleanup(m.freeze)
	return m
}

type videoUDPConn struct {
	*net.UDPConn
	peer *net.UDPAddr
}

func (c *videoUDPConn) Write(data []byte) (int, error) { return c.WriteToUDP(data, c.peer) }
func videoUDPpair(t *testing.T) (net.Conn, net.Conn) {
	t.Helper()
	a, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	b, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		a.Close()
		t.Fatal(err)
	}
	return &videoUDPConn{a, b.LocalAddr().(*net.UDPAddr)}, &videoUDPConn{b, a.LocalAddr().(*net.UDPAddr)}
}

type videoRelay struct {
	reader, writer net.Conn
	scope          *flowScope
	done           <-chan struct{}
}

func startVideoRelay(t *testing.T, m *TrafficManager, ip string, datagram bool) videoRelay {
	t.Helper()
	scope, ok := m.captureFlowScope(ip, false, "")
	if !ok {
		t.Fatal("valid initial flow refused")
	}
	var client, reader, upstream, writer net.Conn
	if datagram {
		client, reader = videoUDPpair(t)
		upstream, writer = videoUDPpair(t)
	} else {
		client, reader = net.Pipe()
		upstream, writer = net.Pipe()
	}
	release, ok := m.trackScopedFlow(ip, scope, client, upstream, false)
	if !ok {
		t.Fatal("valid flow not tracked")
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		defer release()
		if datagram {
			relayDatagrams(client, upstream, ip, m, false, scope)
		} else {
			relay(client, upstream, ip, m, false, scope)
		}
	}()
	t.Cleanup(func() {
		scope.stopTransfers()
		for _, c := range []net.Conn{client, reader, upstream, writer} {
			c.Close()
		}
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Error("relay did not stop")
		}
	})
	return videoRelay{reader: reader, writer: writer, scope: scope, done: done}
}

func transferVideoBlock(v videoRelay, data []byte, datagram bool) error {
	v.reader.SetReadDeadline(time.Now().Add(2 * time.Second))
	v.writer.SetWriteDeadline(time.Now().Add(2 * time.Second))
	sent := make(chan error, 1)
	go func() { _, err := v.writer.Write(data); sent <- err }()
	got := make([]byte, len(data))
	var err error
	if datagram {
		var n int
		n, err = v.reader.Read(got)
		if err == nil && n != len(got) {
			err = io.ErrUnexpectedEOF
		}
	} else {
		_, err = io.ReadFull(v.reader, got)
	}
	if err != nil {
		return err
	}
	if err = <-sent; err != nil {
		return err
	}
	if !bytes.Equal(got, data) {
		return fmt.Errorf("video block changed")
	}
	return nil
}

func TestVideoCurrentTupleSurvivesOldContradictoryLogs(t *testing.T) {
	zone, _ := time.LoadLocation("Pacific/Tahiti")
	for _, proto := range []string{"tcp", "udp"} {
		t.Run(proto, func(t *testing.T) {
			r := newFlowAttributionResolver()
			r.setPresenceJSON(videoPresence([]string{phoneB, phoneC}, []string{"02:00:00:00:00:02", "02:00:00:00:00:03"}, -1))
			key := flowAttributionKey{proto, "192.0.2.2", 51000, "203.0.113.10", 443}
			logLine := func(ip string) string {
				s := bpfFailureLine(time.Now().Add(-2*time.Second).In(zone), ip, "192.0.2.2", "41000", "51000", "203.0.113.10")
				if proto == "udp" {
					s = strings.Replace(s, "l4proto: 6", "l4proto: 17", 1)
				}
				return s
			}
			rule := fmt.Sprintf("IPv4 Upstream:\n%s [02:00:00:00:00:02] 41(ap0) %s:41000 -> 42(testtun0) 192.0.2.2:51000 -> 203.0.113.10:443 [00:00:00:00:00:00] 1500 3ms\nIPv4 Downstream:\n", proto, phoneB)
			raw := rule + logLine(phoneA)
			r.dumpFn = func() (string, error) { return raw, nil }
			if got := r.resolve(key, false); got != phoneB {
				t.Fatalf("current client refused by old log: %q", got)
			}
			raw = rule + logLine(phoneA) + "\n" + logLine(phoneB)
			r.refreshIfOlderThan(0)
			if got := r.resolve(key, false); got != phoneB {
				t.Fatalf("departed owner made current owner ambiguous: %q", got)
			}
			if r.snapshot().BPFDiscardedAbsentOwners != 1 {
				t.Fatal("discard reason not reported")
			}
			// With both owners currently present the contradiction remains real.
			r.setPresenceJSON(videoPresence([]string{phoneA, phoneB, phoneC}, []string{"02:00:00:00:00:01", "02:00:00:00:00:02", "02:00:00:00:00:03"}, -1))
			raw = rule + logLine(phoneA)
			r.refreshIfOlderThan(0)
			if got := r.resolve(key, false); got != "" {
				t.Fatalf("real conflict billed to %q", got)
			}
			if r.snapshot().LastUnresolvedReason != "nat-log-conflict" {
				t.Fatal("conflict reason missing")
			}
		})
	}
}

func TestVideoOldLogCannotAdmitDepartedAccount(t *testing.T) {
	m := videoManager(t)
	r := m.flowAttribution
	r.setPresenceJSON(videoPresence([]string{phoneB, phoneC}, []string{"02:00:00:00:00:02", "02:00:00:00:00:03"}, -1))
	m.mu.Lock()
	m.pruneDepartedLocked(r.presence(), time.Now())
	stillInGrace := m.portalAuthorized[phoneA] != nil
	m.mu.Unlock()
	if !stillInGrace {
		t.Fatal("test must retain the prior session in departure grace")
	}
	zone, _ := time.LoadLocation("Pacific/Tahiti")
	raw := bpfFailureLine(time.Now().Add(-2*time.Second).In(zone), phoneA, "192.0.2.2", "41000", "51000", "203.0.113.10")
	r.dumpFn = func() (string, error) { return raw, nil }
	if got := m.resolveFlowClient("tcp", "192.0.2.2", 51000, "203.0.113.10", 443, false); got != "" {
		t.Fatalf("old account admitted for an absent owner: %q", got)
	}
}

func TestVideoUnknownMACCannotRevokeOrCutRelay(t *testing.T) {
	for _, mode := range []string{"missing-list", "count-mismatch", "failed-dump"} {
		t.Run(mode, func(t *testing.T) {
			m := videoManager(t)
			v := startVideoRelay(t, m, phoneA, false)
			r := m.flowAttribution
			if err := transferVideoBlock(v, []byte("before"), false); err != nil {
				t.Fatal(err)
			}
			r.setPresenceJSON(`{"wifiKnown":false,"leasesKnown":false}`)
			raw := "IPv4 Downstream:\n" + phoneA + ":40000 [02:00:00:00:00:02]\n"
			if mode == "count-mismatch" {
				raw = simulatedClientList(phoneA) + raw
				r.wifiDumpFn = func() (string, error) { return runningSoftApDump(2), nil }
				r.refreshWifiPresence()
			}
			r.dumpFn = func() (string, error) { return raw, nil }
			r.refreshIfOlderThan(0)
			if mode == "failed-dump" {
				r.dumpFn = func() (string, error) { return "", errors.New("temporary state failure") }
				r.refreshIfOlderThan(0)
			}
			if r.presence().authoritative {
				t.Fatal("fixture must be uncertain")
			}
			if !m.flowAllowed(phoneA) {
				t.Fatal("uncertain MAC revoked the account")
			}
			if err := transferVideoBlock(v, []byte("after uncertain Android state"), false); err != nil {
				t.Fatal(err)
			}
			if statsOf(t, m).SessionEnds != 0 {
				t.Fatal("uncertain observation ended a session")
			}
		})
	}
}

func TestVideoLeaseGapKeepsStillAssociatedSession(t *testing.T) {
	m := videoManager(t)
	v := startVideoRelay(t, m, phoneA, false)
	raw := videoPresence([]string{phoneA, phoneB}, []string{"02:00:00:00:00:01", "02:00:00:00:00:02"}, 0)
	s := &Session{traffic: m}
	s.SetClientPresenceJSON(raw)
	p := m.flowAttribution.presence()
	m.mu.Lock()
	m.pruneDepartedLocked(p, time.Now().Add(time.Minute))
	same := m.portalAuthorized[phoneA] == v.scope.authorization
	m.mu.Unlock()
	if !same {
		t.Fatal("missing lease evicted a still-associated station")
	}
	if err := transferVideoBlock(v, []byte("live after missing lease"), false); err != nil {
		t.Fatal(err)
	}
	snapshot := statsOf(t, m)
	if snapshot.PortalAuthorizations[0].Presence != "online" || snapshot.Attribution.CallbackMappingComplete {
		t.Fatal("association and incomplete mapping not distinguished")
	}
	// An actually absent MAC still receives the normal departure grace.
	s.SetClientPresenceJSON(`{"wifiKnown":true,"leasesKnown":true,"associatedMacs":[],"leases":[]}`)
	m.mu.Lock()
	m.pruneDepartedLocked(m.flowAttribution.presence(), time.Now().Add(departedClientGrace+time.Second))
	gone := m.portalAuthorized[phoneA] == nil
	m.mu.Unlock()
	if !gone {
		t.Fatal("confirmed departure retained a ghost account")
	}
	if statsOf(t, m).LastSessionEnd.Reason != "confirmed-departure" {
		t.Fatal("departure reason missing")
	}
}

func TestVideoSameAssociationLoginPreservesStreamsAndClaims(t *testing.T) {
	m := videoManager(t)
	v := startVideoRelay(t, m, phoneA, false)
	old := v.scope.authorization
	m.submitPortalRecharge(phoneA, "KEEP-PENDING")
	if ok, msg := m.submitPortalAccountLogin(phoneA, "1001", "pass"); !ok {
		t.Fatal(msg)
	}
	m.mu.Lock()
	same := m.portalAuthorized[phoneA] == old
	claims := len(m.portalRechargeClaims)
	m.mu.Unlock()
	if !same || claims != 1 {
		t.Fatal("same-device login rotated the session or discarded voucher")
	}
	if err := transferVideoBlock(v, []byte("continued live after login"), false); err != nil {
		t.Fatal(err)
	}
	m.submitPortalAccountLogin(phoneB, "1001", "pass")
	v.reader.SetReadDeadline(time.Now().Add(time.Second))
	_, err := v.reader.Read(make([]byte, 1))
	if !errors.Is(err, io.EOF) {
		t.Fatalf("another device did not revoke the old TCP flow: %v", err)
	}
	if got := statsOf(t, m).LastSessionEnd.Reason; got != "account-moved-to-another-device" {
		t.Fatalf("takeover reason %q", got)
	}
}

func TestVideoPendingReservationsWaitAndResume(t *testing.T) {
	for _, datagram := range []bool{false, true} {
		t.Run(fmt.Sprint("UDP=", datagram), func(t *testing.T) {
			m := videoManager(t)
			a := accountForTest("1001", "pass", 10)
			pushConfig(t, m, a)
			v := startVideoRelay(t, m, phoneA, datagram)
			held := m.reserveTransfer(phoneA, directionDownload, 10, false)
			if held == nil {
				t.Fatal("fixture reservation failed")
			}
			result := make(chan error, 1)
			go func() { result <- transferVideoBlock(v, []byte("0123456789"), datagram) }()
			deadline := time.Now().Add(time.Second)
			for {
				m.mu.Lock()
				waiting := m.quotaChanged != nil
				m.mu.Unlock()
				if waiting {
					break
				}
				if time.Now().After(deadline) {
					held.settle(0)
					t.Fatal("relay did not apply backpressure")
				}
				time.Sleep(time.Millisecond)
			}
			select {
			case err := <-result:
				held.settle(0)
				t.Fatalf("relay ended while quota was only reserved: %v", err)
			default:
			}
			held.settle(0)
			if err := <-result; err != nil {
				t.Fatal("relay failed to resume:", err)
			}
			deadline = time.Now().Add(time.Second)
			for {
				m.mu.Lock()
				used, pending := int64(0), m.accountPending["1001"]
				if usage := m.accountUsage["1001"]; usage != nil {
					used = usage.DataBytes
				}
				m.mu.Unlock()
				if used == 10 && pending == 0 {
					break
				}
				if time.Now().After(deadline) {
					t.Fatalf("incorrect accounting used=%d pending=%d", used, pending)
				}
				time.Sleep(time.Millisecond)
			}
		})
	}
}

func TestVideoPendingReservationCannotAdoptAnotherAccount(t *testing.T) {
	m := videoManager(t)
	pushConfig(t, m, accountForTest("1001", "pass", 10), accountForTest("1002", "other", 100))
	v := startVideoRelay(t, m, phoneA, false)
	held := m.reserveTransfer(phoneA, directionDownload, 10, false)
	result := make(chan error, 1)
	go func() { result <- transferVideoBlock(v, []byte("0123456789"), false) }()
	deadline := time.Now().Add(time.Second)
	for {
		m.mu.Lock()
		waiting := m.quotaChanged != nil
		m.mu.Unlock()
		if waiting {
			break
		}
		if time.Now().After(deadline) {
			held.settle(0)
			t.Fatal("quota wait not reached")
		}
		time.Sleep(time.Millisecond)
	}
	m.submitPortalAccountLogin(phoneA, "1002", "other")
	if err := <-result; err == nil {
		held.settle(0)
		t.Fatal("old stream survived account replacement")
	}
	held.settle(0)
	m.mu.Lock()
	otherUsage := m.accountUsage["1002"]
	pending := m.globalPending
	m.mu.Unlock()
	if otherUsage != nil || pending != 0 {
		t.Fatal("old stream charged or reserved the replacement account")
	}
}

func TestVideoAttributionWaitIsBoundedAndShared(t *testing.T) {
	r := newFlowAttributionResolver()
	started, release := make(chan struct{}), make(chan struct{})
	var calls atomic.Int32
	r.dumpFn = func() (string, error) { calls.Add(1); close(started); <-release; return sampleTetheringDump, nil }
	var once sync.Once
	finish := func() { once.Do(func() { close(release) }) }
	t.Cleanup(finish)
	var wg sync.WaitGroup
	begin := time.Now()
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); r.refreshBefore(0, begin.Add(80*time.Millisecond)) }()
	}
	<-started
	wg.Wait()
	if elapsed := time.Since(begin); elapsed > 500*time.Millisecond {
		t.Fatalf("flow admission queued behind dump: %s", elapsed)
	}
	if calls.Load() != 1 {
		t.Fatal("concurrent flow admission duplicated the OS query")
	}
	r.mu.Lock()
	done := r.refreshDone
	r.mu.Unlock()
	finish()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("shared refresh did not finish")
	}
	if got := r.resolve(flowAttributionKey{"tcp", "192.0.2.2", 61001, "142.250.74.14", 443}, false); got != "192.168.43.20" {
		t.Fatalf("late metadata was not reusable: %q", got)
	}
}

func TestVideoEightSessionsContinuousTCPAndUDP(t *testing.T) {
	if testing.Short() {
		t.Skip("prolonged streaming regression")
	}
	m := newPortalManager(t)
	t.Cleanup(m.freeze)
	ips, macs := make([]string, 8), make([]string, 8)
	accounts := make([]PortalAccount, 8)
	for i := range ips {
		ips[i] = fmt.Sprintf("192.168.7.%d", 100+i)
		macs[i] = fmt.Sprintf("02:00:00:00:01:%02x", i+1)
		accounts[i] = accountForTest(fmt.Sprintf("50%02d", i), "pass", 1_000_000)
		accounts[i].UnlimitedUntilMillis = time.Now().Add(time.Hour).UnixMilli()
		accounts[i].UnlimitedDownloadBitsPerSecond = 0
		accounts[i].UnlimitedUploadBitsPerSecond = 0
	}
	full := videoPresence(ips, macs, -1)
	partial := videoPresence(ips, macs, 0)
	m.flowAttribution.setPresenceJSON(full)
	pushConfig(t, m, accounts...)
	m.flowAttribution.dumpFn = func() (string, error) { return "", errors.New("temporary Android NAT dump failure") }
	m.flowAttribution.wifiDumpFn = func() (string, error) { return "", errors.New("temporary Wi-Fi dump failure") }
	workerStop := make(chan struct{})
	workerDone := make(chan struct{})
	go func() { defer close(workerDone); (&Session{traffic: m}).maintainClientPresence(workerStop) }()
	t.Cleanup(func() {
		close(workerStop)
		select {
		case <-workerDone:
		case <-time.After(3 * time.Second):
			t.Error("presence worker did not stop")
		}
	})
	results := make(chan error, 8)
	began := time.Now()
	for i, ip := range ips {
		if ok, msg := m.submitPortalAccountLogin(ip, accounts[i].Number, "pass"); !ok {
			t.Fatal(msg)
		}
		datagram := i%2 == 1
		v := startVideoRelay(t, m, ip, datagram)
		go func() {
			payload := make([]byte, 1024)
			for n := 0; n < 1400; n++ {
				binary.BigEndian.PutUint32(payload, uint32(n))
				if err := transferVideoBlock(v, payload, datagram); err != nil {
					results <- fmt.Errorf("client %d frame %d: %w", i, n, err)
					return
				}
				time.Sleep(50 * time.Millisecond)
			}
			results <- nil
		}()
	}
	ticker := time.NewTicker(250 * time.Millisecond)
	defer ticker.Stop()
	deadline := time.NewTimer(100 * time.Second)
	defer deadline.Stop()
	completed, changes := 0, 0
	for completed < 8 {
		select {
		case err := <-results:
			if err != nil {
				t.Fatal(err)
			}
			completed++
		case <-ticker.C:
			changes++
			if changes%2 == 0 {
				(&Session{traffic: m}).SetClientPresenceJSON(full)
				if ok, msg := m.submitPortalAccountLogin(ips[0], accounts[0].Number, "pass"); !ok {
					t.Fatal(msg)
				}
			} else {
				(&Session{traffic: m}).SetClientPresenceJSON(partial)
			}
		case <-deadline.C:
			t.Fatal("streaming regression exceeded its deadline")
		}
	}
	snapshot := statsOf(t, m)
	if snapshot.SessionEnds != 0 || len(snapshot.PortalAuthorizations) != 8 {
		t.Fatal("streaming perturbations ended an account")
	}
	t.Logf("8 accounts: 4 TCP + 4 one-way UDP; 1400 x 1024 bytes per account; duration=%s; NAT failures=%d; session ends=%d", time.Since(began), snapshot.Attribution.DumpFailures, snapshot.SessionEnds)
}

func TestVideoOldCachedMACIsOnlyDisplayed(t *testing.T) {
	m := videoManager(t)
	v := startVideoRelay(t, m, phoneA, false)
	r := m.flowAttribution
	r.setPresenceJSON(`{"wifiKnown":false,"leasesKnown":false}`)
	r.dumpFn = func() (string, error) { return "IPv4 Downstream:\n" + phoneA + ":40000 [02:00:00:00:00:02]\n", nil }
	r.refreshIfOlderThan(0)
	// The next complete client list contains no current MAC observation.
	r.dumpFn = func() (string, error) { return simulatedClientList(phoneA), nil }
	r.refreshIfOlderThan(0)
	if !r.presence().authoritative {
		t.Fatal("fixture needs a current client list")
	}
	if !m.flowAllowed(phoneA) {
		t.Fatal("a historical MAC was reused as a new identity proof")
	}
	if err := transferVideoBlock(v, []byte("still valid after cached MAC"), false); err != nil {
		t.Fatal(err)
	}
}

func TestVideoPeerClosureCancelsQuotaAndPacing(t *testing.T) {
	for _, pendingQuota := range []bool{true, false} {
		t.Run(fmt.Sprint("quota=", pendingQuota), func(t *testing.T) {
			m := videoManager(t)
			var held *transferReservation
			if pendingQuota {
				pushConfig(t, m, accountForTest("1001", "pass", 10))
			} else {
				m.globalDownloadLimiter.setRate(1)
			}
			v := startVideoRelay(t, m, phoneA, false)
			if pendingQuota {
				held = m.reserveTransfer(phoneA, directionDownload, 10, false)
				if held == nil {
					t.Fatal("fixture failed")
				}
				defer held.settle(0)
			}
			writeDone := make(chan error, 1)
			go func() { _, err := v.writer.Write([]byte("0123456789")); writeDone <- err }()
			deadline := time.Now().Add(time.Second)
			for {
				m.mu.Lock()
				waiting := m.quotaChanged != nil
				if !pendingQuota {
					waiting = m.globalPending > 0
				}
				m.mu.Unlock()
				if waiting {
					break
				}
				if time.Now().After(deadline) {
					t.Fatal("relay did not wait")
				}
				time.Sleep(time.Millisecond)
			}
			v.reader.Close()
			select {
			case <-v.done:
			case <-time.After(time.Second):
				t.Fatal("closed video left a pending relay")
			}
			if err := <-writeDone; err != nil {
				t.Fatal(err)
			}
		})
	}
}
