package datapath

import (
	"bufio"
	"fmt"
	"io"
	"net"
	"strings"
	"testing"
	"time"
)

func TestClientAppDownloadProxyReturnsCompleteAPK(t *testing.T) {
	backend, err := net.Listen("tcp", clientAppBridgeAddress)
	if err != nil {
		t.Fatalf("listen Shizzi+ backend: %v", err)
	}
	t.Cleanup(func() { _ = backend.Close() })

	apk := []byte("virtual-shizzi-plus-apk")
	backendDone := make(chan struct{})
	go func() {
		defer close(backendDone)
		conn, acceptErr := backend.Accept()
		if acceptErr != nil {
			return
		}
		defer conn.Close()

		reader := bufio.NewReader(conn)
		for {
			line, readErr := reader.ReadString('\n')
			if readErr != nil {
				return
			}
			if line == "\r\n" {
				break
			}
		}

		_, _ = fmt.Fprintf(
			conn,
			"HTTP/1.1 200 OK\r\n"+
				"Content-Type: application/vnd.android.package-archive\r\n"+
				"Content-Disposition: attachment; filename=\"Shizzi-Plus-0.2.4.apk\"\r\n"+
				"Content-Length: %d\r\n"+
				"X-Shizzi-Version: 0.2.4\r\n"+
				"Cache-Control: no-store\r\n"+
				"Connection: close\r\n\r\n",
			len(apk),
		)
		_, _ = conn.Write(apk)
	}()

	manager := newTrafficManager()
	manager.portalClientApp = portalClientApp{
		Available: true,
		Version:   "0.2.4",
		FileName:  "Shizzi-Plus-0.2.4.apk",
		SizeBytes: int64(len(apk)),
		SHA256:    "virtual-sha256",
	}

	server, client := net.Pipe()
	done := make(chan struct{})
	go func() {
		defer close(done)
		manager.serveClientAppDownload(server, "GET")
		_ = server.Close()
	}()

	_ = client.SetDeadline(time.Now().Add(5 * time.Second))
	response, readErr := io.ReadAll(client)
	_ = client.Close()
	if readErr != nil {
		t.Fatalf("read Shizzi+ response: %v", readErr)
	}
	<-done
	<-backendDone

	text := string(response)
	for _, expected := range []string{
		"HTTP/1.1 200 OK",
		"Content-Type: application/vnd.android.package-archive",
		"Content-Disposition: attachment; filename=\"Shizzi-Plus-0.2.4.apk\"",
		"X-Shizzi-Version: 0.2.4",
		string(apk),
	} {
		if !strings.Contains(text, expected) {
			t.Fatalf("download response missing %q: %q", expected, text)
		}
	}
	if !strings.HasSuffix(text, string(apk)) {
		t.Fatal("downloaded APK body was truncated")
	}
}
