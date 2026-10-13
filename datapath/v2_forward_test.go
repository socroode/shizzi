package datapath

import (
    "fmt"
    "net"
    "net/netip"
    "testing"
    "time"

    "dev.shizzi/datapath/core"
    "gvisor.dev/gvisor/pkg/tcpip"
    "gvisor.dev/gvisor/pkg/tcpip/stack"
)

func v2TestNAT(client string, duplicate string) string {
    line := func(c string) string {
        return fmt.Sprintf("tcp [aa:bb:cc:dd:ee:ff] 41(41) %s:40001 -> 42(testtun0) 192.0.2.2:40001 -> 1.1.1.1:443 [00:00:00:00:00:00] 1500 -\n",c)
    }
    result := "IPv4 Upstream:\n"+line(client)
    if duplicate != "" { result += line(duplicate) }
    return result+"IPv4 Downstream:\n"
}

func v2TestFlowKey() core.FlowKey {
    return core.FlowKey{
        Protocol: "tcp",
        Translated: netip.MustParseAddrPort("192.0.2.2:40001"),
        Destination: netip.MustParseAddrPort("1.1.1.1:443"),
    }
}

func v2TestAccount(now time.Time) PortalAccount {
    return PortalAccount{
        Number:"1001", Enabled:true, PinSalt:"salt",
        PinHash:hashPortalPin("salt","7788"),
        DataBalanceBytes:1000, DataValidUntilMillis:now.Add(time.Hour).UnixMilli(),
        DataDownloadBitsPerSecond:100_000_000,
        DataUploadBitsPerSecond:100_000_000,
        UnlimitedDownloadBitsPerSecond:100_000_000,
        UnlimitedUploadBitsPerSecond:100_000_000,
    }
}

func v2SetupFlow(t *testing.T) (*v2AccountBridge, *TrafficManager, *v2FlowLease) {
    t.Helper()
    now:=time.Now()
    manager:=newTrafficManager()
    acc:=v2TestAccount(now)
    manager.portalAccounts[acc.Number]=acc
    bridge:=newV2AccountBridge(manager)
    bridge.Sync()
    if err:=bridge.Associated("02:11:22:33:44:55","192.168.247.161");err!=nil{t.Fatal(err)}
    if err:=bridge.registry.AuthenticateFromPhysicalIP(
        netip.MustParseAddr("192.168.247.161"),"1001","7788",bridge,
    );err!=nil{t.Fatal(err)}
    bridge.nat.Replace(v2TestNAT("192.168.247.161",""),time.Now())
    lease,err:=bridge.openV2Flow(v2TestFlowKey(),time.Now())
    if err!=nil {t.Fatal(err)}
    return bridge,manager,lease
}

func TestV2TCPStrictNATAndAccountGate(t *testing.T){
    bridge,_,lease:=v2SetupFlow(t)
    if lease.account!="1001"{t.Fatal("NAT didn't bind to authenticated account")}
    key:=v2TestFlowKey()
    key.Destination=netip.MustParseAddrPort("8.8.8.8:443")
    if _,err:=bridge.openV2Flow(key,time.Now());err==nil{t.Fatal("destination changed but was authorized")}
    key=v2TestFlowKey();key.Translated=netip.MustParseAddrPort("192.0.2.2:40002")
    if _,err:=bridge.openV2Flow(key,time.Now());err==nil{t.Fatal("translated port spoof passed")}
    bridge.nat.Replace(v2TestNAT("192.168.247.161","192.168.247.162"),time.Now())
    if _,err:=bridge.openV2Flow(v2TestFlowKey(),time.Now());err==nil{t.Fatal("conflicting physical owners passed")}
    bridge.nat.Invalidate()
    if _,err:=bridge.openV2Flow(v2TestFlowKey(),time.Now());err==nil{t.Fatal("stale NAT allowed Internet")}
}

func TestV2CombinedUploadDownloadQuota(t *testing.T){
    bridge,mgr,lease:=v2SetupFlow(t)
    first:=lease.reserve(600,directionDownload)
    second:=lease.reserve(700,directionUpload)
    third:=lease.reserve(1,directionUpload)
    if first!=600||second!=400||third!=0{
        t.Fatalf("wrong shared quota 600+700: %d+%d then %d",first,second,third)
    }
    mgr.mu.Lock()
    u:=mgr.accountUsage["1001"]
    total:=u.UpBytes+u.DownBytes
    counted:=u.DataBytes
    mgr.mu.Unlock()
    if total!=1000||counted!=1000{t.Fatalf("account quota incorrect: %d %d",total,counted)}
    if lease.reserve(10,directionDownload)!=0{t.Fatal("spent voucher still transferring")}
    if got:=bridge.registry.AuthorizeDirect(netip.MustParseAddr("192.168.247.161"),core.Portal,time.Now());got!=core.Allow{
        t.Fatalf("quota exhausted blocked portal: %s",got)
    }
}

