package datapath

import (
    "encoding/json"
    "net/http"
    "net/http/httptest"
    "net/netip"
    "net/url"
    "strings"
    "testing"
    "time"

    "dev.shizzi/datapath/core"
)

const v2MacA = "02:11:22:33:44:55"
const v2MacB = "02:66:77:88:99:aa"

func v2WriteAccounts(t *testing.T, manager *TrafficManager, accounts ...PortalAccount) {
    t.Helper()
    payload, err := json.Marshal(portalConfig{Accounts: accounts, Title: "Shizzi"})
    if err != nil { t.Fatal(err) }
    manager.setPortalConfig(true, string(payload))
}

func v2Account(number, pin string, now time.Time) PortalAccount {
    return PortalAccount{
        Number: number, Name: "Abonné", PinSalt: "unique-per-account-salt",
        PinHash: hashPortalPin("unique-per-account-salt", pin), Enabled: true,
        DataBalanceBytes: 12_000_000_000, DataValidUntilMillis: now.Add(30*24*time.Hour).UnixMilli(),
        DataDownloadBitsPerSecond: 2_000_000, DataUploadBitsPerSecond: 1_000_000,
        MediaUntilMillis: now.Add(7*24*time.Hour).UnixMilli(),
    }
}

func v2Authorize(t *testing.T, b *v2AccountBridge, ip, pin string) int {
    t.Helper()
    endpoint := core.PortalEndpoint{Sessions: b.registry, Verifier: b}
    form := url.Values{"account": {"1001"}, "pin": {pin}}
    request := httptest.NewRequest(http.MethodPost, "http://192.168.247.1:8093/login", strings.NewReader(form.Encode()))
    request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
    request.RemoteAddr = ip+":40555"
    response := httptest.NewRecorder()
    endpoint.ServeHTTP(response, request)
    return response.Code
}

func TestV2RealLegacyPINAndVoucherDataBridge(t *testing.T) {
    now := time.Now()
    manager := newTrafficManager()
    bridge := newV2AccountBridge(manager)
    account := v2Account("1001", "471102", now)
    v2WriteAccounts(t, manager, account)
    bridge.Sync()

    ip := netip.MustParseAddr("192.168.247.161")
    if err := bridge.Associated(v2MacA, ip.String()); err != nil { t.Fatal(err) }
    if bridge.registry.AuthorizeDirect(ip, core.Portal, now) != core.Allow {
        t.Fatal("homepage must be public even before PIN login")
    }
    if bridge.registry.AuthorizeDirect(ip, core.Internet, now) != core.DenyLoginRequired {
        t.Fatal("unverified user got Internet")
    }

    if got := v2Authorize(t, bridge, ip.String(), "wrong"); got != http.StatusForbidden {
        t.Fatalf("bad PIN returned %d", got)
    }
    if got := v2Authorize(t, bridge, ip.String(), "471102"); got != http.StatusOK {
        t.Fatalf("correct legacy PIN returned %d", got)
    }
    if got := bridge.registry.AuthorizeDirect(ip, core.Internet, now); got != core.Allow {
        t.Fatalf("valid Data voucher denied: %s", got)
    }
    if got := bridge.registry.AuthorizeDirect(ip, core.Media, now); got != core.Allow {
        t.Fatalf("valid Media voucher denied: %s", got)
    }

    // Media survives loss of paid Internet, without logging the station out.
    account.DataBalanceBytes = 0
    v2WriteAccounts(t, manager, account)
    bridge.Sync()
    if got := bridge.registry.AuthorizeDirect(ip, core.Internet, now); got != core.DenyVoucherRequired {
        t.Fatalf("exhausted Data still works: %s", got)
    }
    if got := bridge.registry.AuthorizeDirect(ip, core.Media, now); got != core.Allow {
        t.Fatalf("valid Media was incorrectly disconnected: %s", got)
    }
    if got := bridge.registry.AuthorizeDirect(ip, core.Portal, now); got != core.Allow {
        t.Fatalf("local portal revoked when Internet ended: %s", got)
    }
}

