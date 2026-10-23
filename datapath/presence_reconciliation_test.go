package datapath

import (
    "fmt"
    "strings"
    "testing"
    "time"
)

func simulatedClientList(ips ...string) string {
    entries := make([]string, 0, len(ips))
    for _, ip := range ips {
        entries = append(entries, "/"+ip+"=downstream: 41")
    }
    // A known, explicitly empty Client Information list is different from a
    // missing or truncated Android dumpsys output.
    return "Tethering:\nClient Information:\n{android.net.ip.IpServer@1={" +
        strings.Join(entries, ", ") + "}}\n"
}

func sessionPresence(t *testing.T, m *TrafficManager, ip string) string {
    t.Helper()
    for _, session := range statsOf(t, m).PortalAuthorizations {
        if session.IP == ip {
            return session.Presence
        }
    }
    t.Fatalf("missing session for %s", ip)
    return ""
}

func TestPresenceRefreshMarksSessionsWithoutFalseOnlineClaim(t *testing.T) {
    m := newPortalManager(t)
    pushConfig(t, m, accountForTest("1001", "pass", 100000))
    if ok, msg := m.submitPortalAccountLogin(phoneA, "1001", "pass"); !ok {
        t.Fatal(msg)
    }
    m.flowAttribution.dumpFn = func() (string, error) { return simulatedClientList(), nil }
    m.refreshClientPresence()
    if got := sessionPresence(t, m, phoneA); got != "missing" {
        t.Fatalf("presence=%s; wanted missing", got)
    }
    // Polling traffic statistics must never revoke the connection on its own.
    if m.portalRequiredFor(phoneA) {
        t.Fatal("a one-off missing presence unexpectedly removed the session")
    }
    // No authoritative presence list: protect a device whose Wi-Fi went to sleep.
    m.flowAttribution.mu.Lock()
    m.flowAttribution.lastRefresh = time.Now().Add(-time.Minute)
    m.flowAttribution.mu.Unlock()
    m.flowAttribution.dumpFn = func() (string, error) { return "IPv4 Upstream:\nIPv4 Downstream:\n", nil }
    m.refreshClientPresence()
    if got := sessionPresence(t, m, phoneA); got != "unknown" {
        t.Fatalf("unknown presence was incorrectly reported as %s", got)
    }
    m.mu.Lock()
    missingSince := m.portalAuthorized[phoneA].missingSince
    m.mu.Unlock()
    if !missingSince.IsZero() {
        t.Fatal("missing grace period not reset on ambiguous Android presence")
    }
    if m.portalRequiredFor(phoneA) {
        t.Fatal("unknown presence logged out a sleeping client")
    }
}

func TestAuthoritativeDepartureReleasesAccountAfter45Seconds(t *testing.T) {
    m := newPortalManager(t)
    pushConfig(t, m, accountForTest("1001", "pass", 100000), accountForTest("1002", "pass", 100000))
    m.submitPortalAccountLogin(phoneA, "1001", "pass")
    m.submitPortalAccountLogin(phoneB, "1002", "pass")
    m.submitPortalRecharge(phoneB, "voucher-pending")
    m.flowAttribution.dumpFn = func() (string, error) { return simulatedClientList(phoneA), nil }

    m.refreshClientPresence()
    if got := sessionPresence(t, m, phoneA); got != "online" {
        t.Fatalf("remaining client status %q", got)
    }
    if got := sessionPresence(t, m, phoneB); got != "missing" {
        t.Fatalf("departed client status %q", got)
    }
    if m.portalRequiredFor(phoneB) {
        t.Fatal("departing client was revoked before grace expired")
    }
    m.mu.Lock()
    m.portalAuthorized[phoneB].missingSince = time.Now().Add(-departedClientGrace - time.Second)
    m.mu.Unlock()
    m.refreshClientPresence()
    if !m.portalRequiredFor(phoneB) {
        t.Fatal("departed client was not released after grace period")
    }
    if m.portalRequiredFor(phoneA) {
        t.Fatal("another active client's session was revoked")
    }
    if got := len(statsOf(t, m).PortalRechargeClaims); got != 0 {
        t.Fatalf("orphaned voucher claim after departure: %d", got)
    }
    if ok, msg := m.submitPortalAccountLogin(phoneC, "1002", "pass"); !ok {
        t.Fatalf("reusing departed account was rejected: %s", msg)
    }
    if !m.flowAllowed(phoneC) {
        t.Fatal("account not portable to new Wi-Fi client")
    }
}

