package datapath

import (
	"crypto/hmac"
	"crypto/pbkdf2"
	"crypto/sha256"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Keep the legacy digest as PBKDF2 input so existing PINs can migrate without
// recovering their plaintext. This is a password verifier, never an API token.
const pinKDFIterations = 120000

func accountPinHash(account PortalAccount, pin string) string {
	base := hashPortalPin(account.PinSalt, pin)
	if account.PinKDFIterations == 0 {
		return base
	}
	if account.PinKDFIterations != pinKDFIterations {
		return ""
	}
	key, err := pbkdf2.Key(sha256.New, base, []byte(account.PinSalt), pinKDFIterations, 32)
	if err != nil {
		return ""
	}
	return hex.EncodeToString(key)
}

//go:embed portal_crypto.js
var portalCryptoJS []byte

type accountChallenge struct {
	IP       string
	Number   string
	Verifier string
	Expires  time.Time
}

func (m *TrafficManager) accountChallenge(ip, rawNumber string) map[string]any {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := time.Now()
	if m.accountChallenges == nil {
		m.accountChallenges = make(map[string]accountChallenge)
	}
	for nonce, c := range m.accountChallenges {
		if now.After(c.Expires) {
			delete(m.accountChallenges, nonce)
		}
	}
	number := normalizeAccountNumber(rawNumber)
	if ip == "" || len(m.accountChallenges) >= 128 || m.loginBlockedLocked("account|"+number, now) {
		return map[string]any{"ok": false, "message": "Réessayez dans quelques secondes."}
	}
	account, exists := m.portalAccounts[number]
	salt := account.PinSalt
	iterations := account.PinKDFIterations
	verifier := account.PinHash
	if !exists || !account.Enabled {
		salt = randomHex(16)
		iterations = pinKDFIterations
		verifier = ""
	}
	nonce := randomHex(32)
	m.accountChallenges[nonce] = accountChallenge{ip, number, verifier, now.Add(time.Minute)}
	return map[string]any{"ok": true, "salt": salt, "iterations": iterations, "nonce": nonce}
}

func accountProof(verifier, nonce, number string) string {
	mac := hmac.New(sha256.New, []byte(verifier))
	mac.Write([]byte("shizzi-account-v1|" + nonce + "|" + number))
	return hex.EncodeToString(mac.Sum(nil))
}

func (m *TrafficManager) submitAccountProof(ip, rawNumber, nonce, proof string) (bool, string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := time.Now()
	number := normalizeAccountNumber(rawNumber)
	key := "account|" + number
	challenge, exists := m.accountChallenges[nonce]
	delete(m.accountChallenges, nonce) // one use, including a rejected proof
	if m.loginBlockedLocked(key, now) {
		return false, "Trop de tentatives. Réessayez dans quelques secondes."
	}
	account, valid := m.portalAccounts[number]
	if !exists || ip == "" || challenge.IP != ip || challenge.Number != number || now.After(challenge.Expires) ||
		challenge.Verifier == "" || !valid || !account.Enabled || account.PinHash != challenge.Verifier ||
		!hmac.Equal([]byte(accountProof(account.PinHash, nonce, number)), []byte(strings.ToLower(proof))) {
		m.failedLoginLocked(key, now)
		return false, "Compte ou code incorrect."
	}
	return m.authorizeAccountLocked(ip, number, now.UnixMilli())
}

func writeJSONHTTP(conn net.Conn, status int, value any) {
	data, err := json.Marshal(value)
	if err != nil {
		return
	}
	fmt.Fprintf(conn, "HTTP/1.1 %d %s\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nContent-Length: %d\r\nConnection: close\r\n\r\n", status, http.StatusText(status), len(data))
	conn.Write(data)
}

func (m *TrafficManager) serveAccountSecurity(conn net.Conn, r *http.Request, ip string) bool {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		return false
	}
	switch r.URL.Path {
	case "/auth/challenge":
		writeJSONHTTP(conn, 200, m.accountChallenge(ip, r.URL.Query().Get("account")))
		return true
	case "/portal-crypto.js":
		fmt.Fprintf(conn, "HTTP/1.1 200 OK\r\nContent-Type: application/javascript\r\nCache-Control: no-store\r\nContent-Length: %d\r\nConnection: close\r\n\r\n", len(portalCryptoJS))
		if r.Method != http.MethodHead {
			conn.Write(portalCryptoJS)
		}
		return true
	}
	return false
}

func (m *TrafficManager) validAccountMutation(r *http.Request, ip string) bool {
	if !samePortalOrigin(r.Host) {
		return false
	}
	if origin := r.Header.Get("Origin"); origin != "" {
		parsed, err := url.Parse(origin)
		if err != nil || !samePortalOrigin(parsed.Host) {
			return false
		}
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.checkIdentityLocked(ip)
	auth := m.portalAuthorized[ip]
	return auth != nil && auth.ID != "" && hmac.Equal([]byte(auth.ID), []byte(r.Form.Get("csrf")))
}

func (m *TrafficManager) injectAccountSecurity(page, ip string) string {
	m.mu.Lock()
	auth := m.portalAuthorized[ip]
	csrf := ""
	if auth != nil {
		csrf = auth.ID
	}
	m.mu.Unlock()
	for _, action := range []string{"recharge", "logout"} {
		tag := `<form method="post" action="/` + action + `">`
		page = strings.ReplaceAll(page, tag, tag+`<input type="hidden" name="csrf" value="`+csrf+`">`)
	}
	if !strings.Contains(page, `id="shizzi-login"`) {
		return page
	}
	script := `<script src="/portal-crypto.js"></script><script>
 (function(){var f=document.getElementById('shizzi-login');if(!f)return;
 f.addEventListener('submit',async function(e){e.preventDefault();var b=f.querySelector('button');b.disabled=true;
 try{var number=f.querySelector('[name=account]').value.replace(/\D/g,'');
 var pin=document.getElementById('shizzi-pin');var secret=pin.value;
 var c=await (await fetch('/auth/challenge?account='+encodeURIComponent(number),{cache:'no-store'})).json();
 if(!c.ok)throw Error(c.message||'Connexion indisponible.');
 var base=CryptoJS.SHA256(c.salt+':'+secret).toString();secret='';pin.value='';
 var verifier=c.iterations===0?base:CryptoJS.PBKDF2(base,CryptoJS.enc.Utf8.parse(c.salt),{keySize:8,iterations:c.iterations,hasher:CryptoJS.algo.SHA256}).toString();
 var proof=CryptoJS.HmacSHA256('shizzi-account-v1|'+c.nonce+'|'+number,verifier).toString();
 ['nonce','proof'].forEach(function(n){var x=document.createElement('input');x.type='hidden';x.name=n;x.value=n==='nonce'?c.nonce:proof;f.appendChild(x);});
 f.querySelector('[name=account]').value=number;f.submit();
 }catch(err){alert(err.message||'Connexion impossible. Réessayez.');b.disabled=false;}
 });})();</script>`
	if i := strings.LastIndex(strings.ToLower(page), "</body>"); i >= 0 {
		return page[:i] + script + page[i:]
	}
	return page + script
}
