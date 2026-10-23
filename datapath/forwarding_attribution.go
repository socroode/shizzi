package datapath

import "time"

const forwardingAttributionBudget = 8 * time.Second

// Preserve the TCP endpoint (or the queued first UDP datagrams) during a
// transient NAT publication failure. Never infer an owner from a logged-in
// account: only the normal exact-tuple and trusted-presence resolver may admit.
func (m *TrafficManager) resolveForwardingClient(protocol, sourceIP string, sourcePort uint16, destinationIP string, destinationPort uint16, wait bool) string {
	if !wait || !isSharedTunnelAddress(sourceIP) || m.flowAttribution == nil {
		return m.resolveFlowClient(protocol, sourceIP, sourcePort, destinationIP, destinationPort, wait)
	}
	m.mu.Lock()
	required := m.requireClientAttribution
	m.mu.Unlock()
	if !required {
		return m.resolveFlowClient(protocol, sourceIP, sourcePort, destinationIP, destinationPort, wait)
	}
	if pacingStopped(m.stopData) {
		return ""
	}
	select {
	case m.pendingAttribution <- struct{}{}:
		defer func() { <-m.pendingAttribution }()
	default:
		return ""
	}
	return m.flowAttribution.resolveWithin(flowAttributionKey{
		Protocol: protocol, PublicIP: sourceIP, PublicPort: sourcePort,
		DstIP: destinationIP, DstPort: destinationPort,
	}, true, forwardingAttributionBudget, m.stopData)
}
