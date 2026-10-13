// Package core contains the device-identity and authorization invariants for
// Shizzi V2. It does not depend on Android APIs or on the TUN implementation.
package core

import (
    "errors"
    "net"
    "net/netip"
    "strings"
    "sync"
    "time"
)

var (
    ErrInvalidStation = errors.New("invalid Wi-Fi station MAC")
    ErrInvalidIP = errors.New("invalid client IPv4 address")
    ErrStationAbsent = errors.New("station is not associated with Wi-Fi")
    ErrUnboundStation = errors.New("station has no verified IPv4 address")
    ErrInvalidAccount = errors.New("empty account number")
    ErrLoginDenied = errors.New("invalid credentials or unverifiable Wi-Fi client")
)

type Rights struct {
    Disabled bool
    DataBytes int64
    DataValidUntil time.Time
    UnlimitedUntil time.Time
    MediaUntil time.Time
}

func (r Rights) InternetAllowed(now time.Time) bool {
    if r.Disabled { return false }
    unlimited := !r.UnlimitedUntil.IsZero() && now.Before(r.UnlimitedUntil)
    data := r.DataBytes > 0 && !r.DataValidUntil.IsZero() && now.Before(r.DataValidUntil)
    return unlimited || data
}

func (r Rights) MediaAllowed(now time.Time) bool {
    return !r.Disabled && !r.MediaUntil.IsZero() && now.Before(r.MediaUntil)
}

type station struct {
    ip netip.Addr
    account string
    // generation changes on every authentication/revocation. It prevents a
    // TCP/UDP socket from inheriting a later session on the same station.
    generation uint64
}

type StationSnapshot struct {
    MAC string
    IP netip.Addr
    Account string
    Associated bool
}

// Registry keeps only an ephemeral association between a Wi-Fi station, its
// VERIFIED DHCP/neighbor IPv4 address, and its logged-in account. An address
// or MAC alone is not proof of credentials and never creates authorization.
//
// WifiDeparted must be called on an authoritative SoftAP departure event.
// An absent DNS/NAT rule, a sleeping device, or a timed-out dumpsys is NOT a
// departure event. A later WifiJoined starts a new unauthenticated session.
// Account rights persist independently of the station's Wi-Fi lifetime.
type Registry struct {
    mu sync.RWMutex
    stations map[string]*station
    ipOwner map[netip.Addr]string
    accountOwner map[string]string
    rights map[string]Rights
}

func NewRegistry() *Registry {
    return &Registry{
        stations: make(map[string]*station),
        ipOwner: make(map[netip.Addr]string),
        accountOwner: make(map[string]string),
        rights: make(map[string]Rights),
    }
}

func canonicalMAC(value string) (string, error) {
    parsed, err := net.ParseMAC(strings.TrimSpace(value))
    if err != nil || len(parsed) != 6 || parsed[0]&1 != 0 {
        return "", ErrInvalidStation
    }
    allZero := true
    for _, b := range parsed { if b != 0 { allZero = false } }
    if allZero { return "", ErrInvalidStation }
    return strings.ToLower(parsed.String()), nil
}

func validClientIP(ip netip.Addr) bool {
    return ip.IsValid() && ip.Is4() && !ip.IsUnspecified() &&
        !ip.IsLoopback() && !ip.IsMulticast() && !ip.IsLinkLocalUnicast()
}

func (r *Registry) WifiJoined(mac string) error {
    id, err := canonicalMAC(mac)
    if err != nil { return err }
    r.mu.Lock()
    defer r.mu.Unlock()
    // A second join notification is not a disconnect or a new login.
    if _, present := r.stations[id]; !present {
        r.stations[id] = &station{}
    }
    return nil
}

// BindAddress requires independent Android DHCP/neighbor evidence linking
// the IPv4 address to the already associated MAC. It must not be called with
// an address guessed from an unrelated NAT flow.
func (r *Registry) BindAddress(mac string, ip netip.Addr) error {
    id, err := canonicalMAC(mac)
    if err != nil { return err }
    if !validClientIP(ip) { return ErrInvalidIP }

    r.mu.Lock()
    defer r.mu.Unlock()
    target, present := r.stations[id]
    if !present { return ErrStationAbsent }
    if target.ip == ip { return nil }

    if oldOwner, taken := r.ipOwner[ip]; taken && oldOwner != id {
        if old := r.stations[oldOwner]; old != nil {
            // Address reuse must never inherit the old user's authorization.
            r.revokeLoginLocked(oldOwner, old)
            old.ip = netip.Addr{}
        }
    }
    if target.ip.IsValid() { delete(r.ipOwner, target.ip) }
    target.ip = ip
    r.ipOwner[ip] = id
    return nil
}

