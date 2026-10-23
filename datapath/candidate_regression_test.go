package datapath

import (
	"bufio"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func candidateHTTP(t *testing.T, m *TrafficManager, ip, request string) *http.Response {
	t.Helper()
	server, client := net.Pipe()
	done := make(chan struct{})
	go func() { defer close(done); m.servePortal(server, ip) }()
	client.SetDeadline(time.Now().Add(3 * time.Second))
	fmt.Fprint(client, request)
	raw, err := io.ReadAll(client)
	client.Close()
	<-done
	if err != nil {
		t.Fatal(err)
	}
	response, err := http.ReadResponse(bufio.NewReader(strings.NewReader(string(raw))), nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { response.Body.Close() })
	return response
}

func TestCandidateCaptiveProbeBeforeAfterLoginAndOffline(t *testing.T) {
	m := newPortalManager(t)
	pushConfig(t, m, accountForTest("1001", "1234", 1000000))
	for _, path := range []string{"/generate_204", "/gen_204", "/hotspot-detect.html", "/connecttest.txt", "/ncsi.txt"} {
		t.Run(path, func(t *testing.T) {
			response := candidateHTTP(t, m, phoneA, "GET "+path+" HTTP/1.1\r\nHost: connectivitycheck.gstatic.com\r\n\r\n")
			if response.StatusCode != 302 || response.Header.Get("Location") != "http://192.0.2.1/" {
				t.Fatal("missing captive redirect", response.StatusCode)
			}
		})
	}
	if ok, msg := m.submitPortalAccountLogin(phoneA, "1001", "1234"); !ok {
		t.Fatal(msg)
	}
	response := candidateHTTP(t, m, phoneA, "GET /generate_204 HTTP/1.1\r\nHost: connectivitycheck.gstatic.com\r\n\r\n")
	if response.StatusCode != 204 {
		t.Fatal("authorized client remains captive")
	}
	m.mu.Lock()
	m.upstreamAvailable = false
	m.mu.Unlock()
	response = candidateHTTP(t, m, phoneA, "GET /generate_204 HTTP/1.1\r\nHost: connectivitycheck.gstatic.com\r\n\r\n")
	if response.StatusCode != 302 {
		t.Fatal("offline portal unreachable")
	}
	response = candidateHTTP(t, m, phoneA, "GET / HTTP/1.1\r\nHost: 192.0.2.1\r\n\r\n")
	body, _ := io.ReadAll(response.Body)
	if !strings.Contains(string(body), "Internet du routeur indisponible") {
		t.Fatal("offline status missing")
	}
}

func TestCandidateHTTPProofIsSingleUseAndBoundToClient(t *testing.T) {
	m := newPortalManager(t)
	a := accountForTest("1001", "1234", 1000000)
	pushConfig(t, m, a)
	c := m.accountChallenge(phoneA, a.Number)
	nonce := c["nonce"].(string)
	proof := accountProof(a.PinHash, nonce, a.Number)
	if ok, _ := m.submitAccountProof(phoneB, a.Number, nonce, proof); ok {
		t.Fatal("challenge transferred to another client")
	}
	if ok, _ := m.submitAccountProof(phoneA, a.Number, nonce, proof); ok {
		t.Fatal("rejected nonce reused")
	}
	c = m.accountChallenge(phoneA, a.Number)
	nonce = c["nonce"].(string)
	proof = accountProof(a.PinHash, nonce, a.Number)
	body := fmt.Sprintf("account=1001&nonce=%s&proof=%s", nonce, proof)
	response := candidateHTTP(t, m, phoneA, fmt.Sprintf("POST /login HTTP/1.1\r\nHost: 192.0.2.1\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: %d\r\n\r\n%s", len(body), body))
	if response.StatusCode != 200 || !m.flowAllowed(phoneA) {
		t.Fatal("HTTP proof did not authorize")
	}
	if ok, _ := m.submitAccountProof(phoneA, a.Number, nonce, proof); ok {
		t.Fatal("proof replay accepted")
	}
}

func TestCandidatePlainPINOnHTTPIsRejected(t *testing.T) {
	m := newPortalManager(t)
	pushConfig(t, m, accountForTest("1001", "1234", 1000000))
	body := "account=1001&pin=1234"
	candidateHTTP(t, m, phoneA, fmt.Sprintf("POST /login HTTP/1.1\r\nHost: 192.0.2.1\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: %d\r\n\r\n%s", len(body), body))
	if m.flowAllowed(phoneA) {
		t.Fatal("plaintext HTTP PIN authorized")
	}
}

func TestCandidateLastQuotaByteReservedOnceAcrossEightFlows(t *testing.T) {
	m := newPortalManager(t)
	a := accountForTest("1001", "1234", 1)
	a.DataDownloadBitsPerSecond = 0
	pushConfig(t, m, a)
	m.submitPortalAccountLogin(phoneA, a.Number, "1234")
	var wg sync.WaitGroup
	var admitted atomic.Int64
	start := make(chan struct{})
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			r := m.reserveTransfer(phoneA, directionDownload, 1, false)
			if r == nil {
				return
			}
			admitted.Add(1)
			r.settle(r.count)
		}()
	}
	close(start)
	wg.Wait()
	if admitted.Load() != 1 || m.accountUsage[a.Number].DataBytes != 1 {
		t.Fatal("concurrent quota overshoot", admitted.Load())
	}
}

