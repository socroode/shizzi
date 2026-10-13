package core

import (
    "errors"
    "html/template"
    "net"
    "net/http"
    "net/netip"
    "strings"
)

// PortalEndpoint deliberately separates the PUBLIC captive landing page from
// account privileges. Requests received as shared 192.0.2.2 may read GET /
// but may NEVER acquire an Internet session from that shared address.
//
// The physical-hotspot listener will reuse this handler with a verifier and
// the original, un-NATed remote address. It must never trust proxy headers.
type PortalEndpoint struct {
    Sessions *Registry
    Verifier PINVerifier
    // LoginAction points at the bound physical hotspot listener, not at
    // the translated 192.0.2.2 tunnel address.
    LoginAction string
    LocalBase string
}

const publicPortalHTML = `<!doctype html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Shizzi Hotspot — Accueil</title>
<style>
*{box-sizing:border-box}
body{margin:0;min-height:100vh;background:radial-gradient(circle at 15% 0%,#123f50 0,#081724 42%,#05101b 100%);font-family:system-ui,-apple-system,Segoe UI,sans-serif;color:#e2e8f0;padding:28px 12px}
.card{max-width:540px;margin:0 auto;border:1px solid #22d3ee55;border-radius:22px;background:#0d2235;box-shadow:0 28px 85px #0006;padding:25px}
.eyebrow{font-size:11px;font-weight:800;letter-spacing:.15em;color:#4adecc}
h1{font-size:30px;line-height:1.15;margin:12px 0;color:#f0fdff}
h2{font-size:18px;margin:0 0 7px}
.sub{color:#a9c1cc;font-size:14px;line-height:1.5}
label{display:block;margin:14px 0 6px;color:#d8eaf1;font-weight:700;font-size:13px}
input{background:#071727;color:#f6ffff;width:100%;border:1px solid #316b82;border-radius:12px;padding:13px;font-size:16px}
input:focus{outline:2px solid #38bdf8}
button{display:block;background:linear-gradient(90deg,#38bdf8,#34d399);color:#06202a;padding:14px 16px;border:0;border-radius:14px;font-weight:900;width:100%;margin-top:18px;font-size:16px}
.panel{margin-top:20px;padding:16px;border:1px solid #22d3ee55;border-radius:18px;background:#071b2a}
.panel p{margin:0;color:#cbd5e1;font-size:13px;line-height:1.45}
.muted{font-size:12px;color:#94a3b8;line-height:1.6}
a{color:#67e8f9;font-weight:700;display:inline-block;margin-top:8px}
</style></head><body><main class="card">
<div class="eyebrow">PORTAIL LOCAL · SHIZZI</div>
<h1>Bienvenue sur Shizzi</h1>
<p class="sub">Connecté au Wi-Fi Shizzi ? La page d'accueil reste accessible même sans forfait Internet.</p>
<section class="panel"><div class="eyebrow">COMPTE SHIZZI</div>
<h2>Se connecter</h2>
<form method="post" action="{{.LoginAction}}" autocomplete="on">
<label for="account">Numéro de compte</label>
<input id="account" name="account" inputmode="numeric" pattern="[0-9]+" autocomplete="username" required maxlength="40">
<label for="pin">Code PIN</label>
<input id="pin" name="pin" type="password" autocomplete="current-password" required maxlength="128">
<button type="submit">Se connecter</button></form>
<p class="muted">Le numéro de compte et le PIN valident la session. Seul un voucher Internet actif autorise ensuite Internet.</p></section>
<section class="panel"><div class="eyebrow">MEDIA LOCAL</div><h2>Shizzi Media</h2>
<p>Films, séries et musique avec un voucher Média actif.</p>
{{if .LocalBase}}<a href="{{.LocalBase}}/media/">Ouvrir la médiathèque</a>{{else}}<p class="muted">Connexion au hotspot en cours de vérification.</p>{{end}}</section>
<section class="panel"><div class="eyebrow">MESSAGERIE LOCALE</div><h2>Messagerie Shizzi</h2>
<p>Messages et appels entre comptes connectés au Wi-Fi Shizzi.</p>
{{if .LocalBase}}<a href="{{.LocalBase}}/chat/">Ouvrir la messagerie</a>{{end}}</section>
<section class="panel"><div class="eyebrow">APPLICATIONS LOCALES</div><h2>Shizzi+ · Admin</h2>
<p>Téléchargements disponibles sans voucher Internet.</p>
<p><a href="/shizzi-plus.apk">Télécharger Shizzi+</a> · <a href="/shizzi-admin.apk">Télécharger Shizzi Admin</a></p></section>
<p class="muted">Cette reconstruction est en cours de validation : aucun accès Internet n'est attribué à un appareil non authentifié.</p>
</main></body></html>`

var portalPage = template.Must(template.New("login").Parse(publicPortalHTML))

