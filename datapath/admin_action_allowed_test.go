package datapath

import "testing"

// Keep the HTTP admin gateway allowlist in sync with Android's command dispatcher.
func TestAdminActionAllowedCouponCommands(t *testing.T) {
	for _, action := range []string{
		"account.coupon.redeem",       // Redeem an existing Internet or Media code.
		"account.coupon.issue",        // Issue Internet DATA or UNLIMITED directly.
		"account.coupon.media.issue",  // Issue a Media voucher directly.
		"account.create",              // Existing account commands remain available.
		"voucher.generate",
		"media.voucher.generate",
	} {
		if !adminActionAllowed(action) {
			t.Errorf("valid admin action %q was denied by the router", action)
		}
	}

	// Adding a few specific verbs must not open access to arbitrary admin commands.
	for _, action := range []string{
		"", "account.coupon", "account.coupon.delete",
		"account.coupon.issue.extra", "account.coupon.media.delete",
		"server.shutdown",
	} {
		if adminActionAllowed(action) {
			t.Errorf("unexpected admin action %q was allowed by the router", action)
		}
	}
}
