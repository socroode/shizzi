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
	attributionRetryDelay           = 80 * time.Millisecond
	attributionDumpTimeout          = 1800 * time.Millisecond
	attributionFallbackDumpTimeout  = 2500 * time.Millisecond
)

// Android's full tethering dump can grow to several megabytes on an active
// hotspot. Reading all of it for each new TCP/UDP flow caused the resolver to
// hit its timeout before the OPPO/ColorOS BPF rule could be parsed. Stop the
// dump as soon as the IPv4 upstream section has been emitted; awk exits at the
// downstream header, closing the pipe early instead of buffering the rest.
const ipv4UpstreamDumpScript = `dumpsys tethering | awk '
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

func dumpTetheringState() (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), attributionDumpTimeout)
	out, err := exec.CommandContext(ctx, "sh", "-c", ipv4UpstreamDumpScript).CombinedOutput()
	ctxErr := ctx.Err()
	cancel()

	if ctxErr == nil && err == nil {
		raw := string(out)
		if strings.Contains(strings.ToLower(raw), "ipv4 upstream:") {
			return raw, nil
		}
	}

	// Preserve compatibility with OEMs whose tethering dump does not expose the
	// standard section header. The fallback is only used when the fast section
	// extractor cannot produce a usable section; OPPO/ColorOS takes the fast
	// path observed in the field report.
	if ctxErr != nil {
		return "", fmt.Errorf("targeted dumpsys tethering timeout: %w", ctxErr)
	}

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
		return "", fmt.Errorf(
			"targeted tethering dump failed (%v); fallback failed: %w",
			err,
			fallbackErr,
		)
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
	if r == nil {
		return ""
	}

	deadline := time.Now()
	if waitForRule {
		deadline = deadline.Add(attributionRuleWait)
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
