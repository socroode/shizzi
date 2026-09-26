package datapath

import (
	"testing"
	"time"
)

const sampleTetheringDump = `Tethering:
  Forwarding rules:
    IPv4 Upstream: proto [inDstMac] iif(iface) src -> nat -> dst [outDstMac] pmtu age
      tcp [aa:bb:cc:dd:ee:ff] 12(wlan0) 192.168.43.20:50123 -> 33(testtun0) 192.0.2.2:61001 -> 142.250.74.14:443 [00:00:00:00:00:00] 1500 20ms
      udp [aa:bb:cc:dd:ee:11] 12(wlan0) 192.168.43.21:55000 -> 33(testtun0) 192.0.2.2:62002 -> 1.1.1.1:53 [00:00:00:00:00:00] 1500 5ms
    IPv4 Downstream: proto [inDstMac] iif(iface) src -> nat -> dst [outDstMac] pmtu age
      tcp [00:00:00:00:00:00] 33(testtun0) 142.250.74.14:443 -> 12(wlan0) 192.0.2.2:61001 -> 192.168.43.20:50123 [aa:bb:cc:dd:ee:ff] 1500 20ms
`


const sampleColorOSTetheringDump = `Tethering:
  Forwarding rules:
    IPv4 Upstream: proto [inDstMac] iif iface src -> oif iface nat -> dst
      tcp [aa:bb:cc:dd:ee:ff] wlan0 192.168.101.12:52345 -> testtun9 192.0.2.2:41432 -> 1.1.1.1:80 [00:00:00:00:00:00] 1500
      udp [aa:bb:cc:dd:ee:11] wlan0 192.168.101.13:53001 -> testtun9 192.0.2.2:41433 -> 142.250.74.14:443 [00:00:00:00:00:00] 1500
    IPv4 Downstream:
      tcp [00:00:00:00:00:00] testtun9 1.1.1.1:80 -> wlan0 192.0.2.2:41432 -> 192.168.101.12:52345
`

const sampleColorOSHeaderlessDump = `Forwarding rules:
  tcp [aa:bb:cc:dd:ee:ff] wlan0 192.168.101.20:50123 -> testtun9 192.0.2.2:61001 -> 1.1.1.1:80
  tcp [00:00:00:00:00:00] testtun9 1.1.1.1:80 -> wlan0 192.0.2.2:61001 -> 192.168.101.20:50123
`

func TestParseIPv4UpstreamAttributions(t *testing.T) {
	flows := parseIPv4UpstreamAttributions(sampleTetheringDump)

	tcpKey := flowAttributionKey{
		Protocol:   "tcp",
		PublicIP:   "192.0.2.2",
		PublicPort: 61001,
		DstIP:      "142.250.74.14",
		DstPort:    443,
	}
	if got := flows[tcpKey]; got != "192.168.43.20" {
		t.Fatalf("tcp attribution=%q, want 192.168.43.20", got)
	}

	udpKey := flowAttributionKey{
		Protocol:   "udp",
		PublicIP:   "192.0.2.2",
		PublicPort: 62002,
		DstIP:      "1.1.1.1",
		DstPort:    53,
	}
	if got := flows[udpKey]; got != "192.168.43.21" {
		t.Fatalf("udp attribution=%q, want 192.168.43.21", got)
	}

	if len(flows) != 2 {
		t.Fatalf("flow count=%d, want 2 upstream rules only", len(flows))
	}
}

const sampleLiveReno11TetheringDump = `BPF stats:
  IPv4 Upstream: proto [inDstMac] iif(iface) src -> nat -> dst [outDstMac] pmtu age
    tcp [2e:64:de:1d:42:a2] 47(47) 192.168.7.161:57748 -> 48(testtun0) 192.0.2.2:57748 -> 150.138.210.18:443 [00:00:00:00:00:00] 1500 -
    tcp [2e:64:de:1d:42:a2] 47(47) 192.168.7.252:60252 -> 48(testtun0) 192.0.2.2:60252 -> 1.1.1.1:80 [00:00:00:00:00:00] 1500 15571ms
  IPv4 Downstream:
`

const sampleLiveReno11CombinedDump = `Tethering:
  Forwarding rules:
    IPv4 Upstream: proto [inDstMac] iif(iface) src -> nat -> dst [outDstMac] pmtu age
    IPv4 Downstream: proto [inDstMac] iif(iface) src -> nat -> dst [outDstMac] pmtu age
  Some other tethering diagnostics...
BPF stats:
  IPv4 Upstream: proto [inDstMac] iif(iface) src -> nat -> dst [outDstMac] pmtu age
    tcp [2e:64:de:1d:42:a2] 47(47) 192.168.7.161:57748 -> 48(testtun0) 192.0.2.2:57748 -> 150.138.210.18:443 [00:00:00:00:00:00] 1500 -
    tcp [2e:64:de:1d:42:a2] 47(47) 192.168.7.252:60252 -> 48(testtun0) 192.0.2.2:60252 -> 1.1.1.1:80 [00:00:00:00:00:00] 1500 15571ms
  IPv4 Downstream:
`

