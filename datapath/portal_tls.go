package datapath

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"math/big"
	"net"
	"time"
)

type portalTLSIdentity struct {
	CertificatePEM string `json:"certificatePem"`
	PrivateKeyPEM  string `json:"privateKeyPem"`
	Fingerprint    string `json:"fingerprint"`
}

// Android encrypts this private identity at rest and sends it only over Binder.
// A native client pairs by checking the displayed certificate fingerprint.
func GeneratePortalTLSIdentity() (string, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return "", err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return "", err
	}
	cert := &x509.Certificate{SerialNumber: serial, Subject: pkix.Name{CommonName: "Shizzi local router"},
		NotBefore: time.Now().Add(-24 * time.Hour), NotAfter: time.Now().AddDate(10, 0, 0),
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		DNSNames: []string{"shizzi.local"}, IPAddresses: []net.IP{net.ParseIP(portalIP)}}
	der, err := x509.CreateCertificate(rand.Reader, cert, cert, &key.PublicKey, key)
	if err != nil {
		return "", err
	}
	private, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return "", err
	}
	digest := sha256.Sum256(der)
	data, err := json.Marshal(portalTLSIdentity{string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})),
		string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: private})), hex.EncodeToString(digest[:])})
	return string(data), err
}

func (m *TrafficManager) serveTLS(conn net.Conn, ip string) {
	m.mu.Lock()
	cert := m.tlsCertificate
	m.mu.Unlock()
	if cert == nil {
		conn.Close()
		return
	}
	secure := tls.Server(conn, &tls.Config{MinVersion: tls.VersionTLS12, Certificates: []tls.Certificate{*cert}})
	secure.SetDeadline(time.Now().Add(10 * time.Second))
	if err := secure.Handshake(); err != nil {
		secure.Close()
		return
	}
	m.servePortal(secure, ip)
}
