package datapath

import (
	"encoding/json"
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
	bytesPerSec   float64
	next          time.Time
	burstDuration time.Duration
}

func newBandwidthLimiter(bitsPerSecond int64) *bandwidthLimiter {
	l := &bandwidthLimiter{burstDuration: defaultBurstWindow}
	l.setRate(bitsPerSecond)
	return l
}

func (l *bandwidthLimiter) setRate(bitsPerSecond int64) {
	l.mu.Lock()
	defer l.mu.Unlock()

	if bitsPerSecond <= 0 {
		l.bytesPerSec = 0
		l.next = time.Time{}
		return
	}

	l.bytesPerSec = float64(bitsPerSecond) / 8
	l.next = time.Time{}
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

type TrafficManager struct {
	mu sync.Mutex

	globalDownloadLimiter *bandwidthLimiter
	globalUploadLimiter   *bandwidthLimiter

	globalDownloadBitsPerSecond int64
	globalUploadBitsPerSecond   int64
	globalQuotaBytes            int64

	totalUpBytes   int64
	totalDownBytes int64

	sharedUpBytes   int64
	sharedDownBytes int64
	sharedPolicy    ClientPolicy
	sharedDownloadLimiter *bandwidthLimiter
	sharedUploadLimiter   *bandwidthLimiter

	defaultClientPolicy ClientPolicy
	clients             map[string]*clientTraffic

	portalRequired   bool
	portalTitle      string
	portalMessage    string
	portalHTML       string
	portalPasses          map[string]PortalPass
	portalAccounts        map[string]PortalAccount
	portalAuthorized      map[string]PortalAuthorization
	portalAccountSessions map[string]PortalAccountSession
	portalClaims          []PortalClaim
	portalRechargeClaims  []PortalRechargeClaim
}

func newTrafficManager() *TrafficManager {
	return &TrafficManager{
		globalDownloadLimiter:       newBandwidthLimiter(40_000_000),
		globalUploadLimiter:         newBandwidthLimiter(5_000_000),
		globalDownloadBitsPerSecond: 40_000_000,
		globalUploadBitsPerSecond:   5_000_000,
		sharedDownloadLimiter:       newBandwidthLimiter(0),
		sharedUploadLimiter:         newBandwidthLimiter(0),
		clients:                     make(map[string]*clientTraffic),
		portalPasses:                make(map[string]PortalPass),
		portalAccounts:              make(map[string]PortalAccount),
		portalAuthorized:            make(map[string]PortalAuthorization),
		portalAccountSessions:       make(map[string]PortalAccountSession),
	}
}

func (m *TrafficManager) setGlobalPolicy(downloadBps, uploadBps, quotaBytes int64) {
	m.mu.Lock()
	m.globalDownloadBitsPerSecond = downloadBps
	m.globalUploadBitsPerSecond = uploadBps
	m.globalQuotaBytes = quotaBytes
	m.mu.Unlock()

	m.globalDownloadLimiter.setRate(downloadBps)
	m.globalUploadLimiter.setRate(uploadBps)
}

func (m *TrafficManager) setDefaultClientPolicy(downloadBps, uploadBps, quotaBytes int64, blocked bool) {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.defaultClientPolicy = ClientPolicy{
		DownloadBitsPerSecond: downloadBps,
		UploadBitsPerSecond:   uploadBps,
		QuotaBytes:            quotaBytes,
		Blocked:               blocked,
	}

	for _, client := range m.clients {
		if client.Policy == (ClientPolicy{}) {
			client.applyPolicy(m.defaultClientPolicy)
		}
	}
}

func (m *TrafficManager) setClientPolicy(ip string, policy ClientPolicy) {
	if ip == "" {
		return
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	client := m.clientLocked(ip)
	client.applyPolicy(policy)
}

func (m *TrafficManager) setSharedPolicy(policy ClientPolicy) {
	m.mu.Lock()
	m.sharedPolicy = policy
	m.mu.Unlock()

	m.sharedDownloadLimiter.setRate(policy.DownloadBitsPerSecond)
	m.sharedUploadLimiter.setRate(policy.UploadBitsPerSecond)
}


func (c *clientTraffic) applyPolicy(policy ClientPolicy) {
	c.Policy = policy
	if c.downloadLimiter == nil {
		c.downloadLimiter = newBandwidthLimiter(policy.DownloadBitsPerSecond)
	} else {
		c.downloadLimiter.setRate(policy.DownloadBitsPerSecond)
	}

	if c.uploadLimiter == nil {
		c.uploadLimiter = newBandwidthLimiter(policy.UploadBitsPerSecond)
	} else {
		c.uploadLimiter.setRate(policy.UploadBitsPerSecond)
	}
}

func (m *TrafficManager) clientLocked(ip string) *clientTraffic {
	if existing := m.clients[ip]; existing != nil {
		return existing
	}

	created := &clientTraffic{
		IP:               ip,
		downloadLimiter:  newBandwidthLimiter(m.defaultClientPolicy.DownloadBitsPerSecond),
		uploadLimiter:    newBandwidthLimiter(m.defaultClientPolicy.UploadBitsPerSecond),
		Policy:           m.defaultClientPolicy,
	}
	m.clients[ip] = created
	return created
}

func (m *TrafficManager) waitAllowed(ip string, dir direction, byteCount int) bool {
	return m.waitAllowedWithPortalBypass(ip, dir, byteCount, false)
}

func (m *TrafficManager) waitAllowedWithPortalBypass(
	ip string,
	dir direction,
	byteCount int,
	bypassPortal bool,
) bool {
	m.mu.Lock()

	globalUsed := m.totalUpBytes + m.totalDownBytes
	globalLimiter := m.globalUploadLimiter
	if dir == directionDownload {
		globalLimiter = m.globalDownloadLimiter
	}

	if isSharedTunnelAddress(ip) {
		sharedUsed := m.sharedUpBytes + m.sharedDownBytes
		if (!bypassPortal && m.portalRequired && !m.portalAuthorizedLocked(ip)) ||
			(!bypassPortal && m.sharedPolicy.Blocked) ||
			(!bypassPortal && m.globalQuotaBytes > 0 && globalUsed >= m.globalQuotaBytes) ||
			(!bypassPortal && m.sharedPolicy.QuotaBytes > 0 && sharedUsed >= m.sharedPolicy.QuotaBytes) {
			m.mu.Unlock()
			return false
		}

		sharedLimiter := m.sharedUploadLimiter
		if dir == directionDownload {
			sharedLimiter = m.sharedDownloadLimiter
		}
		m.mu.Unlock()

		globalLimiter.wait(byteCount)
		sharedLimiter.wait(byteCount)
		return true
	}

	client := m.clientLocked(ip)
	client.LastSeen = time.Now()
	clientUsed := client.UpBytes + client.DownBytes

	if (!bypassPortal && m.portalRequired && !m.portalAuthorizedLocked(ip)) ||
		(!bypassPortal && client.Policy.Blocked) ||
		(!bypassPortal && m.globalQuotaBytes > 0 && globalUsed >= m.globalQuotaBytes) ||
		(!bypassPortal && client.Policy.QuotaBytes > 0 && clientUsed >= client.Policy.QuotaBytes) {
		m.mu.Unlock()
		return false
	}

	clientLimiter := client.uploadLimiter
	if dir == directionDownload {
		clientLimiter = client.downloadLimiter
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

	m.mu.Lock()
	defer m.mu.Unlock()

	if auth, ok := m.portalAuthorized[ip]; ok {
		if auth.AccountNumber != "" {
			if m.portalAccountUsesMeteredDataLocked(auth, time.Now().UnixMilli()) {
				auth.SessionDataUsedBytes += int64(byteCount)
			}
		} else {
			auth.SessionUsedBytes += int64(byteCount)
		}
		m.portalAuthorized[ip] = auth
	}

	if dir == directionDownload {
		m.totalDownBytes += int64(byteCount)
		if isSharedTunnelAddress(ip) {
			m.sharedDownBytes += int64(byteCount)
			return
		}

		client := m.clientLocked(ip)
		client.LastSeen = time.Now()
		client.DownBytes += int64(byteCount)
		return
	}

	m.totalUpBytes += int64(byteCount)
	if isSharedTunnelAddress(ip) {
		m.sharedUpBytes += int64(byteCount)
		return
	}

	client := m.clientLocked(ip)
	client.LastSeen = time.Now()
	client.UpBytes += int64(byteCount)
}

func (m *TrafficManager) resetStats() {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.totalUpBytes = 0
	m.totalDownBytes = 0
	m.sharedUpBytes = 0
	m.sharedDownBytes = 0
	for _, client := range m.clients {
		client.UpBytes = 0
		client.DownBytes = 0
	}
}

type trafficStatsSnapshot struct {
	GlobalDownloadBitsPerSecond int64                         `json:"globalDownloadBps"`
	GlobalUploadBitsPerSecond   int64                         `json:"globalUploadBps"`
	GlobalQuotaBytes            int64                         `json:"globalQuotaBytes"`
	TotalUpBytes                int64                         `json:"totalUpBytes"`
	TotalDownBytes              int64                         `json:"totalDownBytes"`
	SharedUpBytes               int64                         `json:"sharedUpBytes"`
	SharedDownBytes             int64                         `json:"sharedDownBytes"`
	Clients                     []clientStatsSnapshot         `json:"clients"`
	PortalClaims                []PortalClaim                        `json:"portalClaims,omitempty"`
	PortalAuthorizations        []portalAuthorizationSnapshot        `json:"portalAuthorizations,omitempty"`
	PortalRechargeClaims        []PortalRechargeClaim                `json:"portalRechargeClaims,omitempty"`
	PortalAccountAuthorizations []portalAccountAuthorizationSnapshot `json:"portalAccountAuthorizations,omitempty"`
}

type portalAuthorizationSnapshot struct {
	IP               string `json:"ip"`
	Code             string `json:"code"`
	StartedAtMillis  int64  `json:"startedAtMillis"`
	SessionUsedBytes int64  `json:"sessionUsedBytes"`
}

type portalAccountAuthorizationSnapshot struct {
	IP                   string `json:"ip"`
	AccountNumber        string `json:"accountNumber"`
	StartedAtMillis      int64  `json:"startedAtMillis"`
	SessionDataUsedBytes int64  `json:"sessionDataUsedBytes"`
}

type clientStatsSnapshot struct {
	IP                    string `json:"ip"`
	UpBytes               int64  `json:"upBytes"`
	DownBytes             int64  `json:"downBytes"`
	LastSeenUnixMillis    int64  `json:"lastSeenUnixMillis"`
	DownloadBitsPerSecond int64  `json:"downloadBps"`
	UploadBitsPerSecond   int64  `json:"uploadBps"`
	QuotaBytes            int64  `json:"quotaBytes"`
	Blocked               bool   `json:"blocked"`
	QuotaReached          bool   `json:"quotaReached"`
}

func (m *TrafficManager) statsJSON() string {
	m.mu.Lock()
	defer m.mu.Unlock()

	snapshot := trafficStatsSnapshot{
		GlobalDownloadBitsPerSecond: m.globalDownloadBitsPerSecond,
		GlobalUploadBitsPerSecond:   m.globalUploadBitsPerSecond,
		GlobalQuotaBytes:            m.globalQuotaBytes,
		TotalUpBytes:                m.totalUpBytes,
		TotalDownBytes:              m.totalDownBytes,
		SharedUpBytes:               m.sharedUpBytes,
		SharedDownBytes:             m.sharedDownBytes,
		Clients:                     make([]clientStatsSnapshot, 0, len(m.clients)),
		PortalClaims:                append([]PortalClaim(nil), m.portalClaims...),
		PortalAuthorizations:        make([]portalAuthorizationSnapshot, 0, len(m.portalAuthorized)),
		PortalRechargeClaims:        append([]PortalRechargeClaim(nil), m.portalRechargeClaims...),
		PortalAccountAuthorizations: make([]portalAccountAuthorizationSnapshot, 0, len(m.portalAuthorized)),
	}

	for ip, auth := range m.portalAuthorized {
		if auth.AccountNumber != "" {
			snapshot.PortalAccountAuthorizations = append(
				snapshot.PortalAccountAuthorizations,
				portalAccountAuthorizationSnapshot{
					IP:                   ip,
					AccountNumber:        auth.AccountNumber,
					StartedAtMillis:      auth.StartedAtMillis,
					SessionDataUsedBytes: auth.SessionDataUsedBytes,
				},
			)
			continue
		}
		if !m.portalAuthorizedLocked(ip) {
			continue
		}
		snapshot.PortalAuthorizations = append(
			snapshot.PortalAuthorizations,
			portalAuthorizationSnapshot{
				IP:               ip,
				Code:             auth.Code,
				StartedAtMillis:  auth.StartedAtMillis,
				SessionUsedBytes: auth.SessionUsedBytes,
			},
		)
	}

	for _, client := range m.clients {
		used := client.UpBytes + client.DownBytes
		snapshot.Clients = append(snapshot.Clients, clientStatsSnapshot{
			IP:                    client.IP,
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

	encoded, err := json.Marshal(snapshot)
	if err != nil {
		return "{}"
	}
	return string(encoded)
}

func isSharedTunnelAddress(ip string) bool {
	switch ip {
	case "192.0.2.2", "2001:db8::2":
		return true
	default:
		return false
	}
}
