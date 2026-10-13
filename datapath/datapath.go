// Package datapath terminates tethered client traffic in userspace.
//
// The tethering stack routes hotspot clients into a TUN owned by the shell
// process. Nothing in the kernel forwards those packets onward, so this package
// reads raw IP frames off the TUN fd, terminates TCP and UDP in a gVisor
// netstack instance, and proxies each flow onto an ordinary socket.
package datapath

import (
	"fmt"
	"net"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/icmp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

// nicID identifies the single TUN-backed interface in the stack.
const nicID tcpip.NICID = 1

// Session owns a running netstack bound to one TUN file descriptor. Exposes no
// gVisor types: gomobile carries only a narrow set across JNI, so the Kotlin
// side sees a handle it can start and stop.
type Session struct {
	stack   *stack.Stack
	binding *networkBinding
	traffic *TrafficManager
	v2Bridge *v2AccountBridge
	v2Physical *v2PhysicalPortal
	stopPresence chan struct{}
}

// Start builds a netstack over tunFD, already open, and attaches it to the TUN.
// Ownership does not transfer -- SessionResources holds the TUN, the framework
// interface, and the network handle as one atomic group. mtu is the link MTU.
func Start(tunFD int, mtu int) (*Session, error) {
	// ICMP is registered for both families so the stack can answer and relay
	// errors. IPv6 in particular cannot fragment in flight, so a client only
	// learns a path MTU from the ICMPv6 Packet Too Big it gets back.
	netStack := stack.New(stack.Options{
		NetworkProtocols: []stack.NetworkProtocolFactory{
			ipv4.NewProtocol,
			ipv6.NewProtocol,
		},
		TransportProtocols: []stack.TransportProtocolFactory{
			tcp.NewProtocol,
			udp.NewProtocol,
			icmp.NewProtocol4,
			icmp.NewProtocol6,
		},
	})

	endpoint, err := fdbased.New(&fdbased.Options{
		FDs: []int{tunFD},
		MTU: uint32(mtu),
	})
	if err != nil {
		netStack.Close()
		return nil, fmt.Errorf("datapath.Start: link endpoint over fd %d: %w", tunFD, err)
	}

	if tcpipErr := netStack.CreateNIC(nicID, endpoint); tcpipErr != nil {
		netStack.Close()
		return nil, fmt.Errorf("datapath.Start: create NIC on fd %d: %v", tunFD, tcpipErr)
	}

	// Accept traffic for every destination: these are forwarded flows, not
	// packets addressed to this host, so the stack must not filter by address.
	netStack.SetPromiscuousMode(nicID, true)
	netStack.SetSpoofing(nicID, true)

	netStack.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: nicID},
		{Destination: header.IPv6EmptySubnet, NIC: nicID},
	})

	binding := &networkBinding{}
	traffic := newTrafficManager()
	v2Physical := &v2PhysicalPortal{}
	traffic.v2Physical = v2Physical
	v2Bridge := newV2AccountBridge(traffic)
	traffic.v2Bridge = v2Bridge
	installForwarders(netStack, &net.Dialer{
		Timeout: dialTimeout,
		Control: binding.control,
	}, traffic)

	session := &Session{
		stack: netStack,
		binding: binding,
		traffic: traffic,
		v2Bridge: v2Bridge,
		v2Physical: v2Physical,
		stopPresence: make(chan struct{}),
	}
	go session.maintainClientPresence(session.stopPresence)
	go session.refreshV2NAT(session.stopPresence)
	return session, nil
}

// Keep account/device presence in sync without blocking traffic statistics,
// authentication, NAT attribution or the local captive portal.
func (s *Session) maintainClientPresence(stop <-chan struct{}) {
	ticker := time.NewTicker(clientPresenceRefresh)
	defer ticker.Stop()
	for {
		select {
		case <-stop:
			return
		case <-ticker.C:
			select {
			case <-stop:
				return
			default:
				s.traffic.refreshClientPresence()
			}
		}
	}
}

// refreshV2NAT is deliberately off the hot path. A missing or malformed
// Android NAT snapshot invalidates the entire temporary flow index; it never
// grants access based on an old rule, sole-client guess, or a MAC address.
func (s *Session) refreshV2NAT(stop <-chan struct{}) {
    ticker := time.NewTicker(1200 * time.Millisecond)
    defer ticker.Stop()
    if s.v2Bridge == nil { return }
    s.v2Bridge.freshNATSnapshot()
    for {
        select {
        case <-stop:
            return
        case <-ticker.C:
            s.v2Bridge.freshNATSnapshot()
        }
    }
}

// SetNetwork pins every subsequent dial to a handle from
// Network.getNetworkHandle; 0 unbinds, which is how a session with no VPN runs.
//
// Binding is what makes VPN loss fail rather than fall back: an unbound socket
// follows the default network, so a dropped VPN leaves over the physical one
// and tethered clients keep browsing untunneled with nothing noticing.
//
// int64 because gomobile has no unsigned type. A handle packs a netid above a
// magic word, so it is routinely negative signed; the conversion reinterprets
// the bits without changing them.
func (s *Session) SetNetwork(handle int64) {
	if s.binding == nil {
		return
	}
	s.binding.set(uint64(handle))
}