func TestV2TakeoverSleepAndWifiLoss(t *testing.T) {
    now := time.Now()
    manager := newTrafficManager()
    bridge := newV2AccountBridge(manager)
    account := v2Account("1001", "4444", now)
    account.UnlimitedUntilMillis = now.Add(30*24*time.Hour).UnixMilli()
    v2WriteAccounts(t, manager, account)
    bridge.Sync()
    if err := bridge.Associated(v2MacA, "192.168.247.161"); err != nil { t.Fatal(err) }
    if err := bridge.Associated(v2MacB, "192.168.247.162"); err != nil { t.Fatal(err) }
    a := netip.MustParseAddr("192.168.247.161")
    b := netip.MustParseAddr("192.168.247.162")
    if v2Authorize(t, bridge, a.String(), "4444") != http.StatusOK { t.Fatal("A login rejected") }

    // A repeated event, similar to screen sleep, never revokes a session.
    if err := bridge.Associated(v2MacA, a.String()); err != nil { t.Fatal(err) }
    if got := bridge.registry.AuthorizeDirect(a, core.Internet, now.Add(8*time.Hour)); got != core.Allow {
        t.Fatalf("sleep incorrectly logged out A: %s", got)
    }
    if v2Authorize(t, bridge, b.String(), "4444") != http.StatusOK { t.Fatal("B takeover rejected") }
    if got := bridge.registry.AuthorizeDirect(a, core.Internet, now); got != core.DenyLoginRequired {
        t.Fatalf("A kept account after B takeover: %s", got)
    }
    if got := bridge.registry.AuthorizeDirect(b, core.Internet, now); got != core.Allow {
        t.Fatalf("B cannot access paid Internet: %s", got)
    }
    if err := bridge.Departed(v2MacB); err != nil { t.Fatal(err) }
    if err := bridge.Associated(v2MacB, b.String()); err != nil { t.Fatal(err) }
    if got := bridge.registry.AuthorizeDirect(b, core.Internet, now); got != core.DenyLoginRequired {
        t.Fatalf("Wi-Fi rejoin restored old login: %s", got)
    }
}

func TestV2PINRotationAndAccountSuspension(t *testing.T) {
    now := time.Now()
    manager := newTrafficManager()
    bridge := newV2AccountBridge(manager)
    account := v2Account("1001", "1111", now)
    v2WriteAccounts(t, manager, account)
    bridge.Sync()
    if err := bridge.Associated(v2MacA, "192.168.247.161"); err != nil { t.Fatal(err) }
    if v2Authorize(t, bridge, "192.168.247.161", "1111") != http.StatusOK { t.Fatal("initial login failed") }

    account.PinHash = hashPortalPin(account.PinSalt, "2222")
    v2WriteAccounts(t, manager, account)
    bridge.Sync()
    ip := netip.MustParseAddr("192.168.247.161")
    if got := bridge.registry.AuthorizeDirect(ip, core.Internet, now); got != core.DenyLoginRequired {
        t.Fatalf("PIN rotation kept stale account session: %s", got)
    }
    if v2Authorize(t, bridge, ip.String(), "1111") != http.StatusForbidden { t.Fatal("old PIN works") }
    if v2Authorize(t, bridge, ip.String(), "2222") != http.StatusOK { t.Fatal("new PIN failed") }
    account.Enabled = false
    v2WriteAccounts(t, manager, account)
    bridge.Sync()
    if got := bridge.registry.AuthorizeDirect(ip, core.Media, now); got != core.DenyLoginRequired {
        t.Fatalf("disabled account kept media session: %s", got)
    }
    if got := bridge.registry.AuthorizeDirect(ip, core.Portal, now); got != core.Allow {
        t.Fatalf("disabled account lost public portal: %s", got)
    }
}

func TestV2PINFromSharedNATAndBadClientCannotLogin(t *testing.T) {
    now := time.Now()
    manager := newTrafficManager()
    bridge := newV2AccountBridge(manager)
    v2WriteAccounts(t, manager, v2Account("1001", "12345", now))
    bridge.Sync()
    if err := bridge.Associated(v2MacA, "192.168.247.161"); err != nil { t.Fatal(err) }
    if code := v2Authorize(t, bridge, "192.0.2.2", "12345"); code != http.StatusForbidden {
        t.Fatalf("shared tunnel was allowed to authenticate: %d", code)
    }
    if code := v2Authorize(t, bridge, "192.168.247.169", "12345"); code != http.StatusForbidden {
        t.Fatalf("unknown Wi-Fi client was allowed to authenticate: %d", code)
    }
}

func TestV2DataUsageIsTakenFromLegacyLedger(t *testing.T) {
    now := time.Now()
    manager := newTrafficManager()
    bridge := newV2AccountBridge(manager)
    account := v2Account("1001", "0000", now)
    account.DataBalanceBytes = 500
    v2WriteAccounts(t, manager, account)
    bridge.Sync()
    _ = bridge.Associated(v2MacA, "192.168.247.161")
    if v2Authorize(t, bridge, "192.168.247.161", "0000") != http.StatusOK { t.Fatal("login failed") }

    manager.mu.Lock()
    manager.accountUsage["1001"] = &accountUsage{DataBytes: 500}
    manager.mu.Unlock()
    bridge.Sync()
    ip := netip.MustParseAddr("192.168.247.161")
    if got := bridge.registry.AuthorizeDirect(ip, core.Internet, now); got != core.DenyVoucherRequired {
        t.Fatalf("used-up legacy account not blocked: %s", got)
    }
    account.UnlimitedUntilMillis = now.Add(time.Hour).UnixMilli()
    v2WriteAccounts(t, manager, account)
    bridge.Sync()
    if got := bridge.registry.AuthorizeDirect(ip, core.Internet, now); got != core.Allow {
        t.Fatalf("unlimited voucher not overriding used Data: %s", got)
    }
}
