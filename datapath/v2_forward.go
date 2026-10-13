package datapath

import (
    "errors"
    "net"
    "net/netip"
    "strings"
    "sync"
    "time"

    "dev.shizzi/datapath/core"
    "gvisor.dev/gvisor/pkg/tcpip/stack"
)

const v2NATSnapshotAge = 4 * time.Second

// v2FlowLease binds an Internet stream to ONE authenticated session generation.
// It cannot be reused by a new account on the same IP or the same station.
type v2FlowLease struct {
    bridge *v2AccountBridge
    physicalIP netip.Addr
    account string
    generation uint64
}

type v2RatePair struct {
    upload *bandwidthLimiter
    download *bandwidthLimiter
}

// openV2Flow uses only a fresh full NAT tuple to select the physical source.
// MAC addresses are NOT user credentials; they never choose the account.
func (b *v2AccountBridge) openV2Flow(key core.FlowKey, now time.Time) (*v2FlowLease, error) {
    if b == nil || b.nat == nil {
        return nil, errors.New("v2 NAT collector not initialized")
    }
    if key.Protocol != "tcp" && key.Protocol != "udp" {
        return nil, errors.New("unsupported V2 transport")
    }
    ip, reason := b.nat.ResolveExact(key, now)
    if reason != core.ExactNAT {
        return nil, errors.New(string(reason))
    }
    account, gen, valid := b.registry.InternetLease(ip, now)
    if !valid { return nil, errors.New("client has no authenticated Internet voucher") }
    return &v2FlowLease{bridge: b, physicalIP: ip, account: account, generation: gen}, nil
}

// freshNATSnapshot is fed ONLY from a bounded asynchronous system snapshot,
// never from a live connection handler. Malformed/error snapshots immediately
// invalidate the index, so stale NAT ownership cannot be recycled.
func (b *v2AccountBridge) freshNATSnapshot() {
    if b == nil || b.nat == nil { return }
    output, err := dumpTetheringState()
    if err != nil {
        b.nat.Invalidate()
        return
    }
    b.nat.Replace(output, time.Now())
}

func v2Key(id stack.TransportEndpointID, protocol string) (core.FlowKey, bool) {
    src, errSrc := netip.ParseAddr(addressString(id.RemoteAddress))
    dst, errDst := netip.ParseAddr(addressString(id.LocalAddress))
    if errSrc != nil || errDst != nil || !src.Is4() || !dst.Is4() {
        return core.FlowKey{}, false
    }
    // Reject local portal and private gateway traffic from WAN forwarding.
    if dst == netip.MustParseAddr("192.0.2.1") || dst.IsPrivate() ||
        dst.IsLoopback() || dst.IsLinkLocalUnicast() || dst.IsMulticast() {
        return core.FlowKey{}, false
    }
    return core.FlowKey{
        Protocol: strings.ToLower(protocol),
        Translated: netip.AddrPortFrom(src, uint16(id.RemotePort)),
        Destination: netip.AddrPortFrom(dst, uint16(id.LocalPort)),
    }, true
}

// allowed checks the CURRENT voucher and this EXACT login, even if a socket
// was opened hours ago, the Wi-Fi went off/on or the same account moved.
func (lease *v2FlowLease) allowed(now time.Time) bool {
    return lease != nil && lease.bridge.registry.LeaseStillValid(
        lease.physicalIP, lease.account, lease.generation, now,
    )
}

// reserve authorizes and accounts a byte chunk BEFORE it can leave the
// hotspot. Each account has one shared rate/quota pool across all sockets.
// Reserve only the bytes actually permitted by the current Data quota.
// Note: a failed/short network write may overcount rather than leak free
// traffic; this will be reconciled during the real-device accounting phase.
func (lease *v2FlowLease) reserve(requested int, dir direction) int {
    if requested <= 0 || !lease.allowed(time.Now()) { return 0 }
    m := lease.bridge.manager
    m.mu.Lock()
    account, known := m.portalAccounts[lease.account]
    nowMs := time.Now().UnixMilli()
    if !known || !m.accountHasInternetLocked(account, nowMs) {
        m.mu.Unlock()
        return 0
    }
    bps := account.uploadBps(nowMs)
    if dir == directionDownload { bps = account.downloadBps(nowMs) }
    if bps <= 0 {
        m.mu.Unlock()
        return 0
    }
    limits := m.v2Limits[lease.account]
    if limits == nil {
        limits = &v2RatePair{
            upload: newBandwidthLimiter(account.uploadBps(nowMs)),
            download: newBandwidthLimiter(account.downloadBps(nowMs)),
        }
        m.v2Limits[lease.account] = limits
    }
    limits.upload.setRate(account.uploadBps(nowMs))
    limits.download.setRate(account.downloadBps(nowMs))
    limiter := limits.upload
    if dir == directionDownload { limiter = limits.download }
    m.mu.Unlock()

    // Pacing is shared by account, not by IP or per-socket. Revalidate AFTER
    // waiting: a concurrent takeover or expiry cancels queued bytes.
    limiter.wait(requested)
    if !lease.allowed(time.Now()) { return 0 }

    m.mu.Lock()
    defer m.mu.Unlock()
    if !lease.allowed(time.Now()) { return 0 }
    nowMs = time.Now().UnixMilli()
    account, known = m.portalAccounts[lease.account]
    if !known || !m.accountHasInternetLocked(account, nowMs) {
        return 0
    }
    rate := account.uploadBps(nowMs)
    if dir == directionDownload { rate = account.downloadBps(nowMs) }
    if rate <= 0 { return 0 }

    count := requested
    dataMode := !account.hasUnlimited(nowMs)
    if dataMode {
        remaining := m.remainingDataLocked(account)
        if remaining <= 0 { return 0 }
        if int64(count) > remaining { count = int(remaining) }
    }
    if count <= 0 { return 0 }
    usage := m.usageLocked(lease.account)
    if dir == directionDownload {
        usage.DownBytes += int64(count)
        m.totalDownBytes += int64(count)
    } else {
        usage.UpBytes += int64(count)
        m.totalUpBytes += int64(count)
    }
    if dataMode { usage.DataBytes += int64(count) }
    return count
}

