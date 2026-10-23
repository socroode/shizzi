package datapath

import (
	"net"
	"time"
)

func validClientMAC(raw string) bool {
	mac, err := net.ParseMAC(raw)
	return err == nil && len(mac) == 6 && mac[0]&1 == 0 && raw != "00:00:00:00:00:00"
}

type CameraProfile struct {
	AccountNumber string `json:"accountNumber"`
	MAC           string `json:"mac"`
	EnrollmentID  string `json:"enrollmentId"`
}

type AssociatedClient struct {
	IP  string `json:"ip"`
	MAC string `json:"mac"`
}

// A camera uses a provisioned account, its current association and DHCP lease.
// Its IP is never a permanent exception to the portal. Manual account takeover
// wins, and revoking an automatic session suppresses it until re-enrollment.
func (m *TrafficManager) reconcileCamerasLocked(presence clientPresence) {
	for ip, auth := range m.portalAuthorized {
		if auth.Camera {
			profile, exists := m.cameraProfiles[auth.AccountNumber]
			if !exists || profile.MAC != auth.MAC {
				m.revokeAuthorizationLocked(ip)
			}
		}
	}
	if !presence.authoritative || !presence.eventBased {
		return
	}
	for number, profile := range m.cameraProfiles {
		account, exists := m.portalAccounts[number]
		if !exists || !account.Enabled || m.cameraSuppressed[number] {
			continue
		}
		active := false
		for _, auth := range m.portalAuthorized {
			if auth.AccountNumber == number {
				active = true
				break
			}
		}
		if active {
			continue
		}
		target := ""
		for ip, mac := range presence.macs {
			if mac == profile.MAC {
				if target != "" {
					target = ""
					break
				} // ambiguous lease: fail closed
				target = ip
			}
		}
		if target == "" || m.portalAuthorized[target] != nil {
			continue
		}
		m.portalAuthorized[target] = &PortalAuthorization{AccountNumber: number, StartedAtMillis: time.Now().UnixMilli(),
			MAC: profile.MAC, ID: randomHex(16), done: make(chan struct{}), Camera: true}
	}
}

func (m *TrafficManager) suppressCameraLocked(ip string) {
	if auth := m.portalAuthorized[ip]; auth != nil && m.cameraProfiles[auth.AccountNumber].MAC != "" {
		if m.cameraSuppressed == nil {
			m.cameraSuppressed = make(map[string]bool)
		}
		m.cameraSuppressed[auth.AccountNumber] = true
	}
}
