package datapath

import (
	"io"
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
	var scope *flowScope

	if traffic != nil && dns {
		traffic.serveDNSStream(client, dialer)
		return

	} else if traffic != nil {
		clientIP = traffic.resolveFlowClient(
			"tcp",
			clientIP,
			uint16(id.RemotePort),
			destinationIP,
			destinationPort,
			true,
		)
		if clientIP == "" && !dns {
			// Android may publish the exact translated NAT tuple just AFTER
			// the first attribution window. For captive HTTP, give it three
			// short additional chances before presenting a recovery page.
			// Never infer an IP from another connected hotspot client.
			if (destinationPort == 80 && traffic.portalRecoveryNeeded(destinationIP)) || (destinationPort == 443 && destinationIP == portalIP) {
				for retry := 0; retry < 3 && clientIP == ""; retry++ {
					time.Sleep(350 * time.Millisecond)
					clientIP = traffic.resolveFlowClient(
						"tcp",
						sourceOf(id),
						uint16(id.RemotePort),
						destinationIP,
						destinationPort,
						false,
					)
				}
				if clientIP == "" {
					if destinationPort == 80 {
						traffic.serveUnidentifiedPortal(client)
					} else {
						client.Close()
					}
					return
				}
			} else {
				client.Close()
				return
			}
		}

		if destinationPort == 443 && destinationIP == portalIP && clientIP != "" {
			traffic.serveTLS(client, clientIP)
			return
		}
		if destinationPort == 80 && clientIP != "" &&
			(destinationIP == portalIP || traffic.portalRequiredFor(clientIP)) {
			traffic.servePortal(client, clientIP)
			return
		}
		if destinationPort == 80 && clientIP == "" && destinationIP == portalIP {
			client.Close()
			return
		}
		var admitted bool
		scope, admitted = traffic.captureFlowScope(clientIP, false, "")
		if !admitted {
			traffic.noteRefusedUnauthorized()
			client.Close()
			return
		}
	}

	var upstream net.Conn
	var err error
	if traffic != nil {
		upstream, err = traffic.dialFlow(dialer, scope, "tcp", destinationOf(id))
	} else {
		upstream, err = dialer.Dial("tcp", destinationOf(id))
	}
	if err != nil {
		client.Close()
		return
	}
	if traffic != nil && !dns {
		release, ok := traffic.trackScopedFlow(clientIP, scope, client, upstream, false)
		if !ok {
			client.Close()
			upstream.Close()
			return
		}
		defer release()
	}
	relay(client, upstream, clientIP, traffic, dns, scope)
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
	var scope *flowScope

	if traffic != nil && dns {
		traffic.serveDNSDatagrams(client, dialer)
		return

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
		var admitted bool
		scope, admitted = traffic.captureFlowScope(clientIP, false, "")
		if !admitted {
			traffic.noteRefusedUnauthorized()
			client.Close()
			return
		}
	}

	var upstream net.Conn
	var err error
	if traffic != nil {
		upstream, err = traffic.dialFlow(dialer, scope, "udp", destinationOf(id))
	} else {
		upstream, err = dialer.Dial("udp", destinationOf(id))
	}
	if err != nil {
		client.Close()
		return
	}
	if traffic != nil && !dns {
		release, ok := traffic.trackScopedFlow(clientIP, scope, client, upstream, false)
		if !ok {
			client.Close()
			upstream.Close()
			return
		}
		defer release()
	}
	relayDatagrams(client, upstream, clientIP, traffic, dns, scope)
}

func relay(client, upstream net.Conn, clientIP string, traffic *TrafficManager, bypassPortal bool, scopes ...*flowScope) {
	defer client.Close()
	defer upstream.Close()
	if traffic != nil && !bypassPortal && firstFlowScope(scopes) == nil {
		scope, ok := traffic.captureFlowScope(clientIP, false, "")
		if !ok {
			return
		}
		scopes = []*flowScope{scope}
	}

	done := make(chan bool, 2)
	copyDirection := func(dst, src net.Conn, dir direction) {
		result := copyStreamManaged(dst, src, clientIP, dir, traffic, bypassPortal, scopes...)
		graceful := false
		if result.reason == "read-eof" {
			if writer, ok := dst.(interface{ CloseWrite() error }); ok {
				if err := writer.CloseWrite(); err == nil {
					graceful = true
				} else {
					result.reason = "half-close-error"
				}
			} else {
				result.reason = "half-close-unavailable"
			}
		}
		if traffic != nil {
			traffic.noteFlowEnd("tcp", clientIP, dir, result, firstFlowScope(scopes))
		}
		done <- graceful
	}
	go copyDirection(upstream, client, directionUpload)
	go copyDirection(client, upstream, directionDownload)
	if graceful := <-done; !graceful {
		firstFlowScope(scopes).stopTransfers()
		client.Close()
		upstream.Close()
	}
	// Propagate an orderly FIN without interrupting the opposite response.
	<-done
	firstFlowScope(scopes).stopTransfers()
}

