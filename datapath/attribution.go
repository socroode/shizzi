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

// flowAttributionKey describes the NATed flow visible on the Shizzi test
// network. Android's tethering stack can rewrite several hotspot clients onto
// the same 192.0.2.2 source address. The IPv4 tethering BPF/conntrack dump
// retains the original downstream client address and the translated tuple, so
// Shizzi can recover the physical client's IP before applying portal, quota,
// and rate policies.
type flowAttributionKey struct {
	Protocol   string
	PublicIP   string
	PublicPort uint16
	DstIP      string
	DstPort    uint16
}

type flowAttributionSnapshot struct {
	ResolvedFlows         int64
	FallbackResolvedFlows int64
	UnresolvedFlows       int64
	ClientCount           int
	LastError             string
	LastMiss              string
}

type flowAttributionResolver struct {
	mu sync.Mutex

	dumpFn func() (string, error)

	flows            map[flowAttributionKey]string
	connectedClients map[string]struct{}
	lastRefresh      time.Time
	lastError        string

	resolvedFlows         int64
	fallbackResolvedFlows int64
	unresolvedFlows       int64
	lastMiss              string
}

const (
	attributionRefreshInterval = 100 * time.Millisecond
	attributionPortalWait      = 1500 * time.Millisecond
	attributionRetryDelay      = 50 * time.Millisecond
	attributionDumpTimeout     = 1200 * time.Millisecond
)

var ipv4UpstreamRulePattern = regexp.MustCompile(
	"^(tcp|udp)\\s+\\[[^\\]]*\\]\\s+\\d+\\([^)]*\\)\\s+" +
		"([0-9.]+):(\\d+)\\s+->\\s+\\d+\\([^)]*\\)\\s+" +
		"([0-9.]+):(\\d+)\\s+->\\s+([0-9.]+):(\\d+)\\b",
)

// OEM tethering dumps are not byte-for-byte identical to AOSP. When the
// strict pattern above misses a vendor-formatted rule, three IPv4:port tuples
// are still enough to recover client -> translated -> destination.
var ipv4TuplePattern = regexp.MustCompile(
	`([0-9]{1,3}(?:\.[0-9]{1,3}){3}):(\d+)`,
)

// Android's tethering dump also exposes the clients currently attached to the
// hotspot, even when one of them is idle and therefore has no upstream flow.
// Use that list before falling back to flow-derived clients so a newly joined
// second phone can never be mistaken for the first phone during portal login.
var tetheringConnectedClientPattern = regexp.MustCompile(
	`/([0-9]{1,3}(?:\.[0-9]{1,3}){3})=downstream:`,
)

func newFlowAttributionResolver() *flowAttributionResolver {
	return &flowAttributionResolver{
		dumpFn:           dumpTetheringState,
		flows:            make(map[flowAttributionKey]string),
		connectedClients: make(map[string]struct{}),
	}
}

func dumpTetheringState() (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), attributionDumpTimeout)
	defer cancel()

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

func (r *flowAttributionResolver) resolve(
	key flowAttributionKey,
	waitForRule bool,
) string {
	if r == nil {
		return ""
	}

	deadline := time.Now()
	if waitForRule {
		deadline = deadline.Add(attributionPortalWait)
	}

	for {
		r.mu.Lock()
		if client, fallback := r.lookupLocked(key); client != "" &&
			time.Since(r.lastRefresh) < attributionRefreshInterval {
			r.resolvedFlows++
			if fallback {
				r.fallbackResolvedFlows++
			}
			r.lastMiss = ""
			r.mu.Unlock()
			return client
		}

		if time.Since(r.lastRefresh) >= attributionRefreshInterval {
			r.refreshLocked()
		}
		client, fallback := r.lookupLocked(key)
		if client != "" {
			r.resolvedFlows++
			if fallback {
				r.fallbackResolvedFlows++
			}
			r.lastMiss = ""
			r.mu.Unlock()
			return client
		}
		r.mu.Unlock()

		if !waitForRule || time.Now().After(deadline) {
			r.mu.Lock()
			r.unresolvedFlows++
			r.lastMiss = formatFlowAttributionKey(key)
			r.mu.Unlock()
			return ""
		}
		time.Sleep(attributionRetryDelay)
	}
}

