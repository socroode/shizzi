package datapath

import "time"

type flowCopyResult struct {
	reason string
	bytes int64
}

type flowEndEvent struct {
	AtMillis int64 `json:"atMillis"`
	Protocol string `json:"protocol"`
	IP string `json:"ip"`
	AccountNumber string `json:"accountNumber,omitempty"`
	Direction string `json:"direction"`
	Reason string `json:"reason"`
	Bytes int64 `json:"bytes"`
}

type flowContinuitySnapshot struct {
	PacingWaits int64 `json:"pacingWaits"`
	PacingTotalWaitMillis int64 `json:"pacingTotalWaitMillis"`
	PacingMaxWaitMillis int64 `json:"pacingMaxWaitMillis"`
	PacingCancelled int64 `json:"pacingCancelled"`
	TCPEndedDirections int64 `json:"tcpEndedDirections"`
	UDPEndedDirections int64 `json:"udpEndedDirections"`
	Recent []flowEndEvent `json:"recent"`
}

func (m *TrafficManager) notePacing(elapsed time.Duration, cancelled bool) {
	ms := elapsed.Milliseconds()
	if ms == 0 && !cancelled { return }
	m.mu.Lock()
	defer m.mu.Unlock()
	if ms > 0 {
		m.continuity.PacingWaits++
		m.continuity.PacingTotalWaitMillis += ms
		m.continuity.PacingMaxWaitMillis = max(m.continuity.PacingMaxWaitMillis, ms)
	}
	if cancelled { m.continuity.PacingCancelled++ }
}

func (m *TrafficManager) noteFlowEnd(protocol, ip string, dir direction, result flowCopyResult, scope *flowScope) {
	m.mu.Lock()
	defer m.mu.Unlock()
	reason, account := result.reason, ""
	if scope != nil {
		if pacingStopped(m.stopData) { reason = "service-stopped" } else if pacingStopped(scope.revoked()) { reason = "authorization-ended" } else if pacingStopped(scope.transferDone()) { reason = "peer-direction-ended" }
		if scope.authorization != nil { account = scope.authorization.AccountNumber }
	}
	directionName := "upload"
	if dir == directionDownload { directionName = "download" }
	if protocol == "tcp" { m.continuity.TCPEndedDirections++ } else { m.continuity.UDPEndedDirections++ }
	event := flowEndEvent{time.Now().UnixMilli(), protocol, ip, account, directionName, reason, result.bytes}
	if len(m.continuity.Recent) == 32 { copy(m.continuity.Recent, m.continuity.Recent[1:]); m.continuity.Recent[31] = event } else { m.continuity.Recent = append(m.continuity.Recent, event) }
}

func (m *TrafficManager) continuitySnapshotLocked() flowContinuitySnapshot {
	snapshot := m.continuity
	snapshot.Recent = append([]flowEndEvent(nil), m.continuity.Recent...)
	return snapshot
}
