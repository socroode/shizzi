package datapath

import (
 "archive/zip"
 "bytes"
 "crypto/sha256"
 "encoding/hex"
 "net"
 "net/http"
 "net/http/httptest"
 "strconv"
 "strings"
 "testing"
)

func fakeValidatedAdminAPK(t *testing.T) []byte {
 t.Helper()
 var out bytes.Buffer
 writer:=zip.NewWriter(&out)
 for _,name:=range []string{"AndroidManifest.xml","classes.dex","assets/stored.bin"} {
  header:=&zip.FileHeader{Name:name,Method:zip.Store}
  item,err:=writer.CreateHeader(header)
  if err!=nil {t.Fatal(err)}
  body:=[]byte("test-apk-data")
  if name=="assets/stored.bin" {body=bytes.Repeat([]byte{123},90*1024)}
  if _,err:=item.Write(body);err!=nil {t.Fatal(err)}
 }
 if err:=writer.Close();err!=nil {t.Fatal(err)}
 return out.Bytes()
}

func TestVerifiedAdminAPKNeverDeliversTinyErrorAsAPK(t *testing.T) {
 good:=fakeValidatedAdminAPK(t)
 scenarios:=[]struct {name string; body []byte; sha string; want int}{
  {"complete-admin-apk",good,"",200},
  {"short-server-error",[]byte("Téléchargement Shizzi+ momentanément indisponible."),"",502},
  {"corrupted-admin-archive",bytes.Repeat([]byte("X"),300*1024),"",502},
  {"mismatched-advertised-sha",good,strings.Repeat("1",64),502},
 }
 for _,tc:=range scenarios {
  t.Run(tc.name,func(t *testing.T){
   advertised:=tc.sha
   if advertised=="" {sum:=sha256.Sum256(tc.body);advertised=hex.EncodeToString(sum[:])}
   backend:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
     w.Header().Set("Content-Type","application/vnd.android.package-archive")
   w.Header().Set("Content-Length",strconv.Itoa(len(tc.body)))
     w.Header().Set("Content-Disposition",`attachment; filename="Shizzi-Admin-0.3.0-universal.apk"`)
     w.Header().Set("X-Shizzi-Version","0.3.0-universal")
     w.Header().Set("X-Shizzi-SHA256",advertised)
     _,_=w.Write(tc.body)
   }))
   defer backend.Close()
   _,portStr,_:=net.SplitHostPort(backend.Listener.Addr().String())
   port,_:=strconv.Atoi(portStr)
   m:=newTrafficManager()
   m.portalClientApp=portalClientApp{Available:true,BridgePort:port}
   resp,body:=verifiedDownloadRequest(t,m,"GET","","http://192.0.2.1/shizzi-admin.apk")
   if resp.StatusCode!=tc.want{t.Fatalf("status=%d, want %d, body=%q",resp.StatusCode,tc.want,body[:min(len(body),300)])}
   if tc.want==200 {
    if !bytes.Equal(body,good){t.Fatalf("APK bytes differ: %d",len(body))}
    if resp.Header.Get("X-Shizzi-SHA256")!=advertised {t.Fatal("missing SHA256")}
    if !strings.Contains(resp.Header.Get("Content-Disposition"),".apk"){t.Fatal("missing APK attachment")}
   } else {
    if len(body)<100 || strings.Contains(resp.Header.Get("Content-Disposition"),".apk") ||
     !strings.HasPrefix(resp.Header.Get("Content-Type"),"text/html") ||
     resp.Header.Get("X-Shizzi-Download-Error")!="1" {
      t.Fatalf("bad APK was exposed as downloadable: status=%d headers=%v bytes=%d",resp.StatusCode,resp.Header,len(body))
    }
   }
  })
 }
}

func TestVerifiedAdminAPKSupportsNativeHeadAndRange(t *testing.T) {
 data:=fakeValidatedAdminAPK(t)
 sum:=sha256.Sum256(data)
 backend:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
  w.Header().Set("Content-Type","application/vnd.android.package-archive")
   w.Header().Set("Content-Length",strconv.Itoa(len(data)))
  w.Header().Set("Content-Disposition",`attachment; filename="Shizzi-Admin-0.3.0-universal.apk"`)
  w.Header().Set("X-Shizzi-SHA256",hex.EncodeToString(sum[:]))
  _,_=w.Write(data)
 }))
 defer backend.Close()
 _,portStr,_:=net.SplitHostPort(backend.Listener.Addr().String())
 port,_:=strconv.Atoi(portStr)
 m:=newTrafficManager()
 m.portalClientApp=portalClientApp{Available:true,BridgePort:port}
 head,body:=verifiedDownloadRequest(t,m,"HEAD","","http://192.0.2.1/shizzi-admin.apk")
 if head.StatusCode!=200 || len(body)!=0 || head.ContentLength!=int64(len(data)) {
  t.Fatalf("HEAD metadata bad: status=%d length=%d body=%d",head.StatusCode,head.ContentLength,len(body))
 }
 part,bytesOut:=verifiedDownloadRequest(t,m,"GET","bytes=100-199","http://192.0.2.1/shizzi-admin.apk")
 if part.StatusCode!=206 || !bytes.Equal(bytesOut,data[100:200]) {t.Fatalf("range bad: %d bytes=%d",part.StatusCode,len(bytesOut))}
}
