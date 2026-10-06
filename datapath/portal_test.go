package datapath

import (
	"encoding/json"
	"io"
	"net"
	"os"
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
			MediaUntilMillis:          time.Now().Add(24 * time.Hour).UnixMilli(),
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	return string(raw)
}

func TestPortalPageDoesNotWaitForClientIdentity(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, portalConfigForTest(t))

	resolverCalls := 0
	server, client := net.Pipe()
	done := make(chan struct{})
	go func() {
		defer close(done)
		manager.servePortalWithResolver(server, "", func() string {
			resolverCalls++
			return "192.168.7.66"
		})
	}()

	_ = client.SetDeadline(time.Now().Add(3 * time.Second))
	_, _ = client.Write([]byte(
		"GET / HTTP/1.1\r\nHost: connectivitycheck.gstatic.com\r\nConnection: close\r\n\r\n",
	))
	response, readErr := io.ReadAll(client)
	_ = client.Close()
	if readErr != nil {
		t.Fatal(readErr)
	}
	<-done

	if resolverCalls != 0 {
		t.Fatalf("portal display waited for client identity: resolver calls=%d", resolverCalls)
	}
	if !strings.Contains(string(response), "Ouvrir la connexion compte") {
		t.Fatalf("login page was not rendered for unidentified client: %q", string(response))
	}
}

func TestPortalLoginResolvesIdentityBeforeAuthorization(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, portalConfigForTest(t))

	const resolvedIP = "192.168.7.66"
	resolverCalls := 0
	server, client := net.Pipe()
	done := make(chan struct{})
	go func() {
		defer close(done)
		manager.servePortalWithResolver(server, "", func() string {
			resolverCalls++
			return resolvedIP
		})
	}()

	body := "account=1001&pin=1234"
	request := "POST /login HTTP/1.1\r\n" +
		"Host: 192.0.2.1\r\n" +
		"Content-Type: application/x-www-form-urlencoded\r\n" +
		"Content-Length: 21\r\n" +
		"Connection: close\r\n\r\n" + body
	_ = client.SetDeadline(time.Now().Add(3 * time.Second))
	_, _ = client.Write([]byte(request))
	response, readErr := io.ReadAll(client)
	_ = client.Close()
	if readErr != nil {
		t.Fatal(readErr)
	}
	<-done

	if resolverCalls != 1 {
		t.Fatalf("login identity resolver calls=%d, want 1", resolverCalls)
	}
	if manager.portalRequiredFor(resolvedIP) {
		t.Fatal("resolved client was not authorized after login")
	}
	if _, exists := manager.portalAuthorized[""]; exists {
		t.Fatal("login was incorrectly attached to an unidentified client")
	}
	if !strings.Contains(string(response), "Connexion autorisée.") {
		t.Fatalf("unexpected login response: %q", string(response))
	}
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

func TestSameAccountCanOpenMultipleActiveClientSessions(t *testing.T) {
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
	if manager.portalAuthorized["192.168.7.66"].AccountNumber != "1001" ||
		manager.portalAuthorized["192.168.7.77"].AccountNumber != "1001" {
		t.Fatal("portable account sessions were not mapped to the same account")
	}
}


