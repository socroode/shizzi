package datapath

import (
	"encoding/json"
	"testing"
	"time"
)

func portalConfigForTest(t *testing.T) string {
	t.Helper()
	salt := "abc"
	hash := hashPortalPin(salt, "1234")
	raw, err := json.Marshal(portalConfig{
		Title: "Test",
		Accounts: []PortalAccount{{
			Number:                    "1001",
			Name:                      "RONIU",
			PinSalt:                   salt,
			PinHash:                   hash,
			Enabled:                   true,
			DataBalanceBytes:          1_000_000,
			DataValidUntilMillis:      time.Now().Add(time.Hour).UnixMilli(),
			DataDownloadBitsPerSecond: 2_000_000,
			DataUploadBitsPerSecond:   1_000_000,
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	return string(raw)
}

func TestPortalLoginAuthorizesOnlyResolvedClient(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, portalConfigForTest(t))

	if ok, _ := manager.submitPortalAccountLogin("192.168.7.66", "1001", "bad"); ok {
		t.Fatal("bad PIN authorized")
	}
	if ok, message := manager.submitPortalAccountLogin("192.168.7.66", "1001", "1234"); !ok {
		t.Fatalf("login failed: %s", message)
	}
	if manager.portalRequiredFor("192.168.7.66") {
		t.Fatal("authorized client still requires portal")
	}
	if !manager.portalRequiredFor("192.168.7.77") {
		t.Fatal("second client inherited first client's authorization")
	}
}

func TestPortalDataAllowanceIsPerClientSession(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, portalConfigForTest(t))
	ok, _ := manager.submitPortalAccountLogin("192.168.7.66", "1001", "1234")
	if !ok {
		t.Fatal("login failed")
	}

	manager.account("192.168.7.66", directionDownload, 900_000)
	if !manager.waitAllowed("192.168.7.66", directionDownload, 1) {
		t.Fatal("client blocked before quota")
	}

	manager.account("192.168.7.66", directionDownload, 100_001)
	if manager.waitAllowed("192.168.7.66", directionDownload, 1) {
		t.Fatal("client allowed after account data balance")
	}
}

func TestPortalRechargeClaimCarriesAccountAndClient(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, portalConfigForTest(t))
	ok, _ := manager.submitPortalAccountLogin("192.168.7.66", "1001", "1234")
	if !ok {
		t.Fatal("login failed")
	}
	ok, _ = manager.submitPortalRecharge("192.168.7.66", "shz-abcd-1234")
	if !ok {
		t.Fatal("recharge claim rejected")
	}
	raw := manager.statsJSON()
	var snapshot trafficStatsSnapshot
	if err := json.Unmarshal([]byte(raw), &snapshot); err != nil {
		t.Fatal(err)
	}
	if len(snapshot.PortalRechargeClaims) != 1 {
		t.Fatalf("claims=%d", len(snapshot.PortalRechargeClaims))
	}
	claim := snapshot.PortalRechargeClaims[0]
	if claim.AccountNumber != "1001" || claim.IP != "192.168.7.66" ||
		claim.Code != "SHZ-ABCD-1234" {
		t.Fatalf("claim=%+v", claim)
	}
}

func TestSameAccountCanAuthenticateOnTwoDifferentClients(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, portalConfigForTest(t))

	firstOK, firstMessage := manager.submitPortalAccountLogin(
		"192.168.7.66",
		"1001",
		"1234",
	)
	if !firstOK {
		t.Fatalf("first login failed: %s", firstMessage)
	}

	secondOK, secondMessage := manager.submitPortalAccountLogin(
		"192.168.7.77",
		"1001",
		"1234",
	)
	if !secondOK {
		t.Fatalf("second login failed: %s", secondMessage)
	}

	if manager.portalRequiredFor("192.168.7.66") {
		t.Fatal("first authenticated client returned to the portal")
	}
	if manager.portalRequiredFor("192.168.7.77") {
		t.Fatal("second authenticated client returned to the portal")
	}
	if len(manager.portalAuthorized) != 2 {
		t.Fatalf("authorizations=%d, want 2", len(manager.portalAuthorized))
	}
}

