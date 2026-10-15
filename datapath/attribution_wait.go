package datapath

import "time"

// Share the refresh with every waiting flow. Its bounded OS query may finish
// later, but a proxy never waits beyond its own admission budget or queues a
// separate goroutine for each packet behind the dumpsys mutex.
func (r *flowAttributionResolver) refreshBefore(maxAge time.Duration, deadline time.Time) {
	r.mu.Lock()
	if time.Since(r.lastRefresh) < maxAge {
		r.mu.Unlock()
		return
	}
	if r.refreshDone == nil {
		done := make(chan struct{})
		r.refreshDone = done
		go func() {
			r.refreshIfOlderThan(maxAge)
			r.mu.Lock()
			r.refreshDone = nil
			close(done)
			r.mu.Unlock()
		}()
	}
	done := r.refreshDone
	r.mu.Unlock()
	remaining := time.Until(deadline)
	if remaining <= 0 {
		return
	}
	timer := time.NewTimer(remaining)
	defer timer.Stop()
	select {
	case <-done:
	case <-timer.C:
	}
}
