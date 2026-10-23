package datapath

import (
    "testing"
    "time"
)

func TestConfirmedDepartureUsesTwentySecondGrace(t *testing.T) {
    if departedClientGrace != 20*time.Second {
        t.Fatalf("departure grace is %s, expected exactly 20s", departedClientGrace)
    }
    m := newPortalManager(t)
    pushConfig(t, m, accountForTest("1001", "pass", 100000), accountForTest("1002", "pass", 100000))
    if ok, msg := m.submitPortalAccountLogin(phoneA, "1001", "pass"); !ok { t.Fatal(msg) }
    if ok, msg := m.submitPortalAccountLogin(phoneB, "1002", "pass"); !ok { t.Fatal(msg) }

    presence := clientPresence{authoritative: true, clients: map[string]struct{}{phoneA: {}}}
    began := time.Unix(1000, 0)
    m.mu.Lock()
    m.pruneDepartedLocked(presence, began)
    if m.portalAuthorized[phoneB] == nil || !m.portalAuthorized[phoneB].missingSince.Equal(began) {
        t.Fatal("absence timer did not start on first confirmed missing observation")
    }
    m.pruneDepartedLocked(presence, began.Add(19*time.Second))
    if m.portalAuthorized[phoneB] == nil { t.Fatal("logged out before 20-second grace") }
    if m.portalAuthorized[phoneA] == nil { t.Fatal("logged out a present client") }
    m.pruneDepartedLocked(presence, began.Add(20*time.Second))
    if m.portalAuthorized[phoneB] != nil { t.Fatal("departed client retained after 20 seconds") }
    if m.portalAuthorized[phoneA] == nil { t.Fatal("present client lost access") }
    m.mu.Unlock()
    if ok, msg := m.submitPortalAccountLogin(phoneB, "1002", "pass"); !ok {
        t.Fatalf("portable login blocked after departure: %s", msg)
    }
}

func TestAmbiguousPresenceDoesNotTriggerTwentySecondLogout(t *testing.T) {
    m := newPortalManager(t)
    pushConfig(t, m, accountForTest("1001", "pass", 100000))
    m.submitPortalAccountLogin(phoneA, "1001", "pass")

    began := time.Unix(1000, 0)
    missing := clientPresence{authoritative: true, clients: map[string]struct{}{}}
    unknown := clientPresence{authoritative: false}
    m.mu.Lock()
    m.pruneDepartedLocked(missing, began)
    m.pruneDepartedLocked(unknown, began.Add(11*time.Second))
    if !m.portalAuthorized[phoneA].missingSince.IsZero() {
        t.Fatal("temporary unavailable Wi-Fi reading did not cancel departure countdown")
    }
    m.pruneDepartedLocked(missing, began.Add(21*time.Second))
    if m.portalAuthorized[phoneA] == nil { t.Fatal("uncertain presence revoked account") }
    m.mu.Unlock()
}
