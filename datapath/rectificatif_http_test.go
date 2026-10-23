package datapath

import (
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
)

// Real production gVisor/HTTP paths with simulated Android dumps. One or two
// clients have no installed NAT rule; Android's exact E2BIG log is their only
// identity evidence. No radio, Android kernel, or physical phone is simulated.
func TestRectificatifSixAndEightClientsRecoverPartialNATLoss(t *testing.T) {
	for _, scenario := range []struct{ clients, missing int }{{6, 1}, {8, 2}} {
		t.Run(fmt.Sprintf("%d_clients_%d_missing", scenario.clients, scenario.missing), func(t *testing.T) {
			traffic := newPortalManager(t)
			traffic.setRequireClientAttribution(true)
			traffic.flowAttribution.setPresenceJSON(`{"androidTimezone":"Pacific/Tahiti","androidUtcOffsetSeconds":-36000}`)
			zone, err := time.LoadLocation("Pacific/Tahiti")
			if err != nil {
				t.Fatal(err)
			}
			accounts := make([]PortalAccount, scenario.clients)
			for i := range accounts {
				accounts[i] = accountForTest(fmt.Sprintf("85%02d", i), "test-pin", 1_000_000)
			}
			pushConfig(t, traffic, accounts...)
			var mu sync.Mutex
			rules, errors := []string{}, []string{}
			traffic.flowAttribution.dumpFn = func() (string, error) {
				mu.Lock()
				defer mu.Unlock()
				return "IPv4 Upstream:\n" + strings.Join(rules, "\n") + "\nIPv4 Downstream:\n" + strings.Join(errors, "\n"), nil
			}
			client := linkedStacks(t, traffic)
			var wg sync.WaitGroup
			for i := range accounts {
				wg.Add(1)
				go func(i int) {
					defer wg.Done()
					conn, err := gonet.DialTCP(client, tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{192, 0, 2, 1}), Port: 80}, ipv4.ProtocolNumber)
					if err != nil {
						t.Errorf("client %d dial: %v", i, err)
						return
					}
					defer conn.Close()
					conn.SetDeadline(time.Now().Add(10 * time.Second))
					port := conn.LocalAddr().(*net.TCPAddr).Port
					ip := fmt.Sprintf("192.168.7.%d", 60+i)
					mu.Lock()
					if i >= scenario.clients-scenario.missing {
						line := bpfFailureLine(time.Now().Add(-2*time.Second).In(zone), ip, "192.0.2.2", "41000", fmt.Sprint(port), "192.0.2.1")
						errors = append(errors, strings.ReplaceAll(line, "dstPort: 443", "dstPort: 80"))
					} else {
						rules = append(rules, fmt.Sprintf(" tcp [02:00:00:00:00:%02x] 47(ap0) %s:41000 -> 76(testtun0) 192.0.2.2:%d -> 192.0.2.1:80 [00:00:00:00:00:00] 1500 3ms", i+1, ip, port))
					}
					mu.Unlock()
					challenge := traffic.accountChallenge(ip, accounts[i].Number)
					nonce, _ := challenge["nonce"].(string)
					proof := accountProof(accounts[i].PinHash, nonce, accounts[i].Number)
					body := fmt.Sprintf("account=%s&nonce=%s&proof=%s", accounts[i].Number, nonce, proof)
					fmt.Fprintf(conn, "POST /login HTTP/1.1\r\nHost: 192.0.2.1\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s", len(body), body)
					response, err := io.ReadAll(conn)
					if err != nil || !strings.Contains(string(response), "200 OK") || strings.Contains(string(response), "appareil non identifié") {
						t.Errorf("client %d unrecovered HTTP: %v %q", i, err, response)
					}
				}(i)
			}
			wg.Wait()
			snapshot := statsOf(t, traffic)
			if len(snapshot.PortalAuthorizations) != scenario.clients {
				t.Fatalf("authenticated %d/%d", len(snapshot.PortalAuthorizations), scenario.clients)
			}
			for i, account := range accounts {
				ip := fmt.Sprintf("192.168.7.%d", 60+i)
				traffic.mu.Lock()
				auth := traffic.portalAuthorized[ip]
				correct := auth != nil && auth.AccountNumber == account.Number
				traffic.mu.Unlock()
				if !correct || !traffic.flowAllowed(ip) {
					t.Errorf("client %d inherited wrong account or remains blocked", i)
				}
			}
		})
	}
}
