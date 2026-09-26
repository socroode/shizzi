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
	attributionRefreshInterval      = 180 * time.Millisecond
	attributionRuleWait             = 1800 * time.Millisecond
	portalBindAttributionRuleWait   = 8 * time.Second
	attributionRetryDelay           = 80 * time.Millisecond
	attributionDumpTimeout          = 1800 * time.Millisecond
	attributionFallbackDumpTimeout  = 2500 * time.Millisecond
)

// OPPO/ColorOS exposes more than one "IPv4 Upstream" section. The first one can
// be only a forwarding-surface header while the later "BPF stats:" section
// contains the actual per-flow NAT tuples. Reading the first section was the
// 1.7.9 bug: Shizzi exited before reaching the usable BPF rules.
//
// Stream dumpsys through awk and ignore every earlier IPv4 section. Once
// "BPF stats:" is reached, emit only its IPv4 Upstream block and exit at the
// matching IPv4 Downstream header. This keeps the read small even when the full
// tethering dump is several megabytes.
const bpfIPv4UpstreamDumpScript = `dumpsys tethering | awk '
{
  lower=tolower($0)
}
lower ~ /bpf stats:/ { in_bpf=1; next }
in_bpf && lower ~ /ipv4 upstream:/ { print; in_upstream=1; next }
in_bpf && in_upstream && lower ~ /ipv4 downstream:/ { print; exit }
in_bpf && in_upstream { print }
'`

// Compatibility path for AOSP/OEM builds that do not label their useful
// forwarding table with "BPF stats:". This is deliberately used only when the
// BPF-targeted extractor produced no IPv4 Upstream header at all.
const firstIPv4UpstreamDumpScript = `dumpsys tethering | awk '
/IPv4 Upstream:/ { print; in_upstream=1; next }
in_upstream && /IPv4 Downstream:/ { print; exit }
in_upstream { print }
'`


var (
	attributionProtocolPattern = regexp.MustCompile(`^(tcp|udp)\b`)
	attributionEndpointPattern = regexp.MustCompile(
		`([0-9]{1,3}(?:\.[0-9]{1,3}){3}):(\d{1,5})`,
	)
)

func newFlowAttributionResolver() *flowAttributionResolver {
	return &flowAttributionResolver{
		dumpFn: dumpTetheringState,
		flows:  make(map[flowAttributionKey]string),
	}
}

func runTargetedTetheringDump(script string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), attributionDumpTimeout)
	defer cancel()

	out, err := exec.CommandContext(ctx, "sh", "-c", script).CombinedOutput()
	if ctx.Err() != nil {
		return "", fmt.Errorf("targeted dumpsys tethering timeout: %w", ctx.Err())
	}
	if err != nil {
		return "", fmt.Errorf("targeted dumpsys tethering: %w", err)
	}
	return string(out), nil
}

func dumpTetheringState() (string, error) {
	// Preferred path for the Reno11/ColorOS format observed in the live field
	// capture: skip the earlier empty forwarding surface and wait for BPF stats.
	if raw, err := runTargetedTetheringDump(bpfIPv4UpstreamDumpScript); err == nil {
		if strings.Contains(strings.ToLower(raw), "ipv4 upstream:") {
			return raw, nil
		}
	}

	// AOSP and some OEM builds expose a single useful IPv4 Upstream section
	// without a "BPF stats:" label.
	if raw, err := runTargetedTetheringDump(firstIPv4UpstreamDumpScript); err == nil {
		if strings.Contains(strings.ToLower(raw), "ipv4 upstream:") {
			return raw, nil
		}
	}

	// Last-resort compatibility for headerless OEM dumps. This path is not used
	// on the Reno11 because its BPF section is found by the preferred extractor.
	fallbackCtx, fallbackCancel := context.WithTimeout(
		context.Background(),
		attributionFallbackDumpTimeout,
	)
	defer fallbackCancel()

	fallbackOut, fallbackErr := exec.CommandContext(
		fallbackCtx,
		"dumpsys",
		"tethering",
	).CombinedOutput()
	if fallbackCtx.Err() != nil {
		return "", fmt.Errorf("fallback dumpsys tethering timeout: %w", fallbackCtx.Err())
	}
	if fallbackErr != nil {
		return "", fmt.Errorf("fallback dumpsys tethering: %w", fallbackErr)
	}
	return string(fallbackOut), nil
}

func parseIPv4UpstreamAttributions(raw string) map[flowAttributionKey]string {
	result := make(map[flowAttributionKey]string)
	inUpstream := false
	sawUpstreamHeader := false

	for _, rawLine := range strings.Split(raw, "\n") {
		line := strings.TrimSpace(rawLine)
		lower := strings.ToLower(line)
		switch {
		case strings.Contains(lower, "ipv4 upstream"):
			inUpstream = true
			sawUpstreamHeader = true
			continue
		case strings.Contains(lower, "ipv4 downstream"):
			inUpstream = false
			continue
		}

		protocolMatch := attributionProtocolPattern.FindStringSubmatch(lower)
		if len(protocolMatch) != 2 {
			continue
		}

		endpoints := attributionEndpointPattern.FindAllStringSubmatch(line, -1)
		if len(endpoints) < 3 {
			continue
		}

		clientIP := endpoints[0][1]
		publicIP := endpoints[1][1]
		dstIP := endpoints[2][1]

		// AOSP and OEM builds format the interface columns differently. The
		// stable part of the forwarding-rule line is the three IPv4:port tuples.
		// If an explicit upstream section exists, trust it. When an OEM omits the
		// section header, only accept a private downstream source translated to
		// Shizzi's shared TUN address; this prevents a downstream rule from being
		// mistaken for an upstream rule.
		if sawUpstreamHeader {
			if !inUpstream {
				continue
			}
		} else if !isLikelyHotspotClient(clientIP) || !isSharedTunnelAddress(publicIP) {
			continue
		}

		if net.ParseIP(clientIP) == nil || net.ParseIP(publicIP) == nil || net.ParseIP(dstIP) == nil {
			continue
		}

		if _, err := parseAttributionPort(endpoints[0][2]); err != nil {
			continue
		}
		publicPort, errPublic := parseAttributionPort(endpoints[1][2])
		dstPort, errDst := parseAttributionPort(endpoints[2][2])
		if errPublic != nil || errDst != nil {
			continue
		}

		key := flowAttributionKey{
			Protocol:   protocolMatch[1],
			PublicIP:   publicIP,
			PublicPort: publicPort,
			DstIP:      dstIP,
			DstPort:    dstPort,
		}
		result[key] = clientIP
	}
	return result
}

func isLikelyHotspotClient(raw string) bool {
	ip := net.ParseIP(raw)
	return ip != nil && ip.To4() != nil && ip.IsPrivate()
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
	grace := time.Duration(0)
	if waitForRule {
		grace = attributionRuleWait
	}
	return r.resolveWithGrace(key, grace)
}

func (r *flowAttributionResolver) resolveWithGrace(
	key flowAttributionKey,
	grace time.Duration,
) string {
	if r == nil {
		return ""
	}

	deadline := time.Now().Add(grace)

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

		if grace <= 0 || time.Now().After(deadline) {
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
