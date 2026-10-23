package datapath

import "time"

// Tickets own places in queues, not future bandwidth. Waiting cancellations
// therefore leave no virtual-time debt in either limiter.
type pacingTicket struct{ marker byte }

func (l *bandwidthLimiter) notifyPacingLocked() {
	if l.changed != nil {
		close(l.changed)
	}
	l.changed = make(chan struct{})
}

func (l *bandwidthLimiter) enqueuePacingLocked(ticket *pacingTicket) {
	l.waiters = append(l.waiters, ticket)
	l.notifyPacingLocked()
}

func (l *bandwidthLimiter) removePacingLocked(ticket *pacingTicket) {
	for i, item := range l.waiters {
		if item == ticket {
			copy(l.waiters[i:], l.waiters[i+1:])
			l.waiters[len(l.waiters)-1] = nil
			l.waiters = l.waiters[:len(l.waiters)-1]
			l.notifyPacingLocked()
			return
		}
	}
}

// next records admitted transfers only. A datagram larger than the configured
// burst can use one packet's worth of credit, so it always fits in the bucket.
func (l *bandwidthLimiter) pacingTargetLocked(now time.Time, byteCount int) time.Time {
	if l.bytesPerSec <= 0 {
		return time.Time{}
	}
	transfer := time.Duration(float64(byteCount) / l.bytesPerSec * float64(time.Second))
	floor := now.Add(-max(l.burstDuration, transfer))
	base := l.next
	if base.IsZero() || base.Before(floor) {
		base = floor
	}
	return base.Add(transfer)
}

func pacingStopped(channels ...<-chan struct{}) bool {
	for _, ch := range channels {
		select {
		case <-ch:
			return true
		default:
		}
	}
	return false
}

// Acquire client and router bandwidth together. A client joins the router FIFO
// only when its own head packet is ready. Clocks are charged only at admission.
// Locks always follow router -> client; no TrafficManager lock is held here.
func waitBandwidthUntil(byteCount int, global, client *bandwidthLimiter, revoked, stopped, flowDone <-chan struct{}) bool {
	if pacingStopped(revoked, stopped, flowDone) {
		return false
	}
	if byteCount <= 0 {
		return true
	}
	if client == nil {
		client, global = global, nil
	}
	if client == nil {
		return true
	}
	if global == client {
		global = nil
	}
	ticket := &pacingTicket{}
	client.mu.Lock()
	client.enqueuePacingLocked(ticket)
	client.mu.Unlock()
	joinedGlobal := false
	lock := func() {
		if global != nil {
			global.mu.Lock()
		}
		client.mu.Lock()
	}
	unlock := func() {
		client.mu.Unlock()
		if global != nil {
			global.mu.Unlock()
		}
	}
	defer func() {
		lock()
		client.removePacingLocked(ticket)
		if joinedGlobal {
			global.removePacingLocked(ticket)
		}
		unlock()
	}()
	for {
		lock()
		if pacingStopped(revoked, stopped, flowDone) {
			unlock()
			return false
		}
		now := time.Now()
		delay := time.Duration(0)
		ready := client.waiters[0] == ticket
		clientTarget := time.Time{}
		if ready {
			clientTarget = client.pacingTargetLocked(now, byteCount)
			delay = max(0, clientTarget.Sub(now))
			ready = delay == 0
		}
		if !ready && joinedGlobal {
			global.removePacingLocked(ticket)
			joinedGlobal = false
		}
		globalTarget := time.Time{}
		if ready && global != nil {
			if !joinedGlobal {
				global.enqueuePacingLocked(ticket)
				joinedGlobal = true
			}
			ready = global.waiters[0] == ticket
			if ready {
				globalTarget = global.pacingTargetLocked(now, byteCount)
				delay = max(0, globalTarget.Sub(now))
				ready = delay == 0
			}
		}
		if ready {
			client.next = clientTarget
			if global != nil {
				global.next = globalTarget
			}
			unlock()
			return true
		}
		clientChanged := client.changed
		var globalChanged <-chan struct{}
		if global != nil {
			globalChanged = global.changed
		}
		unlock()
		var timer *time.Timer
		var wake <-chan time.Time
		if delay > 0 {
			timer = time.NewTimer(delay)
			wake = timer.C
		}
		select {
		case <-wake:
		case <-clientChanged:
		case <-globalChanged:
		case <-revoked:
		case <-stopped:
		case <-flowDone:
		}
		if timer != nil {
			timer.Stop()
		}
	}
}

func (l *bandwidthLimiter) waitCancelled(byteCount int, revoked, stopped <-chan struct{}) bool {
	return l.waitCancelledUntil(byteCount, revoked, stopped, nil)
}
func (l *bandwidthLimiter) waitCancelledUntil(byteCount int, revoked, stopped, flowDone <-chan struct{}) bool {
	return waitBandwidthUntil(byteCount, nil, l, revoked, stopped, flowDone)
}
