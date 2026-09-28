package datapath

import (
	"encoding/json"
	"sort"
	"sync"
	"time"
)

const defaultBurstWindow = 250 * time.Millisecond

type direction int

const (
	directionUpload direction = iota
	directionDownload
)

type bandwidthLimiter struct {
	mu            sync.Mutex
	bitsPerSecond int64
	bytesPerSec   float64
	next          time.Time
	burstDuration time.Duration
}

func newBandwidthLimiter(bitsPerSecond int64) *bandwidthLimiter {
	l := &bandwidthLimiter{burstDuration: defaultBurstWindow, bitsPerSecond: -1}
	l.setRate(bitsPerSecond)
	return l
}

// setRate is a no-op when the rate is unchanged. Resetting the pacing clock on
// every policy sync used to hand each client a fresh burst every second, which
// made the configured speed inaccurate.
func (l *bandwidthLimiter) setRate(bitsPerSecond int64) {
	if bitsPerSecond < 0 {
		bitsPerSecond = 0
	}
	l.mu.Lock()
	defer l.mu.Unlock()

	if l.bitsPerSecond == bitsPerSecond {
		return
	}
	l.bitsPerSecond = bitsPerSecond
	l.next = time.Time{}
	if bitsPerSecond == 0 {
		l.bytesPerSec = 0
		return
	}
	l.bytesPerSec = float64(bitsPerSecond) / 8
}

func (l *bandwidthLimiter) wait(byteCount int) {
	if l == nil || byteCount <= 0 {
		return
	}

	l.mu.Lock()
	if l.bytesPerSec <= 0 {
		l.mu.Unlock()
		return
	}

	now := time.Now()
	floor := now.Add(-l.burstDuration)
	if l.next.IsZero() || l.next.Before(floor) {
		l.next = floor
	}

	transfer := time.Duration(float64(byteCount) / l.bytesPerSec * float64(time.Second))
	l.next = l.next.Add(transfer)
	target := l.next
	l.mu.Unlock()

	if delay := time.Until(target); delay > 0 {
		time.Sleep(delay)
	}
}

type ClientPolicy struct {
	DownloadBitsPerSecond int64
	UploadBitsPerSecond   int64
	QuotaBytes            int64
	Blocked               bool
}

type clientTraffic struct {
	IP        string
	UpBytes   int64
	DownBytes int64
	LastSeen  time.Time
	Policy    ClientPolicy

	downloadLimiter *bandwidthLimiter
	uploadLimiter   *bandwidthLimiter
}

func (c *clientTraffic) applyPolicy(policy ClientPolicy) {
	c.Policy = policy
	c.setRates(policy.DownloadBitsPerSecond, policy.UploadBitsPerSecond)
}

func (c *clientTraffic) setRates(downloadBps, uploadBps int64) {
	if c.downloadLimiter == nil {
		c.downloadLimiter = newBandwidthLimiter(downloadBps)
	} else {
		c.downloadLimiter.setRate(downloadBps)
	}
	if c.uploadLimiter == nil {
		c.uploadLimiter = newBandwidthLimiter(uploadBps)
	} else {
		c.uploadLimiter.setRate(uploadBps)
	}
}

// accountUsage counts bytes per account since this datapath instance started.
// It is shared by every session (device) logged into the account, which is what
// makes the Data balance a single pool for B and C on the same account.
//
// DataBytes counts only bytes carried while the account was in Data mode (no
// active Unlimited), i.e. exactly what must be deducted from the Data balance.
// Android applies deltas of these counters and echoes the applied DataBytes
// back as PortalAccount.ConsumedMarkerBytes, so both sides agree on the
// remaining balance without re-pushing the whole policy on every tick.
type accountUsage struct {
	UpBytes   int64
	DownBytes int64
	DataBytes int64
}

