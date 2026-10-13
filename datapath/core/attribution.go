package core

import (
    "net/netip"
    "strings"
    "sync"
    "time"
)

// FlowKey includes protocol, translated source, and exact destination. A
// translated source port on its own is NEVER a safe device identifier.
type FlowKey struct {
    Protocol string
    Translated netip.AddrPort
    Destination netip.AddrPort
}

type natOwner struct {
    ip netip.Addr
    ambiguous bool
}

type Attribution string

const (
    ExactNAT Attribution = "exact_nat"
    MissingNAT Attribution = "no_exact_nat_rule"
    ConflictingNAT Attribution = "conflicting_nat_owners"
    StaleNAT Attribution = "stale_nat_snapshot"
)

// NATIndex is an isolated, replaceable attribution adapter. It knows NOTHING
// about accounts, vouchers or authorization. An Android-specific collector
// will publish fresh snapshots asynchronously (no dumpsys in flow handlers).
// Failures invalidate the snapshot instead of retaining stale ownership.
type NATIndex struct {
    mu sync.RWMutex
    owners map[FlowKey]natOwner
    observedAt time.Time
    maxAge time.Duration
}

func NewNATIndex(maxAge time.Duration) *NATIndex {
    if maxAge <= 0 { maxAge = 2 * time.Second }
    return &NATIndex{owners: make(map[FlowKey]natOwner), maxAge: maxAge}
}

func (n *NATIndex) Invalidate() {
    n.mu.Lock()
    n.owners = make(map[FlowKey]natOwner)
    n.observedAt = time.Time{}
    n.mu.Unlock()
}

func (n *NATIndex) Replace(snapshot string, observed time.Time) int {
    parsed := parseIPv4Upstream(snapshot)
    n.mu.Lock()
    n.owners = parsed
    n.observedAt = observed
    n.mu.Unlock()
    return len(parsed)
}

// ResolveExact returns an address only for a fresh and unambiguous full tuple.
func (n *NATIndex) ResolveExact(key FlowKey, now time.Time) (netip.Addr, Attribution) {
    n.mu.RLock()
    defer n.mu.RUnlock()
    if n.observedAt.IsZero() || now.Before(n.observedAt) ||
        now.Sub(n.observedAt) > n.maxAge {
        return netip.Addr{}, StaleNAT
    }
    record, exists := n.owners[key]
    if !exists { return netip.Addr{}, MissingNAT }
    if record.ambiguous { return netip.Addr{}, ConflictingNAT }
    return record.ip, ExactNAT
}

// parseIPv4Upstream recognizes Android's upstream table; it deliberately
// ignores downstream and historical/client-info sections. A vendor format
// that does not match is a MISS, not a guess. IPv6 needs its own adapter.
func parseIPv4Upstream(snapshot string) map[FlowKey]natOwner {
    result := make(map[FlowKey]natOwner)
    inUpstream := false
    for _, raw := range strings.Split(snapshot, "\n") {
        line := strings.TrimSpace(raw)
        if strings.HasPrefix(line, "IPv4 Upstream:") {
            inUpstream = true
            continue
        }
        if strings.HasPrefix(line, "IPv4 Downstream:") ||
            strings.HasPrefix(line, "IPv6 Upstream:") ||
            strings.HasPrefix(line, "IPv6 Downstream:") {
            inUpstream = false
            continue
        }
        if !inUpstream { continue }

        // Example:
        // tcp [aa:bb:..] 41(41) 192.168.192.161:40766 ->
        // 42(testtun0) 192.0.2.2:40766 -> 14.29.106.145:443 [...]
        sections := strings.Split(line, " -> ")
        if len(sections) != 3 { continue }
        fields := strings.Fields(sections[0])
        if len(fields) < 2 { continue }
        proto := strings.ToLower(fields[0])
        if proto != "tcp" && proto != "udp" { continue }

        client, errClient := netip.ParseAddrPort(fields[len(fields)-1])
        translatedFields := strings.Fields(sections[1])
        destinationFields := strings.Fields(sections[2])
        if errClient != nil || len(translatedFields) == 0 || len(destinationFields) == 0 {
            continue
        }
        translated, errTranslated := netip.ParseAddrPort(translatedFields[len(translatedFields)-1])
        destination, errDestination := netip.ParseAddrPort(destinationFields[0])
        if errTranslated != nil || errDestination != nil || !validClientIP(client.Addr()) ||
            !translated.Addr().Is4() || !destination.Addr().Is4() { continue }

        key := FlowKey{Protocol: proto, Translated: translated, Destination: destination}
        previous, seen := result[key]
        if seen && (previous.ambiguous || previous.ip != client.Addr()) {
            result[key] = natOwner{ambiguous: true}
        } else {
            result[key] = natOwner{ip: client.Addr()}
        }
    }
    return result
}

// Gate applies the rights of the specific, proven physical client. A
// different client sharing a translated address never inherits access.
type Gate struct {
    Sessions *Registry
    NAT *NATIndex
}

func (g Gate) CheckTranslated(key FlowKey, service Service, now time.Time) (Decision, Attribution) {
    if g.Sessions == nil || g.NAT == nil { return DenyUnknownStation, MissingNAT }
    client, reason := g.NAT.ResolveExact(key, now)
    if reason != ExactNAT { return DenyUnknownStation, reason }
    return g.Sessions.AuthorizeDirect(client, service, now), reason
}
