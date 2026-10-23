package datapath

// Bounded, local-only H.264 access-unit relay (not WebRTC peer-to-peer).
// Protocol frames: uint32 N, uint16 width, uint16 height, uint64 ptsUs,
// uint8 flags (1=keyframe, 2=codec configuration), then N encoded bytes.
// Every viewer receives its own copy, and slow viewers drop frames.
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

const camH264MaxPacket = 262144
const camH264MaxSubscribers = 4

type camH264Packet struct {
	data          []byte
	width, height uint16
	ptsUs         uint64
	flags         byte
}
type camH264Channel struct {
	subscribers map[chan camH264Packet]struct{}
	waiting     map[chan camH264Packet]bool
	config      *camH264Packet
	key         *camH264Packet
	updated     time.Time
	uploading   bool
}

var camVideoHub = struct {
	sync.Mutex
	cameras map[string]*camH264Channel
}{cameras: map[string]*camH264Channel{}}

func camH264Put(account string, packet camH264Packet, now time.Time) bool {
	if account == "" || len(packet.data) == 0 || len(packet.data) > camH264MaxPacket ||
		packet.width == 0 || packet.height == 0 || packet.width > 3840 || packet.height > 2160 {
		return false
	}
	camVideoHub.Lock()
	defer camVideoHub.Unlock()
	for id, cam := range camVideoHub.cameras {
		if len(cam.subscribers) == 0 && now.Sub(cam.updated) > 15*time.Second && !cam.uploading {
			delete(camVideoHub.cameras, id)
		}
	}
	cam := camVideoHub.cameras[account]
	if cam == nil {
		if len(camVideoHub.cameras) >= camMaxCameras {
			return false
		}
		cam = &camH264Channel{subscribers: map[chan camH264Packet]struct{}{}}
		camVideoHub.cameras[account] = cam
	}
	packet.data = append([]byte(nil), packet.data...)
	cam.updated = now
	if packet.flags&2 != 0 {
		saved := packet
		cam.config = &saved
		cam.key = nil // resolution or codec changed: old GOP is no longer decodable
	} else if packet.flags&1 != 0 {
		saved := packet
		cam.key = &saved
	}
	if cam.waiting == nil {
		cam.waiting = map[chan camH264Packet]bool{}
	}
	for ch := range cam.subscribers {
		if packet.flags&2 != 0 {
			cam.waiting[ch] = true
		}
		if cam.waiting[ch] && packet.flags&3 == 0 {
			continue
		}
		if packet.flags&1 != 0 {
			cam.waiting[ch] = false
		}
		select {
		case ch <- packet:
		default:
			// Once any reference frame is lost, wait for a new IDR; never decode a broken GOP.
		drain:
			for {
				select {
				case <-ch:
				default:
					break drain
				}
			}
			cam.waiting[ch] = true
			camRecordDrop(account, false)
			if cam.config != nil {
				ch <- *cam.config
			}
			if packet.flags&1 != 0 {
				ch <- packet
				cam.waiting[ch] = false
			}
		}
	}
	return true
}
func camH264Subscribe(account string) (chan camH264Packet, []camH264Packet, bool) {
	camVideoHub.Lock()
	defer camVideoHub.Unlock()
	cam := camVideoHub.cameras[account]
	if cam == nil || len(cam.subscribers) >= camH264MaxSubscribers {
		return nil, nil, false
	}
	ch := make(chan camH264Packet, 4)
	cam.subscribers[ch] = struct{}{}
	if cam.waiting == nil {
		cam.waiting = map[chan camH264Packet]bool{}
	}
	cam.waiting[ch] = true
	initial := []camH264Packet{}
	if cam.config != nil {
		initial = append(initial, *cam.config)
	}
	// A cached IDR without its entire GOP would skip reference frames. Wait for the next fresh IDR.
	return ch, initial, true
}
func camH264Unsubscribe(account string, ch chan camH264Packet) {
	camVideoHub.Lock()
	defer camVideoHub.Unlock()
	if cam := camVideoHub.cameras[account]; cam != nil {
		delete(cam.subscribers, ch)
		delete(cam.waiting, ch)
	}
}
func camH264Write(conn net.Conn, pkt camH264Packet) error {
	n := len(pkt.data)
	if n == 0 || n > camH264MaxPacket {
		return errors.New("invalid h264 packet")
	}
	var header [17]byte
	binary.BigEndian.PutUint32(header[:4], uint32(n))
	binary.BigEndian.PutUint16(header[4:6], pkt.width)
	binary.BigEndian.PutUint16(header[6:8], pkt.height)
	binary.BigEndian.PutUint64(header[8:16], pkt.ptsUs)
	header[16] = pkt.flags
	if err := camWriteAll(conn, header[:]); err != nil {
		return err
	}
	return camWriteAll(conn, pkt.data)
}
func camH264Read(r io.Reader) (camH264Packet, error) {
	var head [17]byte
	if _, err := io.ReadFull(r, head[:]); err != nil {
		return camH264Packet{}, err
	}
	size := binary.BigEndian.Uint32(head[:4])
	if size == 0 || size > camH264MaxPacket {
		return camH264Packet{}, errors.New("oversized frame")
	}
	pkt := camH264Packet{
		width: binary.BigEndian.Uint16(head[4:6]), height: binary.BigEndian.Uint16(head[6:8]),
		ptsUs: binary.BigEndian.Uint64(head[8:16]), flags: head[16], data: make([]byte, int(size)),
	}
	_, err := io.ReadFull(r, pkt.data)
	return pkt, err
}
func (m *TrafficManager) serveCamVideoStream(conn net.Conn, req *http.Request, clientIP string) bool {
	if req.URL.Path != "/api/v1/cam/video-stream" {
		return false
	}
	if req.TLS == nil || !samePortalOrigin(req.Host) {
		writeJSONStatus(conn, "403 Forbidden", map[string]any{"ok": false})
		return true
	}
	if req.Method == http.MethodPost {
		authorized, account := m.mediaAccountIdentity(clientIP)
		if !authorized || account == "" {
			writeJSONStatus(conn, "401 Unauthorized", map[string]any{"ok": false})
			return true
		}
		if !strings.EqualFold(req.Header.Get("Content-Type"), "application/x-shizzicam-h264") {
			writeJSONStatus(conn, "415 Unsupported Media Type", map[string]any{"ok": false})
			return true
		}
		camVideoHub.Lock()
		channel := camVideoHub.cameras[account]
		if channel == nil && len(camVideoHub.cameras) < camMaxCameras {
			channel = &camH264Channel{subscribers: map[chan camH264Packet]struct{}{}, updated: time.Now()}
			camVideoHub.cameras[account] = channel
		}
		if channel == nil || channel.uploading {
			camVideoHub.Unlock()
			writeJSONStatus(conn, "409 Conflict", map[string]any{"ok": false, "error": "camera already streaming"})
			return true
		}
		channel.uploading = true
		camVideoHub.Unlock()
		defer func() { camVideoHub.Lock(); channel.uploading = false; camVideoHub.Unlock() }()
		// An active authenticated stream must not be cut at the portal's 15s deadline.
		for {
			_ = conn.SetReadDeadline(time.Now().Add(8 * time.Second))
			pkt, err := camH264Read(req.Body)
			if err != nil {
				break
			}
			valid, current := m.mediaAccountIdentity(clientIP)
			if !valid || current != account {
				break
			}
			now := time.Now()
			if pkt.flags&2 == 0 && (int64(pkt.ptsUs) < now.Add(-500*time.Millisecond).UnixMicro() || int64(pkt.ptsUs) > now.Add(time.Second).UnixMicro()) {
				camRecordDrop(account, false)
				camVideoHub.Lock()
				for ch := range channel.subscribers {
					if channel.waiting == nil {
						channel.waiting = map[chan camH264Packet]bool{}
					}
					channel.waiting[ch] = true
				drain:
					for {
						select {
						case <-ch:
						default:
							break drain
						}
					}
				}
				camVideoHub.Unlock()
				continue
			}
			if !camH264Put(account, pkt, now) {
				break
			}
			if pkt.flags&2 == 0 {
				camMeasure(account, camVideoIn, len(pkt.data), time.Now())
			}
		}
		return true
	}
	if req.Method != http.MethodGet {
		writeJSONStatus(conn, "405 Method Not Allowed", map[string]any{"ok": false})
		return true
	}
	m.mu.Lock()
	_, allowed := m.requireAdminTokenLocked(req, clientIP)
	m.mu.Unlock()
	if !allowed {
		writeJSONStatus(conn, "401 Unauthorized", map[string]any{"ok": false})
		return true
	}
	account := strings.TrimSpace(req.URL.Query().Get("account"))
	ch, initial, ok := camH264Subscribe(account)
	if !ok {
		writeJSONStatus(conn, "404 Not Found", map[string]any{"ok": false, "error": "camera not publishing"})
		return true
	}
	defer camH264Unsubscribe(account, ch)
	_, _ = fmt.Fprint(conn, "HTTP/1.1 200 OK\r\nContent-Type: application/x-shizzicam-h264\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
	needIDR := true
	deliver := func(pkt camH264Packet) bool {
		if pkt.flags&2 != 0 {
			needIDR = true
		}
		if pkt.flags&2 == 0 && time.Now().UnixMicro()-int64(pkt.ptsUs) > 350000 {
			needIDR = true
			camRecordDrop(account, false)
			return true
		}
		if needIDR && pkt.flags&3 == 0 {
			return true
		}
		if pkt.flags&1 != 0 {
			needIDR = false
		}
		if !m.camAdminValid(req, clientIP) {
			return false
		}
		_ = conn.SetWriteDeadline(time.Now().Add(400 * time.Millisecond))
		if err := camH264Write(conn, pkt); err != nil {
			return false
		}
		if pkt.flags&2 == 0 {
			camMeasure(account, camVideoOut, len(pkt.data), time.Now())
		}
		return true
	}
	for _, pkt := range initial {
		if !deliver(pkt) {
			return true
		}
	}
	idle := time.NewTimer(12 * time.Second)
	defer idle.Stop()
	for {
		select {
		case pkt := <-ch:
			if !deliver(pkt) {
				return true
			}
			if !idle.Stop() {
				select {
				case <-idle.C:
				default:
				}
			}
			idle.Reset(12 * time.Second)
		case <-idle.C:
			return true
		}
	}
}
