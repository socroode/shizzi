package datapath

import (
	"encoding/json"
	"testing"
	"time"
)

const (
	phoneA = "192.168.7.66"
	phoneB = "192.168.7.77"
	phoneC = "192.168.7.88"
)

func accountForTest(number, pin string, dataBytes int64) PortalAccount {
	salt := "salt-" + number
	return PortalAccount{
		Number:                    number,
		Name:                      "Compte " + number,
		PinSalt:                   salt,
		PinHash:                   hashPortalPin(salt, pin),
		Enabled:                   true,
		DataBalanceBytes:          dataBytes,
		DataValidUntilMillis:      time.Now().Add(24 * time.Hour).UnixMilli(),
		DataDownloadBitsPerSecond: 10_000_000,
		DataUploadBitsPerSecond:   5_000_000,
	}
}

func pushConfig(t *testing.T, manager *TrafficManager, accounts ...PortalAccount) {
	t.Helper()
	raw, err := json.Marshal(portalConfig{Accounts: accounts})
	if err != nil {
		t.Fatal(err)
	}
	manager.setPortalConfig(true, string(raw))
}

func statsOf(t *testing.T, manager *TrafficManager) trafficStatsSnapshot {
	t.Helper()
	var snapshot trafficStatsSnapshot
	if err := json.Unmarshal([]byte(manager.statsJSON()), &snapshot); err != nil {
		t.Fatal(err)
	}
	return snapshot
}

func newPortalManager(t *testing.T) *TrafficManager {
	t.Helper()
	manager := newTrafficManager()
	manager.flowAttribution.dumpFn = func() (string, error) { return "", nil }
	return manager
}

// Test 1: three accounts, three phones, each needs its own login.
func TestThreeAccountsEachNeedTheirOwnPortalLogin(t *testing.T) {
	manager := newPortalManager(t)
	pushConfig(t, manager,
		accountForTest("1001", "aaaa", 1_000_000),
		accountForTest("1002", "bbbb", 1_000_000),
		accountForTest("1003", "cccc", 1_000_000),
	)

	for _, ip := range []string{phoneA, phoneB, phoneC} {
		if manager.flowAllowed(ip) {
			t.Fatalf("%s has Internet before login", ip)
		}
	}
	if ok, _ := manager.submitPortalAccountLogin(phoneA, "1001", "aaaa"); !ok {
		t.Fatal("A login failed")
	}
	if !manager.flowAllowed(phoneA) {
		t.Fatal("A blocked after login")
	}
	if manager.flowAllowed(phoneB) || manager.flowAllowed(phoneC) {
		t.Fatal("B or C inherited A's authorization")
	}
	manager.submitPortalAccountLogin(phoneB, "1002", "bbbb")
	manager.submitPortalAccountLogin(phoneC, "1003", "cccc")

	manager.account(phoneA, directionDownload, 100)
	manager.account(phoneB, directionDownload, 200)
	manager.account(phoneC, directionDownload, 300)

	usage := map[string]int64{}
	for _, item := range statsOf(t, manager).AccountUsage {
		usage[item.AccountNumber] = item.DownBytes
	}
	if usage["1001"] != 100 || usage["1002"] != 200 || usage["1003"] != 300 {
		t.Fatalf("usage not separated: %+v", usage)
	}
}

// Test 2: one account, two phones, one shared Data pool.
func TestSameAccountOnTwoPhonesSharesOneDataPool(t *testing.T) {
	manager := newPortalManager(t)
	pushConfig(t, manager, accountForTest("2000", "roniu", 20_000))

	manager.submitPortalAccountLogin(phoneB, "2000", "roniu")
	manager.submitPortalAccountLogin(phoneC, "2000", "roniu")

	manager.account(phoneB, directionDownload, 3_000)
	manager.account(phoneC, directionDownload, 2_000)

	manager.mu.Lock()
	remaining := manager.remainingDataLocked(manager.portalAccounts["2000"])
	manager.mu.Unlock()
	if remaining != 15_000 {
		t.Fatalf("remaining=%d, want 15000 (one pool, not 20000 per phone)", remaining)
	}

	stats := statsOf(t, manager)
	if len(stats.PortalAuthorizations) != 2 {
		t.Fatalf("sessions=%d, want 2", len(stats.PortalAuthorizations))
	}
	for _, session := range stats.PortalAuthorizations {
		if session.AccountNumber != "2000" {
			t.Fatalf("session %s on account %s", session.IP, session.AccountNumber)
		}
	}

	// C drains the rest: both phones stop together.
	manager.account(phoneC, directionDownload, 15_000)
	if manager.flowAllowed(phoneB) || manager.flowAllowed(phoneC) {
		t.Fatal("a phone kept Internet after the shared pool ran out")
	}
	if !manager.portalRequiredFor(phoneB) {
		t.Fatal("portal not reachable for recharge once pool is empty")
	}
}

