package datapath

// Fork build: global hotspot shaping enabled at 40 Mbps down / 5 Mbps up.
import (
	"net"
	"strconv"
	"sync"
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

	// Global hotspot limits shared by every tethered client and every flow.
	// Mbps here means decimal megabits per second, as used by speed tests.
	globalDownloadBitsPerSecond int64 = 40_000_000
	globalUploadBitsPerSecond   int64 = 5_000_000

	// A small TCP buffer keeps shaping smooth while avoiding excessive
	// wakeups. All concurrent TCP streams share the same directional limiter.
	tcpCopyBufferSize = 32 * 1024
)

var (
	globalDownloadLimiter = newBandwidthLimiter(globalDownloadBitsPerSecond)
	globalUploadLimiter   = newBandwidthLimiter(globalUploadBitsPerSecond)
)

// bandwidthLimiter is a process-wide leaky-bucket style pacer.
//
// Each call reserves a slice of the configured bandwidth on one shared
// timeline. Because every client and every TCP/UDP flow uses the same limiter,
// their aggregate traffic cannot exceed the configured rate for that direction.
type bandwidthLimiter struct {
	mu             sync.Mutex
	bytesPerSecond float64
	next           time.Time
}

func newBandwidthLimiter(bitsPerSecond int64) *bandwidthLimiter {
	return &bandwidthLimiter{
		bytesPerSecond: float64(bitsPerSecond) / 8,
	}
}

func (l *bandwidthLimiter) wait(byteCount int) {
	if l == nil || byteCount <= 0 || l.bytesPerSecond <= 0 {
		return
	}

	transferTime := time.Duration(
		float64(byteCount) / l.bytesPerSecond * float64(time.Second),
	)
	if transferTime <= 0 {
		return
	}

	l.mu.Lock()
	now := time.Now()
	start := l.next
	if start.Before(now) {
		start = now
	}
	target := start.Add(transferTime)
	l.next = target
	l.mu.Unlock()

	if delay := time.Until(target); delay > 0 {
		time.Sleep(delay)
	}
}

// installForwarders routes inbound flows to userspace handlers.
//
// Without these the stack silently drops every packet: nothing is listening on
// the addresses tethered clients dial, because those addresses belong to hosts
// out on the internet rather than to this stack.
func installForwarders(netStack *stack.Stack, dialer *net.Dialer) {
	tcpForwarder := tcp.NewForwarder(netStack, defaultRcvWnd, maxInFlightTCP,
		func(request *tcp.ForwarderRequest) { forwardTCP(request, dialer) })
	netStack.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpForwarder.HandlePacket)

	udpForwarder := udp.NewForwarder(netStack,
		func(request *udp.ForwarderRequest) bool { return forwardUDP(request, dialer) })
	netStack.SetTransportProtocolHandler(udp.ProtocolNumber, udpForwarder.HandlePacket)
}

// forwardTCP proxies one client connection to its intended destination.
//
// The upstream socket is dialed before the client endpoint is created, so a
// refused or unreachable destination sends the client a RST rather than
// completing a handshake that then dies.
func forwardTCP(request *tcp.ForwarderRequest, dialer *net.Dialer) {
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
	go relay(client, upstream)
}

// forwardUDP proxies one client datagram flow to its destination.
//
// Returning false leaves the request unhandled, which makes the stack send the
// client an ICMP port unreachable — the right answer when the destination
// could not be reached, and better than dropping the datagram silently.
func forwardUDP(request *udp.ForwarderRequest, dialer *net.Dialer) bool {
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
	go relayDatagrams(client, upstream)
	return true
}

// relay copies bytes both ways until either side finishes, then closes both.
//
// Upload means client -> internet and uses the 5 Mbps shared limiter.
// Download means internet -> client and uses the 40 Mbps shared limiter.
func relay(client, upstream net.Conn) {
	defer client.Close()
	defer upstream.Close()

	done := make(chan struct{}, 2)

	go func() {
		copyStreamLimited(upstream, client, globalUploadLimiter)
		done <- struct{}{}
	}()
	go func() {
		copyStreamLimited(client, upstream, globalDownloadLimiter)
		done <- struct{}{}
	}()

	<-done
}

// copyStreamLimited copies a TCP byte stream while pacing aggregate traffic.
func copyStreamLimited(dst, src net.Conn, limiter *bandwidthLimiter) {
	buffer := make([]byte, tcpCopyBufferSize)

	for {
		read, err := src.Read(buffer)
		if read > 0 {
			limiter.wait(read)
			if _, writeErr := dst.Write(buffer[:read]); writeErr != nil {
				return
			}
		}
		if err != nil {
			return
		}
	}
}

// relayDatagrams copies UDP datagrams both ways until the flow goes idle.
//
// UDP uses the same global directional limiters as TCP, so QUIC and other UDP
// traffic cannot bypass the hotspot cap.
func relayDatagrams(client, upstream net.Conn) {
	defer client.Close()
	defer upstream.Close()

	done := make(chan struct{}, 2)

	go func() {
		copyDatagramsLimited(upstream, client, globalUploadLimiter)
		done <- struct{}{}
	}()
	go func() {
		copyDatagramsLimited(client, upstream, globalDownloadLimiter)
		done <- struct{}{}
	}()

	<-done
}

// copyDatagramsLimited preserves datagram boundaries and applies shaping before
// forwarding each packet.
func copyDatagramsLimited(dst, src net.Conn, limiter *bandwidthLimiter) {
	buffer := make([]byte, maxDatagramSize)

	for {
		if err := src.SetReadDeadline(time.Now().Add(udpFlowTimeout)); err != nil {
			return
		}

		read, err := src.Read(buffer)
		if read > 0 {
			limiter.wait(read)
			if _, writeErr := dst.Write(buffer[:read]); writeErr != nil {
				return
			}
		}
		if err != nil {
			return
		}
	}
}

// maxDatagramSize is large enough for any UDP payload a client can send
// through a 1500-byte-MTU link without fragmentation surprises.
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

func addressString(address tcpip.Address) string {
	return net.IP(address.AsSlice()).String()
}