func (r *Registry) revokeLoginLocked(mac string, s *station) {
    s.generation++
    if s.account != "" && r.accountOwner[s.account] == mac {
        delete(r.accountOwner, s.account)
    }
    s.account = ""
}

func (r *Registry) WifiDeparted(mac string) error {
    id, err := canonicalMAC(mac)
    if err != nil { return err }
    r.mu.Lock()
    defer r.mu.Unlock()
    s, found := r.stations[id]
    if !found { return nil }
    if s.ip.IsValid() && r.ipOwner[s.ip] == id { delete(r.ipOwner, s.ip) }
    r.revokeLoginLocked(id, s)
    delete(r.stations, id)
    return nil
}

// GrantVerifiedLogin MUST be called only AFTER the credential validator has
// approved the account number and password. A successful login takes over the
// account from its previous device without disconnecting either device's Wi-Fi.
func (r *Registry) GrantVerifiedLogin(mac, account string) error {
    id, err := canonicalMAC(mac)
    if err != nil { return err }
    account = strings.TrimSpace(account)
    if account == "" { return ErrInvalidAccount }

    r.mu.Lock()
    defer r.mu.Unlock()
    s, found := r.stations[id]
    if !found { return ErrStationAbsent }
    if !s.ip.IsValid() { return ErrUnboundStation }

    if otherMAC, ok := r.accountOwner[account]; ok && otherMAC != id {
        if other := r.stations[otherMAC]; other != nil {
            r.revokeLoginLocked(otherMAC, other)
        }
    }
    r.revokeLoginLocked(id, s)
    s.account = account
    s.generation++
    r.accountOwner[account] = id
    return nil
}


// PINVerifier is the only credential authority. The raw PIN must be checked
// against a securely stored password hash by the account module. Never
// authenticate based only on a station MAC, IP, voucher code or HTTP cookie.
type PINVerifier interface {
    VerifyPIN(accountNumber, pin string) bool
}

// AuthenticateFromPhysicalIP is called by the portal listener only when its
// remote address is the ORIGINAL physical hotspot-client IP, before NAT.
// Requests reaching gVisor as shared 192.0.2.2 cannot use this entrypoint.
// A Wi-Fi departure and rejoin during slow PIN verification cannot resurrect
// the previous session: the old station object no longer exists.
func (r *Registry) AuthenticateFromPhysicalIP(ip netip.Addr, account, pin string, verifier PINVerifier) error {
    account = strings.TrimSpace(account)
    if !validClientIP(ip) || ip == netip.MustParseAddr("192.0.2.2") ||
        account == "" || pin == "" || verifier == nil {
        return ErrLoginDenied
    }

    r.mu.RLock()
    mac, known := r.ipOwner[ip]
    s := r.stations[mac]
    r.mu.RUnlock()
    if !known || s == nil { return ErrLoginDenied }

    // Credential work is outside the registry lock. Verify again below that
    // the physical session and its IP have NOT been replaced in the meantime.
    if !verifier.VerifyPIN(account, pin) { return ErrLoginDenied }

    r.mu.Lock()
    defer r.mu.Unlock()
    current := r.stations[mac]
    if current != s || current == nil || current.ip != ip || r.ipOwner[ip] != mac {
        return ErrLoginDenied
    }
    if otherMAC, used := r.accountOwner[account]; used && otherMAC != mac {
        if other := r.stations[otherMAC]; other != nil {
            r.revokeLoginLocked(otherMAC, other)
        }
    }
    r.revokeLoginLocked(mac, current)
    current.account = account
    current.generation++
    r.accountOwner[account] = mac
    return nil
}

func (r *Registry) Logout(mac string) error {
    id, err := canonicalMAC(mac)
    if err != nil { return err }
    r.mu.Lock()
    defer r.mu.Unlock()
    if s := r.stations[id]; s != nil { r.revokeLoginLocked(id, s) }
    return nil
}

// RevokeAccount ends the current authenticated device session without
// disconnecting Wi-Fi or deleting the owner's voucher balance.
func (r *Registry) RevokeAccount(account string) {
    r.mu.Lock()
    defer r.mu.Unlock()
    mac, ok := r.accountOwner[account]
    if !ok { return }
    if s := r.stations[mac]; s != nil { r.revokeLoginLocked(mac, s) }
}

