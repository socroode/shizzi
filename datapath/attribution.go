package datapath

import (
	"context"
	"encoding/json"
	"fmt"
	"maps"
	"net"
	"os/exec"
	"regexp"
	"runtime"
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
	BPFDiscardedAbsentOwners int    `json:"bpfDiscardedAbsentOwners"`
	CallbackPresenceKnown    bool   `json:"callbackPresenceKnown"`
	CallbackStationCount     int    `json:"callbackStationCount"`
	CallbackLeaseCount       int    `json:"callbackLeaseCount"`
	CallbackMappingComplete  bool   `json:"callbackMappingComplete"`
	LastUnresolvedReason     string `json:"lastUnresolvedReason,omitempty"`
	ResolvedFlows            int64  `json:"resolvedFlows"`
	FallbackResolvedFlows    int64  `json:"fallbackResolvedFlows"`
	UnresolvedFlows          int64  `json:"unresolvedFlows"`
	UnresolvedTCPFlows       int64  `json:"unresolvedTcpFlows"`
	UnresolvedUDPFlows       int64  `json:"unresolvedUdpFlows"`
	LooseCandidateFlows      int64  `json:"looseCandidateFlows"`
	ClientCount              int    `json:"mappedClients"`
	ClientListKnown          bool   `json:"clientListKnown"`
	RecentBPFFailures        int    `json:"recentBpfFailures"`
	WifiAssociatedClients    int    `json:"wifiAssociatedClients"`
	WifiCountKnown           bool   `json:"wifiCountKnown"`
	SlowestResolveMillis     int64  `json:"slowestResolveMillis"`
	DumpCount                int64  `json:"dumpCount"`
	LastDumpMillis           int64  `json:"lastDumpMillis"`
	SlowestDumpMillis        int64  `json:"slowestDumpMillis"`
	LastError                string `json:"lastError,omitempty"`
	LastMiss                 string `json:"lastMiss,omitempty"`
	AndroidTimezone          string `json:"androidTimezone,omitempty"`
	AndroidTimezoneKnown     bool   `json:"androidTimezoneKnown"`
	AndroidTimezoneSource    string `json:"androidTimezoneSource,omitempty"`
	TimezoneError            string `json:"timezoneError,omitempty"`
	BPFObservedFailures      int    `json:"bpfObservedFailures"`
	BPFRejectedTimestamps    int    `json:"bpfRejectedTimestamps"`
	BPFClockSkipped          int    `json:"bpfClockSkipped"`
	DumpFailures             int64  `json:"dumpFailures"`
	LastDumpError            string `json:"lastDumpError,omitempty"`
	LastDumpErrorMillis      int64  `json:"lastDumpErrorMillis,omitempty"`
	LastUnresolvedFlow       string `json:"lastUnresolvedFlow,omitempty"`
	LastUnresolvedMillis     int64  `json:"lastUnresolvedMillis,omitempty"`
}

// clientPresence is Android's own list of connected hotspot clients.
type clientPresence struct {
	eventBased           bool
	clients              map[string]struct{}
	macs                 map[string]string
	currentMACs          map[string]string
	authoritative        bool
	wifiCount            int
	wifiCountKnown       bool
	associatedMACs       map[string]struct{}
	leasesKnown          bool
	leaseMappingComplete bool
}