// Android deducts reported usage and echoes a marker; the live balance must
// stay exact across that round trip, without per-tick config pushes.
func TestConsumptionMarkerRoundTripKeepsBalanceExact(t *testing.T) {
	manager := newPortalManager(t)
	account := accountForTest("3000", "pin1", 10_000)
	pushConfig(t, manager, account)
	manager.submitPortalAccountLogin(phoneA, "3000", "pin1")

	manager.account(phoneA, directionDownload, 4_000)

	// Android applied 4000 and pushes balance 6000 with marker 4000, while
	// 500 more bytes were already carried.
	manager.account(phoneA, directionDownload, 500)
	account.DataBalanceBytes = 6_000
	account.ConsumedMarkerBytes = 4_000
	account.MarkerEpoch = manager.epoch
	pushConfig(t, manager, account)

	manager.mu.Lock()
	remaining := manager.remainingDataLocked(manager.portalAccounts["3000"])
	manager.mu.Unlock()
	if remaining != 5_500 {
		t.Fatalf("remaining=%d, want 5500", remaining)
	}

	// A marker from another datapath instance is ignored.
	account.MarkerEpoch = manager.epoch - 1
	pushConfig(t, manager, account)
	manager.mu.Lock()
	remaining = manager.remainingDataLocked(manager.portalAccounts["3000"])
	manager.mu.Unlock()
	if remaining != 6_000-4_500 {
		t.Fatalf("remaining with foreign marker=%d", remaining)
	}
}

// Re-pushing the config (e.g. after an admin edit) must not end or reset
// sessions, and must not reset the limiter's pacing.
func TestConfigPushKeepsSessionsAndLimiterState(t *testing.T) {
	manager := newPortalManager(t)
	account := accountForTest("4000", "pin1", 1_000_000)
	pushConfig(t, manager, account)
	manager.submitPortalAccountLogin(phoneA, "4000", "pin1")
	manager.waitAllowed(phoneA, directionDownload, 1)

	manager.mu.Lock()
	limiter := manager.clients[phoneA].downloadLimiter
	limiter.mu.Lock()
	limiter.next = time.Now().Add(time.Hour)
	limiter.mu.Unlock()
	manager.mu.Unlock()

	pushConfig(t, manager, account)
	manager.mu.Lock()
	limiter.setRate(10_000_000)
	manager.mu.Unlock()
	limiter.mu.Lock()
	kept := limiter.next.After(time.Now().Add(30 * time.Minute))
	limiter.mu.Unlock()
	if !kept {
		t.Fatal("same-rate setRate reset the limiter")
	}
	if manager.portalRequiredFor(phoneA) {
		t.Fatal("config push logged the session out")
	}
}

// Test 3: logout on B, same account opens on D.
func TestAccountIsPortableAcrossDevices(t *testing.T) {
	manager := newPortalManager(t)
	pushConfig(t, manager, accountForTest("5000", "pin1", 1_000_000))

	manager.submitPortalAccountLogin(phoneB, "5000", "pin1")
	if ok, _ := manager.submitPortalLogout(phoneB); !ok {
		t.Fatal("logout failed")
	}
	if manager.flowAllowed(phoneB) {
		t.Fatal("B kept Internet after logout")
	}
	const phoneD = "192.168.7.99"
	if ok, _ := manager.submitPortalAccountLogin(phoneD, "5000", "pin1"); !ok {
		t.Fatal("account locked to previous device")
	}
	if !manager.flowAllowed(phoneD) {
		t.Fatal("D has no Internet")
	}
}

