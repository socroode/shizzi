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

	ok, msg, token := manager.submitPortalAccountLoginWithSession(
		"192.168.43.10",
		"25494159",
		"583921",
	)
	if !ok || token == "" {
		t.Fatalf("account login rejected: %s", msg)
	}
	manager.mu.Lock()
	panel := manager.portalAccountPanelLocked("192.168.43.10", token)
	manager.mu.Unlock()

	for _, expected := range []string{"TAIANA", "1.08 GB", "1.00 Mbps", "Validité restante", "Voir ma consommation en direct"} {
		if !strings.Contains(panel, expected) {
			t.Fatalf("account panel missing %q: %s", expected, panel)
		}
	}
}


func TestAccountSessionAllowsRechargeAcrossPortalIPChange(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, `{
	  "accounts": [{
	    "number": "63057303",
	    "pin": "583921",
	    "name": "RONIU",
	    "enabled": true
	  }],
	  "passes": [{
	    "code": "MZW4DVK",
	    "name": "Data 1-1",
	    "quotaBytes": 1000000000,
	    "durationMinutes": 43200,
	    "downloadBps": 1000000,
	    "uploadBps": 1000000,
	    "enabled": true
	  }]
	}`)

	ok, message, token := manager.submitPortalAccountLoginWithSession(
		"192.168.43.10",
		"63057303",
		"583921",
	)
	if !ok || token == "" {
		t.Fatalf("account login failed: ok=%v token=%q message=%s", ok, token, message)
	}

	ok, message = manager.submitPortalRechargeWithSession(
		"2001:db8::2",
		"MZW4DVK",
		token,
	)
	if !ok {
		t.Fatalf("recharge after client IP change failed: %s", message)
	}

	account := manager.portalAccounts["63057303"]
	if account.DataBalanceBytes != 1000000000 {
		t.Fatalf("data balance=%d, want 1000000000", account.DataBalanceBytes)
	}
	if account.DataDownloadBps != 1000000 || account.DataUploadBps != 1000000 {
		t.Fatalf(
			"rate=%d/%d, want 1000000/1000000",
			account.DataDownloadBps,
			account.DataUploadBps,
		)
	}
	if manager.portalPasses["MZW4DVK"].RedeemedAccountNumber != "63057303" {
		t.Fatal("coupon was not redeemed to the logged-in account")
	}
}

func TestFreshAccountLoginInvalidatesPreviousBrowserSession(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, `{
	  "accounts": [{
	    "number": "63057303",
	    "pin": "583921",
	    "enabled": true
	  }],
	  "passes": [{
	    "code": "DATA1",
	    "quotaBytes": 1000000000,
	    "durationMinutes": 43200,
	    "enabled": true
	  }]
	}`)

	ok, _, firstToken := manager.submitPortalAccountLoginWithSession(
		"192.168.43.10",
		"63057303",
		"583921",
	)
	if !ok || firstToken == "" {
		t.Fatal("first account session was not created")
	}

	ok, _, secondToken := manager.submitPortalAccountLoginWithSession(
		"192.168.43.20",
		"63057303",
		"583921",
	)
	if !ok || secondToken == "" || secondToken == firstToken {
		t.Fatal("second account login did not replace the first session")
	}

	if ok, _ := manager.submitPortalRechargeWithSession(
		"192.168.43.10",
		"DATA1",
		firstToken,
	); ok {
		t.Fatal("invalidated first browser session was still able to recharge")
	}
}


