package datapath

import (
	"context"
	"encoding/json"
	"net"
	"strings"
	"time"
)

const adminIdleTimeout = 30 * time.Minute
const adminAbsoluteTimeout = 12 * time.Hour

type loginThrottle struct {
	Failures int
	Until    time.Time
	Last     time.Time
}

func (m *TrafficManager) revokeAuthorizationLocked(ip string) {
	if a := m.portalAuthorized[ip]; a != nil && a.done != nil {
		close(a.done)
	}
	delete(m.portalAuthorized, ip)
	delete(m.portalClaimResults, ip)
	m.removePendingClaimsForIPLocked(ip)
}

func (m *TrafficManager) revokeAdminLocked(token string) {
	if a := m.adminSessions[token]; a != nil && a.done != nil {
		close(a.done)
	}
	delete(m.adminSessions, token)
}

func (m *TrafficManager) checkIdentityLocked(ip string) {
	presence := m.flowAttribution.presence()
	mac := presence.macs[ip]
	if mac == "" {
		return
	}
	if a := m.portalAuthorized[ip]; a != nil && a.MAC != "" && a.MAC != mac {
		m.revokeAuthorizationLocked(ip)
	}
	for token, a := range m.adminSessions {
		if a.IP == ip && a.MAC != "" && a.MAC != mac {
			m.revokeAdminLocked(token)
		}
	}
}

// Capture the admitting session before dialing. A later login on the same IP
// cannot adopt a connection already opened by the previous account.
type flowScope struct {
	authorization *PortalAuthorization
	admin         *adminSession
	open          bool
}

func (m *TrafficManager) captureFlowScope(ip string, media bool, account string) (*flowScope, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.captureFlowScopeLocked(ip, media, account)
}
func (m *TrafficManager) captureFlowScopeLocked(ip string, media bool, account string) (*flowScope, bool) {
	if m.closed || ip == "" {
		return nil, false
	}
	m.checkIdentityLocked(ip)
	now := time.Now().UnixMilli()
	auth := m.portalAuthorized[ip]
	if media {
		if auth == nil || auth.AccountNumber != account || !m.portalAccounts[account].hasMedia(now) {
			return nil, false
		}
		return &flowScope{authorization: auth}, true
	}
	if !m.allowedLocked(ip, now) {
		return nil, false
	}
	if !m.portalRequired {
		return &flowScope{open: true}, true
	}
	if auth != nil && m.portalAuthorizedLocked(ip, now) {
		return &flowScope{authorization: auth}, true
	}
	for token, session := range m.adminSessions {
		if session.IP == ip && m.adminSessionValidLocked(token, time.Now()) {
			return &flowScope{admin: session}, true
		}
	}
	return nil, false
}
func (scope *flowScope) revoked() <-chan struct{} {
	if scope.authorization != nil {
		return scope.authorization.done
	}
	if scope.admin != nil {
		return scope.admin.done
	}
	return nil
}
func (scope *flowScope) validLocked(m *TrafficManager, ip string, media bool) bool {
	if scope == nil || m.closed {
		return false
	}
	m.checkIdentityLocked(ip)
	if scope.open {
		return !m.portalRequired && m.allowedLocked(ip, time.Now().UnixMilli())
	}
	if auth := scope.authorization; auth != nil {
		if m.portalAuthorized[ip] != auth {
			return false
		}
		if media {
			return m.portalAccounts[auth.AccountNumber].hasMedia(time.Now().UnixMilli())
		}
		return m.portalAuthorizedLocked(ip, time.Now().UnixMilli()) && m.allowedLocked(ip, time.Now().UnixMilli())
	}
	if session := scope.admin; session != nil {
		return m.adminConfig.Enabled && m.adminSessions[session.Token] == session && session.IP == ip &&
			m.adminSessionValidLocked(session.Token, time.Now()) && m.allowedLocked(ip, time.Now().UnixMilli())
	}
	return false
}