func TestCandidateReservationBelongsToAdmittingAccount(t *testing.T) {
	m := newPortalManager(t)
	pushConfig(t, m, accountForTest("1001", "1234", 100), accountForTest("1002", "5678", 100))
	m.submitPortalAccountLogin(phoneA, "1001", "1234")
	r := m.reserveTransfer(phoneA, directionDownload, 10, false)
	if r == nil {
		t.Fatal("reservation failed")
	}
	m.submitPortalAccountLogin(phoneA, "1002", "5678")
	if r.pace() {
		t.Fatal("old reservation survived account change")
	}
	r.settle(3)
	if m.accountUsage["1001"].DataBytes != 3 || m.accountUsage["1002"] != nil {
		t.Fatal("old traffic billed to new account")
	}
}

func TestCandidateAccountTakeoverClosesIdleFlow(t *testing.T) {
	m := newPortalManager(t)
	pushConfig(t, m, accountForTest("1001", "1234", 1000000))
	m.submitPortalAccountLogin(phoneA, "1001", "1234")
	server, client := net.Pipe()
	upstream, peer := net.Pipe()
	defer client.Close()
	defer peer.Close()
	release, ok := m.trackFlow(phoneA, server, upstream, false)
	if !ok {
		t.Fatal("flow refused")
	}
	defer release()
	m.submitPortalAccountLogin(phoneB, "1001", "1234")
	client.SetReadDeadline(time.Now().Add(time.Second))
	var b [1]byte
	if _, err := client.Read(b[:]); !errors.Is(err, io.EOF) {
		t.Fatalf("idle flow not closed: %v", err)
	}
}

func TestCandidateIPReuseRevokesBeforeGrace(t *testing.T) {
	m := newPortalManager(t)
	pushConfig(t, m, accountForTest("1001", "1234", 100))
	old := `{"wifiKnown":true,"leasesKnown":true,"associatedMacs":["02:00:00:00:00:01"],"leases":[{"ip":"192.168.7.66","mac":"02:00:00:00:00:01"}]}`
	m.flowAttribution.setPresenceJSON(old)
	m.submitPortalAccountLogin("192.168.7.66", "1001", "1234")
	m.flowAttribution.setPresenceJSON(strings.ReplaceAll(old, "00:01", "00:02"))
	m.mu.Lock()
	m.pruneDepartedLocked(m.flowAttribution.presence(), time.Now())
	m.mu.Unlock()
	if m.portalAuthorized["192.168.7.66"] != nil {
		t.Fatal("new MAC inherited old session")
	}
}

