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

func TestClientAppDownloadUsesNativeBrowserStreaming(t *testing.T) {
	page := injectClientAppDownload(
		`<html><body><a class="download-button" href="http://192.0.2.1/shizzi-plus.apk" download="Shizzi-Plus-0.2.6-test.apk">Télécharger</a><div id="shizzi-download-status"></div></body></html>`,
	)

	for _, expected := range []string{
		`href="http://192.0.2.1/shizzi-plus.apk"`,
		`download="Shizzi-Plus-0.2.6-test.apk"`,
		`Téléchargement direct depuis le routeur Shizzi`,
		`shizziNativeDownload`,
	} {
		if !strings.Contains(page, expected) {
			t.Fatalf("native local download path missing %q", expected)
		}
	}

	for _, forbidden := range []string{
		"event.preventDefault()",
		"fetch(href",
		"response.blob()",
		"URL.createObjectURL",
		"window.location.assign",
	} {
		if strings.Contains(page, forbidden) {
			t.Fatalf("download path still buffers APK in JavaScript via %q", forbidden)
		}
	}
}

func TestClientAppDownloadScriptIsNotInjectedWithoutDownloadLink(t *testing.T) {
	page := `<html><body>Portail sans application</body></html>`
	if actual := injectClientAppDownload(page); actual != page {
		t.Fatal("download script injected when Shizzi+ is unavailable")
	}
}


func TestPortalShowsDirect192DownloadLink(t *testing.T) {
	manager := newTrafficManager()
	manager.mu.Lock()
	manager.portalClientApp = portalClientApp{
		Available: true,
		Version:   "0.2.6-test",
		FileName:  "Shizzi-Plus-0.2.6-test.apk",
		SizeBytes: 123456,
		SHA256:    "0123456789abcdef",
	}
	manager.mu.Unlock()

	server, client := net.Pipe()
	done := make(chan struct{})
	go func() {
		defer close(done)
		manager.servePortal(server, "192.168.7.66")
	}()

	_ = client.SetDeadline(time.Now().Add(3 * time.Second))
	_, _ = fmt.Fprint(client, "GET / HTTP/1.1\r\nHost: 192.0.2.1\r\nConnection: close\r\n\r\n")
	response, err := io.ReadAll(client)
	_ = client.Close()
	if err != nil {
		t.Fatalf("read portal: %v", err)
	}
	<-done

	page := string(response)
	for _, expected := range []string{
		`http://192.0.2.1/shizzi-plus.apk`,
		`Shizzi+ 0.2.6-test`,
		`Shizzi-Plus-0.2.6-test.apk`,
	} {
		if !strings.Contains(page, expected) {
			t.Fatalf("portal direct download missing %q: %s", expected, page)
		}
	}
}

func TestDirectShizziPlusAliasForwardsRangeAndStreamsResponse(t *testing.T) {
	backend, err := net.Listen("tcp", clientAppBridgeAddress)
	if err != nil {
		t.Fatalf("listen Shizzi+ backend: %v", err)
	}
	t.Cleanup(func() { _ = backend.Close() })

	headersSeen := make(chan string, 1)
	doneBackend := make(chan struct{})
	go func() {
		defer close(doneBackend)
		conn, acceptErr := backend.Accept()
		if acceptErr != nil {
			return
		}
		defer conn.Close()

		reader := bufio.NewReader(conn)
		var headers strings.Builder
		for {
			line, readErr := reader.ReadString('\n')
			if readErr != nil {
				return
			}
			headers.WriteString(line)
			if line == "\r\n" {
				break
			}
		}
		headersSeen <- headers.String()

		body := "APK-BYTES-5678"
		_, _ = fmt.Fprintf(
			conn,
			"HTTP/1.1 206 Partial Content\r\n"+
				"Content-Type: application/vnd.android.package-archive\r\n"+
				"Content-Disposition: attachment; filename=\"Shizzi-Plus-0.2.6-test.apk\"\r\n"+
				"Accept-Ranges: bytes\r\n"+
				"Content-Range: bytes 5-18/19\r\n"+
				"Content-Length: %d\r\n"+
				"Connection: close\r\n\r\n%s",
			len(body),
			body,
		)
	}()

	manager := newTrafficManager()
	manager.mu.Lock()
	manager.portalClientApp = portalClientApp{
		Available: true,
		Version:   "0.2.6-test",
		FileName:  "Shizzi-Plus-0.2.6-test.apk",
		SizeBytes: 19,
		SHA256:    "abc",
	}
	manager.mu.Unlock()

	server, client := net.Pipe()
	donePortal := make(chan struct{})
	go func() {
		defer close(donePortal)
		manager.servePortal(server, "192.168.7.66")
	}()

	_ = client.SetDeadline(time.Now().Add(5 * time.Second))
	_, err = fmt.Fprint(
		client,
		"GET /shizzi-plus.apk HTTP/1.1\r\n"+
			"Host: 192.0.2.1\r\n"+
			"Range: bytes=5-\r\n"+
			"User-Agent: Android-DownloadManager\r\n"+
			"Connection: close\r\n\r\n",
	)
	if err != nil {
		t.Fatalf("write direct download request: %v", err)
	}

	response, readErr := io.ReadAll(client)
	_ = client.Close()
	if readErr != nil {
		t.Fatalf("read direct download response: %v", readErr)
	}
	<-donePortal
	<-doneBackend

	backendRequest := <-headersSeen
	if !strings.Contains(backendRequest, "Range: bytes=5-") {
		t.Fatalf("Range header was not forwarded: %q", backendRequest)
	}
	if !strings.Contains(backendRequest, "User-Agent: Android-DownloadManager") {
		t.Fatalf("Android user agent was not forwarded: %q", backendRequest)
	}

	actual := string(response)
	for _, expected := range []string{
		"206 Partial Content",
		"application/vnd.android.package-archive",
		`filename="Shizzi-Plus-0.2.6-test.apk"`,
		"Accept-Ranges: bytes",
		"APK-BYTES-5678",
	} {
		if !strings.Contains(actual, expected) {
			t.Fatalf("direct APK response missing %q: %q", expected, actual)
		}
	}
}
