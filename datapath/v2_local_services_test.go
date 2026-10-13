package datapath

import (
    "bufio"
    "bytes"
    "io"
    "net"
    "net/http"
    "net/http/httptest"
    "net/netip"
    "strings"
    "sync"
    "testing"
    "time"

    "dev.shizzi/datapath/core"
)

func v2TestRegistryLocal(t *testing.T) *core.Registry {
    t.Helper()
    r:=core.NewRegistry()
    if err:=r.WifiJoined("02:11:22:33:44:55");err!=nil{t.Fatal(err)}
    if err:=r.BindAddress("02:11:22:33:44:55",netip.MustParseAddr("192.168.247.161"));err!=nil{t.Fatal(err)}
    if err:=r.SetRights("1001",core.Rights{MediaUntil:time.Now().Add(time.Hour)});err!=nil{t.Fatal(err)}
    if err:=r.GrantVerifiedLogin("02:11:22:33:44:55","1001");err!=nil{t.Fatal(err)}
    return r
}

func v2TestLocalRequest(method,path string,body io.Reader) *http.Request {
    r:=httptest.NewRequest(method,"http://192.168.247.1:8093"+path,body)
    r.RemoteAddr="192.168.247.161:45678"
    return r
}

func TestV2MediaPassAndRangeAreEnforced(t *testing.T) {
    var mu sync.Mutex
    receivedAccount, receivedRange, forged := "","",""
    backend:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
        mu.Lock()
        receivedAccount=r.Header.Get("X-Shizzi-Media-Account")
        receivedRange=r.Header.Get("Range")
        forged=r.Header.Get("X-Shizzi-Chat-Account")
        mu.Unlock()
        w.Header().Set("Content-Type","video/mp4")
        w.Header().Set("Accept-Ranges","bytes")
        w.Header().Set("Content-Range","bytes 0-3/100")
        w.WriteHeader(http.StatusPartialContent)
        _,_=w.Write([]byte("abcd"))
    }))
    defer backend.Close()
    b:=v2LocalBridge{media:strings.TrimPrefix(backend.URL,"http://")}
    registry:=v2TestRegistryLocal(t)
    r:=v2TestLocalRequest("GET","/media/stream?id=movie",nil)
    r.Header.Set("Range","bytes=0-3")
    r.Header.Set("X-Shizzi-Media-Account","admin")
    r.Header.Set("X-Shizzi-Chat-Account","1002")
    recorder:=httptest.NewRecorder()
    if !b.routePhysical(recorder,r,registry){t.Fatal("media route not matched")}
    if recorder.Code!=206 || recorder.Body.String()!="abcd"{
        t.Fatalf("media range response lost: %d %q",recorder.Code,recorder.Body.String())
    }
    mu.Lock()
    acct,rangeHeader,spoofed:=receivedAccount,receivedRange,forged
    mu.Unlock()
    if acct!="1001"||rangeHeader!="bytes=0-3"||spoofed!="" {
        t.Fatalf("backend headers wrong account=%q range=%q spoofed=%q",acct,rangeHeader,spoofed)
    }
    registry.SetRights("1001",core.Rights{}) // session remains, Media voucher expires
    blocked:=httptest.NewRecorder()
    b.routePhysical(blocked,v2TestLocalRequest("GET","/media/",nil),registry)
    if blocked.Code!=http.StatusForbidden {t.Fatalf("Media without voucher %d",blocked.Code)}
    if got:=registry.AuthorizeDirect(netip.MustParseAddr("192.168.247.161"),core.Portal,time.Now());got!=core.Allow{
        t.Fatalf("Media expiry blocked the public portal: %s",got)
    }
}

