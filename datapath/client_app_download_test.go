package datapath

import (
	"strings"
	"testing"
)

func TestClientAppDownloadUsesNativeBrowserStreaming(t *testing.T) {
	page := injectClientAppDownload(
		`<html><body><a class="download-button" href="/download/shizzi-plus.apk" download="Shizzi-Plus-0.2.4.apk">Télécharger</a><div id="shizzi-download-status"></div></body></html>`,
	)

	for _, expected := range []string{
		`href="/download/shizzi-plus.apk"`,
		`download="Shizzi-Plus-0.2.4.apk"`,
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
