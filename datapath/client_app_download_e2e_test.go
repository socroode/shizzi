package datapath

import (
    "bytes"
    "net"
    "net/http"
    "net/http/httptest"
    "strconv"
    "strings"
    "testing"
)

func TestClientAppDownloadProxyReturnsCompleteAPK(t *testing.T) {
    apk:=[]byte("virtual-shizzi-plus-apk")
    upstream:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
        _,_=w.Write(apk)
    }))
    defer upstream.Close()
    _,p,_:=net.SplitHostPort(upstream.Listener.Addr().String())
    port,_:=strconv.Atoi(p)
    manager:=newTrafficManager()
    manager.portalClientApp=verifiedAnnouncement(apk,port)

    response,actual:=verifiedDownloadRequest(t,manager,"GET","",
        "http://192.0.2.1/shizzi-plus.apk")
    if response.StatusCode!=200 || !bytes.Equal(actual,apk) {
        t.Fatalf("incomplete verified APK download: %d %q",response.StatusCode,actual)
    }
    if response.Header.Get("X-Shizzi-SHA256")!=manager.portalClientApp.SHA256 ||
       response.ContentLength!=int64(len(apk)) ||
       !strings.Contains(response.Header.Get("Content-Disposition"),"attachment") {
        t.Fatalf("APK integrity/metadata missing: %+v",response.Header)
    }
}