func TestV2OldStreamsDieOnTakeover(t *testing.T){
    bridge,_,old:=v2SetupFlow(t)
    if err:=bridge.Associated("02:66:77:88:99:aa","192.168.247.162");err!=nil {t.Fatal(err)}
    if err:=bridge.registry.AuthenticateFromPhysicalIP(netip.MustParseAddr("192.168.247.162"),"1001","7788",bridge);err!=nil{t.Fatal(err)}
    if old.allowed(time.Now())||old.reserve(10,directionDownload)!=0{
        t.Fatal("old TCP session retained Internet after account takeover")
    }
    bridge.nat.Replace(v2TestNAT("192.168.247.162",""),time.Now())
    newer,err:=bridge.openV2Flow(v2TestFlowKey(),time.Now())
    if err!=nil{t.Fatalf("new authenticated device denied: %v",err)}
    if newer.reserve(100,directionDownload)!=100{t.Fatal("new device can't use remaining quota")}
    if err:=bridge.Departed("02:66:77:88:99:aa");err!=nil{t.Fatal(err)}
    if newer.allowed(time.Now())||newer.reserve(10,directionUpload)!=0{
        t.Fatal("Wi-Fi departure failed to end existing flow")
    }
    if err:=bridge.Associated("02:66:77:88:99:aa","192.168.247.162");err!=nil{t.Fatal(err)}
    if _,err:=bridge.openV2Flow(v2TestFlowKey(),time.Now());err==nil{
        t.Fatal("Wi-Fi rejoin silently restored account access")
    }
}

func TestV2SameWifiNewLoginRevokesOldGeneration(t *testing.T) {
    bridge,_,old:=v2SetupFlow(t)
    if err:=bridge.registry.AuthenticateFromPhysicalIP(netip.MustParseAddr("192.168.247.161"),"1001","7788",bridge);err!=nil {t.Fatal(err)}
    if old.allowed(time.Now()) {t.Fatal("old stream survived login generation change")}
    current,err:=bridge.openV2Flow(v2TestFlowKey(),time.Now())
    if err!=nil||!current.allowed(time.Now()){t.Fatal("new login not recognized")}
}

func TestV2UnlimitedUsesNoDataAndMediaIndependent(t *testing.T) {
    bridge,mgr,lease:=v2SetupFlow(t)
    mgr.mu.Lock()
    account:=mgr.portalAccounts["1001"]
    account.UnlimitedUntilMillis=time.Now().Add(time.Hour).UnixMilli()
    account.DataBalanceBytes=1
    account.MediaUntilMillis=time.Now().Add(time.Hour).UnixMilli()
    mgr.portalAccounts["1001"]=account
    mgr.mu.Unlock()
    bridge.Sync()
    if n:=lease.reserve(250,directionDownload);n!=250{t.Fatalf("unlimited blocked %d",n)}
    mgr.mu.Lock()
    dataConsumed:=mgr.accountUsage["1001"].DataBytes
    mgr.mu.Unlock()
    if dataConsumed!=0{t.Fatal("unlimited consumed reserved Data quota")}
    mgr.mu.Lock()
    account=mgr.portalAccounts["1001"]
    account.UnlimitedUntilMillis=0
    mgr.portalAccounts["1001"]=account
    mgr.mu.Unlock()
    bridge.Sync()
    if n:=lease.reserve(2,directionDownload);n!=1{t.Fatalf("reserved Data after unlimited wrong: %d",n)}
    if n:=lease.reserve(5,directionDownload);n!=0{t.Fatal("spent Data allowed Internet")}
    ip:=netip.MustParseAddr("192.168.247.161")
    if got:=bridge.registry.AuthorizeDirect(ip,core.Media,time.Now());got!=core.Allow{t.Fatal("media incorrectly tied to Internet")}
}

func TestV2PrivateAndLocalDestinationsNeverDialWAN(t *testing.T) {
    for _,address:=range []string{"192.0.2.1","192.168.247.1","10.1.1.1","127.0.0.1","169.254.0.1","224.0.0.1"} {
        id:=stack.TransportEndpointID{
            RemoteAddress:tcpip.AddrFrom4([4]byte{192,0,2,2}),
            RemotePort:40001,
            LocalAddress:tcpip.AddrFromSlice(net.ParseIP(address).To4()),
            LocalPort:80,
        }
        if _,ok:=v2Key(id,"tcp");ok{t.Fatalf("local destination %s was allowed to WAN",address)}
    }
}
