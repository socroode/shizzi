package datapath

import (
	"fmt"
	"strings"
	"testing"
	"time"
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


func TestPrepaidAccountCanLoginEmptyAndRechargeData(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, `{
	  "accounts": [{
	    "number": "47286153",
	    "pin": "583921",
	    "name": "Client",
	    "enabled": true
	  }],
	  "passes": [{
	    "code": "DATA1",
	    "name": "Data 1 GB",
	    "quotaBytes": 1000000000,
	    "durationMinutes": 43200,
	    "downloadBps": 4000000,
	    "uploadBps": 2000000,
	    "enabled": true
	  }]
	}`)

	ok, message := manager.submitPortalAccountLogin("192.168.43.10", "4728 6153", "583921")
	if !ok {
		t.Fatalf("empty account login rejected: %s", message)
	}
	if manager.portalAuthorizedFor("192.168.43.10") {
		t.Fatal("empty account unexpectedly received Internet access")
	}

	ok, message = manager.submitPortalRecharge("192.168.43.10", "DATA1")
	if !ok {
		t.Fatalf("data recharge rejected: %s", message)
	}
	if !manager.portalAuthorizedFor("192.168.43.10") {
		t.Fatal("recharged account did not receive Internet access")
	}
	account := manager.portalAccounts["47286153"]
	if account.DataBalanceBytes != 1000000000 {
		t.Fatalf("data balance=%d, want 1000000000", account.DataBalanceBytes)
	}
	if manager.portalPasses["DATA1"].RedeemedAccountNumber != "47286153" {
		t.Fatal("coupon was not marked as redeemed to the account")
	}
	if len(manager.portalRechargeClaims) != 1 {
		t.Fatalf("recharge claims=%d, want 1", len(manager.portalRechargeClaims))
	}
}

func TestDataRechargeWaitsUntilUnlimitedEnds(t *testing.T) {
	now := time.Now().UnixMilli()
	account := PortalAccount{
		Number:                 "47286153",
		Enabled:                true,
		UnlimitedUntilMillis:   now + 5*60*1000,
	}
	pass := PortalPass{
		Code:            "DATA30",
		QuotaBytes:      1000000000,
		DurationMinutes: 30 * 24 * 60,
	}
	updated := applyPortalRecharge(account, pass, now)
	wantExpiry := account.UnlimitedUntilMillis + pass.DurationMinutes*60*1000
	if updated.DataExpiresAtMillis != wantExpiry {
		t.Fatalf("data expiry=%d, want %d", updated.DataExpiresAtMillis, wantExpiry)
	}
	if updated.DataBalanceBytes != pass.QuotaBytes {
		t.Fatalf("data balance=%d, want %d", updated.DataBalanceBytes, pass.QuotaBytes)
	}
}

func TestUnlimitedRechargeAccumulatesValidityAndPreservesData(t *testing.T) {
	now := time.Now().UnixMilli()
	account := PortalAccount{
		Number:                 "47286153",
		Enabled:                true,
		DataBalanceBytes:       100000000,
		DataExpiresAtMillis:    now + 2*24*60*60*1000,
		UnlimitedUntilMillis:   now + 5*24*60*60*1000,
	}
	pass := PortalPass{
		Code:            "UNLIM30",
		Name:            "Illimité Eco",
		DurationMinutes: 30 * 24 * 60,
	}
	updated := applyPortalRecharge(account, pass, now)
	wantUnlimited := account.UnlimitedUntilMillis + pass.DurationMinutes*60*1000
	if updated.UnlimitedUntilMillis != wantUnlimited {
		t.Fatalf("unlimited expiry=%d, want %d", updated.UnlimitedUntilMillis, wantUnlimited)
	}
	wantDataExpiry := account.DataExpiresAtMillis + pass.DurationMinutes*60*1000
	if updated.DataExpiresAtMillis != wantDataExpiry {
		t.Fatalf("data expiry=%d, want %d", updated.DataExpiresAtMillis, wantDataExpiry)
	}
	if updated.DataBalanceBytes != account.DataBalanceBytes {
		t.Fatalf("data balance=%d, want %d", updated.DataBalanceBytes, account.DataBalanceBytes)
	}
}

func TestPrepaidAccountMovesToNewClient(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, `{
	  "accounts": [{
	    "number": "47286153",
	    "pin": "583921",
	    "enabled": true,
	    "dataBalanceBytes": 1000,
	    "dataExpiresAtMillis": 0
	  }]
	}`)

	if ok, msg := manager.submitPortalAccountLogin("192.168.43.10", "47286153", "583921"); !ok {
		t.Fatalf("first account login rejected: %s", msg)
	}
	if ok, msg := manager.submitPortalAccountLogin("192.168.43.20", "47286153", "583921"); !ok {
		t.Fatalf("second account login rejected: %s", msg)
	}
	if manager.portalAuthorizedFor("192.168.43.10") {
		t.Fatal("old client stayed authorized after account transfer")
	}
	if !manager.portalAuthorizedFor("192.168.43.20") {
		t.Fatal("new client did not receive the account session")
	}
}


func TestAccountPanelShowsAllocatedRateAndValidity(t *testing.T) {
	manager := newTrafficManager()
	now := time.Now().UnixMilli()
	manager.setPortalConfig(true, fmt.Sprintf(`{
	  "accounts": [{
	    "number": "25494159",
	    "pin": "583921",
	    "name": "TAIANA",
	    "enabled": true,
	    "dataBalanceBytes": 1082000000,
	    "dataExpiresAtMillis": %d,
	    "dataDownloadBps": 1000000,
	    "dataUploadBps": 1000000
	  }]
	}`, now+30*24*60*60*1000))

	if ok, msg := manager.submitPortalAccountLogin("192.168.43.10", "25494159", "583921"); !ok {
		t.Fatalf("account login rejected: %s", msg)
	}
	manager.mu.Lock()
	panel := manager.portalAccountPanelLocked("192.168.43.10")
	manager.mu.Unlock()

	for _, expected := range []string{"TAIANA", "1.08 GB", "1.00 Mbps", "Validité restante", "Voir ma consommation en direct"} {
		if !strings.Contains(panel, expected) {
			t.Fatalf("account panel missing %q: %s", expected, panel)
		}
	}
}
