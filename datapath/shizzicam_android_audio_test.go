package datapath

import (
	"bytes"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

// CI-only local router. Android's real codecs and HTTP client connect through 10.0.2.2.
func TestCamAndroidAudioFixture(t *testing.T) {
	output := os.Getenv("SHIZZI_ANDROID_AUDIO_FIXTURE_OUTPUT")
	if output == "" {
		t.Skip("Android fixture is started only by the instrumentation job")
	}
	m := newTrafficManager()
	config, _ := json.Marshal(portalConfig{Accounts: []PortalAccount{{Number: "8819", Enabled: true, PinSalt: "s", PinHash: hashPortalPin("s", "1234")}}})
	m.setPortalConfig(true, string(config))
	if ok, msg := m.submitPortalAccountLogin("192.168.7.91", "8819", "1234"); !ok {
		t.Fatal(msg)
	}
	m.adminConfig.Enabled = true
	m.adminSessions["android-audio-token"] = &adminSession{Token: "android-audio-token", IP: "192.168.7.92", LastSeenMillis: time.Now().UnixMilli()}
	identityJSON, err := GeneratePortalTLSIdentity()
	if err != nil {
		t.Fatal(err)
	}
	var identity portalTLSIdentity
	if err = json.Unmarshal([]byte(identityJSON), &identity); err != nil {
		t.Fatal(err)
	}
	cert, err := tls.X509KeyPair([]byte(identity.CertificatePEM), []byte(identity.PrivateKeyPEM))
	if err != nil {
		t.Fatal(err)
	}
	m.tlsCertificate = &cert
	var mu sync.Mutex
	active := map[net.Conn]bool{}
	t.Cleanup(func() {
		mu.Lock()
		defer mu.Unlock()
		for c := range active {
			c.Close()
		}
	})
	start := func(ip string) (int, string) {
		listener, e := net.Listen("tcp", "127.0.0.1:0")
		if e != nil {
			t.Fatal(e)
		}
		t.Cleanup(func() { listener.Close() })
		go func() {
			for {
				c, e := listener.Accept()
				if e != nil {
					return
				}
				mu.Lock()
				active[c] = true
				mu.Unlock()
				go func() { m.serveTLS(c, ip); mu.Lock(); delete(active, c); mu.Unlock() }()
			}
		}()
		port := listener.Addr().(*net.TCPAddr).Port
		return port, "https://127.0.0.1:" + strconv.Itoa(port)
	}
	cameraPort, camera := start("192.168.7.91")
	viewerPort, viewer := start("192.168.7.92")
	roots := x509.NewCertPool()
	roots.AppendCertsFromPEM([]byte(identity.CertificatePEM))
	transport := &http.Transport{TLSClientConfig: &tls.Config{RootCAs: roots, ServerName: "192.0.2.1"}, DisableKeepAlives: true}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 5 * time.Second}
	post := func(url, body string) map[string]any {
		req, e := http.NewRequest("POST", url, strings.NewReader(body))
		if e != nil {
			t.Fatal(e)
		}
		req.Host = "192.0.2.1"
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Shizzi-Admin-Token", "android-audio-token")
		response, e := client.Do(req)
		if e != nil {
			t.Fatal(e)
		}
		defer response.Body.Close()
		data, _ := io.ReadAll(response.Body)
		if response.StatusCode != 200 {
			t.Fatalf("fixture setup HTTP %d: %s", response.StatusCode, data)
		}
		var result map[string]any
		if e = json.Unmarshal(data, &result); e != nil {
			t.Fatal(e)
		}
		return result
	}
	post(camera+"/api/v1/cam/hello?side=camera", `{"battery":80,"audio":true,"quality":"360p"}`)
	session := post(viewer+"/api/v1/cam/watch?side=viewer", `{"account":"8819"}`)["session"].(string)
	block, _ := pem.Decode([]byte(identity.CertificatePEM))
	metadata, _ := json.Marshal(map[string]any{"cameraPort": cameraPort, "viewerPort": viewerPort, "account": "8819", "session": session, "adminToken": "android-audio-token", "certificateDerBase64": base64.StdEncoding.EncodeToString(block.Bytes)})
	if err = os.WriteFile(output, metadata, 0600); err != nil {
		t.Fatal(err)
	}
	t.Log("Android audio fixture ready; two loopback TLS listeners; authenticated local account without voucher")
	until := time.Now().Add(6 * time.Minute)
	for time.Now().Before(until) {
		if _, e := os.Stat(output + ".stop"); e == nil {
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatal("Android audio fixture timed out")
}

func TestCamInterphonePersistentTLSDelivery(t *testing.T) {
	m := newTrafficManager()
	config, _ := json.Marshal(portalConfig{Accounts: []PortalAccount{{Number: "8820", Enabled: true, PinSalt: "s", PinHash: hashPortalPin("s", "1234")}}})
	m.setPortalConfig(true, string(config))
	if ok, msg := m.submitPortalAccountLogin("192.168.7.93", "8820", "1234"); !ok {
		t.Fatal(msg)
	}
	m.adminConfig.Enabled = true
	m.adminSessions["talk-tls-token"] = &adminSession{Token: "talk-tls-token", IP: "192.168.7.94", LastSeenMillis: time.Now().UnixMilli()}
	camRtc.Lock()
	camRtc.cameras["8820"] = &camRtcSession{LastSeen: time.Now(), ViewerSession: "talk-session", ViewerIP: "192.168.7.94"}
	camRtc.Unlock()
	raw, e := GeneratePortalTLSIdentity()
	if e != nil {
		t.Fatal(e)
	}
	var identity portalTLSIdentity
	json.Unmarshal([]byte(raw), &identity)
	cert, e := tls.X509KeyPair([]byte(identity.CertificatePEM), []byte(identity.PrivateKeyPEM))
	if e != nil {
		t.Fatal(e)
	}
	m.tlsCertificate = &cert
	roots := x509.NewCertPool()
	roots.AppendCertsFromPEM([]byte(identity.CertificatePEM))
	tr := &http.Transport{TLSClientConfig: &tls.Config{RootCAs: roots, ServerName: "192.0.2.1"}, DisableKeepAlives: true}
	defer tr.CloseIdleConnections()
	client := &http.Client{Transport: tr, Timeout: 5 * time.Second}
	start := func(ip string) string {
		l, e := net.Listen("tcp", "127.0.0.1:0")
		if e != nil {
			t.Fatal(e)
		}
		t.Cleanup(func() { l.Close() })
		go func() {
			for {
				c, e := l.Accept()
				if e != nil {
					return
				}
				go m.serveTLS(c, ip)
			}
		}()
		return "https://" + l.Addr().String()
	}
	camera, viewer := start("192.168.7.93"), start("192.168.7.94")
	get, _ := http.NewRequest("GET", camera+"/api/v1/cam/talk-stream", nil)
	get.Host = "192.0.2.1"
	down, e := client.Do(get)
	if e != nil {
		t.Fatal(e)
	}
	defer down.Body.Close()
	if down.StatusCode != 200 {
		t.Fatal(down.StatusCode)
	}
	pipe, writer := io.Pipe()
	defer writer.Close()
	post, _ := http.NewRequest("POST", viewer+"/api/v1/cam/talk-stream?account=8820&session=talk-session", pipe)
	post.Host = "192.0.2.1"
	post.Header.Set("X-Shizzi-Admin-Token", "talk-tls-token")
	post.Header.Set("Content-Type", "application/x-shizzicam-opus-v3")
	finished := make(chan struct{})
	go func() {
		defer close(finished)
		r, _ := client.Do(post)
		if r != nil {
			r.Body.Close()
		}
	}()
	sent := make(chan error, 1)
	go func() {
		if e := camOpusWrite(writer, camOpusPacket{data: []byte{1, 2, 3}, flags: 2, ptsUs: uint64(time.Now().UnixMicro())}); e != nil {
			sent <- e
			return
		}
		sent <- camOpusWrite(writer, camOpusPacket{data: []byte{4, 5, 6}, ptsUs: uint64(time.Now().UnixMicro())})
	}()
	cfg, e := camOpusRead(down.Body)
	if e != nil || cfg.flags != 2 {
		t.Fatalf("interphone config: %+v %v", cfg, e)
	}
	media, e := camOpusRead(down.Body)
	if e != nil || !bytes.Equal(media.data, []byte{4, 5, 6}) {
		t.Fatalf("interphone media: %+v %v", media, e)
	}
	if e = <-sent; e != nil {
		t.Fatal(e)
	}
	writer.Close()
	down.Body.Close()
	select {
	case <-finished:
	case <-time.After(2 * time.Second):
		t.Fatal("interphone upload did not terminate")
	}
	t.Log(fmt.Sprintf("Verified viewer -> router -> camera TLS media, sequence %d", media.sequence))
}
