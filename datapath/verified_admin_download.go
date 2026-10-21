package datapath

import (
 "archive/zip"
 "bytes"
 "crypto/sha256"
 "encoding/hex"
 "fmt"
 "io"
 "mime"
 "net"
 "net/http"
 "strconv"
 "strings"
 "time"
)

// Verifies the router-bundled Admin APK before making it a downloadable
// attachment. The earlier raw TCP proxy forwarded short error bodies unchanged.
func (m *TrafficManager) serveVerifiedAdminAppDownload(conn net.Conn, request *http.Request, app portalClientApp) {
 _ = conn.SetDeadline(time.Now().Add(4*time.Minute))
 fail:=func(status,message string){writeAPKDownloadError(conn,request.Method,status,message)}
 if app.BridgePort<1 || app.BridgePort>65535 {
  fail("503 Service Unavailable","Serveur de distribution Shizzi Admin absent.")
  return
 }
 transport:=&http.Transport{DialContext: (&net.Dialer{Timeout:3*time.Second}).DialContext,DisableKeepAlives:true}
 defer transport.CloseIdleConnections()
 client:=&http.Client{Transport:transport,Timeout:45*time.Second,CheckRedirect:func(*http.Request,[]*http.Request)error{return http.ErrUseLastResponse}}
 response,err:=client.Get("http://"+net.JoinHostPort("127.0.0.1",strconv.Itoa(app.BridgePort))+"/shizzi-admin.apk")
 if err!=nil {
  fail("503 Service Unavailable","Serveur Shizzi Admin injoignable. Réessayez.")
  return
 }
 defer response.Body.Close()
 if response.StatusCode!=http.StatusOK ||
    response.ContentLength<16*1024 || response.ContentLength>maxVerifiedClientAPKBytes ||
    !strings.HasPrefix(strings.ToLower(response.Header.Get("Content-Type")),"application/vnd.android.package-archive") {
  fail("502 Bad Gateway","Le serveur ne propose pas un fichier APK Admin complet.")
  return
 }
 advertisedSHA:=strings.ToLower(strings.TrimSpace(response.Header.Get("X-Shizzi-SHA256")))
 advertisedDigest,err:=hex.DecodeString(advertisedSHA)
 if err!=nil || len(advertisedDigest)!=sha256.Size {
  fail("502 Bad Gateway","L'empreinte du fichier Admin est absente ou invalide.")
  return
 }
 data,err:=io.ReadAll(io.LimitReader(response.Body,maxVerifiedClientAPKBytes+1))
 actual:=sha256.Sum256(data)
 if err!=nil || int64(len(data))!=response.ContentLength || !bytes.Equal(actual[:],advertisedDigest) ||
    !apkHasAndroidManifestAndDex(data) {
  fail("502 Bad Gateway","Fichier Admin tronqué, altéré ou non conforme. Aucun APK ne sera livré.")
  return
 }
 fileName:="Shizzi-Admin.apk"
 if _,params,err:=mime.ParseMediaType(response.Header.Get("Content-Disposition"));err==nil {
  name:=params["filename"]
  if strings.HasPrefix(name,"Shizzi-Admin-") && strings.HasSuffix(name,".apk") &&
    len(name)<128 && !strings.ContainsAny(name,"/\\\r\n") { fileName=name }
 }
 first,last:=int64(0),int64(len(data)-1)
 status:="200 OK"
 etag:="\""+advertisedSHA+"\""
 if raw:=request.Header.Get("Range");raw!=""&&
    (request.Header.Get("If-Range")=="" || request.Header.Get("If-Range")==etag) {
  valid:=false
  first,last,valid=verifiedClientRange(raw,int64(len(data)))
  if !valid {
   _,_=fmt.Fprintf(conn,"HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */%d\r\nContent-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",len(data))
   return
  }
  status="206 Partial Content"
 }
 _,_=fmt.Fprintf(conn,"HTTP/1.1 %s\r\nContent-Type: application/vnd.android.package-archive\r\nContent-Disposition: %s\r\nContent-Length: %d\r\nAccept-Ranges: bytes\r\nX-Shizzi-Version: %s\r\nX-Shizzi-SHA256: %s\r\nETag: %s\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n",
   status,mime.FormatMediaType("attachment",map[string]string{"filename":fileName}),last-first+1,
   response.Header.Get("X-Shizzi-Version"),advertisedSHA,etag)
 if status=="206 Partial Content" {
  _,_=fmt.Fprintf(conn,"Content-Range: bytes %d-%d/%d\r\n",first,last,len(data))
 }
 _,_=io.WriteString(conn,"\r\n")
 if request.Method==http.MethodHead {return}
 for offset:=first;offset<=last;{
  end:=offset+64*1024
  if end>last+1 {end=last+1}
  n,err:=conn.Write(data[offset:end])
  if n<=0||err!=nil{return}
  offset+=int64(n)
 }
}

func apkHasAndroidManifestAndDex(data []byte)bool {
 if len(data)<16*1024 || !bytes.HasPrefix(data,[]byte("PK\x03\x04")) {return false}
 archive,err:=zip.NewReader(bytes.NewReader(data),int64(len(data)))
 if err!=nil {return false}
 manifest,dex:=false,false
 for _,file:=range archive.File {
  if file.Name=="AndroidManifest.xml"{manifest=true}
  if file.Name=="classes.dex"{dex=true}
 }
 return manifest&&dex
}
