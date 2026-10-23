package datapath

import (
    "fmt"
    "testing"
    "time"
)

// Matches the running OPPO hotspot dump observed on the router. SoftAP
// metrics contain historical counts, so they must not be used as current
// station presence.
func runningSoftApDump(count int) string {
    return fmt.Sprintf(`WifiService:
Dump of SoftApManager id=127054
current StateMachine mode: StartedState
mRole: ROLE_SOFTAP_TETHERED
mApInterfaceName: ap0
mIfaceIsUp: true
mCurrentSoftApConfiguration: ssid = "TEKOMOPAO WIFI 1"
getConnectedClientList().size(): %d
mTimeoutEnabled: false
SoftApManager:
mSoftApTetheredEvents:
event_type=2,num_connected_clients=4
`, count)
}

func TestRunningSoftApParserRejectsHistoryAndStoppedAP(t *testing.T) {
    for _, tc := range []struct {
        name string
        dump string
        wantCount int
        wantKnown bool
    }{
        {"connected-one", runningSoftApDump(1), 1, true},
        {"connected-zero", runningSoftApDump(0), 0, true},
        {"history-only", "mSoftApTetheredEvents: event_type=2,num_connected_clients=4", 0, false},
        {"missing-counter", "Dump of SoftApManager id=1\ncurrent StateMachine mode: StartedState\nmRole: ROLE_SOFTAP_TETHERED\nmIfaceIsUp: true\n", 0, false},
        {"stopped", "Dump of SoftApManager id=1\ncurrent StateMachine mode: IdleState\nmRole: ROLE_SOFTAP_TETHERED\nmIfaceIsUp: false\ngetConnectedClientList().size(): 0\n", 0, false},
    } {
        t.Run(tc.name, func(t *testing.T) {
            got, known := parseRunningSoftApClientCount(tc.dump)
            if got != tc.wantCount || known != tc.wantKnown {
                t.Fatalf("got (%d,%v); want (%d,%v)", got, known, tc.wantCount, tc.wantKnown)
            }
        })
    }
}

func TestZeroAssociatedStationsEventuallyReleasesStaleTetherSessions(t *testing.T) {
    m := newPortalManager(t)
    pushConfig(t, m, accountForTest("1001", "pass", 100000), accountForTest("1002", "pass", 100000))
    if ok, msg := m.submitPortalAccountLogin(phoneA, "1001", "pass"); !ok { t.Fatal(msg) }
    if ok, msg := m.submitPortalAccountLogin(phoneB, "1002", "pass"); !ok { t.Fatal(msg) }
    // Android's Tethering snapshot retains DHCP entries for absent phones.
    m.flowAttribution.dumpFn = func() (string, error) { return simulatedClientList(phoneA, phoneB), nil }
    m.flowAttribution.wifiDumpFn = func() (string, error) { return runningSoftApDump(0), nil }
    m.refreshClientPresence()
    if got := sessionPresence(t, m, phoneA); got != "missing" { t.Fatalf("first presence=%q", got) }
    if got := sessionPresence(t, m, phoneB); got != "missing" { t.Fatalf("second presence=%q", got) }
    if m.portalRequiredFor(phoneA) || m.portalRequiredFor(phoneB) { t.Fatal("revoked before grace elapsed") }
    m.mu.Lock()
    m.portalAuthorized[phoneA].missingSince = time.Now().Add(-departedClientGrace - time.Second)
    m.portalAuthorized[phoneB].missingSince = time.Now().Add(-departedClientGrace - time.Second)
    m.mu.Unlock()
    m.refreshClientPresence()
    if got := len(statsOf(t, m).PortalAuthorizations); got != 0 {
        t.Fatalf("%d stale sessions remain after authoritative empty Wi-Fi + grace", got)
    }
    if ok, msg := m.submitPortalAccountLogin(phoneA, "1001", "pass"); !ok {
        t.Fatalf("portable account could not log back in: %s", msg)
    }
}

func TestSoftApCountMismatchCannotIdentifyWhichAccountLeft(t *testing.T) {
    m := newPortalManager(t)
    pushConfig(t, m, accountForTest("1001", "pass", 100000), accountForTest("1002", "pass", 100000))
    m.submitPortalAccountLogin(phoneA, "1001", "pass")
    m.submitPortalAccountLogin(phoneB, "1002", "pass")
    m.flowAttribution.dumpFn = func() (string, error) { return simulatedClientList(phoneA, phoneB), nil }
    m.flowAttribution.wifiDumpFn = func() (string, error) { return runningSoftApDump(1), nil }
    m.refreshClientPresence()
    if got := sessionPresence(t, m, phoneA); got != "unknown" { t.Fatalf("first=%q; cannot confirm identity from count", got) }
    if got := sessionPresence(t, m, phoneB); got != "unknown" { t.Fatalf("second=%q; cannot confirm identity from count", got) }
    snap := m.flowAttribution.snapshot()
    if !snap.WifiCountKnown || snap.WifiAssociatedClients != 1 || snap.ClientCount != 2 {
        t.Fatalf("unexpected diagnostics: %+v", snap)
    }
    m.mu.Lock()
    m.portalAuthorized[phoneA].missingSince = time.Now().Add(-departedClientGrace - time.Second)
    m.mu.Unlock()
    m.refreshClientPresence()
    if m.portalRequiredFor(phoneA) || m.portalRequiredFor(phoneB) {
        t.Fatal("Wi-Fi count mismatch alone revoked an unidentified active phone")
    }
    // Explicit credential takeover still works with stale tether IPs.
    if ok, msg := m.submitPortalAccountLogin(phoneC, "1001", "pass"); !ok { t.Fatal(msg) }
    if !m.portalRequiredFor(phoneA) || !m.flowAllowed(phoneC) {
        t.Fatal("account takeover failed while tether list is stale")
    }
}

func TestUnavailableWifiDumpDoesNotRevokeKnownTetherSession(t *testing.T) {
    m := newPortalManager(t)
    pushConfig(t, m, accountForTest("1001", "pass", 100000))
    m.submitPortalAccountLogin(phoneA, "1001", "pass")
    m.flowAttribution.dumpFn = func() (string, error) { return simulatedClientList(phoneA), nil }
    m.flowAttribution.wifiDumpFn = func() (string, error) { return "permission denied", fmt.Errorf("wifi service inaccessible") }
    m.refreshClientPresence()
    if got := sessionPresence(t, m, phoneA); got != "online" {
        t.Fatalf("unavailable wifi source must preserve working tether presence, got %q", got)
    }
}