func TestCandidateFailedDumpDoesNotRetainNATIdentity(t *testing.T) {
	r := newFlowAttributionResolver()
	key := flowAttributionKey{"tcp", "192.0.2.2", 40001, "1.1.1.1", 443}
	r.flows[key] = phoneA
	r.lastSuccess = time.Now().Add(-time.Minute)
	r.dumpFn = func() (string, error) { return "", errors.New("dump failed") }
	if got := r.resolve(key, false); got != "" {
		t.Fatal("stale identity retained", got)
	}
}

func TestCandidateTCPRuleCannotIdentifyUDP(t *testing.T) {
	r := newFlowAttributionResolver()
	r.dumpFn = func() (string, error) {
		return "IPv4 Upstream:\n tcp [02:00:00:00:00:01] 47(ap0) 192.168.7.66:41000 -> 76(tun0) 192.0.2.2:40001 -> 1.1.1.1:443 [00:00:00:00:00:00] 1500 3ms\nIPv4 Downstream:", nil
	}
	if got := r.resolve(flowAttributionKey{"udp", "192.0.2.2", 40001, "1.1.1.1", 443}, false); got != "" {
		t.Fatal("cross-protocol identity", got)
	}
}

func TestCandidateClaimAckPreservesNewArrival(t *testing.T) {
	m := newPortalManager(t)
	pushConfig(t, m, accountForTest("1001", "1234", 100), accountForTest("1002", "5678", 100))
	m.submitPortalAccountLogin(phoneA, "1001", "1234")
	m.submitPortalRecharge(phoneA, "FIRST")
	id := m.portalRechargeClaims[0].ID
	m.submitPortalAccountLogin(phoneB, "1002", "5678")
	m.submitPortalRecharge(phoneB, "SECOND")
	m.ackPortalClaims(`["` + id + `"]`)
	if len(m.portalRechargeClaims) != 1 || m.portalRechargeClaims[0].AccountNumber != "1002" {
		t.Fatal("new claim erased")
	}
}

func TestCandidateHTTPLogoutRequiresSessionNonce(t *testing.T) {
	m := newPortalManager(t)
	pushConfig(t, m, accountForTest("1001", "1234", 100))
	m.submitPortalAccountLogin(phoneA, "1001", "1234")
	response := candidateHTTP(t, m, phoneA, "POST /logout HTTP/1.1\r\nHost: 192.0.2.1\r\nContent-Length: 0\r\n\r\n")
	if response.StatusCode != 403 || !m.flowAllowed(phoneA) {
		t.Fatal("CSRF logout accepted")
	}
	body := "csrf=" + m.portalAuthorized[phoneA].ID
	response = candidateHTTP(t, m, phoneA, fmt.Sprintf("POST /logout HTTP/1.1\r\nHost: 192.0.2.1\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: %d\r\n\r\n%s", len(body), body))
	if response.StatusCode != 200 || m.flowAllowed(phoneA) {
		t.Fatal("valid logout failed")
	}
}

func candidateDNSQuery(host string) []byte {
	query := make([]byte, 12)
	query[0] = 1
	query[2] = 1
	query[5] = 1
	for _, label := range strings.Split(host, ".") {
		query = append(query, byte(len(label)))
		query = append(query, label...)
	}
	return append(query, 0, 0, 1, 0, 1)
}

