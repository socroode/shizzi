package datapath

import (
	"context"
	"fmt"
	"net"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

// flowAttributionKey describes the translated flow visible on Shizzi's TUN.
// Android tethering may NAT several physical hotspot clients to 192.0.2.2.
// dumpsys tethering retains the original client IP and the translated tuple,
// allowing Shizzi to recover the physical client before applying policy.
type flowAttributionKey struct {
	Protocol   string
	PublicIP   string
	PublicPort uint16
	DstIP      string
	DstPort    uint16
}

type flowAttributionSnapshot struct {
	ResolvedFlows         int64  `json:"resolvedFlows"`
	FallbackResolvedFlows int64  `json:"fallbackResolvedFlows"`
	UnresolvedFlows       int64  `json:"unresolvedFlows"`
	LooseCandidateFlows   int64  `json:"looseCandidateFlows"`
	ClientCount           int    `json:"mappedClients"`
	ClientListKnown       bool   `json:"clientListKnown"`
	SlowestResolveMillis  int64  `json:"slowestResolveMillis"`
	DumpCount             int64  `json:"dumpCount"`
	LastDumpMillis        int64  `json:"lastDumpMillis"`
	LastError             string `json:"lastError,omitempty"`
	LastMiss              string `json:"lastMiss,omitempty"`
}

// clientPresence is Android's own list of connected hotspot clients.
type clientPresence struct {
	clients       map[string]struct{}
	macs          map[string]string
	authoritative bool
}

type flowAttributionResolver struct {
	mu sync.Mutex
	// refreshMu makes dumpsys single-flight and keeps it outside mu, so flows
	// that already have an answer never queue behind a slow dumpsys.
	refreshMu sync.Mutex

	dumpFn func() (string, error)

	flows            map[flowAttributionKey]string
	connectedClients map[string]struct{}
	clientMACs       map[string]string
	lastRefresh      time.Time
	lastSuccess      time.Time
	lastError        string

	recentMisses map[flowAttributionKey]time.Time

	resolvedFlows         int64
	fallbackResolvedFlows int64
	unresolvedFlows       int64
	looseCandidateFlows   int64
	slowestResolve        time.Duration
	dumpCount             int64
	lastDumpDuration      time.Duration
	lastMiss              string
}

const (
	attributionRefreshInterval = 100 * time.Millisecond
	// A rule seen in a dump this recent is trusted without another dump. The
	// kernel cannot hand the same translated tuple to a second client while
	// the first conntrack entry (and so its rule) still exists.
	attributionCacheTTL        = time.Second
	attributionWait            = 1500 * time.Millisecond
	attributionMultiClientWait = 3500 * time.Millisecond
	attributionRetryDelay      = 50 * time.Millisecond
	attributionDumpTimeout     = 1200 * time.Millisecond
	attributionMissMemory      = 5 * time.Second
	presenceMaxAge             = 15 * time.Second
)

var ipv4UpstreamRulePattern = regexp.MustCompile(
	"^(tcp|udp)\\s+\\[[^\\]]*\\]\\s+\\d+\\([^)]*\\)\\s+" +
		"([0-9.]+):(\\d+)\\s+->\\s+\\d+\\([^)]*\\)\\s+" +
		"([0-9.]+):(\\d+)\\s+->\\s+([0-9.]+):(\\d+)\\b",
)

var ipv4TuplePattern = regexp.MustCompile(
	`([0-9]{1,3}(?:\.[0-9]{1,3}){3}):(\d+)`,
)

var tetheringConnectedClientPattern = regexp.MustCompile(
	`/([0-9]{1,3}(?:\.[0-9]{1,3}){3})=downstream:`,
)

// In "IPv4 Downstream" rules the last tuple is the physical client and the
// bracketed MAC that follows it is the client's MAC (outDstMac).
var ipv4DownstreamClientMACPattern = regexp.MustCompile(
	`([0-9]{1,3}(?:\.[0-9]{1,3}){3}):\d+\s+\[([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})\]`,
)

func newFlowAttributionResolver() *flowAttributionResolver {
	return &flowAttributionResolver{
		dumpFn:           dumpTetheringState,
		flows:            make(map[flowAttributionKey]string),
		connectedClients: make(map[string]struct{}),
		clientMACs:       make(map[string]string),
		recentMisses:     make(map[flowAttributionKey]time.Time),
	}
}

func dumpTetheringState() (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), attributionDumpTimeout)
	defer cancel()

	// Use the absolute binary path. The Shizuku shell process does not always
	// inherit a PATH containing dumpsys on OEM Android builds.
	out, err := exec.CommandContext(ctx, "/system/bin/dumpsys", "tethering").CombinedOutput()
	if ctx.Err() != nil {
		return "", fmt.Errorf("dumpsys tethering timeout: %w", ctx.Err())
	}
	if err != nil {
		return "", fmt.Errorf("dumpsys tethering: %w", err)
	}
	return string(out), nil
}

