package datapath

import (
	"encoding/json"
	"io"
	"net"
	"net/http"
	"strings"
	"testing"
)

func setModulesForTest(t *testing.T, manager *TrafficManager, messenger, media bool) {
	t.Helper()
	var config portalConfig
	if err := json.Unmarshal([]byte(portalConfigForTest(t)), &config); err != nil {
		t.Fatal(err)
	}
	config.Modules = &portalModules{
		MessengerEnabled: messenger,
		MediaEnabled:     media,
	}
	raw, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	manager.setPortalConfig(true, string(raw))
}

func renderedPortalForModulesTest(t *testing.T, manager *TrafficManager, ip string) string {
	t.Helper()
	server, client := net.Pipe()
	done := make(chan struct{})
	go func() {
		defer close(done)
		manager.writePortalHTML(server, ip, "", false)
		_ = server.Close()
	}()
	raw, err := io.ReadAll(client)
	_ = client.Close()
	if err != nil {
		t.Fatal(err)
	}
	<-done
	return string(raw)
}

func portalRequestForModulesTest(t *testing.T, manager *TrafficManager, ip, path string) string {
	t.Helper()
	server, client := net.Pipe()
	done := make(chan struct{})
	go func() {
		defer close(done)
		manager.servePortal(server, ip)
	}()
	if _, err := io.WriteString(client, "GET "+path+" HTTP/1.1\r\nHost: 192.0.2.1\r\nConnection: close\r\n\r\n"); err != nil {
		t.Fatal(err)
	}
	raw, err := io.ReadAll(client)
	_ = client.Close()
	if err != nil {
		t.Fatal(err)
	}
	<-done
	return string(raw)
}

func TestModulesDefaultEnabledForOldPortalConfig(t *testing.T) {
	manager := newTrafficManager()
	manager.setPortalConfig(true, portalConfigForTest(t))

	if !manager.messengerModuleEnabled() {
		t.Fatal("legacy config unexpectedly disabled Messenger")
	}
	if !manager.mediaModuleEnabled() {
		t.Fatal("legacy config unexpectedly disabled Media")
	}
}

func TestDisabledModulesAreAdvertisedBeforeLogin(t *testing.T) {
	manager := newTrafficManager()
	setModulesForTest(t, manager, false, false)

	status := manager.portalStatus("192.168.7.50")
	if status.MessengerEnabled {
		t.Fatal("status.json still advertises Messenger enabled")
	}
	if status.MediaEnabled {
		t.Fatal("status.json still advertises Media enabled")
	}
	if status.Authenticated {
		t.Fatal("unauthenticated test client unexpectedly authenticated")
	}
}

func TestDisabledMessengerAPIIsUnavailable(t *testing.T) {
	manager := newTrafficManager()
	setModulesForTest(t, manager, false, true)

	status, payload := messengerRequest(
		t,
		manager,
		"192.168.7.50",
		http.MethodGet,
		"/api/v1/messenger/me",
		"",
	)
	if !strings.Contains(status, "404") {
		t.Fatalf("Messenger status=%s, want 404", status)
	}
	if payload["ok"] != false {
		t.Fatalf("Messenger disabled payload=%v", payload)
	}
}

func TestDisabledModuleCardsDisappearFromAuthenticatedPortal(t *testing.T) {
	manager := newTrafficManager()
	setModulesForTest(t, manager, false, false)
	if ok, message := manager.submitPortalAccountLogin("192.168.7.50", "1001", "1234"); !ok {
		t.Fatalf("login failed: %s", message)
	}

	page := renderedPortalForModulesTest(t, manager, "192.168.7.50")
	if strings.Contains(page, "Ouvrir Shizzi Messenger") {
		t.Fatal("disabled Messenger card is still visible")
	}
	if strings.Contains(page, "Ouvrir Shizzi Media") {
		t.Fatal("disabled Media card is still visible")
	}
	if !strings.Contains(page, "Test de débit Shizzi") {
		t.Fatal("Internet/local core content disappeared with optional modules")
	}
}

func TestDisabledMediaRouteDoesNotReachMediaServer(t *testing.T) {
	manager := newTrafficManager()
	setModulesForTest(t, manager, true, false)

	response := portalRequestForModulesTest(t, manager, "192.168.7.50", "/media/")
	if !strings.Contains(response, "404 Not Found") {
		t.Fatalf("Media disabled response=%q", response)
	}
	if !strings.Contains(response, "Shizzi Media") || !strings.Contains(response, "désactivé") {
		t.Fatalf("Media disabled explanation missing: %q", response)
	}
}


func TestSixClientRouterKeepsInternetWhenOptionalModulesAreDisabled(t *testing.T) {
	manager, devices := messengerSixDeviceManagerForTest(t)

	manager.mu.Lock()
	accounts := make([]PortalAccount, 0, len(manager.portalAccounts))
	for _, account := range manager.portalAccounts {
		accounts = append(accounts, account)
	}
	manager.mu.Unlock()

	raw, err := json.Marshal(portalConfig{
		Title:    "Virtual Shizzi Router",
		Accounts: accounts,
		Modules: &portalModules{
			MessengerEnabled: false,
			MediaEnabled:     false,
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	manager.setPortalConfig(true, string(raw))

	if len(manager.portalAuthorized) != len(devices) {
		t.Fatalf("module change dropped sessions: got %d want %d", len(manager.portalAuthorized), len(devices))
	}

	for _, device := range devices {
		if !manager.waitAllowed(device.IP, directionDownload, 1) {
			t.Fatalf("%s lost Internet when optional modules were disabled", device.Name)
		}

		status := manager.portalStatus(device.IP)
		if !status.Authenticated || !status.Authorized {
			t.Fatalf("%s lost its account/Internet session: %+v", device.Name, status)
		}
		if status.MessengerEnabled || status.MediaEnabled {
			t.Fatalf("%s received wrong module flags: %+v", device.Name, status)
		}

		httpStatus, _ := messengerRequest(
			t,
			manager,
			device.IP,
			http.MethodGet,
			"/api/v1/messenger/me",
			"",
		)
		if !strings.Contains(httpStatus, "404") {
			t.Fatalf("%s Messenger status=%s, want 404", device.Name, httpStatus)
		}
	}
}