func TestPortableAccountAcrossSixClientsSharesOneUsagePool(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, portalConfigForTest(t))

	clients := []string{
		"192.168.7.61",
		"192.168.7.62",
		"192.168.7.63",
		"192.168.7.64",
		"192.168.7.65",
		"192.168.7.66",
	}
	for _, ip := range clients {
		ok, message := manager.submitPortalAccountLogin(ip, "1001", "1234")
		if !ok {
			t.Fatalf("login %s failed: %s", ip, message)
		}
		manager.account(ip, directionDownload, 100)
	}
	if len(manager.portalAuthorized) != len(clients) {
		t.Fatalf("authorizations=%d, want %d", len(manager.portalAuthorized), len(clients))
	}
	usage := manager.accountUsage["1001"]
	if usage == nil || usage.DownBytes != 600 {
		t.Fatalf("shared account usage=%+v, want 600 download bytes", usage)
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


func TestPortalAutoRefreshRunsEveryTwoSeconds(t *testing.T) {
	page := injectPortalAutoRefresh(`<html><body><div id="shizzi-account"></div></body></html>`)
	if !strings.Contains(page, "setInterval(shizziRefresh,2000)") {
		t.Fatal("portal is not configured for a two-second refresh")
	}
	if !strings.Contains(page, "/status.json?ts=") {
		t.Fatal("portal refresh does not use status.json")
	}
}


func TestMediaProxyTargetKeepsLocalPrefixOutOfUpstream(t *testing.T) {
	cases := []struct {
		path     string
		query    string
		expected string
	}{
		{path: "/media", expected: "/"},
		{path: "/media/", expected: "/"},
		{path: "/media/library", query: "kind=films", expected: "/library?kind=films"},
		{path: "/media/play", query: "id=abc123", expected: "/play?id=abc123"},
		{path: "/media/stream", query: "id=abc123", expected: "/stream?id=abc123"},
	}

	for _, test := range cases {
		if actual := mediaProxyTarget(test.path, test.query); actual != test.expected {
			t.Fatalf("mediaProxyTarget(%q, %q)=%q, want %q", test.path, test.query, actual, test.expected)
		}
	}
}


func TestLocalSpeedtestUsesGenericShizziRouterWording(t *testing.T) {
	source, err := os.ReadFile("portal.go")
	if err != nil {
		t.Fatal(err)
	}
	text := string(source)
	if strings.Contains(strings.ToLower(text), "reno9") {
		t.Fatal("local speedtest wording must not be tied to Reno9")
	}
	for _, expected := range []string{
		"routeur Shizzi → cet appareil",
		"Mesure du débit routeur Shizzi → appareil",
		"vitesse réelle du routeur Shizzi vers cet appareil",
	} {
		if !strings.Contains(text, expected) {
			t.Fatalf("missing generic Shizzi router wording %q", expected)
		}
	}
}

func TestSpeedtestDownloadSizesAreBounded(t *testing.T) {
	cases := map[string]int64{
		"10":  10 * 1024 * 1024,
		"25":  25 * 1024 * 1024,
		"50":  50 * 1024 * 1024,
		"100": 100 * 1024 * 1024,
		"999": 50 * 1024 * 1024,
		"":    50 * 1024 * 1024,
	}

	for raw, expected := range cases {
		if actual := speedtestDownloadBytes(raw); actual != expected {
			t.Fatalf("speedtestDownloadBytes(%q)=%d, want %d", raw, actual, expected)
		}
	}
}


func TestMediaAccessFollowsAccountSession(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, portalConfigForTest(t))
	ip := "192.168.7.66"

	if manager.mediaAccountAuthenticated(ip) {
		t.Fatal("media available before login")
	}
	ok, message := manager.submitPortalAccountLogin(ip, "1001", "1234")
	if !ok {
		t.Fatalf("login failed: %s", message)
	}
	if !manager.mediaAccountAuthenticated(ip) {
		t.Fatal("media unavailable after login")
	}
	ok, _ = manager.submitPortalLogout(ip)
	if !ok {
		t.Fatal("logout failed")
	}
	if manager.mediaAccountAuthenticated(ip) {
		t.Fatal("media still available after logout")
	}
}


func TestMediaPassIsRequiredButInternetRemainsIndependent(t *testing.T) {
	var config portalConfig
	if err := json.Unmarshal([]byte(portalConfigForTest(t)), &config); err != nil {
		t.Fatal(err)
	}
	config.Accounts[0].MediaUntilMillis = 0
	raw, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}

	manager := newTrafficManager()
	manager.setPortalConfig(true, string(raw))
	ip := "192.168.7.66"
	ok, message := manager.submitPortalAccountLogin(ip, "1001", "1234")
	if !ok {
		t.Fatalf("login failed: %s", message)
	}
	if !manager.portalAuthorizedLocked(ip, time.Now().UnixMilli()) {
		t.Fatal("Internet should remain active without a Media pass")
	}
	if manager.mediaPassActive(ip) {
		t.Fatal("Media pass unexpectedly active")
	}

	server, client := net.Pipe()
	done := make(chan struct{})
	go func() {
		defer close(done)
		manager.servePortal(server, ip)
	}()
	_ = client.SetDeadline(time.Now().Add(3 * time.Second))
	_, _ = client.Write([]byte(
		"GET /media/ HTTP/1.1\r\nHost: 192.0.2.1\r\nConnection: close\r\n\r\n",
	))
	response, readErr := io.ReadAll(client)
	_ = client.Close()
	if readErr != nil {
		t.Fatal(readErr)
	}
	<-done

	text := string(response)
	if !strings.Contains(text, "Pass Shizzi Media requis") {
		t.Fatalf("missing Media pass page: %q", text)
	}
	if !strings.Contains(text, "X-Shizzi-Media-Pass: required") {
		t.Fatalf("missing Media pass response marker: %q", text)
	}
}

func TestAdminActionAllowlistKeepsRouterEngineLocal(t *testing.T) {
	allowed := []string{
		"account.create",
		"voucher.generate",
		"media.offer.upsert",
		"media.offer.delete",
		"media.voucher.generate",
		"media.voucher.enable",
		"media.enable",
		"media.folder.create",
		"media.folder.update",
		"media.folder.delete",
		"media.folder.source",
		"media.scan",
		"media.browse",
	}
	for _, action := range allowed {
		if !adminActionAllowed(action) {
			t.Fatalf("expected admin action %q to be allowed", action)
		}
	}

	forbidden := []string{
		"hotspot.start",
		"hotspot.stop",
		"hotspot.band",
		"shizzi.start",
		"shizzi.stop",
		"tun.restart",
		"watchdog.configure",
	}
	for _, action := range forbidden {
		if adminActionAllowed(action) {
			t.Fatalf("router engine action %q must remain local-only", action)
		}
	}
}
