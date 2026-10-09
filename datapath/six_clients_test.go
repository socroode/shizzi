package datapath

import (
 "fmt"
 "io"
 "net"
 "strings"
 "sync"
 "testing"
 "time"
)

// Six logical clients share Android's translated TUN address. This exercises
// production attribution, accounting and session code; it is not an Android
// radio/emulator test.
func TestSixClientsSharedTUNAccountingAndPortableSessions(t *testing.T) {
 manager := newPortalManager(t)
 manager.setRequireClientAttribution(true)
 accounts := make([]PortalAccount, 6)
 ips := make([]string, 6)
 dump := "IPv4 Upstream:\n"
 for i := range accounts {
  ips[i] = fmt.Sprintf("192.168.7.%d", 60+i)
  accounts[i] = accountForTest(fmt.Sprintf("60%02d", i), "test-pass", 1_000_000)
  accounts[i].MediaUntilMillis = time.Now().Add(time.Hour).UnixMilli()
  dump += fmt.Sprintf(" tcp [02:00:00:00:00:%02x] 47(ap0) %s:41000 -> 76(testtun0) 192.0.2.2:%d -> 203.0.113.10:443 [00:00:00:00:00:00] 1500 3ms\n", i+1, ips[i], 61000+i)
 }
 dump += "IPv4 Downstream:\n"
 manager.flowAttribution.dumpFn = func() (string, error) { return dump, nil }
 pushConfig(t, manager, accounts...)
 for i, ip := range ips {
  if manager.flowAllowed(ip) { t.Fatalf("client %d authorized before login", i) }
  if ok, message := manager.submitPortalAccountLogin(ip, accounts[i].Number, "test-pass"); !ok { t.Fatal(message) }
 }
 var wg sync.WaitGroup
 for i := range ips {
  wg.Add(1)
  go func(i int) {
   defer wg.Done()
   for n := 0; n < 100; n++ {
    ip := manager.resolveFlowClient("tcp", "192.0.2.2", uint16(61000+i), "203.0.113.10", 443, true)
    if ip != ips[i] { t.Errorf("client %d resolved to %q", i, ip); return }
    manager.account(ip, directionDownload, (i+1)*10)
    manager.account(ip, directionUpload, i+1)
    if !manager.flowAllowed(ip) { t.Errorf("client %d unexpectedly blocked", i); return }
   }
  }(i)
 }
 wg.Wait()
 usage := map[string]accountUsage{}
 for _, u := range statsOf(t, manager).AccountUsage { usage[u.AccountNumber] = accountUsage{DownBytes: u.DownBytes, UpBytes: u.UpBytes} }
 for i, a := range accounts {
  u := usage[a.Number]
  if u.DownBytes != int64((i+1)*1000) || u.UpBytes != int64((i+1)*100) { t.Fatalf("account %s mixed usage: %+v", a.Number, u) }
 }
 // Administrative refreshes preserve every session.
 pushConfig(t, manager, accounts...)
 if got := len(statsOf(t, manager).PortalAuthorizations); got != 6 { t.Fatalf("sessions after refresh: %d", got) }
 // A wrong password cannot evict the active phone.
 if ok, _ := manager.submitPortalAccountLogin(ips[1], accounts[0].Number, "wrong"); ok { t.Fatal("wrong password accepted") }
 if !manager.flowAllowed(ips[0]) || !manager.flowAllowed(ips[1]) { t.Fatal("wrong password disrupted sessions") }
 // Valid takeover reuses the same connected addresses, without radio changes.
 if ok, msg := manager.submitPortalAccountLogin(ips[1], accounts[0].Number, "test-pass"); !ok { t.Fatal(msg) }
 if manager.flowAllowed(ips[0]) || manager.mediaAccountAuthenticated(ips[0]) { t.Fatal("old client retains access") }
 if !manager.flowAllowed(ips[1]) || !manager.mediaAccountAuthenticated(ips[1]) { t.Fatal("new client lacks access") }
 for _, ip := range ips[2:] { if !manager.flowAllowed(ip) { t.Fatalf("unrelated client %s disconnected", ip) } }
 count := 0
 for _, auth := range statsOf(t, manager).PortalAuthorizations { if auth.AccountNumber == accounts[0].Number { count++ } }
 if count != 1 { t.Fatalf("active devices for transferred account: %d", count) }
 // Revoked client can still open the local portal.
 server, client := net.Pipe()
 done := make(chan struct{})
 go func() { defer close(done); manager.servePortal(server, ips[0]) }()
 client.SetDeadline(time.Now().Add(3*time.Second))
 fmt.Fprint(client, "GET / HTTP/1.1\r\nHost: 192.0.2.1\r\nConnection: close\r\n\r\n")
 response, err := io.ReadAll(client)
 client.Close()
 <-done
 if err != nil { t.Fatal(err) }
 if !strings.Contains(string(response), "Numéro de compte") { t.Fatal("local portal unavailable after takeover") }
}