func TestSharedTunnelPrepaidPagesRequireOwnSessionToken(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, `{
	  "accounts": [
	    {
	      "number": "63057303",
	      "pin": "583921",
	      "name": "RONIU",
	      "enabled": true,
	      "dataBalanceBytes": 12000000000,
	      "dataDownloadBps": 2000000,
	      "dataUploadBps": 1000000
	    },
	    {
	      "number": "70000002",
	      "pin": "654321",
	      "name": "CLIENT-B",
	      "enabled": true,
	      "dataBalanceBytes": 20000000000,
	      "dataDownloadBps": 4000000,
	      "dataUploadBps": 2000000
	    }
	  ]
	}`)

	const sharedIP = "192.0.2.2"
	ok, message, roniuToken := manager.submitPortalAccountLoginWithSession(
		sharedIP,
		"63057303",
		"583921",
	)
	if !ok || roniuToken == "" {
		t.Fatalf("RONIU login failed: ok=%v token=%q message=%s", ok, roniuToken, message)
	}

	ok, message, clientToken := manager.submitPortalAccountLoginWithSession(
		sharedIP,
		"70000002",
		"654321",
	)
	if !ok || clientToken == "" || clientToken == roniuToken {
		t.Fatalf("CLIENT-B login failed: ok=%v token=%q message=%s", ok, clientToken, message)
	}

	manager.mu.Lock()
	roniuPanel := manager.portalAccountPanelLocked(sharedIP, roniuToken)
	clientPanel := manager.portalAccountPanelLocked(sharedIP, clientToken)
	anonymousPanel := manager.portalAccountPanelLocked(sharedIP, "")
	invalidPanel := manager.portalAccountPanelLocked(sharedIP, "not-a-real-session")
	manager.mu.Unlock()

	if !strings.Contains(roniuPanel, "RONIU") || strings.Contains(roniuPanel, "CLIENT-B") {
		t.Fatalf("RONIU session saw the wrong account: %s", roniuPanel)
	}
	if !strings.Contains(clientPanel, "CLIENT-B") || strings.Contains(clientPanel, "RONIU") {
		t.Fatalf("CLIENT-B session saw the wrong account: %s", clientPanel)
	}
	for label, panel := range map[string]string{
		"anonymous": anonymousPanel,
		"invalid":   invalidPanel,
	} {
		if !strings.Contains(panel, "Connexion client") {
			t.Fatalf("%s browser did not receive login page: %s", label, panel)
		}
		if strings.Contains(panel, "RONIU") || strings.Contains(panel, "CLIENT-B") {
			t.Fatalf("%s browser leaked a prepaid account: %s", label, panel)
		}
	}

	roniuStatus := manager.portalUsageStatusForSession(sharedIP, roniuToken)
	clientStatus := manager.portalUsageStatusForSession(sharedIP, clientToken)
	anonymousStatus := manager.portalUsageStatusForSession(sharedIP, "")
	invalidStatus := manager.portalUsageStatusForSession(sharedIP, "not-a-real-session")

	if !roniuStatus.Authenticated || roniuStatus.AccountName != "RONIU" ||
		roniuStatus.AccountNumber != "63057303" {
		t.Fatalf("RONIU status leaked or disappeared: %+v", roniuStatus)
	}
	if !clientStatus.Authenticated || clientStatus.AccountName != "CLIENT-B" ||
		clientStatus.AccountNumber != "70000002" {
		t.Fatalf("CLIENT-B status leaked or disappeared: %+v", clientStatus)
	}
	if anonymousStatus.Authenticated || anonymousStatus.AccountNumber != "" {
		t.Fatalf("anonymous browser inherited an account: %+v", anonymousStatus)
	}
	if invalidStatus.Authenticated || invalidStatus.AccountNumber != "" {
		t.Fatalf("invalid browser session inherited an account: %+v", invalidStatus)
	}

	if manager.portalRequestAuthorizedFor(sharedIP, "") {
		t.Fatal("shared tunnel browser without a session token was treated as account-authorized")
	}
	if !manager.portalRequestAuthorizedFor(sharedIP, roniuToken) {
		t.Fatal("RONIU browser session was not recognized as authorized")
	}
	if !manager.portalRequestAuthorizedFor(sharedIP, clientToken) {
		t.Fatal("CLIENT-B browser session was not recognized as authorized")
	}
}


func TestAmbiguousSharedAccountLoginIsRejectedWithMultipleClients(t *testing.T) {
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
		t.Fatalf("unexpected identification message: %q", message)
	}
}

func TestAmbiguousSharedVoucherLoginIsRejectedWithMultipleClients(t *testing.T) {
	manager := newTrafficManager()
	manager.flowAttribution.dumpFn = func() (string, error) {
		return sampleTetheringDump, nil
	}
	manager.flowAttribution.mu.Lock()
	manager.flowAttribution.refreshLocked()
	manager.flowAttribution.mu.Unlock()
	manager.setPortalConfig(true, portablePassConfig)

	ok, message := manager.submitPortalCode("192.0.2.2", "ECO123")
	if ok {
		t.Fatal("ambiguous shared voucher unexpectedly authorized every client")
	}
	if !strings.Contains(message, "identifier cet appareil") {
		t.Fatalf("unexpected identification message: %q", message)
	}
}

func TestAccountSessionMovesFromSharedAddressToResolvedClient(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, `{
	  "accounts": [{
	    "number": "63057303",
	    "pin": "583921",
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
		t.Fatalf("single-client shared login failed: %s", message)
	}

	manager.mu.Lock()
	auth, rebound := manager.portalAuthorizationForSessionLocked("192.168.43.20", token)
	_, staleShared := manager.portalAuthorized["192.0.2.2"]
	resolved := manager.portalAuthorized["192.168.43.20"]
	manager.mu.Unlock()

	if !rebound || auth.AccountNumber != "63057303" {
		t.Fatalf("session did not rebind to resolved client: rebound=%v auth=%+v", rebound, auth)
	}
	if staleShared {
		t.Fatal("shared authorization remained after session moved to resolved client")
	}
	if resolved.SessionToken != token {
		t.Fatalf("resolved client token=%q, want %q", resolved.SessionToken, token)
	}
}
