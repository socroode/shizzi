package datapath

import "maps"

type presenceState uint8

const (
	presenceUnknown presenceState = iota
	presencePresent
	presenceAbsent
	presenceReplaced
)

// A missing lease is not a missing Wi-Fi station. Only current, confirmed
// mappings may replace a session identity; association alone can keep it alive.
func (p clientPresence) confirmedMAC(ip string) string {
	if _, present := p.clients[ip]; !present {
		return ""
	}
	if p.eventBased {
		if !p.wifiCountKnown || !p.leasesKnown {
			return ""
		}
	} else if !p.authoritative {
		return ""
	}
	if !p.eventBased && p.currentMACs != nil {
		return p.currentMACs[ip]
	}
	return p.macs[ip]
}

func (p clientPresence) identity(ip, sessionMAC string) (presenceState, string) {
	if mac := p.confirmedMAC(ip); mac != "" {
		if sessionMAC != "" && mac != sessionMAC {
			return presenceReplaced, mac
		}
		return presencePresent, mac
	}
	if p.eventBased && p.wifiCountKnown {
		if sessionMAC != "" {
			if _, attached := p.associatedMACs[sessionMAC]; attached {
				// A complete new lease mapping can confirm that this address
				// moved; incomplete leases cannot invalidate the association.
				if p.leaseMappingComplete {
					if _, present := p.clients[ip]; !present {
						return presenceAbsent, ""
					}
				}
				return presencePresent, sessionMAC
			}
			return presenceAbsent, ""
		}
		if !p.leaseMappingComplete {
			return presenceUnknown, ""
		}
	}
	if !p.authoritative {
		return presenceUnknown, ""
	}
	if _, present := p.clients[ip]; present {
		return presencePresent, ""
	}
	return presenceAbsent, ""
}

// Unlike a DHCP dump or station count, complete privileged callbacks establish
// the current physical IP owners. Never discard conflicting logs from counts.
func (p clientPresence) flowOwnerAbsent(ip string) bool {
	if ip == "" || !p.eventBased || !p.wifiCountKnown || !p.leaseMappingComplete {
		return false
	}
	_, present := p.clients[ip]
	return !present
}

func (r *flowAttributionResolver) ownerAbsentLocked(ip string) bool {
	return r.eventPresence != nil && r.eventPresence.flowOwnerAbsent(ip)
}

func samePresence(a, b clientPresence) bool {
	return a.eventBased == b.eventBased && a.authoritative == b.authoritative &&
		a.wifiCount == b.wifiCount && a.wifiCountKnown == b.wifiCountKnown &&
		a.leasesKnown == b.leasesKnown && a.leaseMappingComplete == b.leaseMappingComplete &&
		maps.Equal(a.clients, b.clients) && maps.Equal(a.macs, b.macs) && maps.Equal(a.associatedMACs, b.associatedMACs)
}

func (r *flowAttributionResolver) unresolvedReasonLocked(key flowAttributionKey) string {
	if r.lastError != "" {
		return "android-state-unavailable"
	}
	if client := r.flows[key]; client != "" {
		if r.ownerAbsentLocked(client) {
			return "departed-flow-owner"
		}
		if owner, exists := r.bpfFailedFlows[key]; exists && owner != client && !r.ownerAbsentLocked(owner) {
			return "nat-log-conflict"
		}
	}
	if owner, exists := r.bpfFailedFlows[key]; exists {
		if owner == "" {
			return "ambiguous-log-owner"
		}
		if r.ownerAbsentLocked(owner) {
			return "departed-flow-owner"
		}
	}
	return "nat-rule-missing"
}
