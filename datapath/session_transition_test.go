package datapath

import (
	"testing"
	"time"
)

func TestAccountTakeoverAndSwitchClearPendingVoucherClaims(t *testing.T) {
	m := newPortalManager(t)
	pushConfig(t, m, accountForTest("1001", "pass", 1000000), accountForTest("1002", "pass", 1000000))
	m.submitPortalAccountLogin(phoneA, "1001", "pass")
	m.submitPortalRecharge(phoneA, "voucher-one")
	m.submitPortalAccountLogin(phoneB, "1001", "pass")
	if got := len(statsOf(t, m).PortalRechargeClaims); got != 0 {
		t.Fatalf("takeover retained %d old claims", got)
	}
	m.submitPortalRecharge(phoneB, "voucher-two")
	m.submitPortalAccountLogin(phoneB, "1002", "pass")
	if got := len(statsOf(t, m).PortalRechargeClaims); got != 0 {
		t.Fatalf("account switch retained %d old claims", got)
	}
}

func TestPacedTrafficIsRejectedWhenSessionChanges(t *testing.T) {
	for _, takeover := range []bool{false, true} {
		t.Run(map[bool]string{false: "same-address-account-switch", true: "other-device-takeover"}[takeover], func(t *testing.T) {
			m := newPortalManager(t)
			pushConfig(t, m, accountForTest("1001", "pass", 1000000), accountForTest("1002", "pass", 1000000))
			m.submitPortalAccountLogin(phoneA, "1001", "pass")
			limiter := m.globalDownloadLimiter
			limiter.setRate(8000)
			start := time.Now().Add(300 * time.Millisecond)
			limiter.mu.Lock()
			limiter.next = start
			limiter.mu.Unlock()
			result := make(chan bool, 1)
			go func() { result <- m.waitAllowed(phoneA, directionDownload, 1) }()
			// Wait for the actual limiter reservation, not an assumed scheduler delay.
			deadline := time.Now().Add(2 * time.Second)
			for {
				limiter.mu.Lock()
				reserved := limiter.next.After(start)
				limiter.mu.Unlock()
				if reserved {
					break
				}
				if time.Now().After(deadline) {
					t.Fatal("limiter was not reached")
				}
				time.Sleep(time.Millisecond)
			}
			if takeover {
				m.submitPortalAccountLogin(phoneB, "1001", "pass")
			} else {
				m.submitPortalAccountLogin(phoneA, "1002", "pass")
			}
			if <-result {
				t.Fatal("queued traffic released after session replacement")
			}
		})
	}
}

func TestAccountConfigRevocationClearsPendingVouchers(t *testing.T) {
	for _, change := range []string{"password", "disable", "delete"} {
		t.Run(change, func(t *testing.T) {
			m := newPortalManager(t)
			a := accountForTest("1001", "pass", 1000000)
			pushConfig(t, m, a)
			m.submitPortalAccountLogin(phoneA, "1001", "pass")
			m.submitPortalRecharge(phoneA, "voucher-one")
			switch change {
			case "password":
				a.PinHash = hashPortalPin(a.PinSalt, "new-pass")
				pushConfig(t, m, a)
			case "disable":
				a.Enabled = false
				pushConfig(t, m, a)
			case "delete":
				pushConfig(t, m)
			}
			if m.flowAllowed(phoneA) {
				t.Fatal("revoked account still has Internet")
			}
			if got := len(statsOf(t, m).PortalRechargeClaims); got != 0 {
				t.Fatalf("revocation retained %d claims", got)
			}
		})
	}
}
