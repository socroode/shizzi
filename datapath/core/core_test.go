package core

import (
    "net/netip"
    "testing"
    "time"
)

const (
    phoneA = "02:11:22:33:44:55"
    phoneB = "02:66:77:88:99:aa"
    phoneC = "02:cc:dd:ee:12:34"
)

func mustIP(value string) netip.Addr { return netip.MustParseAddr(value) }

func addStation(t *testing.T, r *Registry, mac, ip string) {
    t.Helper()
    if err := r.WifiJoined(mac); err != nil { t.Fatal(err) }
    if err := r.BindAddress(mac, mustIP(ip)); err != nil { t.Fatal(err) }
}

func TestWifiPresenceNotInactivityControlsSession(t *testing.T) {
    r := NewRegistry()
    now := time.Date(2026, 10, 12, 20, 0, 0, 0, time.UTC)
    addStation(t, r, phoneA, "192.168.247.161")
    if err := r.SetRights("1001", Rights{UnlimitedUntil: now.Add(30 * 24 * time.Hour)}); err != nil { t.Fatal(err) }
    if err := r.GrantVerifiedLogin(phoneA, "1001"); err != nil { t.Fatal(err) }
    ip := mustIP("192.168.247.161")

    // A sleeping phone, a repeated Wi-Fi join event and a DHCP refresh
    // must not cause a logout or a forced Wi-Fi reconnection.
    if err := r.WifiJoined(phoneA); err != nil { t.Fatal(err) }
    if err := r.BindAddress(phoneA, ip); err != nil { t.Fatal(err) }
    if got := r.AuthorizeDirect(ip, Internet, now.Add(9*time.Hour)); got != Allow {
        t.Fatalf("inactivity disconnected authorized user: %s", got)
    }

    if err := r.WifiDeparted(phoneA); err != nil { t.Fatal(err) }
    if got := r.AuthorizeDirect(ip, Internet, now); got != DenyUnknownStation {
        t.Fatalf("departed station still online: %s", got)
    }
    addStation(t, r, phoneA, "192.168.247.161")
    if got := r.AuthorizeDirect(ip, Internet, now); got != DenyLoginRequired {
        t.Fatalf("returning station restored old login: %s", got)
    }
}

func TestVoucherInternetSeparateFromLocalMedia(t *testing.T) {
    r := NewRegistry()
    now := time.Now()
    addStation(t, r, phoneA, "192.168.247.161")
    _ = r.GrantVerifiedLogin(phoneA, "1001")
    ip := mustIP("192.168.247.161")
    if got := r.AuthorizeDirect(ip, Portal, now); got != Allow { t.Fatal(got) }
    if got := r.AuthorizeDirect(ip, Media, now); got != DenyVoucherRequired { t.Fatal(got) }

    _ = r.SetRights("1001", Rights{
        DataBytes: 0,
        DataValidUntil: now.Add(time.Hour),
        MediaUntil: now.Add(24*time.Hour),
    })
    if got := r.AuthorizeDirect(ip, Internet, now); got != DenyVoucherRequired { t.Fatal(got) }
    if got := r.AuthorizeDirect(ip, Media, now); got != Allow { t.Fatal(got) }
    if got := r.AuthorizeDirect(ip, Portal, now); got != Allow { t.Fatal(got) }

    _ = r.SetRights("1001", Rights{
        DataBytes: 12_000_000_000,
        DataValidUntil: now.Add(time.Hour),
        UnlimitedUntil: now.Add(2*time.Hour),
        MediaUntil: now.Add(24*time.Hour),
    })
    if got := r.AuthorizeDirect(ip, Internet, now.Add(90*time.Minute)); got != Allow { t.Fatal(got) }
    if got := r.AuthorizeDirect(ip, Internet, now.Add(3*time.Hour)); got != DenyVoucherRequired { t.Fatal(got) }
}

func TestPortableAccountOneActiveStation(t *testing.T) {
    r := NewRegistry()
    now := time.Now()
    addStation(t, r, phoneA, "192.168.247.161")
    addStation(t, r, phoneB, "192.168.247.162")
    _ = r.SetRights("1001", Rights{UnlimitedUntil: now.Add(time.Hour)})
    _ = r.GrantVerifiedLogin(phoneA, "1001")
    _ = r.GrantVerifiedLogin(phoneB, "1001")
    if got := r.AuthorizeDirect(mustIP("192.168.247.161"), Internet, now); got != DenyLoginRequired {
        t.Fatalf("old device kept portable account: %s", got)
    }
    if got := r.AuthorizeDirect(mustIP("192.168.247.162"), Internet, now); got != Allow {
        t.Fatalf("new device not authorized: %s", got)
    }
    if got := r.AuthorizeDirect(mustIP("192.168.247.161"), Portal, now); got != Allow {
        t.Fatalf("old device lost local portal: %s", got)
    }
}

func TestAddressReuseCannotStealSession(t *testing.T) {
    r := NewRegistry()
    now := time.Now()
    addStation(t, r, phoneA, "192.168.247.161")
    _ = r.SetRights("1001", Rights{UnlimitedUntil: now.Add(time.Hour)})
    _ = r.GrantVerifiedLogin(phoneA, "1001")
    // Android allocates this IP to a different associated station.
    addStation(t, r, phoneB, "192.168.247.161")
    if got := r.AuthorizeDirect(mustIP("192.168.247.161"), Internet, now); got != DenyLoginRequired {
        t.Fatalf("DHCP reuse inherited authorization: %s", got)
    }
    a, ok := r.Snapshot(phoneA)
    if !ok || a.Account != "" || a.IP.IsValid() {
        t.Fatalf("old session not revoked on conflicting verified IP: %+v", a)
    }
}

