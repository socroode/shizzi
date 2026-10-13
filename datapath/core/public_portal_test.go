package core

import (
    "errors"
    "net/netip"
    "net/http"
    "net/http/httptest"
    "net/url"
    "strings"
    "testing"
    "time"
)

type fakePINVerifier struct {
    onVerify func()
}
func (v fakePINVerifier) VerifyPIN(account, pin string) bool {
    if v.onVerify != nil { v.onVerify() }
    return account == "1001" && pin == "123456"
}

func TestPublicPortalDoesNotNeedNATOrAccount(t *testing.T) {
    sessions := NewRegistry()
    ip := netip.MustParseAddr("192.168.247.200")
    if decision := sessions.AuthorizeDirect(ip, Portal, time.Now()); decision != Allow {
        t.Fatalf("anonymous client cannot see local portal: %s", decision)
    }
    if decision := sessions.AuthorizeDirect(ip, Internet, time.Now()); decision != DenyUnknownStation {
        t.Fatalf("anonymous client accessed Internet: %s", decision)
    }

    gate := Gate{Sessions: sessions, NAT: NewNATIndex(time.Second)}
    key := FlowKey{
        Protocol: "tcp",
        Translated: netip.MustParseAddrPort("192.0.2.2:38924"),
        Destination: netip.MustParseAddrPort("192.0.2.1:80"),
    }
    decision, why := gate.CheckTranslated(key, Portal, time.Now())
    if decision != Allow || why != MissingNAT {
        t.Fatalf("portal blocked by absent NAT %s %s", decision, why)
    }
    decision, _ = gate.CheckTranslated(key, Internet, time.Now())
    if decision != DenyUnknownStation { t.Fatalf("no voucher client was allowed: %s", decision) }

    key.Destination = netip.MustParseAddrPort("8.8.8.8:80")
    decision, _ = gate.CheckTranslated(key, Portal, time.Now())
    if decision != DenyUnknownStation { t.Fatal("unrelated HTTP got portal exemption") }
    key.Destination = netip.MustParseAddrPort("192.0.2.1:80")
    key.Protocol = "udp"
    decision, _ = gate.CheckTranslated(key, Portal, time.Now())
    if decision != DenyUnknownStation { t.Fatal("UDP got portal HTTP exemption") }
}

func TestPublicPortalServesFormBeforeDeviceIsRecognized(t *testing.T) {
    endpoint := PortalEndpoint{}
    request := httptest.NewRequest(http.MethodGet, "http://192.0.2.1/", nil)
    request.RemoteAddr = "192.0.2.2:38924"
    response := httptest.NewRecorder()
    endpoint.ServeHTTP(response, request)
    if response.Code != http.StatusOK ||
        !strings.Contains(response.Body.String(), "Numéro de compte") ||
        !strings.Contains(response.Body.String(), "Code PIN") ||
        !strings.Contains(response.Body.String(), "action=\"/login\"") {
        t.Fatalf("anonymous portal unavailable: %d %s", response.Code, response.Body.String())
    }
    if response.Header().Get("Cache-Control") == "" { t.Fatal("portal must prevent cache") }
}

func postLogin(endpoint PortalEndpoint, peerIP, pin string) *httptest.ResponseRecorder {
    form := url.Values{"account": {"1001"}, "pin": {pin}}
    request := httptest.NewRequest(http.MethodPost, "http://192.0.2.1/login", strings.NewReader(form.Encode()))
    request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
    request.RemoteAddr = peerIP + ":40000"
    response := httptest.NewRecorder()
    endpoint.ServeHTTP(response, request)
    return response
}

func TestPINNeverAuthorizesSharedTunnelOrWrongCredentials(t *testing.T) {
    sessions := NewRegistry()
    now := time.Now()
    addStation(t, sessions, phoneA, "192.168.247.161")
    endpoint := PortalEndpoint{Sessions: sessions, Verifier: fakePINVerifier{}}
    response := postLogin(endpoint, "192.0.2.2", "123456")
    if response.Code != http.StatusForbidden { t.Fatalf("shared NAT logged in: %d", response.Code) }
    response = postLogin(endpoint, "192.168.247.161", "wrong")
    if response.Code != http.StatusForbidden { t.Fatalf("bad PIN logged in: %d", response.Code) }
    if got := sessions.AuthorizeDirect(mustIP("192.168.247.161"), Internet, now); got != DenyLoginRequired {
        t.Fatalf("wrong/shared login granted Internet: %s", got)
    }

    response = postLogin(endpoint, "192.168.247.161", "123456")
    if response.Code != http.StatusOK { t.Fatalf("correct PIN failed: %d %s", response.Code, response.Body.String()) }
    if got := sessions.AuthorizeDirect(mustIP("192.168.247.161"), Internet, now); got != DenyVoucherRequired {
        t.Fatalf("correct PIN without voucher granted Internet: %s", got)
    }
    _ = sessions.SetRights("1001", Rights{UnlimitedUntil: now.Add(time.Hour)})
    if got := sessions.AuthorizeDirect(mustIP("192.168.247.161"), Internet, now); got != Allow {
        t.Fatalf("PIN + voucher denied Internet: %s", got)
    }
}