func copyStreamManaged(
	dst, src net.Conn,
	clientIP string,
	dir direction,
	traffic *TrafficManager,
	bypassPortal bool,
	scopes ...*flowScope,
) (result flowCopyResult) {
	buffer := make([]byte, tcpCopyBufferSize)
	for {
		read, err := src.Read(buffer)
		if read > 0 {
			reservation := (*transferReservation)(nil)
			amount := read
			if traffic != nil && !bypassPortal {
				reservation = traffic.reserveTransferForFlow(clientIP, dir, read, false, firstFlowScope(scopes))
				if reservation == nil {
					result.reason = "transfer-not-authorized-or-quota"
					return
				}
				if !reservation.pace() {
					reservation.settle(0)
					result.reason = "pacing-cancelled"
					return
				}
				amount = reservation.count
			}
			written, writeErr := dst.Write(buffer[:amount])
			result.bytes += int64(written)
			if reservation != nil {
				reservation.settle(written)
			} else if written > 0 && traffic != nil {
				traffic.account(clientIP, dir, written)
			}
			if writeErr != nil {
				result.reason = "write-error"
				return
			}
			if written != amount {
				result.reason = "short-write"
				return
			}
			if amount != read {
				result.reason = "quota-exhausted"
				return
			}
		}
		if err != nil {
			result.reason = "read-error"
			if err == io.EOF {
				result.reason = "read-eof"
			}
			return
		}
	}
}

func relayDatagrams(
	client, upstream net.Conn,
	clientIP string,
	traffic *TrafficManager,
	bypassPortal bool,
	scopes ...*flowScope,
) {
	defer client.Close()
	defer upstream.Close()
	if traffic != nil && !bypassPortal && firstFlowScope(scopes) == nil {
		scope, ok := traffic.captureFlowScope(clientIP, false, "")
		if !ok {
			return
		}
		scopes = []*flowScope{scope}
	}
	activity := newUDPFlowActivity(udpFlowTimeout)

	done := make(chan struct{}, 2)
	copyDirection := func(dst, src net.Conn, dir direction) {
		result := copyDatagramsWithActivity(dst, src, clientIP, dir, traffic, bypassPortal, activity, scopes...)
		if traffic != nil {
			traffic.noteFlowEnd("udp", clientIP, dir, result, firstFlowScope(scopes))
		}
		done <- struct{}{}
	}
	go copyDirection(upstream, client, directionUpload)
	go copyDirection(client, upstream, directionDownload)
	<-done
	firstFlowScope(scopes).stopTransfers()
	client.Close()
	upstream.Close()
	<-done
}

func copyDatagramsManaged(
	dst, src net.Conn,
	clientIP string,
	dir direction,
	traffic *TrafficManager,
	bypassPortal bool,
	scopes ...*flowScope,
) {
	copyDatagramsWithActivity(dst, src, clientIP, dir, traffic, bypassPortal, newUDPFlowActivity(udpFlowTimeout), scopes...)
}

func copyDatagramsWithActivity(dst, src net.Conn, clientIP string, dir direction, traffic *TrafficManager, bypassPortal bool, activity *udpFlowActivity, scopes ...*flowScope) (result flowCopyResult) {
	buffer := make([]byte, maxDatagramSize)
	for {
		if err := src.SetReadDeadline(activity.deadline()); err != nil {
			result.reason = "deadline-error"
			return
		}
		read, err := src.Read(buffer)
		if read > 0 {
			activity.touch()
			reservation := (*transferReservation)(nil)
			if traffic != nil && !bypassPortal {
				reservation = traffic.reserveTransferForFlow(clientIP, dir, read, true, firstFlowScope(scopes))
				if reservation == nil {
					result.reason = "transfer-not-authorized-or-quota"
					return
				}
				if !reservation.pace() {
					reservation.settle(0)
					result.reason = "pacing-cancelled"
					return
				}
			}
			written, writeErr := dst.Write(buffer[:read])
			result.bytes += int64(written)
			if written > 0 {
				activity.touch()
			}
			if reservation != nil {
				reservation.settle(written)
			} else if written > 0 && traffic != nil {
				traffic.account(clientIP, dir, written)
			}
			if writeErr != nil {
				result.reason = "write-error"
				return
			}
			if written != read {
				result.reason = "short-write"
				return
			}
		}
		if err != nil {
			if timeout, ok := err.(net.Error); ok && timeout.Timeout() {
				if !activity.idle() {
					continue
				}
				result.reason = "udp-idle"
			} else {
				result.reason = "read-error"
			}
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

func firstFlowScope(scopes []*flowScope) *flowScope {
	if len(scopes) > 0 {
		return scopes[0]
	}
	return nil
}
