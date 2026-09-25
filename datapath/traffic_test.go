package datapath

import (
	"strings"
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


func TestTunnelAddressesStayOutOfClientList(t *testing.T) {
	m := newTrafficManager()

	m.account("192.0.2.2", directionDownload, 100)
	m.account("2001:db8::2", directionUpload, 50)

	var snapshot trafficStatsSnapshot
	if err := json.Unmarshal([]byte(m.statsJSON()), &snapshot); err != nil {
		t.Fatalf("stats json: %v", err)
	}

	if len(snapshot.Clients) != 0 {
		t.Fatalf("internal tunnel addresses must not become clients: %+v", snapshot.Clients)
	}
	if snapshot.SharedDownBytes != 100 || snapshot.SharedUpBytes != 50 {
		t.Fatalf(
			"shared counters wrong: down=%d up=%d",
			snapshot.SharedDownBytes,
			snapshot.SharedUpBytes,
		)
	}
}

func TestSharedPolicyBlocksNatTraffic(t *testing.T) {
	m := newTrafficManager()
	m.setSharedPolicy(ClientPolicy{QuotaBytes: 100})

	if !m.waitAllowed("192.0.2.2", directionDownload, 100) {
		t.Fatal("shared traffic should be allowed up to quota")
	}
	m.account("192.0.2.2", directionDownload, 100)

	if m.waitAllowed("192.0.2.2", directionDownload, 1) {
		t.Fatal("shared NAT traffic should stop after quota")
	}
}


func TestDefaultClientPolicyCanStartBlocked(t *testing.T) {
	m := newTrafficManager()
	m.setDefaultClientPolicy(5_000_000, 2_000_000, 0, true)

	if m.waitAllowed("192.168.1.20", directionDownload, 100) {
		t.Fatal("new client should inherit blocked default policy")
	}

	m.setClientPolicy("192.168.1.20", ClientPolicy{
		DownloadBitsPerSecond: 5_000_000,
		UploadBitsPerSecond:   2_000_000,
		Blocked:               false,
	})
	if !m.waitAllowed("192.168.1.20", directionDownload, 100) {
		t.Fatal("explicit client policy should grant access")
	}
}


func TestCaptivePortalVoucherAuthorizesClient(t *testing.T) {
	m := newTrafficManager()
	m.setPortalConfig(true, `{
		"title":"Test",
		"message":"Login",
		"passes":[{
			"code":"ABCD1234",
			"name":"Guest",
			"downloadUnit":"MBPS",
			"uploadUnit":"MBPS",
			"downloadBps":5000000,
			"uploadBps":2000000,
			"quotaBytes":1000,
			"durationMinutes":60,
			"assignedDeviceId":"",
			"enabled":true
		}]
	}`)

	ip := "192.168.1.21"
	if !m.portalRequiredFor(ip) {
		t.Fatal("client without a pass should be sent to the portal")
	}
	if m.waitAllowed(ip, directionDownload, 10) {
		t.Fatal("client without a pass must not have Internet access")
	}
	if !m.waitAllowedWithPortalBypass(ip, directionDownload, 10, true) {
		t.Fatal("portal bypass traffic such as DNS should remain available")
	}

	ok, _ := m.submitPortalCode(ip, "abcd1234")
	if !ok {
		t.Fatal("valid access code should authorize the client")
	}
	if m.portalRequiredFor(ip) {
		t.Fatal("authorized client should leave captive mode")
	}
	if !m.waitAllowed(ip, directionDownload, 10) {
		t.Fatal("authorized client should have Internet access")
	}
}

func TestCaptivePortalIgnoresLegacyDeviceAssignment(t *testing.T) {
	m := newTrafficManager()
	m.setPortalConfig(true, `{
		"passes":[{
			"code":"USED1234",
			"assignedDeviceId":"aa:bb:cc:dd:ee:ff",
			"enabled":true
		}]
	}`)

	ok, _ := m.submitPortalCode("192.168.1.22", "USED1234")
	if !ok {
		t.Fatal("legacy device assignment must not prevent a prepaid code from moving")
	}
}


func TestCaptivePortalUsagePopup(t *testing.T) {
	m := newTrafficManager()
	m.setPortalConfig(true, `{
		"title":"Test",
		"message":"Login",
		"passes":[{
			"code":"MONTH100",
			"name":"Prepaid 30 days",
			"downloadUnit":"MBPS",
			"uploadUnit":"MBPS",
			"downloadBps":10000000,
			"uploadBps":5000000,
			"quotaBytes":100000000000,
			"durationMinutes":43200,
			"assignedDeviceId":"",
			"enabled":true
		}]
	}`)

	ip := "192.168.1.23"
	ok, _ := m.submitPortalCode(ip, "MONTH100")
	if !ok {
		t.Fatal("valid prepaid voucher should authorize the client")
	}

	page := m.renderPortalPage(ip, "", true, "Access granted")
	if !strings.Contains(page, "Votre consommation") {
		t.Fatal("successful login should show the usage popup")
	}
	if !strings.Contains(page, "100.00 GB") {
		t.Fatal("usage popup should show remaining prepaid data")
	}
	if !strings.Contains(page, "10.00 Mbps / 5.00 Mbps") {
		t.Fatal("usage popup should show the voucher speed")
	}
	if !strings.Contains(page, "192.0.2.1") {
		t.Fatal("usage popup should include the local refresh page")
	}
}


func TestLiveVoucherStatusDashboard(t *testing.T) {
	m := newTrafficManager()
	m.setPortalConfig(true, `{
		"passes":[{
			"code":"LIVE100",
			"name":"Live 100",
			"downloadUnit":"MBPS",
			"uploadUnit":"KBPS",
			"downloadBps":10000000,
			"uploadBps":512000,
			"quotaBytes":100000000000,
			"durationMinutes":43200,
			"assignedDeviceId":"",
			"enabled":true
		}]
	}`)

	ip := "192.168.1.24"
	ok, _ := m.submitPortalCode(ip, "LIVE100")
	if !ok {
		t.Fatal("valid voucher should authorize the client")
	}

	m.account(ip, directionDownload, 25_000_000)
	status := m.portalUsageStatusFor(ip)
	if !status.Authorized {
		t.Fatal("status should report the voucher as authorized")
	}
	if status.UsedBytes < 25_000_000 {
		t.Fatalf("expected live usage to include current traffic, got %d", status.UsedBytes)
	}
	if status.RemainingBytes >= status.QuotaBytes {
		t.Fatal("remaining data should decrease as traffic is accounted")
	}
	if status.Speed != "10.00 Mbps / 512 kbps" {
		t.Fatalf("unexpected mixed-unit speed: %q", status.Speed)
	}

	page := m.renderLiveStatusPage()
	if !strings.Contains(page, "setInterval(refreshNow,2000)") {
		t.Fatal("live dashboard should refresh automatically every 2 seconds")
	}
	if !strings.Contains(page, "/status.json") {
		t.Fatal("live dashboard should fetch local status JSON")
	}
}


func TestExhaustedVoucherRemainsVisibleOnStatusPage(t *testing.T) {
	m := newTrafficManager()
	m.setPortalConfig(true, `{
		"passes":[{
			"code":"LIMIT100",
			"name":"100 MB test",
			"downloadUnit":"MBPS",
			"uploadUnit":"MBPS",
			"downloadBps":1000000,
			"uploadBps":1000000,
			"quotaBytes":100000000,
			"usedBytes":0,
			"durationMinutes":43200,
			"assignedDeviceId":"",
			"enabled":true
		}]
	}`)

	ip := "192.168.1.25"
	ok, _ := m.submitPortalCode(ip, "LIMIT100")
	if !ok {
		t.Fatal("valid voucher should authorize the client")
	}

	m.mu.Lock()
	auth := m.portalAuthorized[ip]
	auth.SessionUsedBytes = 100000000
	m.portalAuthorized[ip] = auth
	m.mu.Unlock()

	if m.portalAuthorizedFor(ip) {
		t.Fatal("quota-exhausted voucher must no longer authorize Internet access")
	}

	status := m.portalUsageStatusFor(ip)
	if status.Code != "LIMIT100" {
		t.Fatalf("status page lost exhausted voucher identity: %+v", status)
	}
	if status.Authorized {
		t.Fatal("exhausted voucher must be shown as inactive")
	}
	if status.PercentUsed != 100 || status.RemainingBytes != 0 {
		t.Fatalf("expected 100%% used and zero remaining: %+v", status)
	}
}