func TestParseReno11CombinedDumpSkipsEmptyEarlierSection(t *testing.T) {
	flows := parseIPv4UpstreamAttributions(sampleLiveReno11CombinedDump)
	key := flowAttributionKey{
		Protocol:   "tcp",
		PublicIP:   "192.0.2.2",
		PublicPort: 60252,
		DstIP:      "1.1.1.1",
		DstPort:    80,
	}
	if got := flows[key]; got != "192.168.7.252" {
		t.Fatalf("combined Reno11 bind attribution=%q, want 192.168.7.252", got)
	}
	if len(flows) != 2 {
		t.Fatalf("combined Reno11 flow count=%d, want 2 BPF flows", len(flows))
	}
}

func TestParseLiveReno11BindFlow(t *testing.T) {
	flows := parseIPv4UpstreamAttributions(sampleLiveReno11TetheringDump)
	key := flowAttributionKey{
		Protocol:   "tcp",
		PublicIP:   "192.0.2.2",
		PublicPort: 60252,
		DstIP:      "1.1.1.1",
		DstPort:    80,
	}
	if got := flows[key]; got != "192.168.7.252" {
		t.Fatalf("live Reno11 bind attribution=%q, want 192.168.7.252", got)
	}
	if len(flows) != 2 {
		t.Fatalf("live Reno11 flow count=%d, want 2", len(flows))
	}
}

func TestParseColorOSIPv4UpstreamAttributions(t *testing.T) {
	flows := parseIPv4UpstreamAttributions(sampleColorOSTetheringDump)

	tcpKey := flowAttributionKey{
		Protocol:   "tcp",
		PublicIP:   "192.0.2.2",
		PublicPort: 41432,
		DstIP:      "1.1.1.1",
		DstPort:    80,
	}
	if got := flows[tcpKey]; got != "192.168.101.12" {
		t.Fatalf("ColorOS tcp attribution=%q, want 192.168.101.12", got)
	}

	udpKey := flowAttributionKey{
		Protocol:   "udp",
		PublicIP:   "192.0.2.2",
		PublicPort: 41433,
		DstIP:      "142.250.74.14",
		DstPort:    443,
	}
	if got := flows[udpKey]; got != "192.168.101.13" {
		t.Fatalf("ColorOS udp attribution=%q, want 192.168.101.13", got)
	}
}

func TestParseHeaderlessOEMAttributionRejectsDownstreamDirection(t *testing.T) {
	flows := parseIPv4UpstreamAttributions(sampleColorOSHeaderlessDump)
	key := flowAttributionKey{
		Protocol:   "tcp",
		PublicIP:   "192.0.2.2",
		PublicPort: 61001,
		DstIP:      "1.1.1.1",
		DstPort:    80,
	}
	if got := flows[key]; got != "192.168.101.20" {
		t.Fatalf("headerless attribution=%q, want 192.168.101.20", got)
	}
	if len(flows) != 1 {
		t.Fatalf("headerless flow count=%d, want upstream rule only", len(flows))
	}
}

func TestFlowAttributionResolverSeparatesTwoPhonesBehindSharedTun(t *testing.T) {
	resolver := newFlowAttributionResolver()
	resolver.dumpFn = func() (string, error) {
		return sampleTetheringDump, nil
	}
	resolver.lastRefresh = time.Time{}

	first := resolver.resolve(
		flowAttributionKey{
			Protocol:   "tcp",
			PublicIP:   "192.0.2.2",
			PublicPort: 61001,
			DstIP:      "142.250.74.14",
			DstPort:    443,
		},
		false,
	)
	second := resolver.resolve(
		flowAttributionKey{
			Protocol:   "udp",
			PublicIP:   "192.0.2.2",
			PublicPort: 62002,
			DstIP:      "1.1.1.1",
			DstPort:    53,
		},
		false,
	)

	if first != "192.168.43.20" {
		t.Fatalf("first phone=%q", first)
	}
	if second != "192.168.43.21" {
		t.Fatalf("second phone=%q", second)
	}
	if !resolver.hasMultipleClients() {
		t.Fatal("resolver did not detect multiple physical hotspot clients")
	}
}

