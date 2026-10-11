package datapath

import (
    "fmt"
    "strings"
    "testing"
    "time"
)

// Real-world OPPO Reno9 dumpsys tethering layout from a failed map write.
// Its reverse entry is also present in a full dump, but must be ignored.
func bpfFailureLine(at time.Time, client, publicIP, sourcePort, translatedPort, destIP string) string {
    return fmt.Sprintf("%s - [api31.BpfCoordinatorShimImpl] ERROR Could not insert entry (iif: 41, dstMac: de:a5:9e:b6:5c:83, l4proto: 6, src4: /%s, dst4: /%s, srcPort: %s, dstPort: 443, oif: 42, ethDstMac: 00:00:00:00:00:00, ethSrcMac: 00:00:00:00:00:00, ethProto: 2048, pmtu: 1500, src46: /%s, dst46: /%s, srcPort: %s, dstPort: 443, lastUsed: 0): android.system.ErrnoException: nativeWriteToMapEntry failed: E2BIG (Argument list too long)",
        at.Format("2006-01-02T15:04:05.999999"), client, destIP, sourcePort, publicIP, destIP, translatedPort)
}

func TestFreshExactBPFFailureRecoversOnlyCorrectTablet(t *testing.T) {
    now := time.Now()
    log := bpfFailureLine(now.Add(-2*time.Second), "192.168.192.161", "192.0.2.2", "41850", "41850", "49.7.252.7")
    raw := "IPv4 Upstream:\nIPv4 Downstream:\nClient Information:\n" +
        "{android.net.ip.IpServer@1={/192.168.192.161=downstream: 41, /192.168.192.137=downstream: 41}}\n" +
        log + "\n"

    flows, count := parseRecentBPFFailedAttributions(raw, now)
    key := flowAttributionKey{"tcp", "192.0.2.2", 41850, "49.7.252.7", 443}
    if count != 1 || flows[key] != "192.168.192.161" {
        t.Fatalf("failed to recover exact tuple (count %d): %+v", count, flows)
    }

    m := newTrafficManager()
    m.flowAttribution.dumpFn = func() (string, error) { return raw, nil }
    got := m.resolveFlowClient("tcp", "192.0.2.2", 41850, "49.7.252.7", 443, true)
    if got != "192.168.192.161" {
        t.Fatalf("client %s, want tablet .161", got)
    }
    if got := m.resolveFlowClient("tcp", "192.0.2.2", 41850, "49.7.252.25", 443, false); got != "" {
        t.Fatalf("destination-only mismatch was attributed to %s", got)
    }
    if got := m.resolveFlowClient("tcp", "192.0.2.2", 41850, "49.7.252.7", 80, false); got != "" {
        t.Fatalf("destination-port mismatch was attributed to %s", got)
    }
    m.flowAttribution.mu.Lock()
    m.flowAttribution.lastRefresh = time.Now().Add(-time.Minute)
    m.flowAttribution.mu.Unlock()
}

func TestOldOrMalformedBPFFailureCannotAuthorizeClient(t *testing.T) {
    now := time.Now()
    fresh := bpfFailureLine(now, "192.168.192.161", "192.0.2.2", "49932", "49932", "49.7.252.25")
    stale := bpfFailureLine(now.Add(-time.Minute), "192.168.192.137", "192.0.2.2", "41850", "41850", "49.7.252.7")
    reverse := strings.Replace(fresh, "src46: /192.0.2.2", "src46: /49.7.252.25", 1)
    noError := strings.Replace(fresh, "E2BIG", "EPERM", 1)
    flows, count := parseRecentBPFFailedAttributions(strings.Join([]string{stale, reverse, noError}, "\n"), now)
    if count != 0 || len(flows) != 0 {
        t.Fatalf("stale/reverse/unrelated error should not authorize anything, got %d %+v", count, flows)
    }
    flows, count = parseRecentBPFFailedAttributions(fresh, now)
    if count != 1 || len(flows) != 1 {
        t.Fatalf("fresh failure missing: count=%d flows=%v", count, flows)
    }
}

func TestConflictingBPFClientsFailClosed(t *testing.T) {
    now := time.Now()
    a := bpfFailureLine(now, "192.168.192.161", "192.0.2.2", "41850", "41850", "49.7.252.7")
    b := bpfFailureLine(now, "192.168.192.137", "192.0.2.2", "41850", "41850", "49.7.252.7")
    raw := "IPv4 Upstream:\n" + a + "\n" + b + "\n"
    flows, count := parseRecentBPFFailedAttributions(raw, now)
    key := flowAttributionKey{"tcp", "192.0.2.2", 41850, "49.7.252.7", 443}
    if count != 2 {
        t.Fatalf("wanted two witnessed failures, got %d", count)
    }
    if value, ok := flows[key]; !ok || value != "" {
        t.Fatalf("conflicting owners must be marked ambiguous, got %q/%v", value, ok)
    }
    m := newTrafficManager()
    m.flowAttribution.dumpFn = func() (string, error) { return raw, nil }
    if got := m.resolveFlowClient("tcp", "192.0.2.2", 41850, "49.7.252.7", 443, false); got != "" {
        t.Fatalf("conflicting client got unauthorized identity %s", got)
    }
}

func TestBPFFailureDoesNotOverrideConflictingExistingNAT(t *testing.T) {
    now := time.Now()
    bpf := bpfFailureLine(now, "192.168.192.161", "192.0.2.2", "41850", "41850", "49.7.252.7")
    nat := "tcp [aa:aa:aa:aa:aa:01] 41(ap0) 192.168.192.137:41850 -> 42(testtun0) 192.0.2.2:41850 -> 49.7.252.7:443 [00:00:00:00:00:00] 1500 12ms"
    raw := "IPv4 Upstream:\n" + nat + "\nIPv4 Downstream:\n" + bpf
    m := newTrafficManager()
    m.flowAttribution.dumpFn = func() (string, error) { return raw, nil }
    if got := m.resolveFlowClient("tcp", "192.0.2.2", 41850, "49.7.252.7", 443, false); got != "" {
        t.Fatalf("conflicting NAT/BPF sources should never map to a user: %s", got)
    }
}
