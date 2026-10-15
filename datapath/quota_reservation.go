package datapath

import "time"

type transferReservation struct {
	manager       *TrafficManager
	scope         *flowScope
	ip            string
	direction     direction
	count         int
	accountNumber string
	data          bool
	authorization *PortalAuthorization
	clientLimiter *bandwidthLimiter
	globalLimiter *bandwidthLimiter
}

// A reservation includes all concurrent flows. It is settled only for bytes
// actually written, and belongs to the account that admitted the transfer.
func (m *TrafficManager) reserveTransfer(ip string, dir direction, requested int, datagram bool) *transferReservation {
	return m.reserveTransferScoped(ip, dir, requested, datagram, nil)
}
func (m *TrafficManager) reserveTransferScoped(ip string, dir direction, requested int, datagram bool, scope *flowScope) *transferReservation {
	reservation, _ := m.tryReserveTransferScoped(ip, dir, requested, datagram, scope, false)
	return reservation
}

// Backpressure while another socket holds the available balance. Pending
// reservations are not consumed quota, and must not terminate a valid stream.
func (m *TrafficManager) reserveTransferForFlow(ip string, dir direction, requested int, datagram bool, scope *flowScope) *transferReservation {
	if scope == nil {
		var ok bool
		scope, ok = m.captureFlowScope(ip, false, "")
		if !ok {
			return nil
		}
	}
	for {
		reservation, changed := m.tryReserveTransferScoped(ip, dir, requested, datagram, scope, true)
		if reservation != nil || changed == nil {
			return reservation
		}
		select {
		case <-changed:
		case <-scope.revoked():
			return nil
		case <-scope.transferDone():
			return nil
		case <-m.stopData:
			return nil
		case <-time.After(time.Second):
			// Recheck plan expiry even when no reservation settles.
		}
	}
}