// lookupLocked first tries the exact 5-tuple recorded by Android. Some OEM
// tethering/netstack combinations expose the same translated flow to gVisor
// with a destination/protocol detail that does not byte-match dumpsys even
// though the translated source port still identifies the NAT flow. In that
// case we fall back only when every matching rule belongs to the same original
// hotspot client. If two phones could match, attribution fails closed.
func (r *flowAttributionResolver) lookupLocked(
	key flowAttributionKey,
) (clientIP string, fallback bool) {
	if client := r.flows[key]; client != "" {
		return client, false
	}

	if client := r.uniqueClientLocked(func(candidate flowAttributionKey) bool {
		return candidate.Protocol == key.Protocol &&
			candidate.PublicIP == key.PublicIP &&
			candidate.PublicPort == key.PublicPort
	}); client != "" {
		return client, true
	}

	if client := r.uniqueClientLocked(func(candidate flowAttributionKey) bool {
		return candidate.PublicIP == key.PublicIP &&
			candidate.PublicPort == key.PublicPort &&
			candidate.DstIP == key.DstIP &&
			candidate.DstPort == key.DstPort
	}); client != "" {
		return client, true
	}

	if client := r.uniqueClientLocked(func(candidate flowAttributionKey) bool {
		return candidate.PublicIP == key.PublicIP &&
			candidate.PublicPort == key.PublicPort
	}); client != "" {
		return client, true
	}

	return "", false
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

func (r *flowAttributionResolver) refreshLocked() {
	raw, err := r.dumpFn()
	r.lastRefresh = time.Now()
	if err != nil {
		r.lastError = err.Error()
		return
	}

	r.flows = parseIPv4UpstreamAttributions(raw)
	r.connectedClients = parseTetheringConnectedClients(raw)
	r.lastError = ""
}

func (r *flowAttributionResolver) clientSetLocked() map[string]struct{} {
	if len(r.connectedClients) > 0 {
		clients := make(map[string]struct{}, len(r.connectedClients))
		for clientIP := range r.connectedClients {
			clients[clientIP] = struct{}{}
		}
		return clients
	}

	// Compatibility fallback for OEM dumps/tests that do not expose the
	// Client Information section.
	clients := make(map[string]struct{})
	for _, clientIP := range r.flows {
		if clientIP != "" && !isSharedTunnelAddress(clientIP) {
			clients[clientIP] = struct{}{}
		}
	}
	return clients
}

func (r *flowAttributionResolver) hasMultipleClients() bool {
	if r == nil {
		return false
	}

	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.clientSetLocked()) > 1
}

// singleConnectedClient returns a physical hotspot client only when Android's
// current tethering state proves there is exactly one. The refresh is forced:
// this path is used for portal identity, where a stale one-client snapshot
// after a second phone joins would attach an account to the wrong device.
func (r *flowAttributionResolver) singleConnectedClient() string {
	if r == nil {
		return ""
	}

	r.mu.Lock()
	defer r.mu.Unlock()
	r.refreshLocked()

	clients := r.clientSetLocked()
	if len(clients) != 1 {
		return ""
	}
	for clientIP := range clients {
		return clientIP
	}
	return ""
}

func (r *flowAttributionResolver) snapshot() flowAttributionSnapshot {
	if r == nil {
		return flowAttributionSnapshot{}
	}

	r.mu.Lock()
	defer r.mu.Unlock()

	clients := make(map[string]struct{})
	for _, clientIP := range r.flows {
		if clientIP != "" {
			clients[clientIP] = struct{}{}
		}
	}

	return flowAttributionSnapshot{
		ResolvedFlows:         r.resolvedFlows,
		FallbackResolvedFlows: r.fallbackResolvedFlows,
		UnresolvedFlows:       r.unresolvedFlows,
		ClientCount:           len(clients),
		LastError:             r.lastError,
		LastMiss:              r.lastMiss,
	}
}
