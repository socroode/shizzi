package datapath

import (
	"net"
	"strconv"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const (
	maxInFlightTCP    = 512
	defaultRcvWnd     = 0
	dialTimeout       = 10 * time.Second
	udpFlowTimeout    = 60 * time.Second
	tcpCopyBufferSize = 32 * 1024
)

func installForwarders(netStack *stack.Stack, dialer *net.Dialer, traffic *TrafficManager) {
	tcpForwarder := tcp.NewForwarder(
		netStack,
		defaultRcvWnd,
		maxInFlightTCP,
		func(request *tcp.ForwarderRequest) {
			forwardTCP(request, dialer, traffic)
		},
	)
	netStack.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpForwarder.HandlePacket)

	udpForwarder := udp.NewForwarder(
		netStack,
		func(request *udp.ForwarderRequest) bool {
			return forwardUDP(request, dialer, traffic)
		},
	)
	netStack.SetTransportProtocolHandler(udp.ProtocolNumber, udpForwarder.HandlePacket)
}

func isDNSPort(port uint16) bool { return port == 53 }

// forwardTCP accepts the client's handshake first and only then identifies the
// client. Android publishes the tethering NAT rule for a TCP flow once the
// connection is tracked as established, which cannot happen while the SYN is
// held waiting for that same rule. Identification, the portal decision and
// the account check all happen before any upstream dial.
func forwardTCP(request *tcp.ForwarderRequest, dialer *net.Dialer, traffic *TrafficManager) {
	id := request.ID()
	if traffic != nil && traffic.refuseClientIPv6(sourceOf(id)) {
		// RST at once so the client falls back to IPv4 immediately.
		request.Complete(true)
		return
	}

	var queue waiter.Queue
	endpoint, tcpipErr := request.CreateEndpoint(&queue)
	if tcpipErr != nil {
		request.Complete(true)
		return
	}
	request.Complete(false)
	client := gonet.NewTCPConn(&queue, endpoint)

	go handleTCP(client, id, dialer, traffic)
}

func handleTCP(
	client net.Conn,
	id stack.TransportEndpointID,
	dialer *net.Dialer,
	traffic *TrafficManager,
) {
	destinationIP := addressString(id.LocalAddress)
	destinationPort := uint16(id.LocalPort)
	dns := isDNSPort(destinationPort)
	clientIP := sourceOf(id)

	if traffic != nil && dns {
		// DNS is carried unbilled whoever sent it. Most of it is the
		// router's own resolver forwarding hotspot queries (never in the
		// NAT rules), so it is not worth a dumpsys lookup.
		clientIP = ""
	} else if traffic != nil {
		// Captive-portal HTTP must win the race against Android's connectivity
		// check. A brand-new hotspot client can issue its first port-80 request
		// before Android has published the NAT tuple that identifies the
		// physical phone. Waiting the normal 1.5-3.5 s attribution window here
		// makes Android report "no Internet" instead of opening Shizzi.
		//
		// For port 80 while the portal is enabled, do only one immediate
		// attribution refresh. If the flow is still unknown, render the login
		// page without an identity. Stateful POSTs resolve the physical client
		// strictly inside servePortalWithResolver before applying any account.
		if destinationPort == 80 && traffic.portalEnabled() {
			clientIP = traffic.resolveFlowClient(
				"tcp",
				clientIP,
				uint16(id.RemotePort),
				destinationIP,
				destinationPort,
				false,
			)
			if clientIP == "" {
				traffic.servePortalWithResolver(client, "", func() string {
					return traffic.resolveFlowClient(
						"tcp",
						sourceOf(id),
						uint16(id.RemotePort),
						destinationIP,
						destinationPort,
						true,
					)
				})
				return
			}
			if destinationIP == portalIP || traffic.portalRequiredFor(clientIP) {
				traffic.servePortal(client, clientIP)
				return
			}
		} else {
			clientIP = traffic.resolveFlowClient(
				"tcp",
				clientIP,
				uint16(id.RemotePort),
				destinationIP,
				destinationPort,
				true,
			)
			if clientIP == "" {
				// Unknown physical client: never guess, never bill someone else.
				client.Close()
				return
			}
		}

		if !traffic.flowAllowed(clientIP) {
			traffic.noteRefusedUnauthorized()
			client.Close()
			return
		}
	}

	upstream, err := dialer.Dial("tcp", destinationOf(id))
	if err != nil {
		client.Close()
		return
	}
	relay(client, upstream, clientIP, traffic, dns)
}

