package datapath

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"
)

var captiveDNSHosts = map[string]bool{
	"connectivitycheck.gstatic.com": true, "connectivitycheck.android.com": true,
	"clients3.google.com": true, "www.google.com": true,
	"captive.apple.com": true, "www.msftconnecttest.com": true,
	"www.msftncsi.com": true,
}

// Only one bounded, ordinary IN question is accepted. No arbitrary TCP stream,
// zone transfer, compressed question, or caller-selected upstream port.
func parseDNSQuestion(packet []byte) (string, uint16, int, bool) {
	if len(packet) < 17 || len(packet) > 1232 || packet[2]&0xf8 != 0 ||
		binary.BigEndian.Uint16(packet[4:6]) != 1 {
		return "", 0, 0, false
	}
	pos := 12
	labels := []string{}
	total := 0
	for {
		if pos >= len(packet) {
			return "", 0, 0, false
		}
		length := int(packet[pos])
		pos++
		if length == 0 {
			break
		}
		if length > 63 || pos+length > len(packet) {
			return "", 0, 0, false
		}
		labels = append(labels, string(packet[pos:pos+length]))
		pos += length
		total += length + 1
		if total > 254 {
			return "", 0, 0, false
		}
	}
	if pos+4 > len(packet) || binary.BigEndian.Uint16(packet[pos+2:pos+4]) != 1 {
		return "", 0, 0, false
	}
	kind := binary.BigEndian.Uint16(packet[pos : pos+2])
	if kind == 0 || kind >= 251 {
		return "", 0, 0, false
	}
	return strings.ToLower(strings.Join(labels, ".")), kind, pos + 4, true
}

func captiveDNSAnswer(query []byte, kind uint16, end int) []byte {
	answer := append([]byte(nil), query[:end]...)
	answer[2] = 0x81
	answer[3] = 0x80
	for i := 6; i < 12; i++ {
		answer[i] = 0
	}
	if kind == 1 {
		answer[7] = 1
		answer = append(answer, 0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 5, 0, 4, 192, 0, 2, 1)
	}
	return answer
}

func (m *TrafficManager) resolveDNS(query []byte, dialer *net.Dialer) ([]byte, error) {
	host, kind, end, valid := parseDNSQuestion(query)
	if !valid {
		return nil, fmt.Errorf("invalid or oversized DNS request")
	}
	m.mu.Lock()
	now := time.Now()
	if now.Sub(m.dnsWindow) >= time.Second {
		m.dnsWindow = now
		m.dnsCount = 0
	}
	m.dnsCount++
	limited := m.dnsCount > 200
	online := m.upstreamAvailable
	m.mu.Unlock()
	if limited {
		return nil, fmt.Errorf("DNS request limit")
	}
	local := host == "shizzi.local" || (!online && captiveDNSHosts[host])
	if local {
		return captiveDNSAnswer(query, kind, end), nil
	}
	conn, err := dialer.Dial("udp", "8.8.8.8:53")
	if err != nil {
		if captiveDNSHosts[host] {
			return captiveDNSAnswer(query, kind, end), nil
		}
		return nil, err
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(2 * time.Second))
	if _, err = conn.Write(query); err != nil {
		return nil, err
	}
	buffer := make([]byte, 4096)
	n, err := conn.Read(buffer)
	if err != nil {
		if captiveDNSHosts[host] {
			return captiveDNSAnswer(query, kind, end), nil
		}
		return nil, err
	}
	if n < 12 || buffer[2]&0x80 == 0 || buffer[0] != query[0] || buffer[1] != query[1] {
		return nil, fmt.Errorf("invalid DNS reply")
	}
	m.account("", directionUpload, len(query))
	m.account("", directionDownload, n)
	return buffer[:n], nil
}

func (m *TrafficManager) serveDNSStream(client net.Conn, dialer *net.Dialer) {
	defer client.Close()
	client.SetDeadline(time.Now().Add(15 * time.Second))
	for count := 0; count < 32; count++ {
		header := make([]byte, 2)
		if _, err := io.ReadFull(client, header); err != nil {
			return
		}
		size := int(binary.BigEndian.Uint16(header))
		if size < 17 || size > 1232 {
			return
		}
		query := make([]byte, size)
		if _, err := io.ReadFull(client, query); err != nil {
			return
		}
		answer, err := m.resolveDNS(query, dialer)
		if err != nil {
			return
		}
		binary.BigEndian.PutUint16(header, uint16(len(answer)))
		if _, err := client.Write(append(header, answer...)); err != nil {
			return
		}
	}
}

func (m *TrafficManager) serveDNSDatagrams(client net.Conn, dialer *net.Dialer) {
	defer client.Close()
	buffer := make([]byte, 1233)
	for count := 0; count < 64; count++ {
		client.SetReadDeadline(time.Now().Add(15 * time.Second))
		n, err := client.Read(buffer)
		if err != nil {
			return
		}
		answer, err := m.resolveDNS(buffer[:n], dialer)
		if err != nil {
			return
		}
		if _, err := client.Write(answer); err != nil {
			return
		}
	}
}

func captiveProbePath(path string) bool {
	switch path {
	case "/generate_204", "/gen_204", "/connecttest.txt", "/ncsi.txt", "/hotspot-detect.html", "/library/test/success.html", "/success.txt":
		return true
	}
	return false
}

func (m *TrafficManager) serveCaptiveProbe(conn net.Conn, request *http.Request, ip string) {
	m.mu.Lock()
	allowed := m.allowedLocked(ip, time.Now().UnixMilli()) && m.upstreamAvailable
	m.mu.Unlock()
	if !allowed {
		io.WriteString(conn, "HTTP/1.1 302 Found\r\nLocation: http://192.0.2.1/\r\nCache-Control: no-store\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
		return
	}
	body := ""
	switch request.URL.Path {
	case "/connecttest.txt":
		body = "Microsoft Connect Test"
	case "/ncsi.txt":
		body = "Microsoft NCSI"
	case "/hotspot-detect.html", "/library/test/success.html":
		body = "<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>"
	case "/success.txt":
		body = "success\n"
	}
	if body == "" {
		io.WriteString(conn, "HTTP/1.1 204 No Content\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
		return
	}
	fmt.Fprintf(conn, "HTTP/1.1 200 OK\r\nCache-Control: no-store\r\nContent-Length: %d\r\nConnection: close\r\n\r\n", len(body))
	if request.Method != http.MethodHead {
		io.WriteString(conn, body)
	}
}
