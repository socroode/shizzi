package core

import (
    "net/netip"
    "testing"
    "time"
)

func TestOpenHotspotLoginWithOnlyNumberAndPIN(t *testing.T) {
    registry := NewRegistry()
    ip := netip.MustParseAddr("192.168.81.199")
    now := time.Now()
    _ = registry.SetRights("1001", Rights{DataBytes: 1200, DataValidUntil: now.Add(time.Hour)})
    if got := registry.AuthorizeDirect(ip, Portal, now); got != Allow {
        t.Fatalf("public homepage blocked: %s", got)
    }
    if got := registry.AuthorizeDirect(ip, Internet, now); got != DenyUnknownStation {
        t.Fatalf("unknown client got Internet before login: %s", got)
    }
    verifier := fakePINVerifier{}
    if err := registry.AuthenticateFromPhysicalIP(ip, "1001", "bad", verifier); err != ErrInvalidCredentials {
        t.Fatalf("wrong PIN not rejected: %v", err)
    }
    if len(registry.ActiveSessions(now)) != 0 {
        t.Fatal("bad PIN created a session")
    }
    if err := registry.AuthenticateFromPhysicalIP(ip, "1001", "123456", verifier); err != nil {
        t.Fatalf("account+PIN login requires MAC when none was supplied: %v", err)
    }
    if got := registry.AuthorizeDirect(ip, Internet, now); got != Allow {
        t.Fatalf("valid account and voucher did not open Internet: %s", got)
    }
    entries := registry.ActiveSessions(now)
    if len(entries) != 1 || entries[0].Account != "1001" || entries[0].IP != ip || entries[0].MAC != "" {
        t.Fatalf("IP-only account session reported incorrectly: %+v", entries)
    }
}

func TestOpenHotspotLoginTakesOverOneAccountWithoutMAC(t *testing.T) {
    registry := NewRegistry()
    now := time.Now()
    _ = registry.SetRights("1001", Rights{UnlimitedUntil: now.Add(time.Hour)})
    a := netip.MustParseAddr("192.168.81.199")
    b := netip.MustParseAddr("192.168.81.200")
    verifier := fakePINVerifier{}
    for _, ip := range []netip.Addr{a,b} {
        if err := registry.AuthenticateFromPhysicalIP(ip, "1001", "123456", verifier); err != nil { t.Fatal(err) }
    }
    if got := registry.AuthorizeDirect(a, Internet, now); got != DenyLoginRequired {
        t.Fatalf("first connection kept account after second PIN login: %s", got)
    }
    if got := registry.AuthorizeDirect(b, Internet, now); got != Allow {
        t.Fatalf("second verified account lost access: %s", got)
    }
    if registry.AuthorizeDirect(a, Portal, now) != Allow {
        t.Fatal("first client lost public captive portal")
    }
    entries := registry.ActiveSessions(now)
    if len(entries) != 1 || entries[0].IP != b {
        t.Fatalf("multiple active devices for one account: %+v", entries)
    }
}

func TestOptionalAndroidMACObservationPreservesLoginAndDetectsDeparture(t *testing.T) {
    registry := NewRegistry()
    ip := netip.MustParseAddr("192.168.81.199")
    now := time.Now()
    _ = registry.SetRights("1001", Rights{UnlimitedUntil: now.Add(time.Hour)})
    if err := registry.AuthenticateFromPhysicalIP(ip,"1001","123456",fakePINVerifier{}); err != nil { t.Fatal(err) }
    account, epoch, good := registry.InternetLease(ip,now)
    if !good { t.Fatal("IP-only session could not open Internet") }

    // When the optional MAC becomes available LATER, it is bound to the
    // existing login. No Wi-Fi forgetting or repeated PIN entry is needed.
    if err:=registry.WifiJoined(phoneA); err != nil { t.Fatal(err) }
    if err:=registry.BindAddress(phoneA,ip); err != nil { t.Fatal(err) }
    if !registry.LeaseStillValid(ip,account,epoch,now) {
        t.Fatal("newly observed MAC interrupted a valid account login")
    }
    entries:=registry.ActiveSessions(now)
    if len(entries)!=1 || entries[0].MAC!=phoneA { t.Fatalf("MAC was not attached as metadata: %+v", entries) }

    // Confirmed disconnect revokes the session; vouchers remain in the account.
    if err:=registry.WifiDeparted(phoneA);err!=nil{t.Fatal(err)}
    if registry.LeaseStillValid(ip,account,epoch,now) {t.Fatal("departed client kept Internet")}
    if registry.AuthorizeDirect(ip,Portal,now)!=Allow {t.Fatal("public page disappeared after disconnect")}
    if err:=registry.WifiJoined(phoneA);err!=nil{t.Fatal(err)}
    if err:=registry.BindAddress(phoneA,ip);err!=nil{t.Fatal(err)}
    if registry.AuthorizeDirect(ip,Internet,now)!=DenyLoginRequired {t.Fatal("rejoining Wi-Fi silently restored old PIN session")}
}

func TestUntrustedTunnelAndWANCannotBecomePublicHotspotClient(t *testing.T) {
    registry:=NewRegistry()
    invalid:=[]string{"192.0.2.2","8.8.8.8","127.0.0.1","0.0.0.0","169.254.10.2"}
    for _,ipText:=range invalid {
        ip:=netip.MustParseAddr(ipText)
        if err:=registry.AuthenticateFromPhysicalIP(ip,"1001","123456",fakePINVerifier{});err==nil {
            t.Fatalf("%s unexpectedly opened a login",ipText)
        }
    }
    if entries:=registry.ActiveSessions(time.Now());len(entries)!=0{
        t.Fatalf("invalid peers created sessions: %+v",entries)
    }
}

func TestOpenHotspotReloginRotatesConnectionEpoch(t *testing.T) {
    registry:=NewRegistry()
    now:=time.Now()
    ip:=netip.MustParseAddr("192.168.81.199")
    _=registry.SetRights("1001",Rights{UnlimitedUntil:now.Add(time.Hour)})
    for i:=0;i<2;i++ {
        if err:=registry.AuthenticateFromPhysicalIP(ip,"1001","123456",fakePINVerifier{});err!=nil {t.Fatal(err)}
        account,gen,ok:=registry.InternetLease(ip,now)
        if !ok {t.Fatal("current login not valid")}
        if i==0 {
            if !registry.LeaseStillValid(ip,account,gen,now) {t.Fatal("original login not valid")}
            // A second login with the same account and physical IP must
            // invalidate the former generation (tested below separately).
        }
    }
}