func TestV2ChatWithoutInternetVoucherAndNoHeaderSpoof(t *testing.T){
    var seen string
    backend:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
        seen=r.Header.Get("X-Shizzi-Chat-Account")
        if r.URL.Path!="/api/send"{http.Error(w,"path wrong",400);return}
        body,_:=io.ReadAll(r.Body)
        if r.Header.Get("Content-Length")!="5" || string(body)!="hello" {
            http.Error(w,"bad request body",400);return
        }
        w.Header().Set("Content-Type","application/json")
        _,_=w.Write([]byte("{\"ok\":true}"))
    }))
    defer backend.Close()
    b:=v2LocalBridge{chat:strings.TrimPrefix(backend.URL,"http://")}
    registry:=v2TestRegistryLocal(t)
    registry.SetRights("1001",core.Rights{}) // no Internet voucher, no Media pass
    req:=v2TestLocalRequest("POST","/chat/api/send",strings.NewReader("hello"))
    req.Header.Set("Content-Type","text/plain")
    req.Header.Set("X-Shizzi-Chat-Account","9999")
    resp:=httptest.NewRecorder()
    b.routePhysical(resp,req,registry)
    if resp.Code!=200 || seen!="1001" {
        t.Fatalf("local chat blocked or injected: %d account=%q body=%s",resp.Code,seen,resp.Body.String())
    }
    wrongOrigin:=v2TestLocalRequest("POST","/chat/api/send",strings.NewReader("hello"))
    wrongOrigin.Header.Set("Origin","http://attacker.example")
    fail:=httptest.NewRecorder()
    b.routePhysical(fail,wrongOrigin,registry)
    if fail.Code!=http.StatusForbidden {t.Fatal("cross-site chat POST accepted")}
    registry.Logout("02:11:22:33:44:55")
    revoked:=httptest.NewRecorder()
    b.routePhysical(revoked,v2TestLocalRequest("GET","/chat/",nil),registry)
    if revoked.Code!=http.StatusUnauthorized {t.Fatalf("logged out chat access: %d",revoked.Code)}
}

func TestV2UnknownPhysicalIPCannotReadMediaOrChat(t *testing.T) {
    b:=v2LocalBridge{}
    registry:=v2TestRegistryLocal(t)
    for _,path:=range []string{"/media/","/chat/api/snapshot"}{
        r:=v2TestLocalRequest("GET",path,nil)
        r.RemoteAddr="192.168.247.220:50000"
        w:=httptest.NewRecorder()
        b.routePhysical(w,r,registry)
        if w.Code!=http.StatusUnauthorized {t.Fatalf("%s opened for unknown IP (%d)",path,w.Code)}
    }
}

func TestV2PublicAPKStreamsRealBytesAndRange(t *testing.T){
    payload:=bytes.Repeat([]byte{0x41,0x50,0x4b,0x0a},32*1024) // 128 KiB
    backend:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
        if r.URL.Path!="/shizzi-admin.apk" {t.Errorf("wrong backend path %s",r.URL.Path)}
        if r.Header.Get("Range")=="bytes=100-199" {
            w.Header().Set("Content-Range","bytes 100-199/131072")
            w.Header().Set("Content-Type","application/vnd.android.package-archive")
            w.WriteHeader(http.StatusPartialContent)
            _,_=w.Write(payload[100:200]);return
        }
        w.Header().Set("Content-Type","application/vnd.android.package-archive")
        _,_=w.Write(payload)
    }))
    defer backend.Close()
    b:=v2LocalBridge{apps:strings.TrimPrefix(backend.URL,"http://")}
    for _,rng:=range []string{"","bytes=100-199"} {
        server,client:=net.Pipe()
        done:=make(chan struct{})
        go func(){
            defer close(done)
            req:=httptest.NewRequest(http.MethodGet,"http://192.0.2.1/shizzi-admin.apk",nil)
            if rng!="" {req.Header.Set("Range",rng)}
            b.servePublicDownload(server,req)
        }()
        _=client.SetDeadline(time.Now().Add(5*time.Second))
        response,err:=http.ReadResponse(bufio.NewReader(client),&http.Request{Method:http.MethodGet})
        if err!=nil {client.Close();t.Fatal(err)}
        body,err:=io.ReadAll(response.Body)
        response.Body.Close()
        client.Close()
        <-done
        if err!=nil{t.Fatal(err)}
        if rng=="" && (response.StatusCode!=200||!bytes.Equal(body,payload)){
            t.Fatalf("real APK truncated: status=%d received=%d",response.StatusCode,len(body))
        }
        if rng!="" && (response.StatusCode!=206||!bytes.Equal(body,payload[100:200])){
            t.Fatalf("APK range broken status=%d bytes=%d",response.StatusCode,len(body))
        }
    }
}

func TestV2MediaStopsWhenSessionTakenOver(t *testing.T) {
    reg:=v2TestRegistryLocal(t)
    ip:=netip.MustParseAddr("192.168.247.161")
    account,epoch,yes:=reg.AuthenticatedLease(ip)
    if !yes {t.Fatal("no initial login")}
    if !reg.LocalLeaseStillValid(ip,account,epoch,true,time.Now()){t.Fatal("media should start")}
    reg.WifiJoined("02:66:77:88:99:aa")
    reg.BindAddress("02:66:77:88:99:aa",netip.MustParseAddr("192.168.247.162"))
    reg.GrantVerifiedLogin("02:66:77:88:99:aa","1001")
    if reg.LocalLeaseStillValid(ip,account,epoch,true,time.Now()){
        t.Fatal("old browser retained its Media stream after account takeover")
    }
}