// ReplaceRights accepts the latest DURABLE account snapshot from Android.
// Disabled/deleted accounts immediately lose their active sessions. Updating
// a voucher never disconnects a station or invalidates its successful login.
func (r *Registry) ReplaceRights(latest map[string]Rights) {
    r.mu.Lock()
    defer r.mu.Unlock()
    next := make(map[string]Rights, len(latest))
    for account, rights := range latest {
        next[account] = rights
    }
    for account, mac := range r.accountOwner {
        rights, found := next[account]
        if !found || rights.Disabled {
            if s := r.stations[mac]; s != nil { r.revokeLoginLocked(mac, s) }
        }
    }
    r.rights = next
}

func (r *Registry) SetRights(account string, rights Rights) error {
    account = strings.TrimSpace(account)
    if account == "" { return ErrInvalidAccount }
    r.mu.Lock()
    r.rights[account] = rights
    r.mu.Unlock()
    return nil
}

func (r *Registry) Snapshot(mac string) (StationSnapshot, bool) {
    id, err := canonicalMAC(mac)
    if err != nil { return StationSnapshot{}, false }
    r.mu.RLock()
    defer r.mu.RUnlock()
    s, found := r.stations[id]
    if !found { return StationSnapshot{}, false }
    return StationSnapshot{MAC: id, IP: s.ip, Account: s.account, Associated: true}, true
}

// InternetLease contains a temporary routing authorization. The account is
// the USER identity; IP/MAC are only ephemeral network/session selectors.
// Never expose generation as a credential or persist a device/account tie.
func (r *Registry) InternetLease(ip netip.Addr, now time.Time) (string, uint64, bool) {
    r.mu.RLock()
    defer r.mu.RUnlock()
    mac, ok := r.ipOwner[ip]
    if !ok { return "", 0, false }
    s := r.stations[mac]
    if s == nil || s.ip != ip || s.account == "" ||
        r.accountOwner[s.account] != mac { return "", 0, false }
    rights, found := r.rights[s.account]
    if !found || !rights.InternetAllowed(now) { return "", 0, false }
    return s.account, s.generation, true
}

// LeaseStillValid is called for EACH internet transfer, not just connect.
// This revokes existing TCP/UDP flows after Wi-Fi departure, takeover, new
// login on the same station, IP reuse, disabling or voucher expiry.
func (r *Registry) LeaseStillValid(ip netip.Addr, account string, generation uint64, now time.Time) bool {
    r.mu.RLock()
    defer r.mu.RUnlock()
    mac, ok := r.ipOwner[ip]
    if !ok { return false }
    s := r.stations[mac]
    if s == nil || s.ip != ip || s.account != account ||
        s.generation != generation || r.accountOwner[account] != mac { return false }
    rights, exists := r.rights[account]
    return exists && rights.InternetAllowed(now)
}

type Service int

const (
    Portal Service = iota
    Internet
    Media
)

type Decision string

const (
    Allow Decision = "allowed"
    DenyUnknownStation Decision = "unknown_station"
    DenyLoginRequired Decision = "login_required"
    DenyVoucherRequired Decision = "voucher_required"
)

// AuthorizeDirect checks requests whose original physical-client address is
// visible (for example a portal listener bound directly on the hotspot).
// No NAT tuple matching is required for this local path.
func (r *Registry) AuthorizeDirect(ip netip.Addr, service Service, now time.Time) Decision {
    r.mu.RLock()
    defer r.mu.RUnlock()
    // Public captive portal: an unrecognized physical client is allowed to
    // SEE the login page. Device recognition is needed only before binding
    // authenticated rights to a particular Wi-Fi session. The HTTP listener
    // must still be scoped to the private hotspot, never a WAN listener.
    if service == Portal && validClientIP(ip) { return Allow }
    mac, known := r.ipOwner[ip]
    if !known { return DenyUnknownStation }
    s, ok := r.stations[mac]
    if !ok || s.ip != ip { return DenyUnknownStation }
    if s.account == "" { return DenyLoginRequired }
    rights, ok := r.rights[s.account]
    if !ok { return DenyVoucherRequired }
    if service == Internet && rights.InternetAllowed(now) { return Allow }
    if service == Media && rights.MediaAllowed(now) { return Allow }
    return DenyVoucherRequired
}