func (m *TrafficManager) dialFlow(dialer *net.Dialer, scope *flowScope, protocol, target string) (net.Conn, error) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	complete := make(chan struct{})
	defer close(complete)
	go func() {
		select {
		case <-scope.revoked():
			cancel()
		case <-m.stopData:
			cancel()
		case <-complete:
		}
	}()
	return dialer.DialContext(ctx, protocol, target)
}

// Revocation closes sleeping sockets too; the admission identity is immutable.
func (m *TrafficManager) trackFlow(ip string, client, upstream net.Conn, media bool) (func(), bool) {
	account := ""
	if media {
		m.mu.Lock()
		if auth := m.portalAuthorized[ip]; auth != nil {
			account = auth.AccountNumber
		}
		m.mu.Unlock()
	}
	scope, ok := m.captureFlowScope(ip, media, account)
	if !ok {
		return func() {}, false
	}
	return m.trackScopedFlow(ip, scope, client, upstream, media)
}
func (m *TrafficManager) trackScopedFlow(ip string, scope *flowScope, client, upstream net.Conn, media bool) (func(), bool) {
	m.mu.Lock()
	allowed := scope.validLocked(m, ip, media)
	if allowed {
		m.flowWG.Add(1)
	}
	m.mu.Unlock()
	if !allowed {
		return func() {}, false
	}
	done := make(chan struct{})
	go func() {
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		closeConnections := func() { client.Close(); upstream.Close() }
		for {
			select {
			case <-done:
				return
			case <-scope.revoked():
				closeConnections()
				return
			case <-m.stopData:
				closeConnections()
				return
			case <-ticker.C:
				m.mu.Lock()
				active := scope.validLocked(m, ip, media)
				m.mu.Unlock()
				if !active {
					closeConnections()
					return
				}
			}
		}
	}()
	return func() { close(done); m.flowWG.Done() }, true
}

func (m *TrafficManager) loginBlockedLocked(key string, now time.Time) bool {
	if m.loginAttempts == nil {
		m.loginAttempts = make(map[string]*loginThrottle)
	}
	for k, entry := range m.loginAttempts {
		if now.Sub(entry.Last) > time.Hour {
			delete(m.loginAttempts, k)
		}
	}
	entry := m.loginAttempts[key]
	return entry != nil && now.Before(entry.Until)
}

func (m *TrafficManager) failedLoginLocked(key string, now time.Time) {
	if m.loginAttempts == nil {
		m.loginAttempts = make(map[string]*loginThrottle)
	}
	if len(m.loginAttempts) >= 1024 && m.loginAttempts[key] == nil {
		return
	}
	entry := m.loginAttempts[key]
	if entry == nil {
		entry = &loginThrottle{}
		m.loginAttempts[key] = entry
	}
	entry.Failures++
	entry.Last = now
	if entry.Failures >= 5 {
		seconds := 1 << min(entry.Failures-5, 6)
		entry.Until = now.Add(time.Duration(seconds) * time.Second)
	}
}

func (m *TrafficManager) adminSessionValidLocked(token string, now time.Time) bool {
	s := m.adminSessions[token]
	if s == nil {
		return false
	}
	if now.UnixMilli()-s.LastSeenMillis > adminIdleTimeout.Milliseconds() ||
		(s.CreatedAtMillis != 0 && now.UnixMilli()-s.CreatedAtMillis > adminAbsoluteTimeout.Milliseconds()) {
		m.revokeAdminLocked(token)
		return false
	}
	return true
}

func samePortalOrigin(host string) bool {
	host = strings.ToLower(strings.TrimSpace(host))
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}
	return host == portalIP || host == "shizzi.local" || host == "localhost"
}

func (m *TrafficManager) ackPortalClaims(idsJSON string) {
	var ids []string
	if json.Unmarshal([]byte(idsJSON), &ids) != nil || len(ids) > 128 {
		return
	}
	matched := make(map[string]bool)
	for _, id := range ids {
		matched[id] = true
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	remaining := m.portalRechargeClaims[:0]
	for _, claim := range m.portalRechargeClaims {
		if !matched[claim.ID] {
			remaining = append(remaining, claim)
		}
	}
	m.portalRechargeClaims = remaining
}