// SetRequireClientAttribution enables fail-closed client identification.
// Once enabled, a shared 192.0.2.2/2001:db8::2 flow is not forwarded until
// Android's tethering NAT state resolves it to one physical hotspot client.
func (s *Session) SetRequireClientAttribution(required bool) {
	if s.traffic == nil {
		return
	}
	s.traffic.setRequireClientAttribution(required)
}

// SetGlobalPolicy configures an optional aggregate speed/quota ceiling.
// Values <= 0 mean unlimited.
func (s *Session) SetGlobalPolicy(downloadBps, uploadBps, quotaBytes int64) {
	if s.traffic == nil {
		return
	}
	s.traffic.setGlobalPolicy(downloadBps, uploadBps, quotaBytes)
}

// SetDefaultClientPolicy configures newly discovered physical clients.
func (s *Session) SetDefaultClientPolicy(
	downloadBps, uploadBps, quotaBytes int64,
	blocked bool,
) {
	if s.traffic == nil {
		return
	}
	s.traffic.setDefaultClientPolicy(downloadBps, uploadBps, quotaBytes, blocked)
}

// SetClientPolicy configures one resolved physical client IP.
func (s *Session) SetClientPolicy(
	ip string,
	downloadBps, uploadBps, quotaBytes int64,
	blocked bool,
) {
	if s.traffic == nil {
		return
	}
	s.traffic.setClientPolicy(ip, ClientPolicy{
		DownloadBitsPerSecond: downloadBps,
		UploadBitsPerSecond:   uploadBps,
		QuotaBytes:            quotaBytes,
		Blocked:               blocked,
	})
}


// SetPortalConfig enables/disables account-gated captive access and supplies
// the current account snapshot from Android. The Android store remains the
// durable source of truth.
func (s *Session) SetPortalConfig(required bool, configJSON string) {
	if s.traffic == nil {
		return
	}
	s.traffic.setPortalConfig(required, configJSON)
	// Import durable Data, Unlimited and Media entitlements into the separate
	// V2 policy engine; this does NOT re-enable the legacy Internet forwarder.
	if s.v2Bridge != nil { s.v2Bridge.Sync() }
}

// ClearPortalClaims acknowledges recharge requests already handled by Android.
func (s *Session) ClearPortalClaims() {
	if s.traffic == nil {
		return
	}
	s.traffic.clearPortalClaims()
}

func (s *Session) RevokePortalClient(ip string) {
	if s.traffic == nil {
		return
	}
	s.traffic.revokePortalClient(ip)
}

// TrafficStatsJSON exposes per-client counters and attribution diagnostics.
func (s *Session) TrafficStatsJSON() string {
	if s.traffic == nil {
		return "{}"
	}
	return s.traffic.statsJSON()
}

// ResetTrafficStats clears byte counters without changing policies.
func (s *Session) ResetTrafficStats() {
	if s.traffic == nil {
		return
	}
	s.traffic.resetStats()
}

// StartV2DirectPortal binds the PIN endpoint ONLY to the private IPv4
// assigned to the actual hotspot interface by Android. This is not the
// legacy translated TUN listener; the physical TCP peer remains visible.
// A distinct failure message means the client can still read the homepage.
func (s *Session) StartV2DirectPortal(gatewayIP string) string {
    if s == nil || s.stack == nil || s.v2Physical == nil {
        return "ERROR: V2 TUN session is not active"
    }
    action, err := s.v2Physical.start(gatewayIP, s.v2Bridge)
    if err != nil { return "ERROR: " + err.Error() }
    return action
}

// AssociateV2WifiStation must only be fed evidence verified by two Android
// sources: current SoftAP association and tethering lease (MAC + IP).
func (s *Session) AssociateV2WifiStation(mac, physicalIP string) string {
    if s == nil || s.v2Bridge == nil { return "ERROR: V2 bridge unavailable" }
    if err := s.v2Bridge.Associated(mac, physicalIP); err != nil {
        return "ERROR: " + err.Error()
    }
    return "OK"
}

// DepartV2WifiStation is for an AUTHORITY-confirmed SoftAP disassociation.
// Never call this on silence, an idle timer, or a dumpsys timeout.
func (s *Session) DepartV2WifiStation(mac string) string {
    if s == nil || s.v2Bridge == nil { return "ERROR: V2 bridge unavailable" }
    if err := s.v2Bridge.Departed(mac); err != nil { return "ERROR: " + err.Error() }
    return "OK"
}

// Stop tears the netstack down. It does not close the TUN fd.
func (s *Session) Stop() {
	if s.stack == nil {
		return
	}
	if s.v2Physical != nil { s.v2Physical.stop() }
	if s.stopPresence != nil {
		close(s.stopPresence)
		s.stopPresence = nil
	}
	s.stack.Close()
	s.stack = nil
}
