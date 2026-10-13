package datapath

import (
    "bufio"
    "bytes"
    "io"
    "net"
    "net/http"
    "time"

    "dev.shizzi/datapath/core"
    "gvisor.dev/gvisor/pkg/tcpip/stack"
)

// isPublicPortalFlow matches only Shizzi's LOCAL HTTP portal.
// It never exempts arbitrary Internet HTTP traffic from authorization.
func isPublicPortalFlow(id stack.TransportEndpointID) bool {
    return addressString(id.LocalAddress) == "192.0.2.1" && id.LocalPort == 80
}

// servePublicPortalConn is the NAT-independent landing page on the TUN path.
// It deliberately does not receive the physical IP: translated 192.0.2.2
// cannot authenticate a user. /login remains fail closed on this path until
// the physical-hotspot listener is connected with a real PIN verifier.
func servePublicPortalConn(conn net.Conn) {
    defer conn.Close()
    _ = conn.SetDeadline(time.Now().Add(10 * time.Second))
    request, err := http.ReadRequest(bufio.NewReader(io.LimitReader(conn, 16*1024)))
    if err != nil { return }
    defer request.Body.Close()
    request.RemoteAddr = conn.RemoteAddr().String()

    response := &publicPortalResponse{header: make(http.Header)}
    (core.PortalEndpoint{}).ServeHTTP(response, request)
    response.send(conn)
}

type publicPortalResponse struct {
    header http.Header
    code int
    body bytes.Buffer
}

func (p *publicPortalResponse) Header() http.Header { return p.header }

func (p *publicPortalResponse) WriteHeader(code int) {
    if p.code == 0 { p.code = code }
}

func (p *publicPortalResponse) Write(data []byte) (int, error) {
    if p.code == 0 { p.code = http.StatusOK }
    return p.body.Write(data)
}

func (p *publicPortalResponse) send(conn net.Conn) {
    if p.code == 0 { p.code = http.StatusOK }
    response := http.Response{
        StatusCode: p.code,
        ProtoMajor: 1,
        ProtoMinor: 1,
        Header: p.header,
        Body: io.NopCloser(bytes.NewReader(p.body.Bytes())),
        ContentLength: int64(p.body.Len()),
        Close: true,
    }
    _ = response.Write(conn)
}
