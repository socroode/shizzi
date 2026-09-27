package datapath

import (
	"encoding/json"
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

	first := flowAttributionKey{"tcp", "192.0.2.2", 61001, "142.250.74.14", 443}
	if got := flows[first]; got != "192.168.43.20" {
		t.Fatalf("first=%q", got)
	}

	second := flowAttributionKey{"udp", "192.0.2.2", 62002, "1.1.1.1", 53}
	if got := flows[second]; got != "192.168.43.21" {
		t.Fatalf("second=%q", got)
	}
}

func TestTwoPhonesBehindSharedTunStaySeparate(t *testing.T) {
	manager := newTrafficManager()
	manager.flowAttribution.dumpFn = func() (string, error) {
		return sampleTetheringDump, nil
	}

	first := manager.resolveFlowClient(
		"tcp", "192.0.2.2", 61001, "142.250.74.14", 443,
	)
	second := manager.resolveFlowClient(
		"udp", "192.0.2.2", 62002, "1.1.1.1", 53,
	)

	if first != "192.168.43.20" || second != "192.168.43.21" {
		t.Fatalf("resolved first=%q second=%q", first, second)
	}

	manager.account(first, directionDownload, 1200)
	manager.account(second, directionDownload, 3400)

	var stats trafficStatsSnapshot
	if err := json.Unmarshal([]byte(manager.statsJSON()), &stats); err != nil {
		t.Fatal(err)
	}
	if len(stats.Clients) != 2 {
		t.Fatalf("clients=%d, want 2", len(stats.Clients))
	}
	if stats.Clients[0].IP != "192.168.43.20" || stats.Clients[0].DownBytes != 1200 {
		t.Fatalf("first stats=%+v", stats.Clients[0])
	}
	if stats.Clients[1].IP != "192.168.43.21" || stats.Clients[1].DownBytes != 3400 {
		t.Fatalf("second stats=%+v", stats.Clients[1])
	}
}

func TestAmbiguousTranslatedPortFailsClosed(t *testing.T) {
	raw := `IPv4 Upstream: proto [inDstMac] iif(iface) src -> nat -> dst [outDstMac] pmtu age
 tcp [aa:aa:aa:aa:aa:01] 47(47) 192.168.7.66:50000 -> 76(testtun28) 192.0.2.2:50000 -> 142.250.1.1:443 [00:00:00:00:00:00] 1500 3ms
 tcp [aa:aa:aa:aa:aa:02] 47(47) 192.168.7.162:50000 -> 76(testtun28) 192.0.2.2:50000 -> 157.240.1.1:443 [00:00:00:00:00:00] 1500 3ms
IPv4 Downstream:`

	resolver := newFlowAttributionResolver()
	resolver.dumpFn = func() (string, error) { return raw, nil }

	got := resolver.resolve(
		flowAttributionKey{"tcp", "192.0.2.2", 50000, "203.0.113.10", 443},
		false,
	)
	if got != "" {
		t.Fatalf("ambiguous flow attributed to %q", got)
	}
}

func TestRequiredAttributionRejectsUnknownSharedClient(t *testing.T) {
	manager := newTrafficManager()
	manager.setRequireClientAttribution(true)
	manager.flowAttribution.dumpFn = func() (string, error) {
		return `Tethering:
IPv4 Upstream:
IPv4 Downstream:
Client Information:
{android.net.ip.IpServer@1={/192.168.7.66=downstream: 41, /192.168.7.77=downstream: 41}}`, nil
	}

	start := time.Now()
	got := manager.resolveFlowClient(
		"tcp", "192.0.2.2", 55555, "203.0.113.10", 443,
	)
	if got != "" {
		t.Fatalf("unknown shared client resolved to %q", got)
	}
	if manager.waitAllowed(got, directionUpload, 100) {
		t.Fatal("unresolved required identity was allowed")
	}
	if time.Since(start) > 5*time.Second {
		t.Fatal("attribution exceeded bounded wait")
	}
}