func TestUnauthenticatedStationAndInvalidBindings(t *testing.T) {
    r := NewRegistry()
    if err := r.GrantVerifiedLogin(phoneA, "1001"); err != ErrStationAbsent { t.Fatal(err) }
    if err := r.WifiJoined(phoneA); err != nil { t.Fatal(err) }
    if err := r.GrantVerifiedLogin(phoneA, "1001"); err != ErrUnboundStation { t.Fatal(err) }
    if err := r.BindAddress(phoneA, mustIP("127.0.0.1")); err != ErrInvalidIP { t.Fatal(err) }
    if err := r.WifiJoined("00:00:00:00:00:00"); err != ErrInvalidStation { t.Fatal(err) }
    // The local landing page is public on the hotspot, while Internet
    // always remains gated by a verified account session and voucher.
    if got := r.AuthorizeDirect(mustIP("192.168.247.200"), Portal, time.Now()); got != Allow {
        t.Fatalf("anonymous hotspot portal rejected: %s", got)
    }
    if got := r.AuthorizeDirect(mustIP("192.168.247.200"), Internet, time.Now()); got != DenyUnknownStation {
        t.Fatalf("anonymous Internet accepted: %s", got)
    }
}

const sampleNAT = "IPv4 Upstream:\n" +
    "tcp [de:a5:9e:b6:5c:83] 41(41) 192.168.247.161:40766 -> 42(testtun0) 192.0.2.2:40766 -> 14.29.106.145:443 [00:00:00:00:00:00] 1500 -\n" +
    "tcp [de:a5:9e:b6:5c:83] 41(41) 192.168.247.162:40766 -> 42(testtun0) 192.0.2.2:40766 -> 8.8.8.8:443 [00:00:00:00:00:00] 1500 -\n" +
    "IPv4 Downstream:\n" +
    "tcp [00:00:00:00:00:00] 42(testtun0) 14.29.106.145:443 -> 41(41) 192.0.2.2:40766 -> 192.168.247.163:40766 [de:a5:9e:b6:5c:83] 1500 -\n"

func keyTo(dst string) FlowKey {
    return FlowKey{Protocol: "tcp",
        Translated: netip.MustParseAddrPort("192.0.2.2:40766"),
        Destination: netip.MustParseAddrPort(dst)}
}

func TestStrictNATMatchingWithTwoClients(t *testing.T) {
    idx := NewNATIndex(2*time.Second)
    at := time.Now()
    if count := idx.Replace(sampleNAT, at); count != 2 { t.Fatalf("got %d entries", count) }

    a, reason := idx.ResolveExact(keyTo("14.29.106.145:443"), at.Add(time.Second))
    if a != mustIP("192.168.247.161") || reason != ExactNAT { t.Fatalf("%s %s", a, reason) }
    b, reason := idx.ResolveExact(keyTo("8.8.8.8:443"), at.Add(time.Second))
    if b != mustIP("192.168.247.162") || reason != ExactNAT { t.Fatalf("%s %s", b, reason) }
    _, reason = idx.ResolveExact(keyTo("1.1.1.1:443"), at.Add(time.Second))
    if reason != MissingNAT { t.Fatal(reason) } // same translated port is NOT enough
    _, reason = idx.ResolveExact(keyTo("8.8.8.8:443"), at.Add(3*time.Second))
    if reason != StaleNAT { t.Fatal(reason) }
    idx.Invalidate()
    _, reason = idx.ResolveExact(keyTo("8.8.8.8:443"), at)
    if reason != StaleNAT { t.Fatal(reason) }
}

func TestConflictingNATIsRejected(t *testing.T) {
    idx := NewNATIndex(time.Second)
    at := time.Now()
    conflicting := "IPv4 Upstream:\n" +
        "tcp [aa] 41(41) 192.168.247.161:42 -> 42(testtun0) 192.0.2.2:40766 -> 14.29.106.145:443 [bb]\n" +
        "tcp [cc] 41(41) 192.168.247.162:42 -> 42(testtun0) 192.0.2.2:40766 -> 14.29.106.145:443 [dd]\n"
    idx.Replace(conflicting, at)
    _, reason := idx.ResolveExact(keyTo("14.29.106.145:443"), at)
    if reason != ConflictingNAT { t.Fatal(reason) }
}

func TestGateRejectsUnknownBeforeInternet(t *testing.T) {
    r := NewRegistry()
    idx := NewNATIndex(time.Second)
    now := time.Now()
    addStation(t, r, phoneA, "192.168.247.161")
    _ = r.GrantVerifiedLogin(phoneA, "1001")
    _ = r.SetRights("1001", Rights{UnlimitedUntil: now.Add(time.Hour)})
    gate := Gate{Sessions: r, NAT: idx}
    decision, why := gate.CheckTranslated(keyTo("14.29.106.145:443"), Internet, now)
    if decision != DenyUnknownStation || why != StaleNAT { t.Fatalf("%s %s", decision, why) }
    idx.Replace(sampleNAT, now)
    decision, why = gate.CheckTranslated(keyTo("14.29.106.145:443"), Internet, now)
    if decision != Allow || why != ExactNAT { t.Fatalf("%s %s", decision, why) }
    decision, _ = gate.CheckTranslated(keyTo("8.8.8.8:443"), Internet, now)
    if decision != DenyUnknownStation { t.Fatal(decision) }
}