type flowAttributionResolver struct {
	mu sync.Mutex
	// refreshMu makes dumpsys single-flight and keeps it outside mu, so flows
	// that already have an answer never queue behind a slow dumpsys.
	refreshMu     sync.Mutex
	wifiRefreshMu sync.Mutex

	dumpFn     func() (string, error)
	wifiDumpFn func() (string, error)

	flows              map[flowAttributionKey]string
	bpfFailedFlows     map[flowAttributionKey]string
	recentBPFFailures  int
	bpfDiagnostics     bpfFailureDiagnostics
	logLocation        *time.Location
	logTimezone        string
	logTimezoneKnown   bool
	logTimezoneSource  string
	logTimezoneError   string
	logClockRequired   bool
	logClockGeneration uint64
	connectedClients   map[string]struct{}
	clientMACs         map[string]string
	currentClientMACs  map[string]string
	clientListKnown    bool
	eventPresence      *clientPresence
	presenceGeneration uint64
	refreshDone        chan struct{}
	wifiClientCount    int
	wifiCountKnown     bool
	lastWifiRefresh    time.Time
	lastWifiSuccess    time.Time
	lastWifiError      string
	lastRefresh        time.Time
	lastSuccess        time.Time
	lastError          string

	recentMisses map[flowAttributionKey]time.Time

	resolvedFlows         int64
	fallbackResolvedFlows int64
	unresolvedFlows       int64
	unresolvedTCPFlows    int64
	unresolvedUDPFlows    int64
	looseCandidateFlows   int64
	slowestResolve        time.Duration
	dumpCount             int64
	lastDumpDuration      time.Duration
	slowestDump           time.Duration
	lastMiss              string
	dumpFailures          int64
	lastDumpError         string
	lastDumpErrorMillis   int64
	lastUnresolvedFlow    string
	lastUnresolvedMillis  int64
	lastUnresolvedReason  string
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
	attributionDumpWaitDelay   = 200 * time.Millisecond
	attributionMissMemory      = 5 * time.Second
	// A failed BPF insertion can identify a packet, but an old kernel error
	// must never authorize a new client reusing the same tuple.
	bpfFailureAttributionTTL = 8 * time.Second
	presenceMaxAge           = 15 * time.Second
	wifiRefreshInterval      = 10 * time.Second
	wifiPresenceMaxAge       = 20 * time.Second
)

var ipv4UpstreamRulePattern = regexp.MustCompile(
	"^(tcp|udp)\\s+\\[[^\\]]*\\]\\s+\\d+\\([^)]*\\)\\s+" +
		"([0-9.]+):(\\d+)\\s+->\\s+\\d+\\([^)]*\\)\\s+" +
		"([0-9.]+):(\\d+)\\s+->\\s+([0-9.]+):(\\d+)\\b",
)

var ipv4TuplePattern = regexp.MustCompile(
	`([0-9]{1,3}(?:\.[0-9]{1,3}){3}):(\d+)`,
)

var softApConnectedCountPattern = regexp.MustCompile(`(?m)^getConnectedClientList\(\)\.size\(\):\s*(\d+)\s*$`)

// Match only the outgoing/original-client direction. A reverse error has
// src4=remote IP and src46=remote IP, so it must never authenticate a phone.
var bpfFailureTimestampPattern = regexp.MustCompile(`(?m)(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{1,6})\s+-\s+`)
var bpfFailureFlowPattern = regexp.MustCompile(
	`l4proto:\s*(6|17),\s*src4:\s*/([0-9.]+),\s*dst4:\s*/([0-9.]+),\s*srcPort:\s*(\d+),\s*dstPort:\s*(\d+).*?src46:\s*/([0-9.]+),\s*dst46:\s*/([0-9.]+),\s*srcPort:\s*(\d+),\s*dstPort:\s*(\d+)`,
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
		dumpFn:            dumpTetheringState,
		wifiDumpFn:        dumpWifiState,
		flows:             make(map[flowAttributionKey]string),
		bpfFailedFlows:    make(map[flowAttributionKey]string),
		connectedClients:  make(map[string]struct{}),
		clientMACs:        make(map[string]string),
		currentClientMACs: make(map[string]string),
		recentMisses:      make(map[flowAttributionKey]time.Time),
		// Go's Android time.Local defaults to UTC. Android must provide its
		// own clock zone before local, offset-less log timestamps are trusted.
		logClockRequired: runtime.GOOS == "android",
	}
}

func dumpTetheringState() (string, error) {
	// Use the absolute binary path. The Shizuku shell process does not always
	// inherit a PATH containing dumpsys on OEM Android builds.
	return runBoundedDump(attributionDumpTimeout, "/system/bin/dumpsys", "tethering")
}

// SoftApManager reports the *current* associated station count, unlike the
// tethering Client Information list which may retain DHCP/IP entries after a
// phone has left. This is a safety cross-check, not per-device identity.
func dumpWifiState() (string, error) {
	return runBoundedDump(2*time.Second, "/system/bin/dumpsys", "wifi")
}