func TestCandidateOfflineDNSAndPort53PayloadValidation(t *testing.T) {
	m := newTrafficManager()
	m.upstreamAvailable = false
	answer, err := m.resolveDNS(candidateDNSQuery("connectivitycheck.gstatic.com"), &net.Dialer{})
	if err != nil || binary.BigEndian.Uint16(answer[6:8]) != 1 || string(answer[len(answer)-4:]) != string([]byte{192, 0, 2, 1}) {
		t.Fatal("offline captive DNS failed", err)
	}
	if _, _, _, ok := parseDNSQuestion([]byte("AUDIT-NON-DNS")); ok {
		t.Fatal("arbitrary port53 payload accepted")
	}
	invalid := candidateDNSQuery("example.com")
	invalid[len(invalid)-4] = 0
	invalid[len(invalid)-3] = 252
	if _, _, _, ok := parseDNSQuestion(invalid); ok {
		t.Fatal("AXFR accepted")
	}
}

func TestCandidatePinnedTLSAndAdminHTTPRefusal(t *testing.T) {
	m := newTrafficManager()
	raw, err := GeneratePortalTLSIdentity()
	if err != nil {
		t.Fatal(err)
	}
	var identity portalTLSIdentity
	json.Unmarshal([]byte(raw), &identity)
	config, _ := json.Marshal(portalConfig{TLS: identity, RequireSecureAdmin: true})
	m.setPortalConfig(true, string(config))
	response := candidateHTTP(t, m, phoneA, "GET /api/v1/admin/challenge HTTP/1.1\r\nHost: 192.0.2.1\r\n\r\n")
	if response.StatusCode != 426 {
		t.Fatal("Admin accepted HTTP")
	}
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM([]byte(identity.CertificatePEM)) {
		t.Fatal("invalid identity")
	}
	server, client := net.Pipe()
	done := make(chan struct{})
	go func() { defer close(done); m.serveTLS(server, phoneA) }()
	secure := tls.Client(client, &tls.Config{MinVersion: tls.VersionTLS12, RootCAs: roots, ServerName: portalIP})
	secure.SetDeadline(time.Now().Add(3 * time.Second))
	if err := secure.Handshake(); err != nil {
		t.Fatal(err)
	}
	fmt.Fprint(secure, "GET /status.json HTTP/1.1\r\nHost: 192.0.2.1\r\n\r\n")
	body, err := io.ReadAll(secure)
	secure.Close()
	<-done
	if err != nil || !strings.Contains(string(body), "200 OK") {
		t.Fatal("pinned TLS portal failed", err)
	}
}

func TestCandidateCameraReconnectPortabilityAndRevocation(t *testing.T) {
	m := newPortalManager(t)
	a := accountForTest("1001", "1234", 1000000)
	config, _ := json.Marshal(portalConfig{Accounts: []PortalAccount{a}, Cameras: []CameraProfile{{a.Number, "02:00:00:00:00:01", "enrollment-1"}}})
	m.setPortalConfig(true, string(config))
	set := func(ip string) {
		raw := fmt.Sprintf(`{"wifiKnown":true,"leasesKnown":true,"associatedMacs":["02:00:00:00:00:01"],"leases":[{"ip":%q,"mac":"02:00:00:00:00:01"}]}`, ip)
		m.flowAttribution.setPresenceJSON(raw)
		m.mu.Lock()
		m.pruneDepartedLocked(m.flowAttribution.presence(), time.Now())
		m.reconcileCamerasLocked(m.flowAttribution.presence())
		m.mu.Unlock()
	}
	set(phoneA)
	if !m.flowAllowed(phoneA) {
		t.Fatal("camera enrollment not authorized")
	}
	m.submitPortalAccountLogin(phoneB, a.Number, "1234")
	set(phoneA)
	if m.flowAllowed(phoneA) || !m.flowAllowed(phoneB) {
		t.Fatal("camera overruled portable account takeover")
	}
	m.submitPortalLogout(phoneB)
	set(phoneA)
	if m.flowAllowed(phoneA) {
		t.Fatal("camera auto-returned after explicit logout")
	}
	// Re-enrollment is an explicit action and may resume the camera on a new IP.
	var cfg portalConfig
	json.Unmarshal(config, &cfg)
	cfg.Cameras[0].EnrollmentID = "enrollment-2"
	raw, _ := json.Marshal(cfg)
	m.setPortalConfig(true, string(raw))
	m.revokePortalClient(phoneA)
	set(phoneC)
	if m.flowAllowed(phoneC) {
		t.Fatal("camera revocation ignored")
	}
	cfg.Cameras[0].EnrollmentID = "enrollment-3"
	raw, _ = json.Marshal(cfg)
	m.setPortalConfig(true, string(raw))
	if !m.flowAllowed(phoneC) {
		t.Fatal("camera new DHCP IP not authorized after re-enrollment")
	}
}

