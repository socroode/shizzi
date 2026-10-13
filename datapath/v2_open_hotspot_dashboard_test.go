package datapath

import (
    "encoding/json"
    "net/netip"
    "testing"
    "time"
)

// A public hotspot must list account sessions based on the verified login,
// even if Android's MAC client list and tethering DHCP dump are unavailable.
// The legacy 0.4.x dashboard previously showed "Aucune session enregistrée"
// after successful V2 sessions because it queried only portalAuthorized.
func TestV2DevicesDashboardShowsAccountLoginWithoutMAC(t *testing.T) {
    manager:=newTrafficManager()
    bridge:=newV2AccountBridge(manager)
    manager.v2Bridge=bridge
    now:=time.Now()
    account:=v2TestAccount(now)
    manager.portalAccounts["1001"]=account
    bridge.Sync()
    ip:=netip.MustParseAddr("192.168.81.199")
    if err:=bridge.registry.AuthenticateFromPhysicalIP(ip,"1001","7788",bridge);err!=nil{
        t.Fatal(err)
    }

    var report struct{
        PortalAuthorizations []struct{
            IP string `json:"ip"`
            MAC string `json:"mac"`
            AccountNumber string `json:"accountNumber"`
            Authorized bool `json:"authorized"`
            StartedAtMillis int64 `json:"startedAtMillis"`
        } `json:"portalAuthorizations"`
    }
    if err:=json.Unmarshal([]byte(manager.statsJSON()),&report);err!=nil{
        t.Fatal(err)
    }
    if len(report.PortalAuthorizations)!=1 {
        t.Fatalf("account session absent from admin dashboard: %+v", report.PortalAuthorizations)
    }
    session:=report.PortalAuthorizations[0]
    if session.IP!="192.168.81.199" || session.MAC!="" ||
        session.AccountNumber!="1001" || !session.Authorized ||
        session.StartedAtMillis<=0 {
        t.Fatalf("incorrect V2 session dashboard: %+v",session)
    }

    // Losing the Internet allowance does not silently close the local
    // account session, but the dashboard must no longer claim Internet.
    manager.mu.Lock()
    account.DataBalanceBytes=0
    manager.portalAccounts["1001"]=account
    manager.mu.Unlock()
    bridge.Sync()
    if err:=json.Unmarshal([]byte(manager.statsJSON()),&report);err!=nil{t.Fatal(err)}
    if len(report.PortalAuthorizations)!=1 || report.PortalAuthorizations[0].Authorized {
        t.Fatalf("expired data voucher incorrectly shown as online Internet: %+v",report.PortalAuthorizations)
    }
}
