package core

import (
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
</style></head><body><main class="card">
<div class="eyebrow">PORTAIL LOCAL · SHIZZI</div>
<h1>Bienvenue sur Shizzi</h1>
<p class="sub">Connecté au Wi-Fi Shizzi ? La page d'accueil reste accessible même sans forfait Internet.</p>
<section class="panel"><div class="eyebrow">COMPTE SHIZZI</div>
<h2>Se connecter</h2>
<form method="post" action="/login" autocomplete="on">
<label for="account">Numéro de compte</label>
<input id="account" name="account" inputmode="numeric" pattern="[0-9]+" autocomplete="username" required maxlength="40">
<label for="pin">Code PIN</label>
<input id="pin" name="pin" type="password" autocomplete="current-password" required maxlength="128">
<button type="submit">Se connecter</button></form>
<p class="muted">Le numéro de compte et le PIN valident la session. Seul un voucher Internet actif autorise ensuite Internet.</p></section>
<section class="panel"><div class="eyebrow">MEDIA LOCAL</div><h2>Shizzi Media</h2>
<p>Films, séries, musique, dossiers et vouchers Média indépendants du forfait Internet.</p></section>
<section class="panel"><div class="eyebrow">MESSAGERIE LOCALE</div><h2>Messagerie Shizzi</h2>
<p>Messages et appels entre utilisateurs connectés au Wi-Fi Shizzi.</p></section>
<section class="panel"><div class="eyebrow">RECHARGES ET APPLICATIONS</div><h2>Vouchers · Shizzi+ · Admin</h2>
<p>Ces modules de la v0.4.3.5.3 sont réintégrés dans l'interface Android; leurs accès depuis le portail seront reliés après validation du noyau V2.</p></section>
<p class="muted">Cette reconstruction est en cours de validation : aucun accès Internet n'est attribué à un appareil non authentifié.</p>
</main></body></html>`

var portalPage = template.Must(template.New("login").Parse(publicPortalHTML))

func (p PortalEndpoint) ServeHTTP(w http.ResponseWriter, r *http.Request) {
    w.Header().Set("Cache-Control", "no-store, max-age=0")
    w.Header().Set("X-Content-Type-Options", "nosniff")
    w.Header().Set("X-Frame-Options", "DENY")
    w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")

    switch {
    case r.URL.Path == "/" && (r.Method == http.MethodGet || r.Method == http.MethodHead):
        w.Header().Set("Content-Type", "text/html; charset=utf-8")
        if r.Method == http.MethodHead { w.WriteHeader(http.StatusOK); return }
        _ = portalPage.Execute(w, nil)

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
    if r.Header.Get("Sec-Fetch-Site") == "cross-site" ||
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
    if err != nil || p.Sessions == nil ||
        p.Sessions.AuthenticateFromPhysicalIP(ip, account, pin, p.Verifier) != nil {
        http.Error(w, "Compte/PIN incorrect ou connexion Wi-Fi non vérifiable.", http.StatusForbidden)
        return
    }
    // Login creates a local account session; Internet is authorized
    // separately by the voucher-aware traffic policy.
    w.Header().Set("Content-Type", "text/plain; charset=utf-8")
    _, _ = w.Write([]byte("Compte connecté. Les droits Internet et Média dépendent des vouchers actifs."))
}

func sameOriginIfPresent(r *http.Request) bool {
    origin := r.Header.Get("Origin")
    if origin == "" { return true }
    // Captive portal is intentionally HTTP and reachable on the hotspot only.
    // Reject cross-origin form posts that could silently take over a session.
    return origin == "http://"+r.Host
}