func v2TCPRelay(client, upstream net.Conn, lease *v2FlowLease) {
    defer client.Close()
    defer upstream.Close()

    // A lease is checked on every 8-KiB chunk. If the session is revoked
    // while idle we also wake via deadlines instead of waiting indefinitely.
    var once sync.Once
    closeBoth := func() { once.Do(func() { _ = client.Close(); _ = upstream.Close() }) }
    done := make(chan struct{}, 2)
    go func() { v2StreamCopy(upstream, client, lease, directionUpload); closeBoth(); done <- struct{}{} }()
    go func() { v2StreamCopy(client, upstream, lease, directionDownload); closeBoth(); done <- struct{}{} }()
    <-done
}

func v2StreamCopy(dst, src net.Conn, lease *v2FlowLease, dir direction) {
    buffer := make([]byte, 8192)
    for lease.allowed(time.Now()) {
        _ = src.SetReadDeadline(time.Now().Add(2 * time.Second))
        read, err := src.Read(buffer)
        if timeout, ok := err.(net.Error); ok && timeout.Timeout() { continue }
        if read > 0 {
            allowed := lease.reserve(read, dir)
            if allowed <= 0 { return }
            // Recheck session immediately before writing, to avoid sending
            // bytes queued during a login takeover or Wi-Fi disassociation.
            if !lease.allowed(time.Now()) { return }
            _ = dst.SetWriteDeadline(time.Now().Add(10 * time.Second))
            if _, writeErr := dst.Write(buffer[:allowed]); writeErr != nil { return }
            if allowed < read { return }
        }
        if err != nil { return }
    }
}

func v2UDPRelay(client, upstream net.Conn, lease *v2FlowLease) {
    defer client.Close()
    defer upstream.Close()
    var once sync.Once
    closeBoth := func() { once.Do(func() { _ = client.Close(); _ = upstream.Close() }) }
    done := make(chan struct{}, 2)
    go func() { v2DatagramCopy(upstream, client, lease, directionUpload); closeBoth(); done <- struct{}{} }()
    go func() { v2DatagramCopy(client, upstream, lease, directionDownload); closeBoth(); done <- struct{}{} }()
    <-done
}

func v2DatagramCopy(dst, src net.Conn, lease *v2FlowLease, dir direction) {
    buffer := make([]byte, maxDatagramSize)
    for lease.allowed(time.Now()) {
        _ = src.SetReadDeadline(time.Now().Add(2 * time.Second))
        n, err := src.Read(buffer)
        if timeout, ok := err.(net.Error); ok && timeout.Timeout() { continue }
        if n > 0 {
            // UDP packets cannot be truncated. Reject the last datagram
            // if the remaining Data quota is smaller than its payload.
            allowed := lease.reserve(n, dir)
            if allowed != n { return }
            if !lease.allowed(time.Now()) { return }
            _ = dst.SetWriteDeadline(time.Now().Add(10 * time.Second))
            if _, writeErr := dst.Write(buffer[:n]); writeErr != nil { return }
        }
        if err != nil { return }
    }
}

// handleV2TCP never invokes legacy flowAllowed/resolveFlowClient/servePortal.
// Private/local destinations cannot be used to tunnel around voucher limits.
func handleV2TCP(client net.Conn, id stack.TransportEndpointID, dialer *net.Dialer, traffic *TrafficManager) {
    defer client.Close()
    if traffic == nil || traffic.v2Bridge == nil { return }
    key, valid := v2Key(id, "tcp")
    if !valid { return }
    lease, err := traffic.v2Bridge.openV2Flow(key, time.Now())
    if err != nil {
        traffic.noteRefusedUnauthorized()
        return
    }
    upstream, err := dialer.Dial("tcp", destinationOf(id))
    if err != nil { return }
    v2TCPRelay(client, upstream, lease)
}

func handleV2UDP(client net.Conn, id stack.TransportEndpointID, dialer *net.Dialer, traffic *TrafficManager) {
    defer client.Close()
    if traffic == nil || traffic.v2Bridge == nil { return }
    key, valid := v2Key(id, "udp")
    if !valid { return }
    lease, err := traffic.v2Bridge.openV2Flow(key, time.Now())
    if err != nil {
        traffic.noteRefusedUnauthorized()
        return
    }
    upstream, err := dialer.Dial("udp", destinationOf(id))
    if err != nil { return }
    v2UDPRelay(client, upstream, lease)
}
