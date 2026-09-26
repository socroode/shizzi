package datapath

import (
	"context"
	"fmt"
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
	ResolvedFlows   int64
	UnresolvedFlows int64
	ClientCount     int
	LastError       string
}

type flowAttributionResolver struct {
	mu sync.Mutex

	dumpFn func() (string, error)

	flows       map[flowAttributionKey]string
	lastRefresh time.Time
	lastError   string

	resolvedFlows   int64
	unresolvedFlows int64
}

const (
	attributionRefreshInterval = 120 * time.Millisecond
	attributionPortalWait      = 450 * time.Millisecond
	attributionRetryDelay      = 60 * time.Millisecond
	attributionDumpTimeout     = 1200 * time.Millisecond
)

var ipv4UpstreamRulePattern = regexp.MustCompile(
	`^(tcp|udp)s+[[^]]*]s+d+([^)]*)s+([0-9.]+):(d+)s+->s+d+([^)]*)s+([0-9.]+):(d+)s+->s+([0-9.]+):(d+)`,
)

func newFlowAttributionResolver() *flowAttributionResolver {
	return &flowAttributionResolver{
		dumpFn: dumpTetheringState,
		flows:  make(map[flowAttributionKey]string),
	}
}

func dumpTetheringState() (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), attributionDumpTimeout)
	defer cancel()

	out, err := exec.CommandContext(ctx, "dumpsys", "tethering").CombinedOutput()
	if ctx.Err() != nil {
		return "", fmt.Errorf("dumpsys tethering timeout: %w", ctx.Err())
	}
	if err != nil {
		return "", fmt.Errorf("dumpsys tethering: %w", err)
	}
	return string(out), nil
}

func parseIPv4UpstreamAttributions(raw string) map[flowAttributionKey]string {
	result := make(map[flowAttributionKey]string)
	inUpstream := false

	for _, rawLine := range strings.Split(raw, "
") {
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

		match := ipv4UpstreamRulePattern.FindStringSubmatch(line)
		if len(match) != 8 {
			continue
		}

		clientPort, errClient := parseAttributionPort(match[3])
		publicPort, errPublic := parseAttributionPort(match[5])
		dstPort, errDst := parseAttributionPort(match[7])
		if errClient != nil || errPublic != nil || errDst != nil {
			continue
		}
		_ = clientPort // The original port is informative but not needed for reverse lookup.

		clientIP := match[2]
		key := flowAttributionKey{
			Protocol:   strings.ToLower(match[1]),
			PublicIP:   match[4],
			PublicPort: publicPort,
			DstIP:      match[6],
			DstPort:    dstPort,
		}
		if clientIP != "" {
			result[key] = clientIP
		}
	}
	return result
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
		if client := r.flows[key]; client != "" &&
			time.Since(r.lastRefresh) < attributionRefreshInterval {
			r.resolvedFlows++
			r.mu.Unlock()
			return client
		}

		if time.Since(r.lastRefresh) >= attributionRefreshInterval {
			r.refreshLocked()
		}
		client := r.flows[key]
		if client != "" {
			r.resolvedFlows++
			r.mu.Unlock()
			return client
		}
		r.mu.Unlock()

		if !waitForRule || time.Now().After(deadline) {
			r.mu.Lock()
			r.unresolvedFlows++
			r.mu.Unlock()
			return ""
		}
		time.Sleep(attributionRetryDelay)
	}
}

func (r *flowAttributionResolver) refreshLocked() {
	raw, err := r.dumpFn()
	r.lastRefresh = time.Now()
	if err != nil {
		r.lastError = err.Error()
		return
	}

	r.flows = parseIPv4UpstreamAttributions(raw)
	r.lastError = ""
}

func (r *flowAttributionResolver) hasMultipleClients() bool {
	if r == nil {
		return false
	}

	r.mu.Lock()
	defer r.mu.Unlock()

	clients := make(map[string]struct{})
	for _, clientIP := range r.flows {
		if clientIP == "" {
			continue
		}
		clients[clientIP] = struct{}{}
		if len(clients) > 1 {
			return true
		}
	}
	return false
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
		ResolvedFlows:   r.resolvedFlows,
		UnresolvedFlows: r.unresolvedFlows,
		ClientCount:     len(clients),
		LastError:       r.lastError,
	}
}
