package datapath

import (
 "fmt"
 "html"
 "net"
 "net/http"
)

// A download error is a browser-readable page, not an APK attachment.
// This avoids advertising a 50-70 byte server message as an installable file.
func writeAPKDownloadError(conn net.Conn, method, status, message string) {
 page := "<!doctype html><html lang=\"fr\"><meta charset=\"utf-8\"><title>Shizzi : téléchargement impossible</title><body><h2>Téléchargement impossible</h2><p>"+html.EscapeString(message)+"</p><p>Revenez au portail Shizzi et réessayez après avoir vérifié la connexion au routeur.</p></body></html>"
 data:=[]byte(page)
 _,_=fmt.Fprintf(conn,"HTTP/1.1 %s\r\nContent-Type: text/html; charset=utf-8\r\nContent-Disposition: inline; filename=\"shizzi-erreur-telechargement.html\"\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Shizzi-Download-Error: 1\r\nContent-Length: %d\r\nConnection: close\r\n\r\n", status,len(data))
 if method != http.MethodHead {_,_=conn.Write(data)}
}
