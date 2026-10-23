package datapath

// ShizziCam: an opt-in LAN-only, ephemeral JPEG frame relay.
// Images never enter vouchers, the Internet TUN, or persistent storage.
import (
 "bytes"
 "fmt"
 "io"
 "net"
 "net/http"
 "strconv"
 "strings"
 "sync"
 "time"
)

type camFrame struct {
 jpg []byte
 last time.Time
 battery int
}
var camFrames = struct {
 sync.Mutex
 entries map[string]camFrame
}{entries: make(map[string]camFrame)}

const camMaxFrameBytes = 180000
const camMaxCameras = 16
const camTTL = 15*time.Second

func camFrameSnapshot(now time.Time) map[string]camFrame {
 camFrames.Lock()
 defer camFrames.Unlock()
 copy := make(map[string]camFrame)
 for account, frame := range camFrames.entries {
  if now.Sub(frame.last) > camTTL {
   delete(camFrames.entries, account)
   continue
  }
  copy[account] = frame
 }
 return copy
}

func storeCamFrame(account string, jpeg []byte, battery int, now time.Time) bool {
 if len(jpeg) < 8 || len(jpeg) > camMaxFrameBytes || !bytes.HasPrefix(jpeg, []byte{0xff,0xd8,0xff}) || !bytes.HasSuffix(jpeg, []byte{0xff,0xd9}) {
  return false
 }
 camFrames.Lock()
 defer camFrames.Unlock()
 for id, frame := range camFrames.entries {
  if now.Sub(frame.last) > camTTL { delete(camFrames.entries,id) }
 }
 if _, exists := camFrames.entries[account]; !exists && len(camFrames.entries) >= camMaxCameras {return false}
 camFrames.entries[account] = camFrame{jpg: append([]byte(nil),jpeg...),battery:battery,last:now}
 return true
}

func camText(conn net.Conn, status string, body string) {
 data:=[]byte(body)
 _,_=fmt.Fprintf(conn,"HTTP/1.1 %s\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: %d\r\nConnection: close\r\n\r\n",status,len(data))
 _,_=conn.Write(data)
}
func (m *TrafficManager) serveCameraAPI(conn net.Conn, req *http.Request, clientIP string) bool {
 if m.serveCameraRTC(conn,req,clientIP) {return true}
 if m.serveCameraAudio(conn,req,clientIP) {return true}
 if m.serveCameraMetrics(conn,req,clientIP) {return true}
 path:=req.URL.Path
 if path!="/api/v1/cam/frame" && path!="/api/v1/cam/list" { return false }
 // Never transmit images or admin tokens through cleartext captive-portal HTTP.
 if req.TLS==nil || !samePortalOrigin(req.Host) {
  camText(conn,"403 Forbidden","ShizziCam exige HTTPS.")
  return true
 }
 if path=="/api/v1/cam/frame" && req.Method==http.MethodPost {
  authenticated,account:=m.mediaAccountIdentity(clientIP)
  if !authenticated || account=="" { camText(conn,"401 Unauthorized","Connexion au compte Shizzi requise.");return true }
  if req.ContentLength<=0 || req.ContentLength>camMaxFrameBytes {
   camText(conn,"413 Content Too Large","Image trop volumineuse.");return true
  }
  contentType:=strings.ToLower(strings.TrimSpace(strings.Split(req.Header.Get("Content-Type"),";")[0]))
  if contentType!="image/jpeg" {camText(conn,"415 Unsupported Media Type","JPEG requis.");return true}
  data,err:=io.ReadAll(io.LimitReader(req.Body,camMaxFrameBytes+1))
  if err!=nil || len(data)!=int(req.ContentLength) {camText(conn,"400 Bad Request","Image incomplete.");return true}
  battery,_:=strconv.Atoi(req.Header.Get("X-Shizzi-Cam-Battery"))
  if battery<0||battery>100 {battery=-1}
  if !storeCamFrame(account,data,battery,time.Now()) {camText(conn,"422 Unprocessable Entity","Image refusee ou capacite atteinte.");return true}
 camMeasure(account,camVideoIn,len(data),time.Now())
  camText(conn,"200 OK","OK")
  return true
 }
 if req.Method != http.MethodGet {camText(conn,"405 Method Not Allowed","GET/POST uniquement.");return true}
 // Both listing and reading images require the existing remote-admin session.
 m.mu.Lock()
 _,allowed:=m.requireAdminTokenLocked(req,clientIP)
 m.mu.Unlock()
 if !allowed {camText(conn,"401 Unauthorized","Admin requis.");return true}
 frames:=camFrameSnapshot(time.Now())
 if path=="/api/v1/cam/list" {
  type item struct {Account string `json:"account"`; Battery int `json:"battery"`; AgeMillis int64 `json:"ageMillis"`}
  list:=make([]item,0,len(frames))
  for account,frame:=range frames {list=append(list,item{account,frame.battery,time.Since(frame.last).Milliseconds()})}
  camRtc.Lock()
  camRtcPruneLocked(time.Now())
  for account,live:=range camRtc.cameras {
   if _,exists:=frames[account];!exists {
    list=append(list,item{account,live.Battery,time.Since(live.LastSeen).Milliseconds()})
   }
  }
  camRtc.Unlock()
  writeJSONStatus(conn,"200 OK",map[string]any{"ok":true,"cameras":list})
  return true
 }
 account:=strings.TrimSpace(req.URL.Query().Get("account"))
 frame,ok:=frames[account]
 if !ok {camText(conn,"404 Not Found","Camera hors ligne.");return true}
 _,_=fmt.Fprintf(conn,"HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nCache-Control: no-store\r\nContent-Length: %d\r\nConnection: close\r\n\r\n",len(frame.jpg))
 if n,err:=conn.Write(frame.jpg); n>0 && err==nil {camMeasure(account,camVideoOut,n,time.Now())}
 return true
}
