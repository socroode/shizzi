package datapath

import (
    "crypto/subtle"
    "net/netip"
    "strings"
    "sync"
    "time"

    "dev.shizzi/datapath/core"
)

// v2AccountBridge is an ADAPTER between the immutable voucher/account ledger
// restored from 0.4.3.5.3 and the new station-based policy engine. It does not
// enable the legacy NAT datapath or bypass v2UIRecoveryOnly.
type v2AccountBridge struct {
    manager *TrafficManager
    registry *core.Registry
    nat *core.NATIndex
    mu sync.Mutex
    credentialVersions map[string]string
}

var _ core.PINVerifier = (*v2AccountBridge)(nil)

func newV2AccountBridge(manager *TrafficManager) *v2AccountBridge {
    return &v2AccountBridge{
        manager: manager,
        registry: core.NewRegistry(),
        nat: core.NewNATIndex(v2NATSnapshotAge),
        credentialVersions: make(map[string]string),
    }
}

// VerifyPIN runs against the CURRENT Android-pushed PIN hashes, not a copy
// retained from an earlier voucher or session. It doesn't grant any rights.
// The actual portal must separately prove the original physical client IP.
func (b *v2AccountBridge) VerifyPIN(rawAccount, pin string) bool {
    account := normalizeAccountNumber(rawAccount)
    if account == "" || pin == "" { return false }
    b.manager.mu.Lock()
    record, found := b.manager.portalAccounts[account]
    b.manager.mu.Unlock()
    if !found || !record.Enabled || record.PinHash == "" { return false }

    got := hashPortalPin(record.PinSalt, pin)
    if len(got) != len(record.PinHash) { return false }
    return subtle.ConstantTimeCompare([]byte(got), []byte(record.PinHash)) == 1
}

// Sync restores voucher entitlements from the Android durable state. It
// intentionally does not log out when the Internet or Media voucher expires:
// only rights expire; the local session and portal remain available.
//
// A changed PIN, deleted account, or explicit account suspension DOES revoke
// an existing login. No account/session binds permanently to an IP or MAC.
func (b *v2AccountBridge) Sync() {
    b.manager.mu.Lock()
    rights := make(map[string]core.Rights, len(b.manager.portalAccounts))
    versions := make(map[string]string, len(b.manager.portalAccounts))
    for number, account := range b.manager.portalAccounts {
        remaining := b.manager.remainingDataLocked(account)
        dataExpires := time.Time{}
        if account.DataValidUntilMillis <= 0 {
            // Legacy 0.4.3.5.3 deliberately supports unlimited-time Data
            // balances (0 means not time-limited).
            dataExpires = time.Unix(253402300799, 0)
        } else {
            dataExpires = time.UnixMilli(account.DataValidUntilMillis)
        }
        unlimitedExpires := time.Time{}
        if account.UnlimitedUntilMillis > 0 {
            unlimitedExpires = time.UnixMilli(account.UnlimitedUntilMillis)
        }
        mediaExpires := time.Time{}
        if account.MediaUntilMillis > 0 {
            mediaExpires = time.UnixMilli(account.MediaUntilMillis)
        }
        rights[number] = core.Rights{
            Disabled: !account.Enabled,
            DataBytes: remaining,
            DataValidUntil: dataExpires,
            UnlimitedUntil: unlimitedExpires,
            MediaUntil: mediaExpires,
        }
        versions[number] = account.PinSalt + ":" + account.PinHash
    }
    b.manager.mu.Unlock()

    b.mu.Lock()
    defer b.mu.Unlock()
    for number, previous := range b.credentialVersions {
        if current, found := versions[number]; !found || current != previous {
            b.registry.RevokeAccount(number)
        }
    }
    b.registry.ReplaceRights(rights)
    b.credentialVersions = versions
}

// Associated is called ONLY with a SoftAP-confirmed station MAC and the
// matching DHCP/neighbor IPv4 evidence from Android. Never infer this pair
// from arbitrary browser values or from a translated NAT source.
func (b *v2AccountBridge) Associated(mac, ip string) error {
    parsed, err := netip.ParseAddr(strings.TrimSpace(ip))
    if err != nil { return core.ErrInvalidIP }
    if err := b.registry.WifiJoined(mac); err != nil { return err }
    return b.registry.BindAddress(mac, parsed)
}

// Departed is called ONLY on a confirmed Wi-Fi disassociation event.
// Network inactivity, device sleep and a stale DHCP list are not departures.
func (b *v2AccountBridge) Departed(mac string) error {
    return b.registry.WifiDeparted(mac)
}