// forwardUDP runs inside the netstack's packet dispatch: it must never block,
// or every client on the shared TUN stalls while one flow waits for its NAT
// rule. It only creates the endpoint; identification runs in a goroutine and
// the first datagrams wait in the endpoint queue meanwhile.
func forwardUDP(request *udp.ForwarderRequest, dialer *net.Dialer, traffic *TrafficManager) bool {
	id := request.ID()
	if traffic != nil && traffic.refuseClientIPv6(sourceOf(id)) {
		return false
	}

	var queue waiter.Queue
	endpoint, tcpipErr := request.CreateEndpoint(&queue)
	if tcpipErr != nil {
		return false
	}
	client := gonet.NewUDPConn(&queue, endpoint)

	go handleUDP(client, id, dialer, traffic)
	return true
}

func handleUDP(
	client net.Conn,
	id stack.TransportEndpointID,
	dialer *net.Dialer,
	traffic *TrafficManager,
) {
	destinationPort := uint16(id.LocalPort)
	dns := isDNSPort(destinationPort)
	clientIP := sourceOf(id)

	if traffic != nil && dns {
		// DNS is carried unbilled whoever sent it; see handleTCP.
		clientIP = ""
	} else if traffic != nil {
		clientIP = traffic.resolveFlowClient(
			"udp",
			clientIP,
			uint16(id.RemotePort),
			addressString(id.LocalAddress),
			destinationPort,
			!dns,
		)
		if clientIP == "" && !dns {
			client.Close()
			return
		}
		if !dns && !traffic.flowAllowed(clientIP) {
			traffic.noteRefusedUnauthorized()
			client.Close()
			return
		}
	}

	upstream, err := dialer.Dial("udp", destinationOf(id))
	if err != nil {
		client.Close()
		return
	}
	relayDatagrams(client, upstream, clientIP, traffic, dns)
}

func relay(client, upstream net.Conn, clientIP string, traffic *TrafficManager, bypassPortal bool) {
	defer client.Close()
	defer upstream.Close()

	done := make(chan struct{}, 2)
	go func() {
		copyStreamManaged(upstream, client, clientIP, directionUpload, traffic, bypassPortal)
		done <- struct{}{}
	}()
	go func() {
		copyStreamManaged(client, upstream, clientIP, directionDownload, traffic, bypassPortal)
		done <- struct{}{}
	}()
	<-done
}

func copyStreamManaged(
	dst, src net.Conn,
	clientIP string,
	dir direction,
	traffic *TrafficManager,
	bypassPortal bool,
) {
	buffer := make([]byte, tcpCopyBufferSize)

	for {
		read, err := src.Read(buffer)
		if read > 0 {
			if traffic != nil &&
				!traffic.waitAllowedWithPortalBypass(clientIP, dir, read, bypassPortal) {
				return
			}

			written, writeErr := dst.Write(buffer[:read])
			if written > 0 && traffic != nil {
				traffic.account(clientIP, dir, written)
			}
			if writeErr != nil {
				return
			}
		}
		if err != nil {
			return
		}
	}
}

func relayDatagrams(
	client, upstream net.Conn,
	clientIP string,
	traffic *TrafficManager,
	bypassPortal bool,
) {
	defer client.Close()
	defer upstream.Close()

	done := make(chan struct{}, 2)
	go func() {
		copyDatagramsManaged(upstream, client, clientIP, directionUpload, traffic, bypassPortal)
		done <- struct{}{}
	}()
	go func() {
		copyDatagramsManaged(client, upstream, clientIP, directionDownload, traffic, bypassPortal)
		done <- struct{}{}
	}()
	<-done
}

func copyDatagramsManaged(
	dst, src net.Conn,
	clientIP string,
	dir direction,
	traffic *TrafficManager,
	bypassPortal bool,
) {
	buffer := make([]byte, maxDatagramSize)

	for {
		if err := src.SetReadDeadline(time.Now().Add(udpFlowTimeout)); err != nil {
			return
		}

		read, err := src.Read(buffer)
		if read > 0 {
			if traffic != nil &&
				!traffic.waitAllowedWithPortalBypass(clientIP, dir, read, bypassPortal) {
				return
			}

			written, writeErr := dst.Write(buffer[:read])
			if written > 0 && traffic != nil {
				traffic.account(clientIP, dir, written)
			}
			if writeErr != nil {
				return
			}
		}
		if err != nil {
			return
		}
	}
}

const maxDatagramSize = 65535

func destinationOf(id stack.TransportEndpointID) string {
	return net.JoinHostPort(
		addressString(id.LocalAddress),
		strconv.Itoa(int(id.LocalPort)),
	)
}

func sourceOf(id stack.TransportEndpointID) string {
	return addressString(id.RemoteAddress)
}

func addressString(address tcpip.Address) string {
	return net.IP(address.AsSlice()).String()
}
