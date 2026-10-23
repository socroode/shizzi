package datapath

import (
	"encoding/json"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

type camTelemetry struct {
	Battery                int      `json:"battery"`
	Width                  int      `json:"width"`
	Height                 int      `json:"height"`
	Quality                string   `json:"quality"`
	VideoCodec             string   `json:"videoCodec"`
	AudioCodec             string   `json:"audioCodec"`
	CapturedFPS            *float64 `json:"capturedFps"`
	EncodedFPS             *float64 `json:"encodedFps"`
	RenderedFPS            *float64 `json:"renderedFps"`
	LatencyMillis          *float64 `json:"latencyMillis"`
	ClockUncertaintyMillis *float64 `json:"clockUncertaintyMillis"`
	VideoDrops             uint64   `json:"videoDrops"`
	AudioDrops             uint64   `json:"audioDrops"`
	Interruptions          uint64   `json:"interruptions"`
	AudioPlayedSequence    uint64   `json:"audioPlayedSequence"`
	AudioPlaybackRouterUs  int64    `json:"audioPlaybackRouterUs"`
	AudioPlaybackVerified  bool     `json:"audioPlaybackVerified"`
	ThermalStatus          int      `json:"thermalStatus"`
	AudioState             string   `json:"audioState"`
}
type camTelemetryRecord struct {
	camera, viewer         camTelemetry
	cameraAt, viewerAt     time.Time
	videoDrops, audioDrops uint64
}

var camTelemetryHub = struct {
	sync.Mutex
	entries map[string]*camTelemetryRecord
}{entries: map[string]*camTelemetryRecord{}}

func camRecordDrop(account string, audio bool) {
	camTelemetryHub.Lock()
	defer camTelemetryHub.Unlock()
	r := camTelemetryHub.entries[account]
	if r == nil {
		if len(camTelemetryHub.entries) >= camMaxCameras {
			return
		}
		r = &camTelemetryRecord{}
		camTelemetryHub.entries[account] = r
	}
	if audio {
		r.audioDrops++
	} else {
		r.videoDrops++
	}
}
func camTelemetrySet(account string, camera bool, p camTelemetry, at time.Time) {
	camTelemetryHub.Lock()
	defer camTelemetryHub.Unlock()
	for key, r := range camTelemetryHub.entries {
		if at.Sub(r.cameraAt) > time.Minute && at.Sub(r.viewerAt) > time.Minute {
			delete(camTelemetryHub.entries, key)
		}
	}
	r := camTelemetryHub.entries[account]
	if r == nil {
		if len(camTelemetryHub.entries) >= camMaxCameras {
			return
		}
		r = &camTelemetryRecord{}
		camTelemetryHub.entries[account] = r
	}
	if camera {
		r.camera = p
		r.cameraAt = at
	} else {
		r.viewer = p
		r.viewerAt = at
	}
}
func camRecent(at, now time.Time, age time.Duration) bool {
	return !at.IsZero() && now.Sub(at) >= 0 && now.Sub(at) <= age
}

func camEnrichMetrics(items []camMetricItem, now time.Time) []camMetricItem {
	camTelemetryHub.Lock()
	defer camTelemetryHub.Unlock()
	seen := map[string]bool{}
	for _, item := range items {
		seen[item.Account] = true
	}
	for account, r := range camTelemetryHub.entries {
		if !seen[account] && camRecent(r.cameraAt, now, 30*time.Second) {
			items = append(items, camMetricItem{Account: account})
		}
	}
	for i := range items {
		item := &items[i]
		item.RenderedFPS = nil
		item.LatencyMillis = nil
		item.ClockUncertaintyMillis = nil
		item.CapturedFPS = nil
		item.EncodedFPS = nil
		item.Width = 0
		item.Height = 0
		item.VideoCodec = "indisponible"
		item.AudioCodec = "indisponible"
		item.Battery = -1
		r := camTelemetryHub.entries[item.Account]
		if r == nil {
			continue
		}
		item.Connected = camRecent(r.cameraAt, now, 5*time.Second)
		item.VideoDrops = r.videoDrops + r.camera.VideoDrops
		item.AudioDrops = r.audioDrops + r.camera.AudioDrops
		item.Interruptions = r.camera.Interruptions + r.viewer.Interruptions
		if item.Connected {
			item.Battery = r.camera.Battery
			item.Width = r.camera.Width
			item.Height = r.camera.Height
			item.Quality = r.camera.Quality
			item.VideoCodec = r.camera.VideoCodec
			item.AudioCodec = r.camera.AudioCodec
			item.CapturedFPS = r.camera.CapturedFPS
			item.EncodedFPS = r.camera.EncodedFPS
			item.AudioState = r.camera.AudioState
			item.AudioPlayedSequence = r.camera.AudioPlayedSequence
			item.AudioPlaybackVerified = r.camera.AudioPlaybackVerified
			item.AudioPlaybackRouterUs = r.camera.AudioPlaybackRouterUs
		}
		if camRecent(r.viewerAt, now, 5*time.Second) {
			item.RenderedFPS = r.viewer.RenderedFPS
			item.LatencyMillis = r.viewer.LatencyMillis
			if r.camera.ClockUncertaintyMillis != nil && r.viewer.ClockUncertaintyMillis != nil {
				errorMs := *r.camera.ClockUncertaintyMillis + *r.viewer.ClockUncertaintyMillis
				item.ClockUncertaintyMillis = &errorMs
			}
		}
	}
	return items
}
func (m *TrafficManager) serveCamTelemetry(conn net.Conn, r *http.Request, ip string) bool {
	advice := r.URL.Path == "/api/v1/cam/advice"
	clock := r.URL.Path == "/api/v1/cam/clock"
	if !clock && !advice && r.URL.Path != "/api/v1/cam/telemetry" {
		return false
	}
	if r.TLS == nil || !samePortalOrigin(r.Host) {
		writeJSONStatus(conn, "403 Forbidden", map[string]any{"ok": false})
		return true
	}
	camera := r.URL.Query().Get("side") == "camera"
	account := strings.TrimSpace(r.URL.Query().Get("account"))
	if camera {
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
	if advice && r.Method == http.MethodGet && camera {
		camTelemetryHub.Lock()
		count := 0
		level := 4
		now := time.Now()
		for _, entry := range camTelemetryHub.entries {
			if now.Sub(entry.cameraAt) < 5*time.Second {
				count++
			}
		}
		if count >= 6 {
			level = 1
		} else if count >= 4 {
			level = 2
		} else if count >= 2 {
			level = 3
		}
		if own := camTelemetryHub.entries[account]; own != nil && now.Sub(own.viewerAt) < 5*time.Second {
			if own.viewer.LatencyMillis != nil && *own.viewer.LatencyMillis > 400 {
				level = 0
			}
			if own.camera.EncodedFPS != nil && *own.camera.EncodedFPS > 0 && *own.camera.EncodedFPS < 18 && level > 1 {
				level = 1
			}
			if own.viewer.RenderedFPS != nil && *own.viewer.RenderedFPS < 15 && level > 1 {
				level = 1
			}
		}
		camTelemetryHub.Unlock()
		writeJSONStatus(conn, "200 OK", map[string]any{"ok": true, "activeCameras": count, "maxQualityIndex": level, "availableLinkMeasured": false})
		return true
	}
	if clock && r.Method == http.MethodGet {
		writeJSONStatus(conn, "200 OK", map[string]any{"ok": true, "routerUs": time.Now().UnixMicro()})
		return true
	}
	if clock || advice || r.Method != http.MethodPost {
		writeJSONStatus(conn, "405 Method Not Allowed", map[string]any{"ok": false})
		return true
	}
	if account == "" || r.ContentLength < 0 || r.ContentLength > 4096 {
		writeJSONStatus(conn, "400 Bad Request", map[string]any{"ok": false})
		return true
	}
	b, e := io.ReadAll(io.LimitReader(r.Body, 4097))
	var payload camTelemetry
	if e != nil || len(b) > 4096 || json.Unmarshal(b, &payload) != nil {
		writeJSONStatus(conn, "400 Bad Request", map[string]any{"ok": false})
		return true
	}
	for _, v := range []*float64{payload.CapturedFPS, payload.EncodedFPS, payload.RenderedFPS} {
		if v != nil && (*v < 0 || *v > 240) {
			writeJSONStatus(conn, "400 Bad Request", map[string]any{"ok": false})
			return true
		}
	}
	if payload.Width < 0 || payload.Width > 3840 || payload.Height < 0 || payload.Height > 3840 || len(payload.AudioState) > 160 {
		writeJSONStatus(conn, "400 Bad Request", map[string]any{"ok": false})
		return true
	}
	if payload.Battery < 0 || payload.Battery > 100 {
		payload.Battery = -1
	}
	camTelemetrySet(account, camera, payload, time.Now())
	writeJSONStatus(conn, "200 OK", map[string]any{"ok": true})
	return true
}