// Test 5: authenticated but no credit.
func TestAuthenticatedWithoutCreditKeepsPortalOnly(t *testing.T) {
	manager := newPortalManager(t)
	empty := accountForTest("6000", "pin1", 0)
	empty.DataValidUntilMillis = 0
	pushConfig(t, manager, empty)

	ok, message := manager.submitPortalAccountLogin(phoneA, "6000", "pin1")
	if !ok {
		t.Fatalf("login refused: %s", message)
	}
	if manager.flowAllowed(phoneA) {
		t.Fatal("Internet without credit")
	}
	if !manager.portalRequiredFor(phoneA) {
		t.Fatal("portal not served without credit")
	}
	if !manager.waitAllowedWithPortalBypass(phoneA, directionUpload, 60, true) {
		t.Fatal("DNS blocked without credit")
	}
	if ok, _ := manager.submitPortalRecharge(phoneA, "shz-aaaa-bbbb"); !ok {
		t.Fatal("recharge claim refused")
	}

	// Android redeems and pushes the new balance.
	empty.DataBalanceBytes = 5_000
	empty.DataValidUntilMillis = time.Now().Add(time.Hour).UnixMilli()
	pushConfig(t, manager, empty)
	if !manager.flowAllowed(phoneA) {
		t.Fatal("Internet not restored after recharge")
	}
}

func TestUnlimitedDoesNotConsumeStoredData(t *testing.T) {
	manager := newPortalManager(t)
	account := accountForTest("7000", "pin1", 1_000)
	account.UnlimitedUntilMillis = time.Now().Add(time.Hour).UnixMilli()
	account.UnlimitedDownloadBitsPerSecond = 20_000_000
	pushConfig(t, manager, account)
	manager.submitPortalAccountLogin(phoneA, "7000", "pin1")

	manager.account(phoneA, directionDownload, 50_000)
	if !manager.flowAllowed(phoneA) {
		t.Fatal("unlimited blocked")
	}
	stats := statsOf(t, manager)
	if stats.AccountUsage[0].DataBytes != 0 || stats.AccountUsage[0].DownBytes != 50_000 {
		t.Fatalf("usage=%+v", stats.AccountUsage[0])
	}
	if stats.PortalAuthorizations[0].DownloadBps != 20_000_000 {
		t.Fatalf("unlimited speed not applied: %+v", stats.PortalAuthorizations[0])
	}
}

func TestDNSIsCarriedButNeverBilledWhenUnattributed(t *testing.T) {
	manager := newPortalManager(t)
	pushConfig(t, manager, accountForTest("8000", "pin1", 1_000))
	manager.setRequireClientAttribution(true)

	if !manager.waitAllowedWithPortalBypass("", directionUpload, 40, true) {
		t.Fatal("unattributed DNS dropped")
	}
	manager.account("", directionUpload, 40)
	if manager.waitAllowed("", directionUpload, 40) {
		t.Fatal("unattributed non-DNS allowed")
	}
	stats := statsOf(t, manager)
	if stats.UnattributedDNSBytes != 40 || len(stats.AccountUsage) != 0 {
		t.Fatalf("dns billing: %+v", stats)
	}
}

// A phone that leaves must not hand its login to the next phone that gets
// the same DHCP address.
func TestDepartedClientSessionIsReleased(t *testing.T) {
	manager := newTrafficManager()
	connected := "{/192.168.7.66=downstream: 41, /192.168.7.77=downstream: 41}"
	manager.flowAttribution.dumpFn = func() (string, error) {
		return "IPv4 Upstream:\nIPv4 Downstream:\n" + connected, nil
	}
	pushConfig(t, manager, accountForTest("9000", "pin1", 1_000_000))
	manager.submitPortalAccountLogin(phoneA, "9000", "pin1")

	manager.flowAttribution.refreshIfOlderThan(0)
	manager.mu.Lock()
	manager.pruneDepartedLocked(manager.flowAttribution.presence(), time.Now())
	manager.mu.Unlock()
	if manager.portalRequiredFor(phoneA) {
		t.Fatal("present client logged out")
	}

	connected = "{/192.168.7.77=downstream: 41}"
	manager.flowAttribution.refreshIfOlderThan(0)
	now := time.Now()
	manager.mu.Lock()
	manager.pruneDepartedLocked(manager.flowAttribution.presence(), now)
	manager.pruneDepartedLocked(manager.flowAttribution.presence(), now.Add(departedClientGrace))
	manager.mu.Unlock()
	if !manager.portalRequiredFor(phoneA) {
		t.Fatal("departed client's session survived")
	}
}