func parseTetheringConnectedClients(raw string) map[string]struct{} {
	result := make(map[string]struct{})
	for _, match := range tetheringConnectedClientPattern.FindAllStringSubmatch(raw, -1) {
		if len(match) != 2 {
			continue
		}
		ip := strings.TrimSpace(match[1])
		parsed := net.ParseIP(ip)
		if parsed == nil || parsed.To4() == nil || isSharedTunnelAddress(ip) {
			continue
		}
		result[ip] = struct{}{}
	}
	return result
}

func parseIPv4UpstreamAttributions(raw string) map[flowAttributionKey]string {
	result := make(map[flowAttributionKey]string)
	inUpstream := false

	for _, rawLine := range strings.Split(raw, "\n") {
		line := strings.TrimSpace(rawLine)
		switch {
		case strings.HasPrefix(line, "IPv4 Upstream:"):
			inUpstream = true
			continue
		case strings.HasPrefix(line, "IPv4 Downstream:"):
			inUpstream = false
			continue
		}
		if !inUpstream {
			continue
		}

		key, clientIP, ok := parseIPv4UpstreamAttributionLine(line)
		if ok {
			result[key] = clientIP
		}
	}
	return result
}

func parseIPv4UpstreamAttributionLine(line string) (flowAttributionKey, string, bool) {
	match := ipv4UpstreamRulePattern.FindStringSubmatch(line)
	if len(match) == 8 {
		if _, err := parseAttributionPort(match[3]); err == nil {
			publicPort, errPublic := parseAttributionPort(match[5])
			dstPort, errDst := parseAttributionPort(match[7])
			if errPublic == nil && errDst == nil {
				return flowAttributionKey{
					Protocol:   strings.ToLower(match[1]),
					PublicIP:   match[4],
					PublicPort: publicPort,
					DstIP:      match[6],
					DstPort:    dstPort,
				}, match[2], true
			}
		}
	}

	fields := strings.Fields(line)
	if len(fields) == 0 {
		return flowAttributionKey{}, "", false
	}
	protocol := strings.ToLower(fields[0])
	if protocol != "tcp" && protocol != "udp" {
		return flowAttributionKey{}, "", false
	}

	tuples := ipv4TuplePattern.FindAllStringSubmatch(line, -1)
	if len(tuples) < 3 {
		return flowAttributionKey{}, "", false
	}

	clientIP := tuples[0][1]
	publicIP := tuples[1][1]
	dstIP := tuples[2][1]
	if net.ParseIP(clientIP) == nil || net.ParseIP(publicIP) == nil || net.ParseIP(dstIP) == nil {
		return flowAttributionKey{}, "", false
	}

	publicPort, errPublic := parseAttributionPort(tuples[1][2])
	dstPort, errDst := parseAttributionPort(tuples[2][2])
	if errPublic != nil || errDst != nil {
		return flowAttributionKey{}, "", false
	}

	return flowAttributionKey{
		Protocol:   protocol,
		PublicIP:   publicIP,
		PublicPort: publicPort,
		DstIP:      dstIP,
		DstPort:    dstPort,
	}, clientIP, true
}

func parseAttributionPort(raw string) (uint16, error) {
	value, err := strconv.Atoi(raw)
	if err != nil || value < 0 || value > 65535 {
		return 0, fmt.Errorf("invalid port %q", raw)
	}
	return uint16(value), nil
}

func parseIPv4DownstreamClientMACs(raw string) map[string]string {
	result := make(map[string]string)
	inDownstream := false
	for _, rawLine := range strings.Split(raw, "\n") {
		line := strings.TrimSpace(rawLine)
		switch {
		case strings.HasPrefix(line, "IPv4 Downstream:"):
			inDownstream = true
			continue
		case strings.HasPrefix(line, "IPv4 Upstream:"), strings.HasSuffix(line, ":") && !strings.Contains(line, " "):
			inDownstream = false
			continue
		}
		if !inDownstream {
			continue
		}
		matches := ipv4DownstreamClientMACPattern.FindAllStringSubmatch(line, -1)
		if len(matches) == 0 {
			continue
		}
		last := matches[len(matches)-1]
		ip, mac := last[1], strings.ToLower(last[2])
		if isSharedTunnelAddress(ip) || mac == "00:00:00:00:00:00" {
			continue
		}
		result[ip] = mac
	}
	return result
}

