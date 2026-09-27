package datapath

import (
	"strings"
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
	if ok || token != "" {
		t.Fatalf("ambiguous shared login unexpectedly succeeded: ok=%v token=%q", ok, token)
	}
	if !strings.Contains(message, "identifier cet appareil") {
		t.Fatalf("unexpected shared identification message: %q", message)
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


func TestParseIPv4UpstreamAttributionsAcceptsOEMFormatting(t *testing.T) {
	raw := `Tethering:
  Forwarding rules:
    IPv4 Upstream: proto client translated destination
      TCP vendor-prefix 192.168.43.31:51000 => testtun7 192.0.2.2:62000 => 142.250.74.14:443 age=3ms
    IPv4 Downstream:
`

	flows := parseIPv4UpstreamAttributions(raw)
	key := flowAttributionKey{
		Protocol:   "tcp",
		PublicIP:   "192.0.2.2",
		PublicPort: 62000,
		DstIP:      "142.250.74.14",
		DstPort:    443,
	}
	if got := flows[key]; got != "192.168.43.31" {
		t.Fatalf("OEM attribution=%q, want 192.168.43.31; flows=%v", got, flows)
	}
}

func TestParseReno11ThreeHotspotClients(t *testing.T) {
	raw := `IPv4 Upstream: proto [inDstMac] iif(iface) src -> nat -> dst [outDstMac] pmtu age
 tcp [da:e5:d7:05:3d:c8] 47(47) 192.168.7.66:34776 -> 69(testtun21) 192.0.2.2:34776 -> 157.240.8.13:443 [00:00:00:00:00:00] 1500 142668ms
 tcp [da:e5:d7:05:3d:c8] 47(47) 192.168.7.161:60396 -> 69(testtun21) 192.0.2.2:60396 -> 157.240.8.40:443 [00:00:00:00:00:00] 1500 4975ms
 udp [da:e5:d7:05:3d:c8] 47(47) 192.168.7.162:49254 -> 69(testtun21) 192.0.2.2:49254 -> 142.251.156.6:443 [00:00:00:00:00:00] 1500 7778ms
IPv4 Downstream: proto [inDstMac] iif(iface) src -> nat -> dst [outDstMac] pmtu age`
	resolver := newFlowAttributionResolver()
	resolver.dumpFn = func() (string, error) { return raw, nil }
	for _, test := range []struct {
		key flowAttributionKey
		want string
	}{
		{flowAttributionKey{"tcp", "192.0.2.2", 34776, "157.240.8.13", 443}, "192.168.7.66"},
		{flowAttributionKey{"tcp", "192.0.2.2", 60396, "157.240.8.40", 443}, "192.168.7.161"},
		{flowAttributionKey{"udp", "192.0.2.2", 49254, "142.251.156.6", 443}, "192.168.7.162"},
	} {
		if got := resolver.resolve(test.key, false); got != test.want {
			t.Errorf("resolve(%v)=%q, want %q", test.key, got, test.want)
		}
	}
	if got := resolver.snapshot().ClientCount; got != 3 {
		t.Errorf("mapped clients=%d, want 3", got)
	}
}


func TestFlowAttributionFallsBackToUniqueTranslatedPort(t *testing.T) {
	raw := `IPv4 Upstream: proto [inDstMac] iif(iface) src -> nat -> dst [outDstMac] pmtu age
 tcp [a2:91:de:17:d5:91] 47(47) 192.168.7.162:48442 -> 76(testtun28) 192.0.2.2:48442 -> 142.250.207.23:80 [00:00:00:00:00:00] 1500 3ms
IPv4 Downstream:`

	resolver := newFlowAttributionResolver()
	resolver.dumpFn = func() (string, error) { return raw, nil }

	// Reno11 logs showed Android knew the original client while Shizzi's exact
	// tuple lookup still missed. A unique translated source port must therefore
	// recover the client without authorizing the shared 192.0.2.2 address.
	got := resolver.resolve(
		flowAttributionKey{
			Protocol:   "tcp",
			PublicIP:   "192.0.2.2",
			PublicPort: 48442,
			DstIP:      "192.0.2.1",
			DstPort:    80,
		},
		false,
	)
	if got != "192.168.7.162" {
		t.Fatalf("fallback attribution=%q, want 192.168.7.162", got)
	}
	snapshot := resolver.snapshot()
	if snapshot.ResolvedFlows != 1 || snapshot.FallbackResolvedFlows != 1 {
		t.Fatalf("unexpected counters: %+v", snapshot)
	}
}

func TestFlowAttributionFallbackFailsClosedWhenPortIsAmbiguous(t *testing.T) {
	raw := `IPv4 Upstream: proto [inDstMac] iif(iface) src -> nat -> dst [outDstMac] pmtu age
 tcp [aa:aa:aa:aa:aa:01] 47(47) 192.168.7.66:50000 -> 76(testtun28) 192.0.2.2:50000 -> 142.250.1.1:443 [00:00:00:00:00:00] 1500 3ms
 tcp [aa:aa:aa:aa:aa:02] 47(47) 192.168.7.162:50000 -> 76(testtun28) 192.0.2.2:50000 -> 157.240.1.1:443 [00:00:00:00:00:00] 1500 3ms
IPv4 Downstream:`

	resolver := newFlowAttributionResolver()
	resolver.dumpFn = func() (string, error) { return raw, nil }

	got := resolver.resolve(
		flowAttributionKey{
			Protocol:   "tcp",
			PublicIP:   "192.0.2.2",
			PublicPort: 50000,
			DstIP:      "203.0.113.10",
			DstPort:    443,
		},
		false,
	)
	if got != "" {
		t.Fatalf("ambiguous fallback attributed to %q; want fail-closed", got)
	}
	snapshot := resolver.snapshot()
	if snapshot.UnresolvedFlows != 1 {
		t.Fatalf("unresolved=%d, want 1", snapshot.UnresolvedFlows)
	}
}


func TestParseTetheringConnectedClientsIncludesIdlePhones(t *testing.T) {
	raw := `Tethering:
  Forwarding rules:
    IPv4 Upstream: proto [inDstMac] iif(iface) src -> nat -> dst [outDstMac] pmtu age
      tcp [aa:bb:cc:dd:ee:ff] 41(wlan0) 192.168.161.66:51446 -> 42(testtun0) 192.0.2.2:51446 -> 142.251.151.119:443 [00:00:00:00:00:00] 1500 10ms
  Client Information:
    {android.net.ip.IpServer@1={/192.168.161.66=downstream: 41 (aa:bb:cc:dd:ee:ff), client: /192.168.161.66 (8e:e1:38:c6:a0:35), /192.168.161.162=downstream: 41 (aa:bb:cc:dd:ee:ff), client: /192.168.161.162 (a6:10:bd:03:d4:58)}}
`
	clients := parseTetheringConnectedClients(raw)
	if len(clients) != 2 {
		t.Fatalf("connected clients=%v", clients)
	}
	if _, ok := clients["192.168.161.66"]; !ok {
		t.Fatal("missing .66")
	}
	if _, ok := clients["192.168.161.162"]; !ok {
		t.Fatal("missing .162")
	}
}
