package datapath

import (
	"testing"
	"time"
)

const testPortalConfig = `{
  "passes": [{
    "code": "ECO123",
    "name": "Eco",
    "downloadBps": 2000000,
    "uploadBps": 1000000,
    "quotaBytes": 12000000000,
    "durationMinutes": 43200,
    "assignedDeviceId": "",
    "enabled": true
  }]
}`

func TestPortalVoucherSurvivesPendingDeviceAssignment(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, testPortalConfig)

	ok, message := manager.submitPortalCode("192.0.2.2", "eco123")
	if !ok {
		t.Fatalf("voucher rejected: %s", message)
	}

	// SessionService refreshes the live voucher table before Android may have a
	// physical client identity. That refresh must not invalidate the voucher.
	manager.setPortalConfig(true, testPortalConfig)
	if !manager.portalAuthorizedFor("192.0.2.2") {
		t.Fatal("fresh voucher lost authorization while assignment was pending")
	}
}

func TestPortalVoucherBecomesDurableAfterAssignment(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, testPortalConfig)

	ok, _ := manager.submitPortalCode("192.0.2.2", "ECO123")
	if !ok {
		t.Fatal("voucher rejected")
	}

	assigned := `{
      "passes": [{
        "code": "ECO123",
        "name": "Eco",
        "downloadBps": 2000000,
        "uploadBps": 1000000,
        "quotaBytes": 12000000000,
        "durationMinutes": 43200,
        "assignedDeviceId": "aa:bb:cc:dd:ee:ff",
        "enabled": true
      }]
    }`
	manager.setPortalConfig(true, assigned)

	manager.mu.Lock()
	auth := manager.portalAuthorized["192.0.2.2"]
	auth.ProvisionalUntilMillis = time.Now().Add(-time.Second).UnixMilli()
	manager.portalAuthorized["192.0.2.2"] = auth
	manager.mu.Unlock()

	if !manager.portalAuthorizedFor("192.0.2.2") {
		t.Fatal("persisted voucher should remain authorized after provisional grace")
	}
}

func TestPortalVoucherFailsClosedWhenAssignmentNeverArrives(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, testPortalConfig)

	ok, _ := manager.submitPortalCode("192.0.2.2", "ECO123")
	if !ok {
		t.Fatal("voucher rejected")
	}

	manager.mu.Lock()
	auth := manager.portalAuthorized["192.0.2.2"]
	auth.ProvisionalUntilMillis = time.Now().Add(-time.Second).UnixMilli()
	manager.portalAuthorized["192.0.2.2"] = auth
	manager.mu.Unlock()

	if manager.portalAuthorizedFor("192.0.2.2") {
		t.Fatal("unassigned voucher stayed authorized after provisional grace")
	}
}

func TestDisabledVoucherRevokesLiveAuthorization(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, testPortalConfig)

	ok, _ := manager.submitPortalCode("192.0.2.2", "ECO123")
	if !ok {
		t.Fatal("voucher rejected")
	}

	disabled := `{
      "passes": [{
        "code": "ECO123",
        "name": "Eco",
        "downloadBps": 2000000,
        "uploadBps": 1000000,
        "quotaBytes": 12000000000,
        "durationMinutes": 43200,
        "assignedDeviceId": "",
        "enabled": false
      }]
    }`
	manager.setPortalConfig(true, disabled)

	if manager.portalAuthorizedFor("192.0.2.2") {
		t.Fatal("disabled voucher remained authorized")
	}
}