func TestAmbiguousSharedAuthorizationFailsClosedWithMultipleClients(t *testing.T) {
	manager := newTrafficManager()
	manager.flowAttribution.dumpFn = func() (string, error) {
		return sampleTetheringDump, nil
	}
	manager.flowAttribution.mu.Lock()
	manager.flowAttribution.refreshLocked()
	manager.flowAttribution.mu.Unlock()

	manager.setPortalConfig(true, `{
	  "accounts": [{
	    "number": "63057303",
	    "pin": "583921",
	    "name": "RONIU",
	    "enabled": true,
	    "dataBalanceBytes": 1000000000
	  }]
	}`)

	ok, message, token := manager.submitPortalAccountLoginWithSession(
		"192.0.2.2",
		"63057303",
		"583921",
	)
	if !ok || token == "" {
		t.Fatalf("shared login failed: %s", message)
	}
	if manager.portalAuthorizedFor("192.0.2.2") {
		t.Fatal("ambiguous shared TUN authorization leaked to multiple clients")
	}

	ok, message, _ = manager.submitPortalAccountLoginWithSession(
		"192.168.43.20",
		"63057303",
		"583921",
	)
	if !ok {
		t.Fatalf("resolved client login failed: %s", message)
	}
	if !manager.portalAuthorizedFor("192.168.43.20") {
		t.Fatal("resolved physical client was not authorized")
	}
}


func TestFlowAttributionWaitsForDelayedRule(t *testing.T) {
	resolver := newFlowAttributionResolver()
	calls := 0
	resolver.dumpFn = func() (string, error) {
		calls++
		if calls == 1 {
			return "Tethering:\n  Forwarding rules:\n    IPv4 Upstream:\n", nil
		}
		return sampleTetheringDump, nil
	}
	resolver.lastRefresh = time.Time{}

	key := flowAttributionKey{
		Protocol:   "tcp",
		PublicIP:   "192.0.2.2",
		PublicPort: 61001,
		DstIP:      "142.250.74.14",
		DstPort:    443,
	}

	got := resolver.resolve(key, true)
	if got != "192.168.43.20" {
		t.Fatalf("delayed attribution=%q, want 192.168.43.20", got)
	}
	if calls < 2 {
		t.Fatalf("dump calls=%d, want at least 2 retries", calls)
	}
}

func TestFlowAttributionStillFailsClosedAfterGraceWindow(t *testing.T) {
	resolver := newFlowAttributionResolver()
	resolver.dumpFn = func() (string, error) {
		return "Tethering:\n  Forwarding rules:\n    IPv4 Upstream:\n", nil
	}
	resolver.lastRefresh = time.Time{}

	start := time.Now()
	got := resolver.resolve(flowAttributionKey{
		Protocol:   "udp",
		PublicIP:   "192.0.2.2",
		PublicPort: 62002,
		DstIP:      "142.250.74.14",
		DstPort:    443,
	}, true)
	if got != "" {
		t.Fatalf("unresolved attribution=%q, want empty fail-closed result", got)
	}
	if time.Since(start) < attributionRuleWait {
		t.Fatalf("resolver failed before grace window elapsed: %s", time.Since(start))
	}
}


func TestPortalBindUsesExtendedAttributionGrace(t *testing.T) {
	if got := flowAttributionGrace("tcp", portalBindAddress, 80, true); got != portalBindAttributionRuleWait {
		t.Fatalf("bind grace=%s, want %s", got, portalBindAttributionRuleWait)
	}
	if got := flowAttributionGrace("tcp", "142.250.195.163", 443, true); got != attributionRuleWait {
		t.Fatalf("ordinary TCP grace=%s, want %s", got, attributionRuleWait)
	}
	if got := flowAttributionGrace("udp", portalBindAddress, 80, true); got != attributionRuleWait {
		t.Fatalf("non-TCP grace=%s, want %s", got, attributionRuleWait)
	}
	if got := flowAttributionGrace("tcp", portalBindAddress, 80, false); got != 0 {
		t.Fatalf("no-wait bind grace=%s, want 0", got)
	}
}

func TestFlowAttributionExtendedGraceKeepsSameBindTupleAlive(t *testing.T) {
	resolver := newFlowAttributionResolver()
	calls := 0
	resolver.dumpFn = func() (string, error) {
		calls++
		if calls < 4 {
			return "BPF stats:\n  IPv4 Upstream:\n  IPv4 Downstream:\n", nil
		}
		return sampleLiveReno11TetheringDump, nil
	}
	resolver.lastRefresh = time.Time{}

	key := flowAttributionKey{
		Protocol:   "tcp",
		PublicIP:   "192.0.2.2",
		PublicPort: 60252,
		DstIP:      "1.1.1.1",
		DstPort:    80,
	}

	got := resolver.resolveWithGrace(key, 1200*time.Millisecond)
	if got != "192.168.7.252" {
		t.Fatalf("extended bind attribution=%q, want 192.168.7.252", got)
	}
	if calls < 4 {
		t.Fatalf("dump calls=%d, want at least 4 delayed refreshes", calls)
	}
}
