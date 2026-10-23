package datapath

import (
	"bytes"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"testing"
	"time"
)

func TestCam18PersistentTLSChunkedMediaPaths(t *testing.T) {
	m := newTrafficManager()
	config, _ := json.Marshal(portalConfig{Accounts: []PortalAccount{{Number: "8818", Enabled: true, PinSalt: "s", PinHash: hashPortalPin("s", "1234")}}})
	m.setPortalConfig(true, string(config))
	if ok, msg := m.submitPortalAccountLogin("192.168.7.81", "8818", "1234"); !ok {
		t.Fatal(msg)
	}
	m.adminConfig.Enabled = true
	m.adminSessions["tls-token"] = &adminSession{Token: "tls-token", IP: "192.168.7.82", LastSeenMillis: time.Now().UnixMilli()}
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
	roots := x509.NewCertPool()
	roots.AppendCertsFromPEM([]byte(identity.CertificatePEM))
	transport := &http.Transport{TLSClientConfig: &tls.Config{RootCAs: roots, ServerName: "192.0.2.1"}, DisableKeepAlives: true}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 5 * time.Second}
	start := func(ip string) string {
		listener, e := net.Listen("tcp", "127.0.0.1:0")
		if e != nil {
			t.Fatal(e)
		}
		t.Cleanup(func() { listener.Close() })
		go func() {
			for {
				conn, e := listener.Accept()
				if e != nil {
					return
				}
				go m.serveTLS(conn, ip)
			}
		}()
		return "https://" + listener.Addr().String()
	}
	camera, viewer := start("192.168.7.81"), start("192.168.7.82")
	camVideoHub.Lock()
	delete(camVideoHub.cameras, "8818")
	camVideoHub.Unlock()
	camOpusHub.Lock()
	delete(camOpusHub.channels, "listen:8818")
	camOpusHub.Unlock()
	request := func(method, url string, body io.Reader) *http.Request {
		req, e := http.NewRequest(method, url, body)
		if e != nil {
			t.Fatal(e)
		}
		req.Host = "192.0.2.1"
		if bytes.HasPrefix([]byte(url), []byte(viewer)) {
			req.Header.Set("X-Shizzi-Admin-Token", "tls-token")
		}
		return req
	}
	for _, audio := range []bool{false, true} {
		t.Run(fmt.Sprintf("opus=%v", audio), func(t *testing.T) {
			path, contentType := "/api/v1/cam/video-stream", "application/x-shizzicam-h264"
			if audio {
				path = "/api/v1/cam/audio-stream"
				contentType = "application/x-shizzicam-opus-v3"
			}
			reader, writer := io.Pipe()
			defer writer.Close()
			upload := request("POST", camera+path, reader)
			upload.Header.Set("Content-Type", contentType)
			uploaded := make(chan struct{})
			uploadError := make(chan string, 1)
			go func() {
				defer close(uploaded)
				response, e := client.Do(upload)
				if e != nil {
					uploadError <- e.Error()
				} else {
					b, _ := io.ReadAll(response.Body)
					uploadError <- fmt.Sprintf("status=%d body=%s", response.StatusCode, b)
					response.Body.Close()
				}
			}()
			// HTTP clients may buffer their request headers until the first body chunk.
			initialWritten := make(chan error, 1)
			go func() {
				if audio {
					initialWritten <- camOpusWrite(writer, camOpusPacket{data: []byte{9, 8}, flags: 2, ptsUs: uint64(time.Now().UnixMicro())})
				} else {
					var b bytes.Buffer
					for _, v := range []any{uint32(5), uint16(256), uint16(144), uint64(time.Now().UnixMicro()), byte(2)} {
						_ = binary.Write(&b, binary.BigEndian, v)
					}
					b.Write([]byte{0, 0, 0, 1, 0x67})
					_, e := writer.Write(b.Bytes())
					initialWritten <- e
				}
			}()
			deadline := time.Now().Add(6 * time.Second)
			for {
				ready := false
				if audio {
					camOpusHub.Lock()
					c := camOpusHub.channels["listen:8818"]
					ready = c != nil && c.uploading
					camOpusHub.Unlock()
				} else {
					camVideoHub.Lock()
					c := camVideoHub.cameras["8818"]
					ready = c != nil && c.uploading
					camVideoHub.Unlock()
				}
				if ready {
					break
				}
				if time.Now().After(deadline) {
					select {
					case problem := <-uploadError:
						t.Fatal("publisher: " + problem)
					default:
						t.Fatal("chunked publisher never reached router")
					}
				}
				time.Sleep(time.Millisecond)
			}
			response, e := client.Do(request("GET", viewer+path+"?account=8818", nil))
			if e != nil {
				t.Fatal(e)
			}
			defer response.Body.Close()
			if response.StatusCode != 200 {
				t.Fatal(response.StatusCode)
			}
			sent := make(chan error, 1)
			go func() {
				if audio {
					e := camOpusWrite(writer, camOpusPacket{data: []byte{7, 6, 5}, ptsUs: uint64(time.Now().UnixMicro())})
					sent <- e
				} else {
					for _, flags := range []byte{1} {
						var b bytes.Buffer
						for _, v := range []any{uint32(5), uint16(256), uint16(144), uint64(time.Now().UnixMicro()), flags} {
							_ = binary.Write(&b, binary.BigEndian, v)
						}
						b.Write([]byte{0, 0, 0, 1, 0x65})
						if _, e := writer.Write(b.Bytes()); e != nil {
							sent <- e
							return
						}
					}
					sent <- nil
				}
			}()
			if audio {
				first, e := camOpusRead(response.Body)
				if e != nil || first.flags != 2 {
					t.Fatal("Opus configuration lost", e)
				}
				second, e := camOpusRead(response.Body)
				if e != nil || !bytes.Equal(second.data, []byte{7, 6, 5}) || second.sequence <= first.sequence {
					t.Fatal("Opus media lost", e)
				}
			} else {
				first, e := camH264Read(response.Body)
				if e != nil || first.flags != 2 {
					t.Fatal("AVC configuration lost", e)
				}
				second, e := camH264Read(response.Body)
				if e != nil || second.flags != 1 || second.width != 256 || second.height != 144 {
					t.Fatal("AVC keyframe lost", e)
				}
			}
			if e := <-initialWritten; e != nil {
				t.Fatal(e)
			}
			if e := <-sent; e != nil {
				t.Fatal(e)
			}
			writer.Close()
			response.Body.Close()
			select {
			case <-uploaded:
			case <-time.After(2 * time.Second):
				t.Fatal("upload did not close")
			}
		})
	}
}