func TestXForwardedForCannotImpersonateRealWifiClient(t *testing.T) {
    sessions := NewRegistry()
    addStation(t, sessions, phoneA, "192.168.247.161")
    endpoint := PortalEndpoint{Sessions: sessions, Verifier: fakePINVerifier{}}
    form := url.Values{"account": {"1001"}, "pin": {"123456"}}
    request := httptest.NewRequest(http.MethodPost, "http://192.0.2.1/login", strings.NewReader(form.Encode()))
    request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
    request.Header.Set("X-Forwarded-For", "192.168.247.161")
    request.RemoteAddr = "192.0.2.2:57000"
    response := httptest.NewRecorder()
    endpoint.ServeHTTP(response, request)
    if response.Code != http.StatusForbidden { t.Fatal("proxy header spoofed hotspot identity") }
}

func TestWifiDepartureDuringPINVerificationFailsClosed(t *testing.T) {
    sessions := NewRegistry()
    addStation(t, sessions, phoneA, "192.168.247.161")
    verifier := fakePINVerifier{onVerify: func() {
        _ = sessions.WifiDeparted(phoneA)
        _ = sessions.WifiJoined(phoneA)
        _ = sessions.BindAddress(phoneA, mustIP("192.168.247.161"))
    }}
    err := sessions.AuthenticateFromPhysicalIP(mustIP("192.168.247.161"), "1001", "123456", verifier)
    if !errors.Is(err, ErrLoginDenied) { t.Fatalf("old session survived departure: %v", err) }
    snapshot, ok := sessions.Snapshot(phoneA)
    if !ok || snapshot.Account != "" { t.Fatal("returned station received old authentication") }
}

func TestOtherSitesCannotPOSTCredentialsToPortal(t *testing.T) {
    sessions := NewRegistry()
    addStation(t, sessions, phoneA, "192.168.247.161")
    endpoint := PortalEndpoint{Sessions: sessions, Verifier: fakePINVerifier{}}
    form := url.Values{"account": {"1001"}, "pin": {"123456"}}
    request := httptest.NewRequest(http.MethodPost, "http://192.0.2.1/login", strings.NewReader(form.Encode()))
    request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
    request.Header.Set("Origin", "http://evil.example")
    request.RemoteAddr = "192.168.247.161:1234"
    response := httptest.NewRecorder()
    endpoint.ServeHTTP(response, request)
    if response.Code != http.StatusForbidden { t.Fatal("cross-origin PIN login authorized") }
}

func TestTrustedCrossSiteCaptiveOriginMayLoginPhysicalHotspot(t *testing.T) {
    sessions := NewRegistry()
    addStation(t, sessions, phoneA, "192.168.247.161")
    endpoint := PortalEndpoint{Sessions: sessions, Verifier: fakePINVerifier{}}
    form := url.Values{"account": {"1001"}, "pin": {"123456"}}
    request := httptest.NewRequest(http.MethodPost,
        "http://192.168.247.1:8093/login", strings.NewReader(form.Encode()))
    request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
    request.Header.Set("Origin", "http://192.0.2.1")
    request.Header.Set("Sec-Fetch-Site", "cross-site")
    request.RemoteAddr = "192.168.247.161:45678"
    response := httptest.NewRecorder()
    endpoint.ServeHTTP(response, request)
    if response.Code != http.StatusOK {
        t.Fatalf("own public captive origin refused: status %d, body %s",
            response.Code, response.Body.String())
    }
    snap, found := sessions.Snapshot(phoneA)
    if !found || snap.Account != "1001" {
        t.Fatalf("valid PIN did not open local account session: %+v", snap)
    }
}

func TestOtherCrossSiteOriginCannotLoginPhysicalHotspot(t *testing.T) {
    sessions := NewRegistry()
    addStation(t, sessions, phoneA, "192.168.247.161")
    endpoint := PortalEndpoint{Sessions: sessions, Verifier: fakePINVerifier{}}
    form := url.Values{"account": {"1001"}, "pin": {"123456"}}
    request := httptest.NewRequest(http.MethodPost,
        "http://192.168.247.1:8093/login", strings.NewReader(form.Encode()))
    request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
    request.Header.Set("Origin", "http://attacker.example")
    request.Header.Set("Sec-Fetch-Site", "cross-site")
    request.RemoteAddr = "192.168.247.161:45678"
    response := httptest.NewRecorder()
    endpoint.ServeHTTP(response, request)
    if response.Code != http.StatusForbidden {
        t.Fatalf("untrusted origin logged in using victim's browser: %d", response.Code)
    }
}