type TrafficManager struct {
	mu sync.Mutex

	epoch int64

	flowAttribution *flowAttributionResolver

	requireClientAttribution bool

	globalDownloadLimiter *bandwidthLimiter
	globalUploadLimiter   *bandwidthLimiter
	globalDownloadBps     int64
	globalUploadBps       int64
	globalQuotaBytes      int64
	totalUpBytes          int64
	totalDownBytes        int64

	unattributedDNSBytes int64

	defaultClientPolicy ClientPolicy
	clients             map[string]*clientTraffic

	portalRequired       bool
	portalTitle          string
	portalMessage        string
	portalAccounts       map[string]PortalAccount
	portalAuthorized     map[string]*PortalAuthorization
	portalRechargeClaims []PortalRechargeClaim
	portalClaimResults   map[string]PortalClaimResult
	accountUsage         map[string]*accountUsage
}

func newTrafficManager() *TrafficManager {
	return &TrafficManager{
		// Fail closed until Android pushes its first policy: a hotspot
		// client must never get free Internet in the gap between datapath
		// start and the first configuration.
		portalRequired:           true,
		requireClientAttribution: true,
		epoch:                    time.Now().UnixNano(),
		flowAttribution:          newFlowAttributionResolver(),
		globalDownloadLimiter:    newBandwidthLimiter(0),
		globalUploadLimiter:      newBandwidthLimiter(0),
		clients:                  make(map[string]*clientTraffic),
		portalTitle:              "Shizzi Hotspot",
		portalMessage:            "Connectez-vous à votre compte Shizzi.",
		portalAccounts:           make(map[string]PortalAccount),
		portalAuthorized:         make(map[string]*PortalAuthorization),
		accountUsage:             make(map[string]*accountUsage),
		portalClaimResults:       make(map[string]PortalClaimResult),
	}
}

func (m *TrafficManager) setRequireClientAttribution(required bool) {
	m.mu.Lock()
	m.requireClientAttribution = required
	m.mu.Unlock()
}

func (m *TrafficManager) setGlobalPolicy(downloadBps, uploadBps, quotaBytes int64) {
	m.mu.Lock()
	m.globalDownloadBps = downloadBps
	m.globalUploadBps = uploadBps
	m.globalQuotaBytes = quotaBytes
	m.mu.Unlock()
	m.globalDownloadLimiter.setRate(downloadBps)
	m.globalUploadLimiter.setRate(uploadBps)
}

func (m *TrafficManager) setDefaultClientPolicy(downloadBps, uploadBps, quotaBytes int64, blocked bool) {
	m.mu.Lock()
	defer m.mu.Unlock()

	policy := ClientPolicy{
		DownloadBitsPerSecond: downloadBps,
		UploadBitsPerSecond:   uploadBps,
		QuotaBytes:            quotaBytes,
		Blocked:               blocked,
	}
	if policy == m.defaultClientPolicy {
		return
	}
	previous := m.defaultClientPolicy
	m.defaultClientPolicy = policy
	for _, client := range m.clients {
		if client.Policy == previous {
			client.applyPolicy(policy)
		}
	}
}