func TestCandidateEmptyConfigAndCorruptConfigStayClosed(t *testing.T) {
	m := newTrafficManager()
	m.setPortalConfig(true, `{"accounts":[]}`)
	if m.flowAllowed(phoneA) {
		t.Fatal("empty public mode opened Internet")
	}
	m.setPortalConfig(false, `broken-json`)
	if m.flowAllowed(phoneA) {
		t.Fatal("corrupt config disabled gate")
	}
}

func TestCandidatePBKDF2CrossPlatformVector(t *testing.T) {
	a := PortalAccount{PinSalt: "cross-platform-salt", PinKDFIterations: pinKDFIterations}
	hash := accountPinHash(a, "1234")
	if hash != "28c41880cf8f2f594a05750a287add9c78b6ed0606d62743a851f05dbeb30ee1" {
		t.Fatal("KDF mismatch", hash)
	}
	if accountProof(hash, "test-nonce", "1001") != "44cf8d7c1d501a4ff8f892f0a63b9f42633152929e483b18e66d3c686ed516fb" {
		t.Fatal("proof mismatch")
	}
}

func TestCandidateUnauthenticatedAdminReadPreservesResult(t *testing.T) {
	m := newTrafficManager()
	m.adminConfig.Enabled = true
	m.adminResults["result-1"] = AdminCommandResult{ID: "result-1", Success: true, Message: "saved"}
	response := candidateHTTP(t, m, phoneA, "GET /api/v1/admin/result?id=result-1 HTTP/1.1\r\nHost: 192.0.2.1\r\n\r\n")
	if response.StatusCode != 401 || m.adminResults["result-1"].ID == "" {
		t.Fatal("unauthorized read deleted Admin result")
	}
	m.adminSessions["owner"] = &adminSession{Token: "owner", IP: phoneA, LastSeenMillis: time.Now().UnixMilli(), done: make(chan struct{})}
	response = candidateHTTP(t, m, phoneA, "GET /api/v1/admin/result?id=result-1 HTTP/1.1\r\nHost: 192.0.2.1\r\nX-Shizzi-Admin-Token: owner\r\n\r\n")
	body, _ := io.ReadAll(response.Body)
	if response.StatusCode != 200 || !strings.Contains(string(body), "saved") {
		t.Fatal("authorized Admin result missing")
	}
}

func TestCandidateAdminSessionExpiresAndClosesChannel(t *testing.T) {
	m := newTrafficManager()
	m.adminConfig.Enabled = true
	done := make(chan struct{})
	m.adminSessions["owner"] = &adminSession{Token: "owner", IP: phoneA, LastSeenMillis: time.Now().Add(-31 * time.Minute).UnixMilli(), done: done}
	m.mu.Lock()
	valid := m.adminSessionValidLocked("owner", time.Now())
	m.mu.Unlock()
	if valid {
		t.Fatal("expired Admin session accepted")
	}
	select {
	case <-done:
	default:
		t.Fatal("Admin revocation channel remains open")
	}
}

type candidateWriteObservedConn struct {
	net.Conn
	writeStarted chan struct{}
	once         sync.Once
}

func (c *candidateWriteObservedConn) Write(p []byte) (int, error) {
	c.once.Do(func() { close(c.writeStarted) })
	return c.Conn.Write(p)
}

