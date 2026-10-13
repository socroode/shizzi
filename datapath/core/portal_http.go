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
<title>Shizzi — Accueil</title>
<style>
body{font-family:system-ui,sans-serif;max-width:32rem;margin:2rem auto;padding:1rem;line-height:1.5}
label{display:block;margin:.8rem 0 .2rem}
input{box-sizing:border-box;width:100%;padding:.7rem;font-size:1rem}
button{margin-top:1rem;padding:.75rem 1.3rem;font-size:1rem}
small{display:block;margin-top:1rem}
</style></head><body><main>
<h1>Bienvenue sur Shizzi</h1>
<p>Le portail local est accessible dès la connexion au Wi-Fi, même sans forfait Internet.</p>
<form method="post" action="/login" autocomplete="on">
<label for="account">Numéro de compte</label>
<input id="account" name="account" inputmode="numeric" pattern="[0-9]+" autocomplete="username" required maxlength="40">
<label for="pin">Code PIN</label>
<input id="pin" name="pin" type="password" autocomplete="current-password" required maxlength="128">
<button type="submit">Se connecter</button>
</form>
<small>L'accès Internet demande un compte et un PIN valides ainsi qu'un voucher Internet actif.
Le Média dépend de ses propres droits.</small>
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
