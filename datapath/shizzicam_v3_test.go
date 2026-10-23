package datapath

import (
	"bufio"
	"bytes"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"
)

func cam18Request(t *testing.T, m *TrafficManager, method, path, ip, token string, body []byte) (int, []byte) {
	t.Helper()
	a, b := net.Pipe()
	defer b.Close()
	_ = b.SetDeadline(time.Now().Add(3 * time.Second))
	req, _ := http.NewRequest(method, "https://192.0.2.1"+path, bytes.NewReader(body))
	req.TLS = &tls.ConnectionState{}
	req.Header.Set("X-Shizzi-Admin-Token", token)
	done := make(chan struct{})
	go func() { defer close(done); defer a.Close(); m.serveCameraAPI(a, req, ip) }()
	response, err := http.ReadResponse(bufio.NewReader(b), req)
	if err != nil {
		t.Fatal(err)
	}
	data, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	<-done
	return response.StatusCode, data
}
func TestCam18LocalAccountWithoutInternetVoucherAndPortability(t *testing.T) {
	m := newTrafficManager()
	config, _ := json.Marshal(portalConfig{Accounts: []PortalAccount{{Number: "8001", Name: "camera", Enabled: true, PinSalt: "salt", PinHash: hashPortalPin("salt", "2468")}}})
	m.setPortalConfig(true, string(config))
	if ok, msg := m.submitPortalAccountLogin("192.168.7.21", "8001", "2468"); !ok {
		t.Fatal(msg)
	}
	if ok, id := m.mediaAccountIdentity("192.168.7.21"); !ok || id != "8001" {
		t.Fatal("local access requires a voucher")
	}
	code, b := cam18Request(t, m, "GET", "/api/v1/cam/clock?side=camera", "192.168.7.21", "", nil)
	if code != 200 || !bytes.Contains(b, []byte("routerUs")) {
		t.Fatalf("local clock %d %s", code, b)
	}
	if ok, msg := m.submitPortalAccountLogin("192.168.7.22", "8001", "2468"); !ok {
		t.Fatal(msg)
	}
	if ok, _ := m.mediaAccountIdentity("192.168.7.21"); ok {
		t.Fatal("old session survived account portability")
	}
	if ok, _ := m.mediaAccountIdentity("192.168.7.22"); !ok {
		t.Fatal("new local session missing")
	}
	if m.portalAccounts["8001"].DataBalanceBytes != 0 {
		t.Fatal("local media changed voucher balance")
	}
}
func TestCam18ProtectedClockTelemetryAndControlValidation(t *testing.T) {
	m := newTrafficManager()
	m.adminConfig.Enabled = true
	m.adminSessions["token"] = &adminSession{Token: "token", IP: "192.168.7.31", LastSeenMillis: time.Now().UnixMilli()}
	for _, path := range []string{"/api/v1/cam/clock", "/api/v1/cam/metrics", "/api/v1/cam/audio-stream?account=8001"} {
		code, _ := cam18Request(t, m, "GET", path, "192.168.7.31", "", nil)
		if code != 401 {
			t.Fatalf("public media/stats %s %d", path, code)
		}
	}
	code, _ := cam18Request(t, m, "GET", "/api/v1/cam/clock", "192.168.7.31", "token", nil)
	if code != 200 {
		t.Fatal("admin clock refused")
	}
	for _, q := range []string{"144p", "240p", "360p", "480p", "720p", "auto"} {
		if !camRtcAllowedQuality(q) {
			t.Fatal(q)
		}
	}
	if !camRtcValidControl("alarm", "start") || !camRtcValidControl("alarm", "stop") || camRtcValidControl("alarm", "forever") {
		t.Fatal("alarm command bounds")
	}
}
func TestCam18OpusWireConfigurationBoundsAndShortWrites(t *testing.T) {
	p := camOpusPacket{data: []byte{1, 2, 3}, ptsUs: 345, sequence: 7, flags: 2}
	var wire bytes.Buffer
	if e := camOpusWrite(&wire, p); e != nil {
		t.Fatal(e)
	}
	got, e := camOpusRead(&wire)
	if e != nil || got.ptsUs != 345 || got.sequence != 7 || got.flags != 2 || !bytes.Equal(got.data, p.data) {
		t.Fatal("Opus wire mismatch", e)
	}
	for _, n := range []int{0, 4097} {
		var b bytes.Buffer
		b.Write([]byte{byte(n >> 24), byte(n >> 16), byte(n >> 8), byte(n)})
		b.Write(make([]byte, 17))
		if _, e := camOpusRead(&b); e == nil {
			t.Fatal("unbounded Opus length accepted", n)
		}
	}
}
func TestCam18OneToEightIndependentRelaysAndSlowViewerRecovery(t *testing.T) {
	for _, count := range []int{1, 2, 4, 6, 8} {
		t.Run(fmt.Sprint(count), func(t *testing.T) {
			camVideoHub.Lock()
			camVideoHub.cameras = map[string]*camH264Channel{}
			camVideoHub.Unlock()
			camOpusHub.Lock()
			camOpusHub.channels = map[string]*camOpusChannel{}
			camOpusHub.Unlock()
			now := time.Now()
			channels := make([]chan camH264Packet, count)
			audios := make([]chan camOpusPacket, count)
			for i := 0; i < count; i++ {
				id := fmt.Sprintf("cam18-%d", i)
				camH264Put(id, camH264Packet{data: []byte{0, 0, 0, 1, 0x67}, width: 1280, height: 720, flags: 2}, now)
				ch, initial, ok := camH264Subscribe(id)
				if !ok || len(initial) != 1 {
					t.Fatal("bootstrap")
				}
				channels[i] = ch
				audio, _, ok := camOpusSubscribe("listen:" + id)
				if !ok {
					t.Fatal("Opus subscribe")
				}
				audios[i] = audio
				defer camH264Unsubscribe(id, ch)
				defer camOpusUnsubscribe("listen:"+id, audio)
			}
			for frame := 0; frame < 100; frame++ {
				for i := 0; i < count; i++ {
					id := fmt.Sprintf("cam18-%d", i)
					flags := byte(0)
					if frame%22 == 0 {
						flags = 1
					}
					p := camH264Packet{data: []byte{byte(i), byte(frame)}, width: 1280, height: 720, flags: flags}
					if !camH264Put(id, p, now) {
						t.Fatal("video rejected")
					}
					if !camOpusPut("listen:"+id, camOpusPacket{data: []byte{byte(i), 1}, ptsUs: uint64(now.UnixMicro())}, now) {
						t.Fatal("audio rejected")
					}
					if i > 0 || count == 1 {
						select {
						case got := <-channels[i]:
							if got.data[0] != byte(i) {
								t.Fatal("cross-camera video")
							}
						case <-time.After(time.Second):
							t.Fatal("healthy stream blocked by slow viewer")
						}
					}
					got := <-audios[i]
					if got.data[0] != byte(i) {
						t.Fatal("cross-camera audio")
					}
				}
			}
			if count > 1 {
				ch := channels[0]
			drain:
				for {
					select {
					case <-ch:
					default:
						break drain
					}
				}
				camH264Put("cam18-0", camH264Packet{data: []byte{0, 201}, width: 1280, height: 720, flags: 1}, now)
				pkt := <-ch
				if pkt.flags != 1 {
					t.Fatal("decoder recovery must start on IDR")
				}
			}
		})
	}
}
func TestCam18TelemetryNeverInventsRenderedFPSAndExpiresReports(t *testing.T) {
	camTelemetryHub.Lock()
	camTelemetryHub.entries = map[string]*camTelemetryRecord{}
	camTelemetryHub.Unlock()
	defer func() {
		camTelemetryHub.Lock()
		camTelemetryHub.entries = map[string]*camTelemetryRecord{}
		camTelemetryHub.Unlock()
	}()
	now := time.Now()
	fps := 21.0
	latency := 185.0
	camTelemetrySet("telemetry18", true, camTelemetry{Battery: 72, Width: 256, Height: 144, VideoCodec: "H264", CapturedFPS: &fps}, now)
	items := camEnrichMetrics([]camMetricItem{{Account: "telemetry18"}}, now)
	if len(items) != 1 || items[0].RenderedFPS != nil || !items[0].Connected {
		t.Fatal("fabricated render rate")
	}
	camTelemetrySet("telemetry18", false, camTelemetry{RenderedFPS: &fps, LatencyMillis: &latency}, now)
	if camEnrichMetrics(items, now)[0].RenderedFPS == nil {
		t.Fatal("render report discarded")
	}
	old := camEnrichMetrics(items, now.Add(6*time.Second))[0]
	if old.Connected || old.RenderedFPS != nil {
		t.Fatal("stale telemetry presented as current")
	}
}
func TestCam18MalformedTelemetryRejected(t *testing.T) {
	m := newTrafficManager()
	m.adminConfig.Enabled = true
	m.adminSessions["token"] = &adminSession{Token: "token", IP: "192.168.7.31", LastSeenMillis: time.Now().UnixMilli()}
	for _, body := range []string{`{"renderedFps":99999}`, `{"width":-1}`, "<html>error</html>", strings.Repeat("x", 4097)} {
		code, _ := cam18Request(t, m, "POST", "/api/v1/cam/telemetry?side=viewer&account=8001", "192.168.7.31", "token", []byte(body))
		if code != 400 {
			t.Fatalf("invalid report accepted %d", code)
		}
	}
}
