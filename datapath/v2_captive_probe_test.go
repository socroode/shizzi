package datapath

import (
    "bufio"
    "net"
    "net/http"
    "testing"
    "time"
)

func TestV2CaptiveProbesRedirectOnlyKnownConnectivityChecks(t *testing.T) {
    cases:=[]struct{host,path string; wanted bool}{
        {"connectivitycheck.gstatic.com","/generate_204",true},
        {"connectivitycheck.android.com","/generate_204",true},
        {"clients3.google.com","/generate_204",true},
        {"captive.apple.com","/hotspot-detect.html",true},
        {"www.msftconnecttest.com","/connecttest.txt",true},
        {"www.msftncsi.com","/ncsi.txt",true},
        {"detectportal.firefox.com","/canonical.html",true},
        {"example.com","/generate_204",false},
        {"connectivitycheck.gstatic.com","/private",false},
        {"example.com","/",false},
    }
    for _,tc:=range cases{
        req,err:=http.NewRequest("GET","http://"+tc.host+tc.path,nil)
        if err!=nil{t.Fatal(err)}
        if got:=isV2CaptiveProbe(req);got!=tc.wanted{
            t.Errorf("%s%s probe=%v expected=%v",tc.host,tc.path,got,tc.wanted)
        }
    }
}

func TestV2CaptiveRedirectToPublicPortalWithoutAccount(t *testing.T) {
    server,client:=net.Pipe()
    defer client.Close()
    done:=make(chan struct{})
    go func(){serveV2CaptiveProbe(server);close(done)}()
    _=client.SetDeadline(time.Now().Add(4*time.Second))
    if _,err:=client.Write([]byte(
        "GET /generate_204 HTTP/1.1\r\nHost: connectivitycheck.gstatic.com\r\nConnection: close\r\n\r\n",
    ));err!=nil{t.Fatal(err)}
    response,err:=http.ReadResponse(bufio.NewReader(client),&http.Request{Method:"GET"})
    if err!=nil{t.Fatal(err)}
    response.Body.Close()
    <-done
    if response.StatusCode!=http.StatusFound ||
        response.Header.Get("Location")!="http://192.0.2.1/" {
        t.Fatalf("captive redirect wrong: %d %q",response.StatusCode,response.Header.Get("Location"))
    }
}

func TestV2NonProbeCannotEscapeViaHTTP(t *testing.T) {
    server,client:=net.Pipe()
    defer client.Close()
    done:=make(chan struct{})
    go func(){serveV2CaptiveProbe(server);close(done)}()
    _=client.SetDeadline(time.Now().Add(4*time.Second))
    if _,err:=client.Write([]byte(
        "GET /private HTTP/1.1\r\nHost: example.org\r\nConnection: close\r\n\r\n",
    ));err!=nil{t.Fatal(err)}
    buffer:=make([]byte,128)
    count,err:=client.Read(buffer)
    <-done
    if count>0 || err==nil {
        t.Fatalf("unauthenticated non-probe got response: %q (%v)",buffer[:count],err)
    }
}
