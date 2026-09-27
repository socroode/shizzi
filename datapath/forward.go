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
	maxInFlightTCP = 512
	defaultRcvWnd  = 0
	dialTimeout    = 10 * time.Second
	udpFlowTimeout = 60 * time.Second
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

func forwardTCP(request *tcp.ForwarderRequest, dialer *net.Dialer, traffic *TrafficManager) {
	id := request.ID()
	clientIP := sourceOf(id)
	if traffic != nil {
		clientIP = traffic.resolveFlowClient(
			"tcp",
			clientIP,
			uint16(id.RemotePort),
			addressString(id.LocalAddress),
			uint16(id.LocalPort),
		)
		if clientIP == "" {
			request.Complete(true)
			return
		}
	}

	if traffic != nil && uint16(id.LocalPort) == 80 &&
		(traffic.portalRequiredFor(clientIP) || addressString(id.LocalAddress) == portalIP) {
		var queue waiter.Queue
		endpoint, tcpipErr := request.CreateEndpoint(&queue)
		if tcpipErr != nil {
			request.Complete(true)
			return
		}
		request.Complete(false)
		client := gonet.NewTCPConn(&queue, endpoint)
		go traffic.servePortal(client, clientIP)
		return
	}

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
	go relay(client, upstream, clientIP, traffic)
}

func forwardUDP(request *udp.ForwarderRequest, dialer *net.Dialer, traffic *TrafficManager) bool {
	id := request.ID()
	clientIP := sourceOf(id)
	if traffic != nil {
		clientIP = traffic.resolveFlowClient(
			"udp",
			clientIP,
			uint16(id.RemotePort),
			addressString(id.LocalAddress),
			uint16(id.LocalPort),
		)
		if clientIP == "" {
			return false
		}
	}

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
	go relayDatagrams(
		client,
		upstream,
		clientIP,
		traffic,
		uint16(id.LocalPort) == 53,
	)
	return true
}

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
