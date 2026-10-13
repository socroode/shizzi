package datapath

import "testing"

// Do not activate the legacy connectivity engine by importing the 0.4.3.5.3
// UI. Only the independent V2 core may eventually authorize Internet.
func TestLegacyInternetForwardingDisabledInUIRecovery(t *testing.T) {
    if !v2UIRecoveryOnly {
        t.Fatal("legacy forwarder re-enabled before V2 account/PIN policy integration")
    }
}