// resolve maps a translated flow to its physical client, or "" when that
// cannot be done without guessing. waitForRule bounds a wait for Android to
// publish the NAT rule of a brand-new flow.
func (r *flowAttributionResolver) resolve(key flowAttributionKey, waitForRule bool) string {
	if r == nil {
		return ""
	}

	startedAt := time.Now()
	deadline := startedAt
	if waitForRule {
		deadline = deadline.Add(attributionWait)
		r.mu.Lock()
		missedAt, recentlyMissed := r.recentMisses[key]
		r.mu.Unlock()
		if recentlyMissed && time.Since(missedAt) < attributionMissMemory {
			// Same unresolved tuple retried (typically UDP): answer at once
			// instead of stacking another multi-second wait.
			deadline = startedAt
		}
	}

	for attempt := 0; ; attempt++ {
		r.mu.Lock()
		client, fallback := r.lookupLocked(key)
		fresh := time.Since(r.lastRefresh) < attributionCacheTTL
		r.mu.Unlock()
		if client != "" && (fresh || attempt > 0) {
			return r.recordResolved(client, fallback, startedAt)
		}

		r.refreshIfOlderThan(attributionRefreshInterval)

		r.mu.Lock()
		client, fallback = r.lookupLocked(key)
		if client != "" {
			r.mu.Unlock()
			return r.recordResolved(client, fallback, startedAt)
		}
		if waitForRule && r.lastError == "" && len(r.connectedClients) > 1 {
			extended := startedAt.Add(attributionMultiClientWait)
			if deadline.Before(extended) && deadline.After(startedAt) {
				deadline = extended
			}
		}
		r.mu.Unlock()

		if !time.Now().Before(deadline) {
			break
		}
		time.Sleep(attributionRetryDelay)
	}

	r.mu.Lock()
	defer r.mu.Unlock()

	// With exactly one client on Android's own connected-client list, the flow
	// can only be that client's. The list of IPs seen in NAT rules is never
	// used for this: a phone that just joined has no rule yet and would be
	// billed to the only phone that has one.
	if r.lastError == "" && len(r.connectedClients) == 1 &&
		time.Since(r.lastSuccess) < presenceMaxAge {
		for clientIP := range r.connectedClients {
			if r.onlyClientInRulesLocked(clientIP) {
				r.resolvedFlows++
				r.fallbackResolvedFlows++
				r.lastMiss = ""
				r.noteLatencyLocked(startedAt)
				return clientIP
			}
		}
	}

	if r.looseCandidateLocked(key) {
		r.looseCandidateFlows++
	}
	r.unresolvedFlows++
	r.lastMiss = formatFlowAttributionKey(key)
	if waitForRule {
		r.recentMisses[key] = time.Now()
		for missed, at := range r.recentMisses {
			if time.Since(at) > attributionMissMemory {
				delete(r.recentMisses, missed)
			}
		}
	}
	return ""
}

func (r *flowAttributionResolver) recordResolved(client string, fallback bool, startedAt time.Time) string {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.resolvedFlows++
	if fallback {
		r.fallbackResolvedFlows++
	}
	r.lastMiss = ""
	r.noteLatencyLocked(startedAt)
	return client
}

func (r *flowAttributionResolver) noteLatencyLocked(startedAt time.Time) {
	if elapsed := time.Since(startedAt); elapsed > r.slowestResolve {
		r.slowestResolve = elapsed
	}
}

func (r *flowAttributionResolver) onlyClientInRulesLocked(clientIP string) bool {
	for _, candidate := range r.flows {
		if candidate != "" && candidate != clientIP {
			return false
		}
	}
	return true
}

// lookupLocked accepts an exact rule, or a rule that differs only by protocol
// label (some OEM dumps print it differently). Matching on the translated
// port alone is NOT accepted: two phones may share a translated port towards
// different destinations, and the second one's rule may not be published yet.
func (r *flowAttributionResolver) lookupLocked(
	key flowAttributionKey,
) (clientIP string, fallback bool) {
	if client := r.flows[key]; client != "" {
		return client, false
	}

	if client := r.uniqueClientLocked(func(candidate flowAttributionKey) bool {
		return candidate.PublicIP == key.PublicIP &&
			candidate.PublicPort == key.PublicPort &&
			candidate.DstIP == key.DstIP &&
			candidate.DstPort == key.DstPort
	}); client != "" {
		return client, true
	}

	return "", false
}

