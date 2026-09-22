package datapath

// Fork build: global hotspot shaping enabled at 40 Mbps down / 5 Mbps up.
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
	// maxInFlightTCP bounds half-open connections the forwarder will track.
	// Tethered clients are few; this exists to cap memory under a SYN flood
	// from a misbehaving client, not to shape normal traffic.
	maxInFlightTCP = 512

	// rcvWnd of 0 lets netstack pick its default receive window.
	defaultRcvWnd = 0

	// dialTimeout bounds how long a client waits for an unreachable host.
	dialTimeout = 10 * time.Second

	// udpFlowTimeout closes an idle UDP association. DNS exchanges finish in
	// milliseconds; QUIC keeps its own keepalives well inside this.
	udpFlowTimeout = 60 * time.Second

	// A small TCP buffer keeps shaping smooth while avoiding excessive wakeups.
	tcpCopyBufferSize = 32 * 1024
)

// installForwarders routes inbound flows to userspace handlers.
//
// Without these the stack silently drops every packet: nothing is listening on
// the addresses tethered clients dial, because those addresses belong to hosts
// out on the internet rather than to this stack.
func installForwarders(netStack *stack.Stack, dialer *net.Dialer, traffic *TrafficManager) {
	tcpForwarder := tcp.NewForwarder(netStack, defaultRcvWnd, maxInFlightTCP,
		func(request *tcp.ForwarderRequest) { forwardTCP(request, dialer, traffic) })
	netStack.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpForwarder.HandlePacket)

	udpForwarder := udp.NewForwarder(netStack,
		func(request *udp.ForwarderRequest) bool { return forwardUDP(request, dialer, traffic) })
	netStack.SetTransportProtocolHandler(udp.ProtocolNumber, udpForwarder.HandlePacket)
}

// forwardTCP proxies one client connection to its intended destination.
//
// The upstream socket is dialed before the client endpoint is created, so a
// refused or unreachable destination sends the client a RST rather than
// completing a handshake that then dies.
func forwardTCP(request *tcp.ForwarderRequest, dialer *net.Dialer, traffic *TrafficManager) {
	id := request.ID()

	upstream, err := dialer.Dial("tcp", destinationOf(id))
	if err != nil {
		request.Complete(true)
		return
	}

	var queue waiter.Queue
	endpoint, tcpipErr := request.CreateEndpoint(&queue)
	if tcpipErr != nil {
		upstream.Close()
		request.Complete(true)
		return
	}
	request.Complete(false)

	client := gonet.NewTCPConn(&queue, endpoint)
	go relay(client, upstream, sourceOf(id), traffic)
}

// forwardUDP proxies one client datagram flow to its destination.
//
// Returning false leaves the request unhandled, which makes the stack send the
// client an ICMP port unreachable — the right answer when the destination
// could not be reached, and better than dropping the datagram silently.
func forwardUDP(request *udp.ForwarderRequest, dialer *net.Dialer, traffic *TrafficManager) bool {
	id := request.ID()

	upstream, err := dialer.Dial("udp", destinationOf(id))
	if err != nil {
		return false
	}

	var queue waiter.Queue
	endpoint, tcpipErr := request.CreateEndpoint(&queue)
	if tcpipErr != nil {
		upstream.Close()
		return false
	}

	client := gonet.NewUDPConn(&queue, endpoint)
	go relayDatagrams(client, upstream, sourceOf(id), traffic)
	return true
}

// relay copies bytes both ways until either side finishes, then closes both.
func relay(client, upstream net.Conn, clientIP string, traffic *TrafficManager) {
	defer client.Close()
	defer upstream.Close()

	done := make(chan struct{}, 2)

	go func() {
		copyStreamManaged(upstream, client, clientIP, directionUpload, traffic)
		done <- struct{}{}
	}()
	go func() {
		copyStreamManaged(client, upstream, clientIP, directionDownload, traffic)
		done <- struct{}{}
	}()

	<-done
}

// copyStreamManaged copies a TCP stream while applying global/client rate limits
// and accounting quota usage after successful writes.
func copyStreamManaged(
	dst, src net.Conn,
	clientIP string,
	dir direction,
	traffic *TrafficManager,
) {
	buffer := make([]byte, tcpCopyBufferSize)

	for {
		read, err := src.Read(buffer)
		if read > 0 {
			if traffic != nil && !traffic.waitAllowed(clientIP, dir, read) {
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

// relayDatagrams copies UDP datagrams both ways until the flow goes idle.
func relayDatagrams(client, upstream net.Conn, clientIP string, traffic *TrafficManager) {
	defer client.Close()
	defer upstream.Close()

	done := make(chan struct{}, 2)

	go func() {
		copyDatagramsManaged(upstream, client, clientIP, directionUpload, traffic)
		done <- struct{}{}
	}()
	go func() {
		copyDatagramsManaged(client, upstream, clientIP, directionDownload, traffic)
		done <- struct{}{}
	}()

	<-done
}

// copyDatagramsManaged preserves datagram boundaries and applies shaping/quota
// accounting to UDP just like TCP.
func copyDatagramsManaged(
	dst, src net.Conn,
	clientIP string,
	dir direction,
	traffic *TrafficManager,
) {
	buffer := make([]byte, maxDatagramSize)

	for {
		if err := src.SetReadDeadline(time.Now().Add(udpFlowTimeout)); err != nil {
			return
		}

		read, err := src.Read(buffer)
		if read > 0 {
			if traffic != nil && !traffic.waitAllowed(clientIP, dir, read) {
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

// maxDatagramSize is large enough for any UDP payload a client can send.
const maxDatagramSize = 65535

// destinationOf renders the address a flow was actually addressed to.
//
// LocalAddress is the destination from the stack's perspective: the client
// dialed it, and this stack received it only because the NIC is promiscuous.
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
