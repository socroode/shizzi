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
	ClientCount           int    `json:"mappedClients"`
	LastError             string `json:"lastError,omitempty"`
	LastMiss              string `json:"lastMiss,omitempty"`
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
	attributionWait             = 1500 * time.Millisecond
	attributionMultiClientWait  = 3500 * time.Millisecond
	attributionRetryDelay       = 50 * time.Millisecond
	attributionDumpTimeout      = 1200 * time.Millisecond
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

func (r *flowAttributionResolver) resolve(key flowAttributionKey, waitForRule bool) string {
	if r == nil {
		return ""
	}

	startedAt := time.Now()
	deadline := startedAt
	if waitForRule {
		deadline = deadline.Add(attributionWait)
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

		if waitForRule && r.lastError == "" && len(r.clientSetLocked()) > 1 {
			extended := startedAt.Add(attributionMultiClientWait)
			if deadline.Before(extended) {
				deadline = extended
			}
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
			if waitForRule {
				// A single physical hotspot client is unambiguous even before
				// Android publishes its NAT rule. With 2+ clients we fail closed.
				r.refreshLocked()
				if r.lastError == "" {
					clients := r.clientSetLocked()
					if len(clients) == 1 {
						for clientIP := range clients {
							r.resolvedFlows++
							r.fallbackResolvedFlows++
							r.lastMiss = ""
							r.mu.Unlock()
							return clientIP
						}
					}
				}
			}
			r.unresolvedFlows++
			r.lastMiss = formatFlowAttributionKey(key)
			r.mu.Unlock()
			return ""
		}
		time.Sleep(attributionRetryDelay)
	}
}

func (r *flowAttributionResolver) lookupLocked(
	key flowAttributionKey,
) (clientIP string, fallback bool) {
	if client := r.flows[key]; client != "" {
		return client, false
	}

	// Some OEMs expose a destination detail differently from gVisor. A
	// translated source port is still usable only if every matching rule maps
	// to one physical client. Ambiguity never chooses a client.
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

	clients := make(map[string]struct{})
	for _, clientIP := range r.flows {
		if clientIP != "" && !isSharedTunnelAddress(clientIP) {
			clients[clientIP] = struct{}{}
		}
	}
	return clients
}

func (r *flowAttributionResolver) snapshot() flowAttributionSnapshot {
	if r == nil {
		return flowAttributionSnapshot{}
	}

	r.mu.Lock()
	defer r.mu.Unlock()

	clients := r.clientSetLocked()
	return flowAttributionSnapshot{
		ResolvedFlows:         r.resolvedFlows,
		FallbackResolvedFlows: r.fallbackResolvedFlows,
		UnresolvedFlows:       r.unresolvedFlows,
		ClientCount:           len(clients),
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
