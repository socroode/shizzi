package datapath

import (
	"sync"
	"time"
)

// A one-way live video still owns an active UDP flow. Only inactivity in
// both directions expires it; an idle uplink cannot close a busy downlink.
type udpFlowActivity struct {
	mu       sync.Mutex
	lastSeen time.Time
	timeout  time.Duration
}

func newUDPFlowActivity(timeout time.Duration) *udpFlowActivity {
	return &udpFlowActivity{lastSeen: time.Now(), timeout: timeout}
}

func (a *udpFlowActivity) touch() {
	a.mu.Lock()
	a.lastSeen = time.Now()
	a.mu.Unlock()
}

func (a *udpFlowActivity) deadline() time.Time {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.lastSeen.Add(a.timeout)
}

func (a *udpFlowActivity) idle() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return time.Since(a.lastSeen) >= a.timeout
}