func TestInvalidOrTemporarilyEmptyDumpsNeverRevokeAccount(t *testing.T) {
    m := newPortalManager(t)
    pushConfig(t, m, accountForTest("1001", "pass", 100000))
    m.submitPortalAccountLogin(phoneA, "1001", "pass")
    m.flowAttribution.dumpFn = func() (string, error) { return "", fmt.Errorf("Android tethering temporarily unavailable") }
    m.mu.Lock()
    m.portalAuthorized[phoneA].missingSince = time.Now().Add(-time.Minute)
    m.mu.Unlock()
    m.refreshClientPresence()
    if got := sessionPresence(t, m, phoneA); got != "unknown" {
        t.Fatalf("bad Android dump reported presence %q", got)
    }
    if m.portalRequiredFor(phoneA) {
        t.Fatal("client was revoked because Android tethering dumpsys failed")
    }
}

func TestSixClientsReconcileAndPortableTakeover(t *testing.T) {
    m := newPortalManager(t)
    accounts := make([]PortalAccount, 6)
    ips := make([]string, 6)
    for i := range accounts {
        accounts[i] = accountForTest(fmt.Sprintf("%04d", i+2000), "pass", 100000)
        ips[i] = fmt.Sprintf("192.168.7.%d", 80+i)
    }
    pushConfig(t, m, accounts...)
    for i, ip := range ips {
        if ok, msg := m.submitPortalAccountLogin(ip, accounts[i].Number, "pass"); !ok {
            t.Fatalf("login %d: %s", i, msg)
        }
    }
    dump := simulatedClientList(ips...)
    m.flowAttribution.dumpFn = func() (string, error) { return dump, nil }
    m.refreshClientPresence()
    for _, ip := range ips {
        if got := sessionPresence(t, m, ip); got != "online" {
            t.Fatalf("%s initially %s", ip, got)
        }
    }
    // Android now lists the other five phones only.
    dump = simulatedClientList(ips[1:]...)
    m.flowAttribution.mu.Lock()
    m.flowAttribution.lastRefresh = time.Now().Add(-time.Minute)
    m.flowAttribution.mu.Unlock()
    m.refreshClientPresence()
    if got := sessionPresence(t, m, ips[0]); got != "missing" {
        t.Fatalf("departed phone status=%s", got)
    }
    m.mu.Lock()
    m.portalAuthorized[ips[0]].missingSince = time.Now().Add(-departedClientGrace - time.Second)
    m.mu.Unlock()
    m.refreshClientPresence()
    if got := len(statsOf(t, m).PortalAuthorizations); got != 5 {
        t.Fatalf("authenticated sessions=%d; expected five remaining clients", got)
    }
    for _, ip := range ips[1:] {
        if !m.flowAllowed(ip) {
            t.Fatalf("unrelated client %s blocked", ip)
        }
    }
    newIP := "192.168.7.99"
    if ok, msg := m.submitPortalAccountLogin(newIP, accounts[0].Number, "pass"); !ok {
        t.Fatalf("new phone could not take over departed account: %s", msg)
    }
    if !m.flowAllowed(newIP) {
        t.Fatal("new phone blocked despite valid credentials")
    }
    if got := len(statsOf(t, m).PortalAuthorizations); got != 6 {
        t.Fatalf("session count=%d, expected six including new client", got)
    }
}