func TestPresenceUnknownNeverLogsOut(t *testing.T) {
	manager := newPortalManager(t)
	pushConfig(t, manager, accountForTest("9100", "pin1", 1_000_000))
	manager.submitPortalAccountLogin(phoneA, "9100", "pin1")
	now := time.Now()
	manager.mu.Lock()
	manager.pruneDepartedLocked(manager.flowAttribution.presence(), now)
	manager.pruneDepartedLocked(manager.flowAttribution.presence(), now.Add(time.Hour))
	manager.mu.Unlock()
	if manager.portalRequiredFor(phoneA) {
		t.Fatal("session dropped without an authoritative client list")
	}
}

// C's brand-new flow shares a translated port with B's rule but has no rule
// of its own yet: it must not be billed to B.
func TestSharedTranslatedPortWithoutOwnRuleIsNotGuessed(t *testing.T) {
	resolver := newFlowAttributionResolver()
	resolver.dumpFn = func() (string, error) {
		return `IPv4 Upstream:
 tcp [aa:aa:aa:aa:aa:01] 47(47) 192.168.7.77:50000 -> 76(testtun28) 192.0.2.2:50000 -> 142.250.1.1:443 [00:00:00:00:00:00] 1500 3ms
IPv4 Downstream:
{/192.168.7.77=downstream: 41, /192.168.7.88=downstream: 41}`, nil
	}
	got := resolver.resolve(flowAttributionKey{"tcp", "192.0.2.2", 50000, "157.240.1.1", 443}, false)
	if got != "" {
		t.Fatalf("flow guessed as %q", got)
	}
	if resolver.snapshot().LooseCandidateFlows != 1 {
		t.Fatal("loose candidate not reported")
	}
}

// With one phone on Android's client list the only rule-less flow is its own;
// but the fallback must never rely on the IPs found in NAT rules.
func TestSingleClientFallbackNeedsAndroidClientList(t *testing.T) {
	resolver := newFlowAttributionResolver()
	resolver.dumpFn = func() (string, error) {
		return `IPv4 Upstream:
 tcp [aa:aa:aa:aa:aa:01] 47(47) 192.168.7.77:50000 -> 76(testtun28) 192.0.2.2:50000 -> 142.250.1.1:443 [00:00:00:00:00:00] 1500 3ms
IPv4 Downstream:`, nil
	}
	if got := resolver.resolve(flowAttributionKey{"tcp", "192.0.2.2", 50001, "1.1.1.1", 443}, true); got != "" {
		t.Fatalf("fallback used NAT-rule IPs: %q", got)
	}

	resolver = newFlowAttributionResolver()
	resolver.dumpFn = func() (string, error) {
		return "IPv4 Upstream:\nIPv4 Downstream:\n{/192.168.7.77=downstream: 41}", nil
	}
	if got := resolver.resolve(flowAttributionKey{"tcp", "192.0.2.2", 50001, "1.1.1.1", 443}, true); got != phoneB {
		t.Fatalf("single listed client not resolved: %q", got)
	}
}

func TestRepeatedMissDoesNotWaitAgain(t *testing.T) {
	resolver := newFlowAttributionResolver()
	resolver.dumpFn = func() (string, error) {
		return "IPv4 Upstream:\nIPv4 Downstream:\n{/192.168.7.66=downstream: 41, /192.168.7.77=downstream: 41}", nil
	}
	key := flowAttributionKey{"udp", "192.0.2.2", 40000, "1.1.1.1", 443}
	resolver.resolve(key, true)
	started := time.Now()
	resolver.resolve(key, true)
	if time.Since(started) > 500*time.Millisecond {
		t.Fatal("retried unresolved tuple waited again")
	}
}

func TestDownstreamRulesYieldClientMAC(t *testing.T) {
	macs := parseIPv4DownstreamClientMACs(sampleTetheringDump)
	if macs["192.168.43.20"] != "aa:bb:cc:dd:ee:ff" {
		t.Fatalf("macs=%v", macs)
	}
}
