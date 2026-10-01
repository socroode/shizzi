package datapath

import (
	"encoding/json"
	"io"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"
)

func messengerManagerForTest(t *testing.T) *TrafficManager {
	t.Helper()
	shizziMessenger = newMessengerHub()

	account := func(number, name string) PortalAccount {
		salt := "salt-" + number
		return PortalAccount{
			Number: number,
			Name: name,
			PinSalt: salt,
			PinHash: hashPortalPin(salt, "1234"),
			Enabled: true,
			DataBalanceBytes: 1_000_000,
			DataValidUntilMillis: time.Now().Add(time.Hour).UnixMilli(),
			DataDownloadBitsPerSecond: 2_000_000,
			DataUploadBitsPerSecond: 1_000_000,
		}
	}
	raw, err := json.Marshal(portalConfig{
		Title: "Messenger Test",
		Accounts: []PortalAccount{
			account("1001", "RONIU"),
			account("1002", "TERA"),
			account("1003", "TEIVA"),
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	manager := newTrafficManager()
	manager.setPortalConfig(true, string(raw))
	return manager
}

func messengerRequest(
	t *testing.T,
	manager *TrafficManager,
	ip, method, path, body string,
) (string, map[string]any) {
	t.Helper()
	server, client := net.Pipe()
	defer client.Close()

	request, err := http.NewRequest(method, "http://192.0.2.1"+path, strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}

	done := make(chan struct{})
	go func() {
		defer close(done)
		_ = manager.serveMessengerAPI(server, request, ip)
		_ = server.Close()
	}()

	raw, err := io.ReadAll(client)
	if err != nil {
		t.Fatal(err)
	}
	<-done
	parts := strings.SplitN(string(raw), "\r\n\r\n", 2)
	if len(parts) != 2 {
		t.Fatalf("invalid response: %q", string(raw))
	}
	statusLine := strings.SplitN(parts[0], "\r\n", 2)[0]
	payload := map[string]any{}
	if err := json.Unmarshal([]byte(parts[1]), &payload); err != nil {
		t.Fatalf("invalid json: %v body=%q", err, parts[1])
	}
	return statusLine, payload
}

func TestMessengerRequiresLoggedInAccount(t *testing.T) {
	manager := messengerManagerForTest(t)
	status, _ := messengerRequest(
		t, manager, "192.168.7.50", http.MethodGet,
		"/api/v1/messenger/me", "",
	)
	if !strings.Contains(status, "401") {
		t.Fatalf("status=%s, want 401", status)
	}

	ok, message := manager.submitPortalAccountLogin("192.168.7.50", "1001", "1234")
	if !ok {
		t.Fatalf("login failed: %s", message)
	}
	status, payload := messengerRequest(
		t, manager, "192.168.7.50", http.MethodGet,
		"/api/v1/messenger/me", "",
	)
	if !strings.Contains(status, "200") || payload["ok"] != true {
		t.Fatalf("status=%s payload=%v", status, payload)
	}
}

func TestMessengerDirectMessageIsAccountScoped(t *testing.T) {
	manager := messengerManagerForTest(t)
	if ok, msg := manager.submitPortalAccountLogin("192.168.7.51", "1001", "1234"); !ok {
		t.Fatal(msg)
	}
	if ok, msg := manager.submitPortalAccountLogin("192.168.7.52", "1002", "1234"); !ok {
		t.Fatal(msg)
	}

	status, payload := messengerRequest(
		t, manager, "192.168.7.51", http.MethodPost,
		"/api/v1/messenger/messages",
		`{"to":"1002","text":"Ia ora na"}`,
	)
	if !strings.Contains(status, "200") || payload["ok"] != true {
		t.Fatalf("send failed: %s %v", status, payload)
	}

	_, inbox := messengerRequest(
		t, manager, "192.168.7.52", http.MethodGet,
		"/api/v1/messenger/messages?peer=1001&after=0", "",
	)
	messages, ok := inbox["messages"].([]any)
	if !ok || len(messages) != 1 {
		t.Fatalf("messages=%v", inbox["messages"])
	}

	// A third account must not inherit that direct conversation.
	if ok, msg := manager.submitPortalAccountLogin("192.168.7.53", "1003", "1234"); !ok {
		t.Fatal(msg)
	}
	_, other := messengerRequest(
		t, manager, "192.168.7.53", http.MethodGet,
		"/api/v1/messenger/messages?peer=1001&after=0", "",
	)
	third, ok := other["messages"].([]any)
	if !ok || len(third) != 0 {
		t.Fatalf("third account leaked messages: %v", other["messages"])
	}
}

func TestMessengerGroupAndCallSignalStayInsideMembers(t *testing.T) {
	manager := messengerManagerForTest(t)
	if ok, msg := manager.submitPortalAccountLogin("192.168.7.61", "1001", "1234"); !ok {
		t.Fatal(msg)
	}
	if ok, msg := manager.submitPortalAccountLogin("192.168.7.62", "1002", "1234"); !ok {
		t.Fatal(msg)
	}
	if ok, msg := manager.submitPortalAccountLogin("192.168.7.63", "1003", "1234"); !ok {
		t.Fatal(msg)
	}

	_, created := messengerRequest(
		t, manager, "192.168.7.61", http.MethodPost,
		"/api/v1/messenger/groups",
		`{"name":"Famille","members":["1002"]}`,
	)
	group := created["group"].(map[string]any)
	groupID := group["id"].(string)

	// Non-member 1003 cannot signal a group call into the room.
	status, _ := messengerRequest(
		t, manager, "192.168.7.63", http.MethodPost,
		"/api/v1/messenger/signal",
		`{"to":"1002","kind":"offer","callId":"c1","media":"video","groupId":"`+groupID+`","sdp":"test"}`,
	)
	if !strings.Contains(status, "403") {
		t.Fatalf("non-member signal status=%s, want 403", status)
	}

	status, _ = messengerRequest(
		t, manager, "192.168.7.61", http.MethodPost,
		"/api/v1/messenger/signal",
		`{"to":"1002","kind":"offer","callId":"c2","media":"video","groupId":"`+groupID+`","sdp":"test"}`,
	)
	if !strings.Contains(status, "200") {
		t.Fatalf("member signal status=%s, want 200", status)
	}
}
