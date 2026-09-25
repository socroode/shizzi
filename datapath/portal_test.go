package datapath

import (
	"strings"
	"testing"
)

const portablePassConfig = `{
  "passes": [{
    "code": "ECO123",
    "name": "Eco",
    "downloadBps": 2000000,
    "uploadBps": 1000000,
    "quotaBytes": 12000000000,
    "durationMinutes": 43200,
    "usedBytes": 1000000,
    "expiresAtMillis": 0,
    "enabled": true
  }]
}`

func TestPortableVoucherTransfersToNewClient(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, portablePassConfig)

	ok, message := manager.submitPortalCode("192.168.43.10", "eco123")
	if !ok {
		t.Fatalf("first login rejected: %s", message)
	}
	if !manager.portalAuthorizedFor("192.168.43.10") {
		t.Fatal("first client was not authorized")
	}

	ok, message = manager.submitPortalCode("192.168.43.20", "ECO123")
	if !ok {
		t.Fatalf("portable login rejected on second client: %s", message)
	}
	if manager.portalAuthorizedFor("192.168.43.10") {
		t.Fatal("old client stayed authorized after voucher transfer")
	}
	if !manager.portalAuthorizedFor("192.168.43.20") {
		t.Fatal("new client did not receive transferred voucher")
	}
}

func TestPortableVoucherKeepsPersistedQuotaUsage(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, `{
      "passes": [{
        "code": "DATA1",
        "quotaBytes": 1000,
        "usedBytes": 900,
        "durationMinutes": 60,
        "enabled": true
      }]
    }`)

	ok, message := manager.submitPortalCode("192.168.43.2", "DATA1")
	if !ok {
		t.Fatalf("voucher rejected with remaining data: %s", message)
	}
	auth := manager.portalAuthorized["192.168.43.2"]
	if auth.QuotaRemainingBytes != 100 {
		t.Fatalf("remaining quota=%d, want 100", auth.QuotaRemainingBytes)
	}
}

func TestPortableVoucherRejectsExhaustedBalance(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, `{
      "passes": [{
        "code": "EMPTY",
        "quotaBytes": 1000,
        "usedBytes": 1000,
        "durationMinutes": 60,
        "enabled": true
      }]
    }`)

	if ok, _ := manager.submitPortalCode("192.168.43.2", "EMPTY"); ok {
		t.Fatal("exhausted voucher was accepted")
	}
}

func TestPortalAuthorizationSnapshotReportsVoucherSession(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, portablePassConfig)

	if ok, message := manager.submitPortalCode("192.168.43.2", "ECO123"); !ok {
		t.Fatalf("voucher rejected: %s", message)
	}
	manager.account("192.168.43.2", directionDownload, 1234)

	raw := manager.statsJSON()
	for _, expected := range []string{"portalAuthorizations", "ECO123", "1234"} {
		if !strings.Contains(raw, expected) {
			t.Fatalf("authorization snapshot missing %q: %s", expected, raw)
		}
	}
}