func TestCandidateSlowChallengeReaderDoesNotLockTraffic(t *testing.T) {
	m := newTrafficManager()
	m.adminConfig = remoteAdminConfig{Enabled: true, Username: "admin", PasswordSalt: "salt", PasswordHash: "verifier"}
	server, client := net.Pipe()
	defer client.Close()
	writing := make(chan struct{})
	observed := &candidateWriteObservedConn{Conn: server, writeStarted: writing}
	finished := make(chan struct{})
	go func() { defer close(finished); m.servePortal(observed, phoneA) }()
	fmt.Fprint(client, "GET /api/v1/admin/challenge?username=admin HTTP/1.1\r\nHost: 192.0.2.1\r\n\r\n")
	// Do not read the response; net.Pipe makes the server's write block.
	select {
	case <-writing:
	case <-time.After(time.Second):
		t.Fatal("response write never started")
	}
	snapshot := make(chan struct{})
	go func() { m.statsJSON(); close(snapshot) }()
	select {
	case <-snapshot:
	case <-time.After(time.Second):
		t.Fatal("challenge response holds policy mutex")
	}
	client.Close()
	<-finished
}

func TestCandidateAssociatedStationIsRequiredForSoleClientFallback(t *testing.T) {
	r := newFlowAttributionResolver()
	r.dumpFn = func() (string, error) { return "IPv4 Upstream:\nIPv4 Downstream:", nil }
	r.setPresenceJSON(`{"wifiKnown":true,"leasesKnown":true,"associatedMacs":["02:00:00:00:00:01"],"leases":[{"ip":"192.168.7.66","mac":"02:00:00:00:00:01"}]}`)
	key := flowAttributionKey{"tcp", "192.0.2.2", 41001, "1.1.1.1", 443}
	if got := r.resolve(key, false); got != "192.168.7.66" {
		t.Fatal("trusted sole-station fallback failed", got)
	}
	r.setPresenceJSON(`{"wifiKnown":true,"leasesKnown":true,"associatedMacs":["02:00:00:00:00:01","02:00:00:00:00:02"],"leases":[{"ip":"192.168.7.66","mac":"02:00:00:00:00:01"}]}`)
	if got := r.resolve(key, false); got != "" {
		t.Fatal("one DHCP lease hid two Wi-Fi stations", got)
	}
}

func TestCandidateFreezeWaitsForFinalAccountBytes(t *testing.T) {
	m := newPortalManager(t)
	pushConfig(t, m, accountForTest("1001", "1234", 1000))
	m.submitPortalAccountLogin(phoneA, "1001", "1234")
	server, client := net.Pipe()
	upstream, peer := net.Pipe()
	defer client.Close()
	defer peer.Close()
	release, ok := m.trackFlow(phoneA, server, upstream, false)
	if !ok {
		t.Fatal("flow refused")
	}
	reservation := m.reserveTransfer(phoneA, directionDownload, 10, false)
	if reservation == nil {
		t.Fatal("reservation refused")
	}
	go func() {
		client.SetReadDeadline(time.Now().Add(time.Second))
		var b [1]byte
		client.Read(b[:])
		reservation.settle(7)
		release()
	}()
	m.freeze()
	m.flowWG.Wait()
	if m.accountUsage["1001"].DataBytes != 7 || m.globalPending != 0 {
		t.Fatal("final usage snapshot precedes flow settlement")
	}
}

func TestCandidateCameraNeverAutoAuthenticatesFromDHCPOnly(t *testing.T) {
	m := newPortalManager(t)
	a := accountForTest("1001", "1234", 1000)
	pushConfig(t, m, a)
	m.cameraProfiles = map[string]CameraProfile{a.Number: {a.Number, "02:00:00:00:00:01", "enrollment"}}
	presence := clientPresence{authoritative: true, clients: map[string]struct{}{phoneA: {}}, macs: map[string]string{phoneA: "02:00:00:00:00:01"}}
	m.mu.Lock()
	m.reconcileCamerasLocked(presence)
	m.mu.Unlock()
	if m.flowAllowed(phoneA) {
		t.Fatal("DHCP lease auto-authorized a camera without observed association")
	}
}

