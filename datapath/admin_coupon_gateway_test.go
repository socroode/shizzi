package datapath

import (
	"bufio"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"
)

// Cover the real HTTP boundary that previously rejected valid coupon commands
// before the Android command dispatcher ever saw them.
func TestAdminCouponHTTPGateway(t *testing.T) {
	for _, tc := range []struct {
		name   string
		action string
		params string
		authed bool
		status int
	}{
		{"data", "account.coupon.issue", `{"number":"1001","offerId":"eco-12"}`, true, http.StatusAccepted},
		{"unlimited", "account.coupon.issue", `{"number":"1001","offerId":"unlimited-eco"}`, true, http.StatusAccepted},
		{"media", "account.coupon.media.issue", `{"number":"1001","offerId":"media-day"}`, true, http.StatusAccepted},
		{"existing-code", "account.coupon.redeem", `{"number":"1001","code":"COUPON"}`, true, http.StatusAccepted},
		{"no-auth-issue", "account.coupon.issue", "{}", false, http.StatusUnauthorized},
		{"no-auth-media", "account.coupon.media.issue", "{}", false, http.StatusUnauthorized},
		{"no-auth-redeem", "account.coupon.redeem", "{}", false, http.StatusUnauthorized},
		{"unknown-action", "account.coupon.delete", "{}", true, http.StatusForbidden},
	} {
		t.Run(tc.name, func(t *testing.T) {
			manager := newTrafficManager()
			manager.adminConfig.Enabled = true
			const ip = "192.168.7.66"
			const token = "coupon-admin-session"
			if tc.authed {
				manager.adminSessions[token] = &adminSession{
					Token: token, IP: ip, LastSeenMillis: time.Now().UnixMilli(),
				}
			}
			payload, err := json.Marshal(map[string]any{
				"action": tc.action, "params": json.RawMessage(tc.params),
			})
			if err != nil {
				t.Fatal(err)
			}
			request, err := http.NewRequest(http.MethodPost,
				"http://192.0.2.1/api/v1/admin/command", strings.NewReader(string(payload)))
			if err != nil {
				t.Fatal(err)
			}
			request.Header.Set("X-Shizzi-Admin-Token", token)
			server, client := net.Pipe()
			defer client.Close()
			_ = client.SetDeadline(time.Now().Add(3 * time.Second))
			done := make(chan bool, 1)
			go func() {
				defer server.Close()
				done <- manager.serveAdminAPI(server, request, ip)
			}()
			response, err := http.ReadResponse(bufio.NewReader(client), request)
			if err != nil {
				t.Fatal(err)
			}
			body, err := io.ReadAll(response.Body)
			_ = response.Body.Close()
			if err != nil {
				t.Fatal(err)
			}
			if !<-done {
				t.Fatal("admin command endpoint was not handled")
			}
			if response.StatusCode != tc.status {
				t.Fatalf("HTTP %d, want %d: %s", response.StatusCode, tc.status, body)
			}
			if tc.status != http.StatusAccepted {
				if len(manager.adminCommands) != 0 {
					t.Fatal("rejected command reached the Android queue")
				}
				return
			}
			var reply struct {
				OK      bool   `json:"ok"`
				ID      string `json:"id"`
				Pending bool   `json:"pending"`
			}
			if err := json.Unmarshal(body, &reply); err != nil {
				t.Fatal(err)
			}
			if !reply.OK || !reply.Pending || reply.ID == "" || len(manager.adminCommands) != 1 {
				t.Fatalf("missing accepted command: %s", body)
			}
			queued := manager.adminCommands[0]
			if queued.ID != reply.ID || queued.Action != tc.action || queued.IP != ip ||
				string(queued.Params) != tc.params {
				t.Fatalf("command changed in transit: %+v", queued)
			}
		})
	}
}