func (m *TrafficManager) setClientPolicy(ip string, policy ClientPolicy) {
	if ip == "" {
		return
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.clientLocked(ip).applyPolicy(policy)
}

// resolveFlowClient maps a flow seen on the shared TUN back to the physical
// hotspot client. waitForRule bounds how long it may wait for Android to
// publish the NAT rule; it must be false on any path that runs inside the
// netstack dispatch loop.
func (m *TrafficManager) resolveFlowClient(
	protocol, sourceIP string,
	sourcePort uint16,
	destinationIP string,
	destinationPort uint16,
	waitForRule bool,
) string {
	if !isSharedTunnelAddress(sourceIP) || m.flowAttribution == nil {
		return sourceIP
	}

	m.mu.Lock()
	required := m.requireClientAttribution
	m.mu.Unlock()

	resolved := m.flowAttribution.resolve(
		flowAttributionKey{
			Protocol:   protocol,
			PublicIP:   sourceIP,
			PublicPort: sourcePort,
			DstIP:      destinationIP,
			DstPort:    destinationPort,
		},
		required && waitForRule,
	)
	if resolved != "" {
		return resolved
	}
	if required {
		return ""
	}
	return sourceIP
}

func (m *TrafficManager) clientLocked(ip string) *clientTraffic {
	if existing := m.clients[ip]; existing != nil {
		return existing
	}

	created := &clientTraffic{
		IP:              ip,
		Policy:          m.defaultClientPolicy,
		downloadLimiter: newBandwidthLimiter(m.defaultClientPolicy.DownloadBitsPerSecond),
		uploadLimiter:   newBandwidthLimiter(m.defaultClientPolicy.UploadBitsPerSecond),
	}
	m.clients[ip] = created
	return created
}

// flowAllowed answers, without pacing, whether ip may open a general
// (non-DNS, non-portal) flow right now. Used to refuse a flow before dialling
// upstream at all.
func (m *TrafficManager) flowAllowed(ip string) bool {
	if ip == "" {
		return false
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.allowedLocked(ip, time.Now().UnixMilli())
}

func (m *TrafficManager) allowedLocked(ip string, nowMillis int64) bool {
	if m.portalRequired && !m.portalAuthorizedLocked(ip, nowMillis) {
		return false
	}
	globalUsed := m.totalUpBytes + m.totalDownBytes
	if m.globalQuotaBytes > 0 && globalUsed >= m.globalQuotaBytes {
		return false
	}
	client := m.clients[ip]
	if client == nil {
		return !m.defaultClientPolicy.Blocked
	}
	if m.portalRequired {
		// In account mode the session's rights come from its account, not
		// from a per-IP policy that could outlive a logout or an IP reuse.
		return true
	}
	clientUsed := client.UpBytes + client.DownBytes
	return !client.Policy.Blocked &&
		!(client.Policy.QuotaBytes > 0 && clientUsed >= client.Policy.QuotaBytes)
}

func (m *TrafficManager) waitAllowed(ip string, dir direction, byteCount int) bool {
	return m.waitAllowedWithPortalBypass(ip, dir, byteCount, false)
}

// waitAllowedWithPortalBypass paces and authorises byteCount bytes for ip.
// bypassPortal is used for DNS only: name resolution must keep working before
// login (captive-portal detection depends on it) and even when the flow could
// not be attributed, in which case ip is "".
func (m *TrafficManager) waitAllowedWithPortalBypass(
	ip string,
	dir direction,
	byteCount int,
	bypassPortal bool,
) bool {
	if bypassPortal {
		if dir == directionDownload {
			m.globalDownloadLimiter.wait(byteCount)
		} else {
			m.globalUploadLimiter.wait(byteCount)
		}
		return true
	}
	if ip == "" {
		// Identity is mandatory once prepaid/account enforcement is enabled.
		return false
	}

	now := time.Now()
	nowMillis := now.UnixMilli()

	m.mu.Lock()
	if !m.allowedLocked(ip, nowMillis) {
		m.mu.Unlock()
		return false
	}

	client := m.clientLocked(ip)
	client.LastSeen = now
	if m.portalRequired {
		if authorization := m.portalAuthorized[ip]; authorization != nil {
			account := m.portalAccounts[authorization.AccountNumber]
			client.setRates(account.downloadBps(nowMillis), account.uploadBps(nowMillis))
		}
	}

	clientLimiter := client.uploadLimiter
	globalLimiter := m.globalUploadLimiter
	if dir == directionDownload {
		clientLimiter = client.downloadLimiter
		globalLimiter = m.globalDownloadLimiter
	}
	m.mu.Unlock()

	globalLimiter.wait(byteCount)
	clientLimiter.wait(byteCount)
	return true
}

func (m *TrafficManager) account(ip string, dir direction, byteCount int) {
	if byteCount <= 0 {
		return
	}
	bytes := int64(byteCount)

	m.mu.Lock()
	defer m.mu.Unlock()

	if dir == directionDownload {
		m.totalDownBytes += bytes
	} else {
		m.totalUpBytes += bytes
	}
	if ip == "" {
		m.unattributedDNSBytes += bytes
		return
	}

	client := m.clientLocked(ip)
	client.LastSeen = time.Now()
	if dir == directionDownload {
		client.DownBytes += bytes
	} else {
		client.UpBytes += bytes
	}

	authorization := m.portalAuthorized[ip]
	if authorization == nil {
		return
	}
	if dir == directionDownload {
		authorization.DownBytes += bytes
	} else {
		authorization.UpBytes += bytes
	}

	usage := m.usageLocked(authorization.AccountNumber)
	if dir == directionDownload {
		usage.DownBytes += bytes
	} else {
		usage.UpBytes += bytes
	}
	nowMillis := time.Now().UnixMilli()
	if account, ok := m.portalAccounts[authorization.AccountNumber]; ok &&
		!account.hasUnlimited(nowMillis) && account.dataValid(nowMillis) {
		usage.DataBytes += bytes
	}
}

func (m *TrafficManager) usageLocked(accountNumber string) *accountUsage {
	usage := m.accountUsage[accountNumber]
	if usage == nil {
		usage = &accountUsage{}
		m.accountUsage[accountNumber] = usage
	}
	return usage
}

func (m *TrafficManager) resetStats() {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.totalUpBytes = 0
	m.totalDownBytes = 0
	for _, client := range m.clients {
		client.UpBytes = 0
		client.DownBytes = 0
	}
}

type clientStatsSnapshot struct {
	IP                    string `json:"ip"`
	MAC                   string `json:"mac,omitempty"`
	UpBytes               int64  `json:"upBytes"`
	DownBytes             int64  `json:"downBytes"`
	LastSeenUnixMillis    int64  `json:"lastSeenUnixMillis"`
	DownloadBitsPerSecond int64  `json:"downloadBps"`
	UploadBitsPerSecond   int64  `json:"uploadBps"`
	QuotaBytes            int64  `json:"quotaBytes"`
	Blocked               bool   `json:"blocked"`
	QuotaReached          bool   `json:"quotaReached"`
}

type accountUsageSnapshot struct {
	AccountNumber string `json:"accountNumber"`
	UpBytes       int64  `json:"upBytes"`
	DownBytes     int64  `json:"downBytes"`
	DataBytes     int64  `json:"dataBytes"`
}

type trafficStatsSnapshot struct {
	Epoch                    int64                       `json:"epoch"`
	GlobalDownloadBps        int64                       `json:"globalDownloadBps"`
	GlobalUploadBps          int64                       `json:"globalUploadBps"`
	GlobalQuotaBytes         int64                       `json:"globalQuotaBytes"`
	TotalUpBytes             int64                       `json:"totalUpBytes"`
	TotalDownBytes           int64                       `json:"totalDownBytes"`
	UnattributedDNSBytes     int64                       `json:"unattributedDnsBytes"`
	RequireClientAttribution bool                        `json:"requireClientAttribution"`
	PortalRequired           bool                        `json:"portalRequired"`
	Clients                  []clientStatsSnapshot       `json:"clients"`
	Attribution              flowAttributionSnapshot     `json:"attribution"`
	PortalAuthorizations     []PortalAuthorizationStatus `json:"portalAuthorizations,omitempty"`
	PortalRechargeClaims     []PortalRechargeClaim       `json:"portalRechargeClaims,omitempty"`
	AccountUsage             []accountUsageSnapshot      `json:"accountUsage,omitempty"`
}

func (m *TrafficManager) statsJSON() string {
	// Keep the client list fresh even when no new flow triggers a dumpsys, so
	// sessions of phones that left the hotspot can be released.
	m.flowAttribution.refreshIfOlderThan(clientPresenceRefresh)
	presence := m.flowAttribution.presence()

	m.mu.Lock()
	m.pruneDepartedLocked(presence, time.Now())

	snapshot := trafficStatsSnapshot{
		Epoch:                    m.epoch,
		GlobalDownloadBps:        m.globalDownloadBps,
		GlobalUploadBps:          m.globalUploadBps,
		GlobalQuotaBytes:         m.globalQuotaBytes,
		TotalUpBytes:             m.totalUpBytes,
		TotalDownBytes:           m.totalDownBytes,
		UnattributedDNSBytes:     m.unattributedDNSBytes,
		RequireClientAttribution: m.requireClientAttribution,
		PortalRequired:           m.portalRequired,
		Clients:                  make([]clientStatsSnapshot, 0, len(m.clients)),
		PortalAuthorizations:     make([]PortalAuthorizationStatus, 0, len(m.portalAuthorized)),
		PortalRechargeClaims:     append([]PortalRechargeClaim(nil), m.portalRechargeClaims...),
		AccountUsage:             make([]accountUsageSnapshot, 0, len(m.accountUsage)),
	}

	nowMillis := time.Now().UnixMilli()
	for ip, authorization := range m.portalAuthorized {
		account := m.portalAccounts[authorization.AccountNumber]
		snapshot.PortalAuthorizations = append(
			snapshot.PortalAuthorizations,
			PortalAuthorizationStatus{
				IP:                   ip,
				MAC:                  presence.macs[ip],
				AccountNumber:        authorization.AccountNumber,
				StartedAtMillis:      authorization.StartedAtMillis,
				SessionDataUsedBytes: authorization.UpBytes + authorization.DownBytes,
				UpBytes:              authorization.UpBytes,
				DownBytes:            authorization.DownBytes,
				Authorized:           m.portalAuthorizedLocked(ip, nowMillis),
				DownloadBps:          account.downloadBps(nowMillis),
				UploadBps:            account.uploadBps(nowMillis),
			},
		)
	}
	sort.Slice(snapshot.PortalAuthorizations, func(i, j int) bool {
		return snapshot.PortalAuthorizations[i].IP < snapshot.PortalAuthorizations[j].IP
	})

	for number, usage := range m.accountUsage {
		snapshot.AccountUsage = append(snapshot.AccountUsage, accountUsageSnapshot{
			AccountNumber: number,
			UpBytes:       usage.UpBytes,
			DownBytes:     usage.DownBytes,
			DataBytes:     usage.DataBytes,
		})
	}
	sort.Slice(snapshot.AccountUsage, func(i, j int) bool {
		return snapshot.AccountUsage[i].AccountNumber < snapshot.AccountUsage[j].AccountNumber
	})

	for _, client := range m.clients {
		used := client.UpBytes + client.DownBytes
		snapshot.Clients = append(snapshot.Clients, clientStatsSnapshot{
			IP:                    client.IP,
			MAC:                   presence.macs[client.IP],
			UpBytes:               client.UpBytes,
			DownBytes:             client.DownBytes,
			LastSeenUnixMillis:    client.LastSeen.UnixMilli(),
			DownloadBitsPerSecond: client.Policy.DownloadBitsPerSecond,
			UploadBitsPerSecond:   client.Policy.UploadBitsPerSecond,
			QuotaBytes:            client.Policy.QuotaBytes,
			Blocked:               client.Policy.Blocked,
			QuotaReached:          client.Policy.QuotaBytes > 0 && used >= client.Policy.QuotaBytes,
		})
	}
	m.mu.Unlock()

	sort.Slice(snapshot.Clients, func(i, j int) bool {
		return snapshot.Clients[i].IP < snapshot.Clients[j].IP
	})
	snapshot.Attribution = m.flowAttribution.snapshot()

	encoded, err := json.Marshal(snapshot)
	if err != nil {
		return "{}"
	}
	return string(encoded)
}

// pruneDepartedLocked releases the portal session of a client that Android no
// longer lists as connected. Without it, a phone that leaves lets the next
// phone handed the same DHCP address inherit its login. Only acts on an
// authoritative client list, and only after a grace period so a transient
// dumpsys hiccup does not log everyone out.
func (m *TrafficManager) pruneDepartedLocked(presence clientPresence, now time.Time) {
	if !presence.authoritative {
		for _, authorization := range m.portalAuthorized {
			authorization.missingSince = time.Time{}
		}
		return
	}
	for ip, authorization := range m.portalAuthorized {
		if _, present := presence.clients[ip]; present {
			authorization.missingSince = time.Time{}
			continue
		}
		if authorization.missingSince.IsZero() {
			authorization.missingSince = now
			continue
		}
		if now.Sub(authorization.missingSince) >= departedClientGrace {
			delete(m.portalAuthorized, ip)
		}
	}
}

const (
	clientPresenceRefresh = 5 * time.Second
	departedClientGrace   = 45 * time.Second
)
