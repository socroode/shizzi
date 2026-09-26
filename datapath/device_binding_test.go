package datapath

import (
	"encoding/json"
	"fmt"
	"testing"
)

func prepaidBindingConfig(t *testing.T, epoch string, count int) string {
	t.Helper()
	accounts := make([]PortalAccount, 0, count)
	for i := 0; i < count; i++ {
		accounts = append(accounts, PortalAccount{
			Number:           fmt.Sprintf("820000%02d", i),
			Pin:              fmt.Sprintf("73%04d", i),
			Name:             fmt.Sprintf("CLIENT-%02d", i+1),
			Enabled:          true,
			DataBalanceBytes: 12_000_000_000,
			DataDownloadBps:  2_000_000,
			DataUploadBps:    1_000_000,
		})
	}
	raw, err := json.Marshal(portalConfigPayload{
		SessionEpoch: epoch,
		Accounts:     accounts,
	})
	if err != nil {
		t.Fatalf("marshal portal config: %v", err)
	}
	return string(raw)
}

func TestThreePrepaidPhonesBindIndependentlyFromSharedPortalIP(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, prepaidBindingConfig(t, "1.7.5-device-bind-v1", 3))

	const sharedIP = "192.0.2.2"
	tokens := make([]string, 3)
	realIPs := []string{"192.168.43.20", "192.168.43.21", "192.168.43.22"}

	for i := 0; i < 3; i++ {
		number := fmt.Sprintf("820000%02d", i)
		pin := fmt.Sprintf("73%04d", i)
		ok, message, token := manager.submitPortalAccountLoginWithSession(sharedIP, number, pin)
		if !ok || token == "" {
			t.Fatalf("client %d shared login failed: ok=%v token=%q message=%s", i+1, ok, token, message)
		}
		tokens[i] = token

		if manager.portalSessionBoundIP(token) != "" {
			t.Fatalf("client %d bound before device bridge", i+1)
		}
		if manager.portalAuthorizedFor(sharedIP) {
			t.Fatalf("client %d leaked authorization onto shared TUN IP", i+1)
		}

		if got := manager.bindPortalAccountSession(realIPs[i], token); got != token {
			t.Fatalf("client %d bind returned token %q, want %q", i+1, got, token)
		}
		if got := manager.portalSessionBoundIP(token); got != realIPs[i] {
			t.Fatalf("client %d bound IP=%q, want %q", i+1, got, realIPs[i])
		}
		if !manager.portalAuthorizedFor(realIPs[i]) {
			t.Fatalf("client %d real IP not Internet-authorized", i+1)
		}
	}

	for i, token := range tokens {
		status := manager.portalUsageStatusForSession(sharedIP, token)
		wantNumber := fmt.Sprintf("820000%02d", i)
		wantName := fmt.Sprintf("CLIENT-%02d", i+1)
		if !status.Authenticated || !status.Authorized {
			t.Fatalf("client %d status inactive: %+v", i+1, status)
		}
		if status.AccountNumber != wantNumber || status.AccountName != wantName {
			t.Fatalf(
				"client %d saw wrong account: number=%q name=%q, want %q/%q",
				i+1,
				status.AccountNumber,
				status.AccountName,
				wantNumber,
				wantName,
			)
		}
	}

	if manager.portalAuthorizedFor(sharedIP) {
		t.Fatal("shared TUN IP became authorized after three independent binds")
	}
}

func TestTenPrepaidPhonesStayIndependentBehindSharedPortal(t *testing.T) {
	const users = 10
	manager := newTrafficManager()
	manager.setPortalConfig(true, prepaidBindingConfig(t, "1.7.5-device-bind-v1", users))

	const sharedIP = "192.0.2.2"
	tokens := make([]string, users)

	for i := 0; i < users; i++ {
		number := fmt.Sprintf("820000%02d", i)
		pin := fmt.Sprintf("73%04d", i)
		tokenIP := fmt.Sprintf("192.168.43.%d", 30+i)

		ok, message, token := manager.submitPortalAccountLoginWithSession(sharedIP, number, pin)
		if !ok || token == "" {
			t.Fatalf("client %d login failed: %s", i+1, message)
		}
		tokens[i] = token
		manager.bindPortalAccountSession(tokenIP, token)

		if !manager.portalAuthorizedFor(tokenIP) {
			t.Fatalf("client %d not authorized on %s", i+1, tokenIP)
		}
	}

	for i, token := range tokens {
		status := manager.portalUsageStatusForSession(sharedIP, token)
		wantNumber := fmt.Sprintf("820000%02d", i)
		if status.AccountNumber != wantNumber {
			t.Fatalf("client %d got account %q, want %q", i+1, status.AccountNumber, wantNumber)
		}
	}
	if manager.portalAuthorizedFor(sharedIP) {
		t.Fatal("shared TUN IP should never carry a prepaid account authorization")
	}
}

func TestPortalSessionEpochInvalidatesLegacyBrowserSessionsOnly(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, prepaidBindingConfig(t, "1.7.4", 1))

	ok, message, token := manager.submitPortalAccountLoginWithSession(
		"192.168.43.50",
		"82000000",
		"730000",
	)
	if !ok || token == "" {
		t.Fatalf("legacy login failed: %s", message)
	}
	if !manager.portalAuthorizedFor("192.168.43.50") {
		t.Fatal("legacy session not authorized before epoch change")
	}

	manager.setPortalConfig(true, prepaidBindingConfig(t, "1.7.5-device-bind-v1", 1))

	if manager.portalSessionBoundIP(token) != "" {
		t.Fatal("legacy browser token survived session epoch change")
	}
	if manager.portalAuthorizedFor("192.168.43.50") {
		t.Fatal("legacy account authorization survived session epoch change")
	}
	if account, exists := manager.portalAccounts["82000000"]; !exists ||
		account.DataBalanceBytes != 12_000_000_000 {
		t.Fatalf("account balance was not preserved across epoch change: %+v exists=%v", account, exists)
	}
}