func (m *TrafficManager) tryReserveTransferScoped(ip string, dir direction, requested int, datagram bool, scope *flowScope, waitPending bool) (*transferReservation, <-chan struct{}) {
	if requested <= 0 || ip == "" {
		return nil, nil
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	now := time.Now().UnixMilli()
	if scope == nil {
		var ok bool
		scope, ok = m.captureFlowScopeLocked(ip, false, "")
		if !ok {
			return nil, nil
		}
	}
	if !scope.validLocked(m, ip, false) {
		return nil, nil
	}
	n := int64(requested)
	available := n
	if m.globalQuotaBytes > 0 {
		available = min(available, m.globalQuotaBytes-m.totalUpBytes-m.totalDownBytes)
		n = min(n, m.globalQuotaBytes-m.totalUpBytes-m.totalDownBytes-m.globalPending)
	}
	auth := scope.authorization
	number := ""
	data := false
	client := m.clientLocked(ip)
	if !m.portalRequired && client.Policy.QuotaBytes > 0 {
		available = min(available, client.Policy.QuotaBytes-client.UpBytes-client.DownBytes)
		n = min(n, client.Policy.QuotaBytes-client.UpBytes-client.DownBytes-m.clientPending[ip])
	}
	if m.portalRequired && auth != nil {
		account := m.portalAccounts[auth.AccountNumber]
		number = auth.AccountNumber
		data = !account.hasUnlimited(now)
		if data {
			available = min(available, m.remainingDataLocked(account))
			n = min(n, m.remainingDataLocked(account)-m.accountPending[number])
		}
		client.setRates(account.downloadBps(now), account.uploadBps(now))
	} else if m.portalRequired && m.adminAuthorizedLocked(ip) {
		client.setRates(m.adminConfig.DownloadBps, m.adminConfig.UploadBps)
	}
	if available <= 0 || (datagram && available < int64(requested)) {
		return nil, nil
	}
	if n <= 0 || (datagram && n < int64(requested)) || (waitPending && n < available) {
		if m.quotaChanged == nil {
			m.quotaChanged = make(chan struct{})
		}
		return nil, m.quotaChanged
	}
	if m.accountPending == nil {
		m.accountPending = make(map[string]int64)
	}
	if m.clientPending == nil {
		m.clientPending = make(map[string]int64)
	}
	m.clientPending[ip] += n
	m.globalPending += n
	if data {
		m.accountPending[number] += n
	}
	clientLimiter, globalLimiter := client.uploadLimiter, m.globalUploadLimiter
	if dir == directionDownload {
		clientLimiter, globalLimiter = client.downloadLimiter, m.globalDownloadLimiter
	}
	return &transferReservation{manager: m, scope: scope, ip: ip, direction: dir, count: int(n), accountNumber: number, data: data, authorization: auth, clientLimiter: clientLimiter, globalLimiter: globalLimiter}, nil
}

func (r *transferReservation) pace() bool {
	revoked := r.scope.revoked()
	if !r.globalLimiter.waitCancelledUntil(r.count, revoked, r.manager.stopData, r.scope.transferDone()) ||
		!r.clientLimiter.waitCancelledUntil(r.count, revoked, r.manager.stopData, r.scope.transferDone()) {
		return false
	}
	m := r.manager
	m.mu.Lock()
	defer m.mu.Unlock()
	return r.scope.validLocked(m, r.ip, false)
}

func (r *transferReservation) settle(written int) {
	m := r.manager
	m.mu.Lock()
	defer m.mu.Unlock()
	defer m.notifyQuotaLocked()
	m.globalPending -= int64(r.count)
	m.clientPending[r.ip] -= int64(r.count)
	if r.data {
		m.accountPending[r.accountNumber] -= int64(r.count)
	}
	bytes := int64(max(0, min(written, r.count)))
	if bytes == 0 {
		return
	}
	client := m.clientLocked(r.ip)
	client.LastSeen = time.Now()
	if r.direction == directionDownload {
		m.totalDownBytes += bytes
		client.DownBytes += bytes
	} else {
		m.totalUpBytes += bytes
		client.UpBytes += bytes
	}
	auth := r.authorization
	if auth == nil {
		return
	}
	usage := m.usageLocked(r.accountNumber)
	if r.direction == directionDownload {
		auth.DownBytes += bytes
		usage.DownBytes += bytes
	} else {
		auth.UpBytes += bytes
		usage.UpBytes += bytes
	}
	if r.data {
		usage.DataBytes += bytes
	}
}

func (m *TrafficManager) notifyQuotaLocked() {
	if m.quotaChanged != nil {
		close(m.quotaChanged)
	}
	m.quotaChanged = nil
}

func (l *bandwidthLimiter) waitCancelled(byteCount int, revoked, stopped <-chan struct{}) bool {
	return l.waitCancelledUntil(byteCount, revoked, stopped, nil)
}

func (l *bandwidthLimiter) waitCancelledUntil(byteCount int, revoked, stopped, flowDone <-chan struct{}) bool {
	if l == nil || byteCount <= 0 {
		return true
	}
	l.mu.Lock()
	if l.bytesPerSec <= 0 {
		l.mu.Unlock()
		return true
	}
	now := time.Now()
	floor := now.Add(-l.burstDuration)
	if l.next.IsZero() || l.next.Before(floor) {
		l.next = floor
	}
	l.next = l.next.Add(time.Duration(float64(byteCount) / l.bytesPerSec * float64(time.Second)))
	delay := time.Until(l.next)
	l.mu.Unlock()
	if delay <= 0 {
		return true
	}
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-timer.C:
		return true
	case <-revoked:
		return false
	case <-stopped:
		return false
	case <-flowDone:
		return false
	}
}

func (m *TrafficManager) freeze() {
	m.mu.Lock()
	if !m.closed {
		m.closed = true
		close(m.stopData)
		for ip := range m.portalAuthorized {
			m.revokeAuthorizationWithReasonLocked(ip, "service-stopped")
		}
		for token := range m.adminSessions {
			m.revokeAdminLocked(token)
		}
	}
	m.mu.Unlock()
}
