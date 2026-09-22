package datapath

import (
	"encoding/json"
	"testing"
)

func TestTrafficManagerQuotaAndStats(t *testing.T) {
	m := newTrafficManager()
	m.setGlobalPolicy(0, 0, 100)
	m.setClientPolicy("192.168.1.2", ClientPolicy{QuotaBytes: 60})

	if !m.waitAllowed("192.168.1.2", directionDownload, 40) {
		t.Fatal("first transfer should be allowed")
	}
	m.account("192.168.1.2", directionDownload, 40)

	if !m.waitAllowed("192.168.1.2", directionUpload, 20) {
		t.Fatal("transfer up to quota should be allowed")
	}
	m.account("192.168.1.2", directionUpload, 20)

	if m.waitAllowed("192.168.1.2", directionDownload, 1) {
		t.Fatal("client should be blocked after reaching quota")
	}

	var snapshot trafficStatsSnapshot
	if err := json.Unmarshal([]byte(m.statsJSON()), &snapshot); err != nil {
		t.Fatalf("stats json: %v", err)
	}
	if snapshot.TotalDownBytes != 40 || snapshot.TotalUpBytes != 20 {
		t.Fatalf("unexpected totals: down=%d up=%d", snapshot.TotalDownBytes, snapshot.TotalUpBytes)
	}
	if len(snapshot.Clients) != 1 || !snapshot.Clients[0].QuotaReached {
		t.Fatalf("client quota state not reflected: %+v", snapshot.Clients)
	}
}

func TestBlockedClientIsDenied(t *testing.T) {
	m := newTrafficManager()
	m.setClientPolicy("192.168.1.3", ClientPolicy{Blocked: true})

	if m.waitAllowed("192.168.1.3", directionDownload, 100) {
		t.Fatal("blocked client should not be allowed")
	}
}

func TestResetStatsKeepsPolicy(t *testing.T) {
	m := newTrafficManager()
	policy := ClientPolicy{DownloadBitsPerSecond: 10_000_000, QuotaBytes: 1000}
	m.setClientPolicy("192.168.1.4", policy)
	m.account("192.168.1.4", directionDownload, 500)

	m.resetStats()

	var snapshot trafficStatsSnapshot
	if err := json.Unmarshal([]byte(m.statsJSON()), &snapshot); err != nil {
		t.Fatalf("stats json: %v", err)
	}
	if snapshot.TotalDownBytes != 0 || len(snapshot.Clients) != 1 {
		t.Fatalf("stats not reset correctly: %+v", snapshot)
	}
	if snapshot.Clients[0].DownloadBitsPerSecond != policy.DownloadBitsPerSecond {
		t.Fatal("reset should not clear policy")
	}
}
