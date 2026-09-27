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

type TrafficManager struct {
	mu sync.Mutex

	flowAttribution *flowAttributionResolver

	requireClientAttribution bool

	globalDownloadLimiter *bandwidthLimiter
	globalUploadLimiter   *bandwidthLimiter
	globalDownloadBps     int64
	globalUploadBps       int64
	globalQuotaBytes      int64
	totalUpBytes          int64
	totalDownBytes        int64

	defaultClientPolicy ClientPolicy
	clients             map[string]*clientTraffic
}

func newTrafficManager() *TrafficManager {
	return &TrafficManager{
		flowAttribution:       newFlowAttributionResolver(),
		globalDownloadLimiter: newBandwidthLimiter(0),
		globalUploadLimiter:   newBandwidthLimiter(0),
		clients:               make(map[string]*clientTraffic),
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

func (m *TrafficManager) resolveFlowClient(
	protocol, sourceIP string,
	sourcePort uint16,
	destinationIP string,
	destinationPort uint16,
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
		required,
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

func (m *TrafficManager) waitAllowed(ip string, dir direction, byteCount int) bool {
	if ip == "" {
		// Identity is mandatory once prepaid/account enforcement is enabled.
		return false
	}

	m.mu.Lock()
	globalUsed := m.totalUpBytes + m.totalDownBytes
	if m.globalQuotaBytes > 0 && globalUsed >= m.globalQuotaBytes {
		m.mu.Unlock()
		return false
	}

	client := m.clientLocked(ip)
	client.LastSeen = time.Now()
	clientUsed := client.UpBytes + client.DownBytes
	if client.Policy.Blocked ||
		(client.Policy.QuotaBytes > 0 && clientUsed >= client.Policy.QuotaBytes) {
		m.mu.Unlock()
		return false
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
	if ip == "" || byteCount <= 0 {
		return
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	client := m.clientLocked(ip)
	client.LastSeen = time.Now()
	if dir == directionDownload {
		m.totalDownBytes += int64(byteCount)
		client.DownBytes += int64(byteCount)
		return
	}

	m.totalUpBytes += int64(byteCount)
	client.UpBytes += int64(byteCount)
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
	UpBytes               int64  `json:"upBytes"`
	DownBytes             int64  `json:"downBytes"`
	LastSeenUnixMillis    int64  `json:"lastSeenUnixMillis"`
	DownloadBitsPerSecond int64  `json:"downloadBps"`
	UploadBitsPerSecond   int64  `json:"uploadBps"`
	QuotaBytes            int64  `json:"quotaBytes"`
	Blocked               bool   `json:"blocked"`
	QuotaReached          bool   `json:"quotaReached"`
}

type trafficStatsSnapshot struct {
	GlobalDownloadBps       int64                   `json:"globalDownloadBps"`
	GlobalUploadBps         int64                   `json:"globalUploadBps"`
	GlobalQuotaBytes        int64                   `json:"globalQuotaBytes"`
	TotalUpBytes            int64                   `json:"totalUpBytes"`
	TotalDownBytes          int64                   `json:"totalDownBytes"`
	RequireClientAttribution bool                    `json:"requireClientAttribution"`
	Clients                 []clientStatsSnapshot   `json:"clients"`
	Attribution             flowAttributionSnapshot `json:"attribution"`
}

func (m *TrafficManager) statsJSON() string {
	m.mu.Lock()
	snapshot := trafficStatsSnapshot{
		GlobalDownloadBps:        m.globalDownloadBps,
		GlobalUploadBps:          m.globalUploadBps,
		GlobalQuotaBytes:         m.globalQuotaBytes,
		TotalUpBytes:             m.totalUpBytes,
		TotalDownBytes:           m.totalDownBytes,
		RequireClientAttribution: m.requireClientAttribution,
		Clients:                  make([]clientStatsSnapshot, 0, len(m.clients)),
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