func (p PortalEndpoint) ServeHTTP(w http.ResponseWriter, r *http.Request) {
    w.Header().Set("Cache-Control", "no-store, max-age=0")
    w.Header().Set("X-Content-Type-Options", "nosniff")
    w.Header().Set("X-Frame-Options", "DENY")
    csp := "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'"
    // The public TUN page deliberately POSTs to the private SoftAP gateway,
    // because only that real TCP peer address can identify a Wi-Fi station.
    // The action is constructed by the trusted server from a numeric gateway.
    if strings.HasPrefix(p.LoginAction, "http://") &&
        strings.HasSuffix(p.LoginAction, ":8093/login") {
        origin := strings.TrimSuffix(p.LoginAction, "/login")
        csp = "default-src 'none'; style-src 'unsafe-inline'; form-action 'self' " +
            origin + "; frame-ancestors 'none'"
    }
    w.Header().Set("Content-Security-Policy", csp)

    switch {
    case r.URL.Path == "/" && (r.Method == http.MethodGet || r.Method == http.MethodHead):
        w.Header().Set("Content-Type", "text/html; charset=utf-8")
        if r.Method == http.MethodHead { w.WriteHeader(http.StatusOK); return }
        action := p.LoginAction
        if action == "" { action = "/login" }
        local := p.LocalBase
        if local == "" && strings.HasPrefix(p.LoginAction,"http://") {
            local = strings.TrimSuffix(p.LoginAction, "/login")
        }
        _ = portalPage.Execute(w, struct{ LoginAction, LocalBase string }{action, local})

    case r.URL.Path == "/login" && r.Method == http.MethodPost:
        p.login(w, r)

    case r.URL.Path == "/" || r.URL.Path == "/login":
        w.Header().Set("Allow", "GET, HEAD, POST")
        http.Error(w, "Méthode non autorisée.", http.StatusMethodNotAllowed)

    default:
        http.NotFound(w, r)
    }
}

func (p PortalEndpoint) login(w http.ResponseWriter, r *http.Request) {
    // The public captive page is served at 192.0.2.1 while the PIN is
    // submitted to the physical hotspot gateway. Browsers label that
    // legitimate IP-to-IP form POST as "cross-site". Permit ONLY that
    // specific trusted public origin; unrelated cross-site sites stay denied.
    fromPublicCaptive := r.Header.Get("Origin") == "http://192.0.2.1"
    if (r.Header.Get("Sec-Fetch-Site") == "cross-site" && !fromPublicCaptive) ||
        !sameOriginIfPresent(r) {
        http.Error(w, "Requête non autorisée.", http.StatusForbidden)
        return
    }
    r.Body = http.MaxBytesReader(w, r.Body, 4096)
    if err := r.ParseForm(); err != nil {
        http.Error(w, "Requête invalide.", http.StatusBadRequest)
        return
    }
    account := strings.TrimSpace(r.PostForm.Get("account"))
    pin := r.PostForm.Get("pin")

    // RemoteAddr is the actual TCP peer from the socket, NOT a user-provided
    // header, browser fingerprint or gateway IP inferred from the NAT tunnel.
    host, _, err := net.SplitHostPort(r.RemoteAddr)
    if err != nil {
        http.Error(w, "Connexion non vérifiable.", http.StatusForbidden)
        return
    }
    ip, err := netip.ParseAddr(host)
    if err != nil || p.Sessions == nil {
        http.Error(w, "Connexion au point d'accès non vérifiable.", http.StatusForbidden)
        return
    }
    if authErr := p.Sessions.AuthenticateFromPhysicalIP(ip, account, pin, p.Verifier); authErr != nil {
        if errors.Is(authErr, ErrInvalidCredentials) {
            http.Error(w, "Numéro de compte ou PIN incorrect.", http.StatusForbidden)
        } else {
            http.Error(w, "Connexion réseau modifiée. Réessayez le formulaire sans couper le Wi-Fi.", http.StatusForbidden)
        }
        return
    }
    // Login creates a local account session; Internet is authorized
    // separately by the voucher-aware traffic policy.
    w.Header().Set("Content-Type", "text/html; charset=utf-8")
    _, _ = w.Write([]byte("<!doctype html><html lang=\"fr\"><meta charset=\"utf-8\"><title>Shizzi — connecté</title>"+
        "<body style=\"font-family:system-ui;max-width:36rem;margin:2rem auto;padding:1rem\">"+
        "<h1>Compte Shizzi connecté</h1><p>Les droits Internet et Média dépendent des vouchers actifs.</p>"+
        "<p><a href=\"/media/\">Média</a> · <a href=\"/chat/\">Messagerie</a> · "+
        "<a href=\"/\">Accueil</a></p></body></html>"))
}

func sameOriginIfPresent(r *http.Request) bool {
    origin := r.Header.Get("Origin")
    if origin == "" { return true }
    // Captive portal is intentionally HTTP and reachable on the hotspot only.
    // Reject cross-origin form posts that could silently take over a session.
    return origin == "http://"+r.Host || origin == "http://192.0.2.1"
}
