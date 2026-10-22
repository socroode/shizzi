package datapath

import (
 "bytes"
 "crypto/sha256"
 "encoding/hex"
 "io"
 "net"
 "net/http"
 "net/http/httptest"
 "os"
 "strconv"
 "strings"
 "testing"
 "time"
)

// CI sets these paths to the actual *signed* APKs produced during this build.
// The test exercises the real captive download route, not just a byte copy.
func TestSignedCameraAndViewerDownloads(t *testing.T) {
 plusPath:=os.Getenv("SHIZZI_SIGNED_CAM_APK")
 viewerPath:=os.Getenv("SHIZZI_SIGNED_VIEWER_APK")
 if plusPath=="" || viewerPath=="" {t.Skip("requires signed APKs from Gradle build")}
 plus,err:=os.ReadFile(plusPath);if err!=nil {t.Fatal(err)}
 viewer,err:=os.ReadFile(viewerPath);if err!=nil {t.Fatal(err)}
 if len(plus)<1024*1024 || len(viewer)<1024*1024 {t.Fatal("signed APK unexpectedly small")}
 upstream:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
  switch r.URL.Path {
  case "/shizzi-plus.apk":_,_=w.Write(plus)
  case "/shizzicam-admin.apk":_,_=w.Write(viewer)
  default:w.WriteHeader(http.StatusNotFound)
  }
 }))
 defer upstream.Close()
 _,p,_:=net.SplitHostPort(upstream.Listener.Addr().String())
 port,err:=strconv.Atoi(p);if err!=nil {t.Fatal(err)}
 m:=newTrafficManager()
 m.portalClientApp=verifiedAnnouncement(plus,port)
 m.portalClientApp.FileName="Shizzi-PlusCam.apk"
 m.portalCamAdminApp=verifiedAnnouncement(viewer,port)
 m.portalCamAdminApp.FileName="ShizziCam-Admin.apk"
 for _,tc:=range []struct{
  name string
  expected []byte
  path string
  app portalClientApp
 }{{"camera",plus,"/shizzi-plus.apk",m.portalClientApp},{"viewer",viewer,"/shizzicam-admin.apk",m.portalCamAdminApp}}{
  h:=sha256.Sum256(tc.expected)
  wantSHA:=hex.EncodeToString(h[:])
  for _,method:=range []string{"HEAD","GET"} {
   response,body:=verifiedDownloadRequest(t,m,method,"","http://192.0.2.1"+tc.path+"?sha256="+wantSHA)
   if response.StatusCode!=http.StatusOK || response.ContentLength!=int64(len(tc.expected)) ||
      response.Header.Get("X-Shizzi-SHA256")!=wantSHA ||
      !strings.Contains(response.Header.Get("Content-Type"),"package-archive") {
     t.Fatalf("%s %s failed: status=%d size=%d headers=%v",tc.name,method,response.StatusCode,response.ContentLength,response.Header)
   }
   if method=="GET"&&!bytes.Equal(body,tc.expected) {t.Fatalf("%s downloaded APK corrupted or truncated",tc.name)}
   if method=="HEAD"&&len(body)!=0 {t.Fatalf("%s HEAD returned APK data",tc.name)}
  }
 }
 // Verify both correct links are rendered to local clients.
 server,client:=net.Pipe()
 done:=make(chan struct{})
 go func(){defer close(done);m.servePortal(server,"192.168.7.66")}()
 _=client.SetDeadline(time.Now().Add(7*time.Second))
 _,_=io.WriteString(client,"GET / HTTP/1.1\r\nHost: 192.0.2.1\r\nConnection: close\r\n\r\n")
 page,err:=io.ReadAll(client)
 _=client.Close()
 <-done
 if err!=nil {t.Fatal(err)}
 for _,needle:=range []string{"/shizzi-plus.apk?sha256="+m.portalClientApp.SHA256,
 "/shizzicam-admin.apk?sha256="+m.portalCamAdminApp.SHA256} {
  if !strings.Contains(string(page),needle){t.Fatalf("portal missing verified APK link %q",needle)}
 }
}