// looseCandidateLocked is diagnostics only: a rule shares the translated port
// but not the destination. A high count on a device means its dump reports
// destinations differently and the parser needs adapting, not guessing.
func (r *flowAttributionResolver) looseCandidateLocked(key flowAttributionKey) bool {
	for candidate := range r.flows {
		if candidate.PublicIP == key.PublicIP && candidate.PublicPort == key.PublicPort {
			return true
		}
	}
	return false
}

func (r *flowAttributionResolver) uniqueClientLocked(
	matches func(flowAttributionKey) bool,
) string {
	client := ""
	for candidate, candidateClient := range r.flows {
		if candidateClient == "" || !matches(candidate) {
			continue
		}
		if client == "" {
			client = candidateClient
			continue
		}
		if client != candidateClient {
			return ""
		}
	}
	return client
}

func formatFlowAttributionKey(key flowAttributionKey) string {
	return fmt.Sprintf(
		"%s %s:%d -> %s:%d",
		key.Protocol,
		key.PublicIP,
		key.PublicPort,
		key.DstIP,
		key.DstPort,
	)
}

// refreshIfOlderThan runs dumpsys at most once concurrently, without holding
// mu while the process runs.
func (r *flowAttributionResolver) refreshIfOlderThan(maxAge time.Duration) {
	if r == nil {
		return
	}
	r.refreshMu.Lock()
	defer r.refreshMu.Unlock()

	r.mu.Lock()
	stale := time.Since(r.lastRefresh) >= maxAge
	r.mu.Unlock()
	if !stale {
		return
	}

	started := time.Now()
	raw, err := r.dumpFn()
	finished := time.Now()

	var (
		flows   map[flowAttributionKey]string
		clients map[string]struct{}
		macs    map[string]string
	)
	if err == nil {
		flows = parseIPv4UpstreamAttributions(raw)
		clients = parseTetheringConnectedClients(raw)
		macs = parseIPv4DownstreamClientMACs(raw)
	}

	r.mu.Lock()
	defer r.mu.Unlock()
	r.lastRefresh = finished
	r.dumpCount++
	r.lastDumpDuration = finished.Sub(started)
	if err != nil {
		r.lastError = err.Error()
		return
	}
	r.flows = flows
	r.connectedClients = clients
	for ip, mac := range macs {
		r.clientMACs[ip] = mac
	}
	if len(clients) > 0 {
		for ip := range r.clientMACs {
			if _, ok := clients[ip]; !ok {
				delete(r.clientMACs, ip)
			}
		}
	}
	r.lastSuccess = finished
	r.lastError = ""
}

func (r *flowAttributionResolver) presence() clientPresence {
	if r == nil {
		return clientPresence{}
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	result := clientPresence{
		clients: make(map[string]struct{}, len(r.connectedClients)),
		macs:    make(map[string]string, len(r.clientMACs)),
		authoritative: r.lastError == "" && len(r.connectedClients) > 0 &&
			time.Since(r.lastSuccess) < presenceMaxAge,
	}
	for ip := range r.connectedClients {
		result.clients[ip] = struct{}{}
	}
	for ip, mac := range r.clientMACs {
		result.macs[ip] = mac
	}
	return result
}

func (r *flowAttributionResolver) snapshot() flowAttributionSnapshot {
	if r == nil {
		return flowAttributionSnapshot{}
	}

	r.mu.Lock()
	defer r.mu.Unlock()

	return flowAttributionSnapshot{
		ResolvedFlows:         r.resolvedFlows,
		FallbackResolvedFlows: r.fallbackResolvedFlows,
		UnresolvedFlows:       r.unresolvedFlows,
		LooseCandidateFlows:   r.looseCandidateFlows,
		ClientCount:           len(r.connectedClients),
		ClientListKnown:       len(r.connectedClients) > 0,
		SlowestResolveMillis:  r.slowestResolve.Milliseconds(),
		DumpCount:             r.dumpCount,
		LastDumpMillis:        r.lastDumpDuration.Milliseconds(),
		LastError:             r.lastError,
		LastMiss:              r.lastMiss,
	}
}

func isSharedTunnelAddress(ip string) bool {
	switch ip {
	case "192.0.2.2", "2001:db8::2":
		return true
	default:
		return false
	}
}
