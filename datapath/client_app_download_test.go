package datapath

import (
	"strings"
	"testing"
)

func TestClientAppDownloadUsesBrowserFetchBeforeSave(t *testing.T) {
	page := injectClientAppDownload(
		`<html><body><a class="download-button" href="/download/shizzi-plus.apk" download="Shizzi-Plus-0.2.4.apk">Télécharger</a><div id="shizzi-download-status"></div></body></html>`,
	)

	for _, expected := range []string{
		"fetch(href",
		"response.blob()",
		"Content-Length",
		"URL.createObjectURL(blob)",
		"save.download=fileName",
		"Shizzi+ reçu depuis le Wi-Fi local",
	} {
		if !strings.Contains(page, expected) {
			t.Fatalf("local browser download script missing %q", expected)
		}
	}
}

func TestClientAppDownloadScriptIsNotInjectedWithoutDownloadLink(t *testing.T) {
	page := `<html><body>Portail sans application</body></html>`
	if actual := injectClientAppDownload(page); actual != page {
		t.Fatal("download script injected when Shizzi+ is unavailable")
	}
}
