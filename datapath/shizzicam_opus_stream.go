package datapath

// v3 persistent Opus relay. A camera and a viewer only connect to the router.
import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

const camOpusMaxPacket = 4096
const camOpusMaxAge = 200 * time.Millisecond

type camOpusPacket struct {
	data     []byte
	ptsUs    uint64
	sequence uint64
	flags    byte
	at       time.Time
}
type camOpusChannel struct {
	subscribers map[chan camOpusPacket]struct{}
	config      *camOpusPacket
	next        uint64
	updated     time.Time
	uploading   bool
}

var camOpusHub = struct {
	sync.Mutex
	channels map[string]*camOpusChannel
}{channels: map[string]*camOpusChannel{}}

func camOpusPut(key string, p camOpusPacket, now time.Time) bool {
	if key == "" || len(p.data) == 0 || len(p.data) > camOpusMaxPacket || (p.flags != 0 && p.flags != 2) {
		return false
	}
	camOpusHub.Lock()
	defer camOpusHub.Unlock()
	for id, c := range camOpusHub.channels {
		if !c.uploading && len(c.subscribers) == 0 && now.Sub(c.updated) > 30*time.Second {
			delete(camOpusHub.channels, id)
		}
	}
	c := camOpusHub.channels[key]
	if c == nil {
		if len(camOpusHub.channels) >= 2*camMaxCameras {
			return false
		}
		c = &camOpusChannel{subscribers: map[chan camOpusPacket]struct{}{}}
		camOpusHub.channels[key] = c
	}
	c.next++
	p.sequence = c.next
	p.at = now
	p.data = append([]byte(nil), p.data...)
	c.updated = now
	if p.flags == 2 {
		saved := p
		c.config = &saved
	}
	for ch := range c.subscribers {
		select {
		case ch <- p:
		default:
		drain:
			for {
				select {
				case <-ch:
				default:
					break drain
				}
			}
			if c.config != nil {
				ch <- *c.config
			}
			select {
			case ch <- p:
			default:
			}
		}
	}
	return true
}
func camOpusSubscribe(key string) (chan camOpusPacket, *camOpusPacket, bool) {
	camOpusHub.Lock()
	defer camOpusHub.Unlock()
	c := camOpusHub.channels[key]
	if c == nil {
		if len(camOpusHub.channels) >= 2*camMaxCameras {
			return nil, nil, false
		}
		c = &camOpusChannel{subscribers: map[chan camOpusPacket]struct{}{}, updated: time.Now()}
		camOpusHub.channels[key] = c
	}
	if len(c.subscribers) >= 4 {
		return nil, nil, false
	}
	ch := make(chan camOpusPacket, 8)
	c.subscribers[ch] = struct{}{}
	return ch, c.config, true
}
func camOpusUnsubscribe(key string, ch chan camOpusPacket) {
	camOpusHub.Lock()
	defer camOpusHub.Unlock()
	if c := camOpusHub.channels[key]; c != nil {
		delete(c.subscribers, ch)
	}
}
func camOpusRead(r io.Reader) (camOpusPacket, error) {
	var h [21]byte
	if _, err := io.ReadFull(r, h[:]); err != nil {
		return camOpusPacket{}, err
	}
	n := binary.BigEndian.Uint32(h[:4])
	if n == 0 || n > camOpusMaxPacket || (h[20] != 0 && h[20] != 2) {
		return camOpusPacket{}, errors.New("invalid Opus packet")
	}
	p := camOpusPacket{data: make([]byte, int(n)), ptsUs: binary.BigEndian.Uint64(h[4:12]), sequence: binary.BigEndian.Uint64(h[12:20]), flags: h[20]}
	_, err := io.ReadFull(r, p.data)
	return p, err
}
func camOpusWrite(w io.Writer, p camOpusPacket) error {
	if len(p.data) == 0 || len(p.data) > camOpusMaxPacket {
		return errors.New("invalid Opus packet")
	}
	var h [21]byte
	binary.BigEndian.PutUint32(h[:4], uint32(len(p.data)))
	binary.BigEndian.PutUint64(h[4:12], p.ptsUs)
	binary.BigEndian.PutUint64(h[12:20], p.sequence)
	h[20] = p.flags
	if err := camWriteAll(w, h[:]); err != nil {
		return err
	}
	return camWriteAll(w, p.data)
}
func camWriteAll(w io.Writer, b []byte) error {
	for len(b) > 0 {
		n, e := w.Write(b)
		if e != nil {
			return e
		}
		if n == 0 {
			return io.ErrShortWrite
		}
		b = b[n:]
	}
	return nil
}
func (m *TrafficManager) camAdminValid(req *http.Request, ip string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	_, ok := m.requireAdminTokenLocked(req, ip)
	return ok
}
func (m *TrafficManager) camTalkValid(req *http.Request, ip, account string) bool {
	if !m.camAdminValid(req, ip) {
		return false
	}
	camRtc.Lock()
	defer camRtc.Unlock()
	c := camRtc.cameras[account]
	return c != nil && req.URL.Query().Get("session") != "" && c.ViewerSession == req.URL.Query().Get("session") && c.ViewerIP == ip
}
func (m *TrafficManager) serveCamOpusStream(conn net.Conn, r *http.Request, ip string) bool {
	talk := r.URL.Path == "/api/v1/cam/talk-stream"
	if !talk && r.URL.Path != "/api/v1/cam/audio-stream" {
		return false
	}
	if r.TLS == nil || !samePortalOrigin(r.Host) {
		writeJSONStatus(conn, "403 Forbidden", map[string]any{"ok": false})
		return true
	}
	upload := r.Method == http.MethodPost
	if !upload && r.Method != http.MethodGet {
		writeJSONStatus(conn, "405 Method Not Allowed", map[string]any{"ok": false})
		return true
	}
	account := strings.TrimSpace(r.URL.Query().Get("account"))
	cameraSide := upload != talk
	if cameraSide {
		ok, num := m.mediaAccountIdentity(ip)
		if !ok {
			writeJSONStatus(conn, "401 Unauthorized", map[string]any{"ok": false})
			return true
		}
		account = num
	} else if !m.camAdminValid(r, ip) {
		writeJSONStatus(conn, "401 Unauthorized", map[string]any{"ok": false})
		return true
	}
	if account == "" {
		writeJSONStatus(conn, "400 Bad Request", map[string]any{"ok": false})
		return true
	}
	authorized := func() bool {
		if cameraSide {
			ok, num := m.mediaAccountIdentity(ip)
			return ok && num == account
		}
		if upload && talk {
			return m.camTalkValid(r, ip, account)
		}
		return m.camAdminValid(r, ip)
	}
	if !authorized() {
		writeJSONStatus(conn, "403 Forbidden", map[string]any{"ok": false})
		return true
	}
	key := "listen:" + account
	if talk {
		key = "talk:" + account
	}
	incoming, outgoing := camAudioIn, camAudioOut
	if talk {
		incoming, outgoing = camIntercomIn, camIntercomOut
	}
	if upload {
		if !strings.EqualFold(r.Header.Get("Content-Type"), "application/x-shizzicam-opus-v3") {
			writeJSONStatus(conn, "415 Unsupported Media Type", map[string]any{"ok": false})
			return true
		}
		ch, _, ok := camOpusSubscribe(key)
		if !ok {
			writeJSONStatus(conn, "429 Too Many Requests", map[string]any{"ok": false})
			return true
		}
		camOpusUnsubscribe(key, ch)
		camOpusHub.Lock()
		c := camOpusHub.channels[key]
		if c.uploading {
			camOpusHub.Unlock()
			writeJSONStatus(conn, "409 Conflict", map[string]any{"ok": false})
			return true
		}
		c.uploading = true
		camOpusHub.Unlock()
		defer func() { camOpusHub.Lock(); c.uploading = false; camOpusHub.Unlock() }()
		for {
			_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
			p, err := camOpusRead(r.Body)
			if err != nil || !authorized() {
				break
			}
			now := time.Now()
			if p.flags == 0 && (p.ptsUs > uint64(now.Add(time.Second).UnixMicro()) || int64(p.ptsUs) < now.Add(-camOpusMaxAge).UnixMicro()) {
				camRecordDrop(account, true)
				continue
			}
			if !camOpusPut(key, p, now) {
				break
			}
			if p.flags == 0 {
				camMeasure(account, incoming, len(p.data), now)
			}
		}
		return true
	}
	ch, config, ok := camOpusSubscribe(key)
	if !ok {
		writeJSONStatus(conn, "429 Too Many Requests", map[string]any{"ok": false})
		return true
	}
	defer camOpusUnsubscribe(key, ch)
	if _, err := fmt.Fprint(conn, "HTTP/1.1 200 OK\r\nContent-Type: application/x-shizzicam-opus-v3\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"); err != nil {
		return true
	}
	deliver := func(p camOpusPacket) bool {
		if p.flags == 0 && time.Since(p.at) > camOpusMaxAge {
			camRecordDrop(account, true)
			return true
		}
		_ = conn.SetWriteDeadline(time.Now().Add(400 * time.Millisecond))
		if camOpusWrite(conn, p) != nil {
			return false
		}
		if p.flags == 0 {
			camMeasure(account, outgoing, len(p.data), time.Now())
		}
		return true
	}
	if config != nil && !deliver(*config) {
		return true
	}
	idle := time.NewTicker(time.Second)
	defer idle.Stop()
	for {
		select {
		case p := <-ch:
			if !authorized() || !deliver(p) {
				return true
			}
		case <-idle.C:
			if !authorized() {
				return true
			}
		}
	}
}