func TestCandidateFlowAdmissionCannotMoveToNewAccountDuringDial(t *testing.T) {
	m := newPortalManager(t)
	pushConfig(t, m, accountForTest("1001", "1234", 1000), accountForTest("1002", "5678", 1000))
	m.submitPortalAccountLogin(phoneA, "1001", "1234")
	scope, ok := m.captureFlowScope(phoneA, false, "")
	if !ok {
		t.Fatal("admission refused")
	}
	// The upstream socket completes after the portal account changed.
	m.submitPortalAccountLogin(phoneA, "1002", "5678")
	server, client := net.Pipe()
	upstream, peer := net.Pipe()
	defer server.Close()
	defer client.Close()
	defer upstream.Close()
	defer peer.Close()
	if release, ok := m.trackScopedFlow(phoneA, scope, server, upstream, false); ok {
		release()
		t.Fatal("old connection adopted the new account")
	}
	if reservation := m.reserveTransferScoped(phoneA, directionDownload, 1, false, scope); reservation != nil {
		reservation.settle(0)
		t.Fatal("old flow reserved the new account's quota")
	}
	if m.accountUsage["1002"] != nil {
		t.Fatal("old flow billed new account")
	}
}

func TestCandidateMediaKeepsItsAdmittingAccountAcrossTakeover(t *testing.T) {
	m := newPortalManager(t)
	a := accountForTest("1001", "1234", 1000)
	b := accountForTest("1002", "5678", 1000)
	a.MediaUntilMillis = time.Now().Add(time.Hour).UnixMilli()
	b.MediaUntilMillis = a.MediaUntilMillis
	pushConfig(t, m, a, b)
	m.submitPortalAccountLogin(phoneA, a.Number, "1234")
	scope, ok := m.captureFlowScope(phoneA, true, a.Number)
	if !ok {
		t.Fatal("media admission refused")
	}
	m.submitPortalAccountLogin(phoneA, b.Number, "5678")
	if _, ok := m.captureFlowScope(phoneA, true, a.Number); ok {
		t.Fatal("old media account accepted under new session")
	}
	server, client := net.Pipe()
	upstream, peer := net.Pipe()
	defer server.Close()
	defer client.Close()
	defer upstream.Close()
	defer peer.Close()
	if release, ok := m.trackScopedFlow(phoneA, scope, server, upstream, true); ok {
		release()
		t.Fatal("old media connection survived account takeover during opening")
	}
}

func TestCandidateAdminFlowDoesNotSpendInactiveAccountCredit(t *testing.T) {
	m := newPortalManager(t)
	pushConfig(t, m, accountForTest("1001", "1234", 0))
	m.submitPortalAccountLogin(phoneA, "1001", "1234")
	m.adminConfig.Enabled = true
	m.adminSessions["owner"] = &adminSession{Token: "owner", IP: phoneA, LastSeenMillis: time.Now().UnixMilli(), done: make(chan struct{})}
	scope, ok := m.captureFlowScope(phoneA, false, "")
	if !ok || scope.admin == nil {
		t.Fatal("active Admin did not receive its own access")
	}
	reservation := m.reserveTransferScoped(phoneA, directionDownload, 1, false, scope)
	if reservation == nil {
		t.Fatal("inactive account blocked Admin's access")
	}
	if !reservation.pace() {
		reservation.settle(0)
		t.Fatal("Admin pacing refused")
	}
	reservation.settle(1)
	if m.accountUsage["1001"] != nil || m.totalDownBytes != 1 {
		t.Fatal("Admin traffic billed to inactive account")
	}
}
