package datapath

import (
	"encoding/json"
	"strings"
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
	ok, _ = manager.submitPortalRecharge("192.168.7.66", "abc123def4")
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
		claim.Code != "ABC123DEF4" {
		t.Fatalf("claim=%+v", claim)
	}
}

func TestSameAccountIsRefusedOnSecondActiveClient(t *testing.T) {
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
	if secondOK {
		t.Fatal("second client unexpectedly opened the same account")
	}
	if secondMessage != "Ce compte est déjà utilisé sur un autre appareil." {
		t.Fatalf("unexpected refusal message: %s", secondMessage)
	}

	if manager.portalRequiredFor("192.168.7.66") {
		t.Fatal("first authenticated client returned to the portal")
	}
	if !manager.portalRequiredFor("192.168.7.77") {
		t.Fatal("second client was authorized")
	}
	if len(manager.portalAuthorized) != 1 {
		t.Fatalf("authorizations=%d, want 1", len(manager.portalAuthorized))
	}
}


func TestPortalCustomizationPreservesFunctionalContent(t *testing.T) {
	custom := `<!doctype html><html><head><title>{{TITLE}}</title></head><body><h1>{{MESSAGE}}</h1>{{CONTENT}}</body></html>`
	rendered := applyPortalCustomization(
		custom,
		"TEKOMOPAO WIFI",
		"Bienvenue",
		"",
		`<form action="/login"><input name="account"></form>`,
	)
	if !strings.Contains(rendered, "TEKOMOPAO WIFI") {
		t.Fatal("custom title was not rendered")
	}
	if !strings.Contains(rendered, `name="account"`) {
		t.Fatal("functional account login content was lost")
	}
}

func TestPortalCustomizationInjectsContentWhenPlaceholderIsMissing(t *testing.T) {
	custom := `<html><body><div>branding only</div></body></html>`
	rendered := applyPortalCustomization(
		custom,
		"Title",
		"Message",
		"",
		`<form action="/login"><button>Login</button></form>`,
	)
	if !strings.Contains(rendered, `action="/login"`) {
		t.Fatal("functional content was not injected")
	}
	if strings.Index(rendered, `action="/login"`) > strings.Index(rendered, "</body>") {
		t.Fatal("functional content was injected after body")
	}
}
