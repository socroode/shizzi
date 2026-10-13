package datapath

import (
    "fmt"
    "net/netip"
    "strings"
    "testing"
    "time"

    "dev.shizzi/datapath/core"
)

// This is a Go multi-station SIMULATION, not eight physical Android phones.
// It deliberately uses eight distinct DHCP assignments, hotspot station
// associations, accounts, and translated TCP tuples on ONE shared TUN IP.
func TestV2EightConcurrentAccountsNoCrossBilling(t *testing.T) {
    now := time.Now()
    mgr := newTrafficManager()
    bridge := newV2AccountBridge(mgr)
    var nat strings.Builder
    nat.WriteString("IPv4 Upstream:\n")
    leases := make([]*v2FlowLease, 0, 8)
    macs := make([]string, 0, 8)

    for i:=0;i<8;i++ {
        accountNumber := fmt.Sprintf("%04d",1100+i)
        mac := fmt.Sprintf("02:10:20:30:40:%02x",i+1)
        physical := fmt.Sprintf("192.168.247.%d",161+i)
        port := 41000+i
        record := v2TestAccount(now)
        record.Number = accountNumber
        record.DataBalanceBytes = 1024
        record.PinHash = hashPortalPin(record.PinSalt,accountNumber)
        mgr.portalAccounts[accountNumber] = record
        macs = append(macs,mac)
        if err:=bridge.Associated(mac,physical);err!=nil{t.Fatal(err)}
        fmt.Fprintf(&nat,
            "tcp [aa:bb:cc:dd:ee:ff] 41(41) %s:%d -> 42(testtun0) 192.0.2.2:%d -> 1.1.1.1:443 [00:00:00:00:00:00] 1500 -\n",
            physical,port,port)
    }
    nat.WriteString("IPv4 Downstream:\n")
    bridge.Sync()
    for i:=0;i<8;i++{
        account:=fmt.Sprintf("%04d",1100+i)
        physical:=netip.MustParseAddr(fmt.Sprintf("192.168.247.%d",161+i))
        if err:=bridge.registry.AuthenticateFromPhysicalIP(physical,account,account,bridge);err!=nil{
            t.Fatalf("client %d PIN: %v",i,err)
        }
    }
    bridge.nat.Replace(nat.String(),time.Now())
    for i:=0;i<8;i++{
        key:=core.FlowKey{
            Protocol:"tcp",
            Translated:netip.MustParseAddrPort(fmt.Sprintf("192.0.2.2:%d",41000+i)),
            Destination:netip.MustParseAddrPort("1.1.1.1:443"),
        }
        lease,err:=bridge.openV2Flow(key,time.Now())
        if err!=nil{t.Fatalf("client %d NAT+session: %v",i,err)}
        if lease.account!=fmt.Sprintf("%04d",1100+i){
            t.Fatalf("client %d acquired other user's account %s",i,lease.account)
        }
        if n:=lease.reserve(128,directionDownload);n!=128{
            t.Fatalf("client %d billed %d instead of 128",i,n)
        }
        leases=append(leases,lease)
    }
    mgr.mu.Lock()
    for i:=0;i<8;i++{
        account:=fmt.Sprintf("%04d",1100+i)
        usage:=mgr.accountUsage[account]
        if usage==nil||usage.DataBytes!=128{
            mgr.mu.Unlock()
            t.Fatalf("account %s usage incorrect: %+v",account,usage)
        }
    }
    mgr.mu.Unlock()

    // Revoke one account without disturbing the other 7.
    mgr.mu.Lock()
    suspended:=mgr.portalAccounts["1103"]
    suspended.Enabled=false
    mgr.portalAccounts["1103"]=suspended
    mgr.mu.Unlock()
    bridge.Sync()
    if leases[3].allowed(time.Now())||leases[3].reserve(5,directionUpload)!=0{
        t.Fatal("disabled account 1103 still has internet")
    }
    for i,lease:=range leases {
        if i==3 {continue}
        if !lease.allowed(time.Now()) {
            t.Fatalf("suspending client 3 incorrectly revoked client %d",i)
        }
    }
    // Wi-Fi exit of client 0 does not erase its balance or close others.
    if err:=bridge.Departed(macs[0]);err!=nil{t.Fatal(err)}
    if leases[0].allowed(time.Now()){t.Fatal("client 0 kept session after Wi-Fi departure")}
    for i:=1;i<8;i++{
        if i==3 {continue}
        if !leases[i].allowed(time.Now()){t.Fatalf("client %d lost session",i)}
    }
    mgr.mu.Lock()
    remaining:=mgr.portalAccounts["1100"].DataBalanceBytes-mgr.accountUsage["1100"].DataBytes
    mgr.mu.Unlock()
    if remaining!=896{t.Fatalf("departure erased or duplicated Data balance: %d",remaining)}
}