// Only trust a running, tethered SoftAP manager. Historical metrics, stopped
// managers, and absent counters must never be mistaken for zero clients.
func parseRunningSoftApClientCount(raw string) (int, bool) {
	const marker = "Dump of SoftApManager"
	if !strings.Contains(raw, marker) {
		return 0, false
	}
	total := 0
	found := false
	for _, block := range strings.Split(raw, marker)[1:] {
		if end := strings.Index(block, "\nSoftApManager:"); end >= 0 {
			block = block[:end]
		}
		if !strings.Contains(block, "current StateMachine mode: StartedState") ||
			!strings.Contains(block, "mRole: ROLE_SOFTAP_TETHERED") ||
			!strings.Contains(block, "mIfaceIsUp: true") {
			continue
		}
		match := softApConnectedCountPattern.FindStringSubmatch(block)
		if len(match) != 2 {
			return 0, false
		}
		count, err := strconv.Atoi(match[1])
		if err != nil || count < 0 {
			return 0, false
		}
		total += count
		found = true
	}
	return total, found
}

func runBoundedDump(timeout time.Duration, name string, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	command := exec.CommandContext(ctx, name, args...)
	// dumpsys hands its stdout to system_server, which writes the dump
	// itself. Killing dumpsys on timeout does not close that pipe, so without
	// WaitDelay CombinedOutput waits for system_server to finish and the
	// timeout bounds nothing (seen on the Reno11: 13 s attribution waits).
	command.WaitDelay = attributionDumpWaitDelay
	out, err := command.CombinedOutput()
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

// parseRecentBPFFailedAttributions reads Android's *own* failed offload
// insertions, observed on OPPO/ColorOS when the BPF map returns E2BIG.
// These are not guessed port-only matches: the OS explicitly logs the
// originating IPv4, the translated IPv4/port, the destination and protocol.
// Old errors and conflicting owners are deliberately unusable.
func parseRecentBPFFailedAttributions(raw string, now time.Time) (map[flowAttributionKey]string, int) {
	flows, failures, _ := parseBPFFailureAttributions(raw, now, true)
	return flows, failures
}

type bpfFailureDiagnostics struct {
	observed           int
	rejectedTimestamps int
	clockSkipped       int
	absentOwners       int
}

func parseBPFFailureAttributions(raw string, now time.Time, clockKnown bool) (map[flowAttributionKey]string, int, bpfFailureDiagnostics) {
	return parseBPFFailuresForPresence(raw, now, clockKnown, nil)
}

func parseBPFFailuresForPresence(raw string, now time.Time, clockKnown bool, presence *clientPresence) (map[flowAttributionKey]string, int, bpfFailureDiagnostics) {
	result := make(map[flowAttributionKey]string)
	ambiguous := make(map[flowAttributionKey]bool)
	failures := 0
	diagnostics := bpfFailureDiagnostics{}
	for _, line := range strings.Split(raw, "\n") {
		if !strings.Contains(line, "Could not insert entry") || !strings.Contains(line, "E2BIG") {
			continue
		}
		diagnostics.observed++
		if !clockKnown {
			diagnostics.clockSkipped++
			continue
		}
		// The timestamp in dumpsys tethering uses the phone's local clock.
		// now must already use the trusted Android zone, not Go's Android
		// time.Local (UTC). Never widen the age window to compensate for a zone.
		timestamp := bpfFailureTimestampPattern.FindStringSubmatch(line)
		if len(timestamp) != 2 {
			diagnostics.rejectedTimestamps++
			continue
		}
		at, err := time.ParseInLocation("2006-01-02T15:04:05.999999", timestamp[1], now.Location())
		if err != nil || now.Sub(at) < -2*time.Second || now.Sub(at) > bpfFailureAttributionTTL {
			diagnostics.rejectedTimestamps++
			continue
		}
		fields := bpfFailureFlowPattern.FindStringSubmatch(line)
		if len(fields) != 10 {
			continue
		}
		proto := ""
		switch fields[1] {
		case "6":
			proto = "tcp"
		case "17":
			proto = "udp"
		default:
			continue
		}
		originalIP, destinationIP, translatedIP, translatedDest := fields[2], fields[3], fields[6], fields[7]
		if !validPhysicalIPv4(originalIP) || !isSharedTunnelAddress(translatedIP) ||
			destinationIP != translatedDest || net.ParseIP(destinationIP) == nil {
			continue
		}
		translatedPort, translatedErr := parseAttributionPort(fields[8])
		destinationPort, destinationErr := parseAttributionPort(fields[5])
		translatedDestPort, translatedDestErr := parseAttributionPort(fields[9])
		if translatedErr != nil || destinationErr != nil || translatedDestErr != nil || destinationPort != translatedDestPort {
			continue
		}
		key := flowAttributionKey{
			Protocol: proto, PublicIP: translatedIP, PublicPort: translatedPort,
			DstIP: destinationIP, DstPort: destinationPort,
		}
		if presence != nil && presence.flowOwnerAbsent(originalIP) {
			diagnostics.absentOwners++
			continue
		}
		failures++
		if oldIP, exists := result[key]; exists && oldIP != originalIP {
			ambiguous[key] = true
		} else {
			result[key] = originalIP
		}
	}
	for key := range ambiguous {
		// An empty result is deliberately preserved as an ambiguity marker.
		result[key] = ""
	}
	return result, failures, diagnostics
}

func validPhysicalIPv4(value string) bool {
	parsed := net.ParseIP(value)
	return parsed != nil && parsed.To4() != nil && !isSharedTunnelAddress(value) &&
		!parsed.IsUnspecified() && !parsed.IsMulticast()
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
		if len(r.connectedClients) > 1 || (r.eventPresence != nil && r.eventPresence.wifiCount > 1) {
			deadline = startedAt.Add(attributionMultiClientWait)
		}
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
		fresh := r.lastError == "" && time.Since(r.lastSuccess) < attributionCacheTTL
		r.mu.Unlock()
		if client != "" && fresh {
			return r.recordResolved(client, fallback, startedAt)
		}

		if attempt > 0 && !time.Now().Before(deadline) {
			break
		}
		if waitForRule {
			r.refreshBefore(attributionRefreshInterval, deadline)
		} else {
			r.refreshIfOlderThan(attributionRefreshInterval)
		}

		r.mu.Lock()
		client, fallback = r.lookupLocked(key)
		if client != "" && r.lastError == "" && time.Since(r.lastSuccess) < attributionCacheTTL {
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
		if remaining := time.Until(deadline); remaining > 0 {
			time.Sleep(min(attributionRetryDelay, remaining))
		}
	}

	r.mu.Lock()
	defer r.mu.Unlock()

	// A sole DHCP entry is not proof of a sole Wi-Fi station. Only a
	// live Android callback snapshot with one associated MAC and one lease
	// may identify an otherwise unknown flow. Contradictory tuples never fall back.
	if _, conflict := r.bpfFailedFlows[key]; !conflict && r.eventPresence != nil &&
		r.eventPresence.authoritative && r.eventPresence.wifiCountKnown &&
		r.eventPresence.wifiCount == 1 && len(r.eventPresence.clients) == 1 {
		for ip := range r.eventPresence.clients {
			if r.eventPresence.macs[ip] != "" && r.onlyClientInRulesLocked(ip) {
				r.resolvedFlows++
				r.fallbackResolvedFlows++
				r.lastMiss = ""
				r.noteLatencyLocked(startedAt)
				return ip
			}
		}
	}

	if r.looseCandidateLocked(key) {
		r.looseCandidateFlows++
	}
	r.unresolvedFlows++
	if key.Protocol == "udp" {
		r.unresolvedUDPFlows++
	} else {
		r.unresolvedTCPFlows++
	}
	r.lastMiss = formatFlowAttributionKey(key)
	r.lastUnresolvedFlow = r.lastMiss
	r.lastUnresolvedReason = r.unresolvedReasonLocked(key)
	r.lastUnresolvedMillis = time.Now().UnixMilli()
	r.noteLatencyLocked(startedAt)
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

// lookupLocked accepts only an exact protocol/source/destination tuple.
// Matching on the translated
// port alone is NOT accepted: two phones may share a translated port towards
// different destinations, and the second one's rule may not be published yet.
func (r *flowAttributionResolver) lookupLocked(
	key flowAttributionKey,
) (clientIP string, fallback bool) {
	if client := r.flows[key]; client != "" {
		if r.ownerAbsentLocked(client) {
			return "", false
		}
		// A freshly failed BPF insertion naming a different phone proves
		// that cached translated tuple attribution cannot be trusted.
		if owner, logged := r.bpfFailedFlows[key]; logged && owner != client && !r.ownerAbsentLocked(owner) {
			return "", false
		}
		return client, false
	}
	if client := r.bpfFailedFlows[key]; client != "" {
		if r.ownerAbsentLocked(client) {
			return "", false
		}
		return client, true
	}
	if _, ambiguous := r.bpfFailedFlows[key]; ambiguous {
		return "", false
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
	r.mu.Lock()
	logNow := finished
	if r.logLocation != nil {
		logNow = finished.In(r.logLocation)
	}
	clockKnown := !r.logClockRequired || r.logTimezoneKnown
	clockGeneration := r.logClockGeneration
	presenceGeneration := r.presenceGeneration
	var eventPresence *clientPresence
	if r.eventPresence != nil {
		p := clonePresence(*r.eventPresence)
		eventPresence = &p
	}
	r.mu.Unlock()

	var (
		flows          map[flowAttributionKey]string
		bpfFlows       map[flowAttributionKey]string
		bpfFailures    int
		bpfDiagnostics bpfFailureDiagnostics
		clients        map[string]struct{}
		macs           map[string]string
	)
	if err == nil {
		flows = parseIPv4UpstreamAttributions(raw)
		bpfFlows, bpfFailures, bpfDiagnostics = parseBPFFailuresForPresence(raw, logNow, clockKnown, eventPresence)
		clients = parseTetheringConnectedClients(raw)
		macs = parseIPv4DownstreamClientMACs(raw)
	}

	r.mu.Lock()
	defer r.mu.Unlock()
	r.lastRefresh = finished
	r.dumpCount++
	r.lastDumpDuration = finished.Sub(started)
	if r.lastDumpDuration > r.slowestDump {
		r.slowestDump = r.lastDumpDuration
	}
	if err != nil {
		r.lastError = err.Error()
		r.dumpFailures++
		r.lastDumpError = r.lastError
		r.lastDumpErrorMillis = finished.UnixMilli()
		r.flows = make(map[flowAttributionKey]string)
		r.bpfFailedFlows = make(map[flowAttributionKey]string)
		r.currentClientMACs = make(map[string]string)
		r.recentBPFFailures = 0
		r.bpfDiagnostics = bpfFailureDiagnostics{}
		return
	}
	if clockGeneration != r.logClockGeneration || presenceGeneration != r.presenceGeneration {
		// A clock change raced parsing. NAT rules are independent of the
		// log zone, but no identity parsed with the old zone may be installed.
		bpfFlows = make(map[flowAttributionKey]string)
		bpfFailures = 0
		bpfDiagnostics = bpfFailureDiagnostics{}
		r.lastRefresh = time.Time{}
	}
	r.flows = flows
	r.bpfFailedFlows = bpfFlows
	r.recentBPFFailures = bpfFailures
	r.bpfDiagnostics = bpfDiagnostics
	r.connectedClients = clients
	r.currentClientMACs = macs
	// An explicitly emitted Client Information section can establish that
	// Android currently lists zero clients. Without it, an empty NAT dump
	// cannot prove that a sleeping client has left the hotspot.
	r.clientListKnown = strings.Contains(raw, "Client Information:") || len(clients) > 0
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

// Wi-Fi checks run separately from NAT attribution. An unavailable dumpsys
// never logs anyone out and cannot block packet forwarding.
func (r *flowAttributionResolver) refreshWifiPresence() {
	if r == nil || r.wifiDumpFn == nil {
		return
	}
	r.wifiRefreshMu.Lock()
	defer r.wifiRefreshMu.Unlock()
	r.mu.Lock()
	stale := time.Since(r.lastWifiRefresh) >= wifiRefreshInterval
	r.mu.Unlock()
	if !stale {
		return
	}
	raw, err := r.wifiDumpFn()
	count, known := parseRunningSoftApClientCount(raw)
	r.mu.Lock()
	defer r.mu.Unlock()
	r.lastWifiRefresh = time.Now()
	r.wifiCountKnown = err == nil && known
	if err == nil && known {
		r.wifiClientCount = count
		r.lastWifiSuccess = r.lastWifiRefresh
		r.lastWifiError = ""
	} else if err != nil {
		r.lastWifiError = err.Error()
	} else {
		r.lastWifiError = "no running tethered SoftAP station count"
	}
}

func (r *flowAttributionResolver) presence() clientPresence {
	if r == nil {
		return clientPresence{}
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.eventPresence != nil && r.eventPresence.wifiCountKnown {
		return clonePresence(*r.eventPresence)
	}

	result := clientPresence{
		clients:     make(map[string]struct{}, len(r.connectedClients)),
		macs:        make(map[string]string, len(r.clientMACs)),
		currentMACs: maps.Clone(r.currentClientMACs),
		authoritative: r.lastError == "" && r.clientListKnown &&
			time.Since(r.lastSuccess) < presenceMaxAge,
		wifiCount:      r.wifiClientCount,
		wifiCountKnown: r.wifiCountKnown && time.Since(r.lastWifiSuccess) < wifiPresenceMaxAge,
	}
	for ip := range r.connectedClients {
		result.clients[ip] = struct{}{}
	}
	for ip, mac := range r.clientMACs {
		result.macs[ip] = mac
	}
	if result.wifiCountKnown {
		switch {
		case result.wifiCount == 0:
			// No wireless station is attached: stale leases cannot hold sessions open.
			// The normal 20-second departure grace applies.
			result.clients = make(map[string]struct{})
			result.authoritative = true
		case result.wifiCount != len(result.clients):
			// Count mismatch does not reveal WHICH MAC is absent. Mark
			// presence unknown rather than falsely confirming or evicting a
			// still-connected user.
			result.authoritative = false
		}
	}
	return result
}

func (r *flowAttributionResolver) snapshot() flowAttributionSnapshot {
	if r == nil {
		return flowAttributionSnapshot{}
	}

	r.mu.Lock()
	defer r.mu.Unlock()

	snapshot := flowAttributionSnapshot{
		BPFDiscardedAbsentOwners: r.bpfDiagnostics.absentOwners,
		LastUnresolvedReason:     r.lastUnresolvedReason,
		ResolvedFlows:            r.resolvedFlows,
		FallbackResolvedFlows:    r.fallbackResolvedFlows,
		UnresolvedFlows:          r.unresolvedFlows,
		UnresolvedTCPFlows:       r.unresolvedTCPFlows,
		UnresolvedUDPFlows:       r.unresolvedUDPFlows,
		LooseCandidateFlows:      r.looseCandidateFlows,
		ClientCount:              len(r.connectedClients),
		RecentBPFFailures:        r.recentBPFFailures,
		ClientListKnown: r.lastError == "" && r.clientListKnown &&
			time.Since(r.lastSuccess) < presenceMaxAge,
		WifiAssociatedClients: r.wifiClientCount,
		WifiCountKnown:        r.wifiCountKnown && time.Since(r.lastWifiSuccess) < wifiPresenceMaxAge,
		SlowestResolveMillis:  r.slowestResolve.Milliseconds(),
		DumpCount:             r.dumpCount,
		LastDumpMillis:        r.lastDumpDuration.Milliseconds(),
		SlowestDumpMillis:     r.slowestDump.Milliseconds(),
		LastError:             r.lastError,
		LastMiss:              r.lastMiss,
		AndroidTimezone:       r.logTimezone,
		AndroidTimezoneKnown:  r.logTimezoneKnown,
		AndroidTimezoneSource: r.logTimezoneSource,
		TimezoneError:         r.logTimezoneError,
		BPFObservedFailures:   r.bpfDiagnostics.observed,
		BPFRejectedTimestamps: r.bpfDiagnostics.rejectedTimestamps,
		BPFClockSkipped:       r.bpfDiagnostics.clockSkipped,
		DumpFailures:          r.dumpFailures,
		LastDumpError:         r.lastDumpError,
		LastDumpErrorMillis:   r.lastDumpErrorMillis,
		LastUnresolvedFlow:    r.lastUnresolvedFlow,
		LastUnresolvedMillis:  r.lastUnresolvedMillis,
	}
	if r.eventPresence != nil {
		snapshot.CallbackPresenceKnown = r.eventPresence.wifiCountKnown
		snapshot.CallbackStationCount = r.eventPresence.wifiCount
		snapshot.CallbackLeaseCount = len(r.eventPresence.clients)
		snapshot.CallbackMappingComplete = r.eventPresence.leaseMappingComplete
	}
	return snapshot
}

func isSharedTunnelAddress(ip string) bool {
	switch ip {
	case "192.0.2.2", "2001:db8::2":
		return true
	default:
		return false
	}
}

// These snapshots originate from the privileged Android API callbacks, not
// from browser-submitted identities. A lease is usable only for an associated MAC.
func (r *flowAttributionResolver) setPresenceJSON(raw string) {
	var event struct {
		AndroidTimezone         *string  `json:"androidTimezone"`
		AndroidUTCOffsetSeconds *int     `json:"androidUtcOffsetSeconds"`
		WifiKnown               bool     `json:"wifiKnown"`
		LeasesKnown             bool     `json:"leasesKnown"`
		AssociatedMACs          []string `json:"associatedMacs"`
		Leases                  []struct {
			IP  string `json:"ip"`
			MAC string `json:"mac"`
		} `json:"leases"`
	}
	if json.Unmarshal([]byte(raw), &event) != nil {
		return
	}
	if event.AndroidTimezone != nil || event.AndroidUTCOffsetSeconds != nil {
		zone := ""
		if event.AndroidTimezone != nil {
			zone = *event.AndroidTimezone
		}
		r.setAndroidLogTimezone(zone, event.AndroidUTCOffsetSeconds)
	}
	p := clientPresence{eventBased: true, clients: make(map[string]struct{}), macs: make(map[string]string),
		wifiCountKnown: event.WifiKnown, leasesKnown: event.LeasesKnown, associatedMACs: make(map[string]struct{})}
	associated := make(map[string]bool)
	for _, mac := range event.AssociatedMACs {
		mac = strings.ToLower(mac)
		if validClientMAC(mac) {
			associated[mac] = true
			p.associatedMACs[mac] = struct{}{}
		} else {
			p.wifiCountKnown = false
		}
	}
	p.wifiCount = len(p.associatedMACs)
	ambiguous := make(map[string]bool)
	for _, lease := range event.Leases {
		mac := strings.ToLower(lease.MAC)
		if validPhysicalIPv4(lease.IP) && validClientMAC(mac) && (!event.WifiKnown || associated[mac]) {
			if previous := p.macs[lease.IP]; previous != "" && previous != mac {
				ambiguous[lease.IP] = true
			}
			p.clients[lease.IP] = struct{}{}
			p.macs[lease.IP] = mac
		}
	}
	for ip := range ambiguous {
		delete(p.clients, ip)
		delete(p.macs, ip)
	}
	covered := make(map[string]bool)
	for _, mac := range p.macs {
		covered[mac] = true
	}
	p.leaseMappingComplete = p.wifiCountKnown && (p.wifiCount == 0 || (event.LeasesKnown && len(covered) == p.wifiCount && len(ambiguous) == 0))
	p.authoritative = p.leaseMappingComplete
	r.mu.Lock()
	if r.eventPresence == nil || !samePresence(*r.eventPresence, p) {
		r.presenceGeneration++
		r.lastRefresh = time.Time{}
	}
	r.eventPresence = &p
	r.mu.Unlock()
}
func clonePresence(p clientPresence) clientPresence {
	result := p
	result.currentMACs = maps.Clone(p.currentMACs)
	result.clients = make(map[string]struct{})
	result.macs = make(map[string]string)
	result.associatedMACs = make(map[string]struct{})
	for ip := range p.clients {
		result.clients[ip] = struct{}{}
	}
	for ip, mac := range p.macs {
		result.macs[ip] = mac
	}
	for mac := range p.associatedMACs {
		result.associatedMACs[mac] = struct{}{}
	}
	return result
}
