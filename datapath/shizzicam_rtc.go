package datapath

import (
 "encoding/json"
 "io"
 "net"
 "net/http"
 "strconv"
 "strings"
 "sync"
 "time"
)

const camRtcTTL = 20*time.Second
const camRtcQueueLimit = 64

type camRtcMessage struct {
 ID uint64 `json:"id"`
 Type string `json:"type"`
 Session string `json:"session"`
 SDP string `json:"sdp,omitempty"`
 Mid string `json:"mid,omitempty"`
 MLine int `json:"mline,omitempty"`
 Candidate string `json:"candidate,omitempty"`
 Quality string `json:"quality,omitempty"`
 Command string `json:"command,omitempty"`
 Value string `json:"value,omitempty"`
 RefID uint64 `json:"refId,omitempty"`
 OK *bool `json:"ok,omitempty"`
 Error string `json:"error,omitempty"`
}
type camRtcSession struct {
 LastSeen time.Time
 Battery int
 Audio bool
 Quality string
 ViewerSession string
 ViewerIP string
 ViewerLastSeen time.Time
 ToCamera []camRtcMessage
 ToViewer []camRtcMessage
 Counter uint64
}
var camRtc = struct{
 sync.Mutex
 cameras map[string]*camRtcSession
}{cameras:make(map[string]*camRtcSession)}

func camRtcPruneLocked(now time.Time) {
 for account,session:=range camRtc.cameras {
  if now.Sub(session.LastSeen)>camRtcTTL {delete(camRtc.cameras,account)}
 }
}
func camRtcPostMessage(session *camRtcSession, toViewer bool, msg camRtcMessage) {
 session.Counter++
 msg.ID=session.Counter
 if toViewer {
  session.ToViewer=append(session.ToViewer,msg)
  if len(session.ToViewer)>camRtcQueueLimit {session.ToViewer=session.ToViewer[len(session.ToViewer)-camRtcQueueLimit:]}
 }else{
  session.ToCamera=append(session.ToCamera,msg)
  if len(session.ToCamera)>camRtcQueueLimit {session.ToCamera=session.ToCamera[len(session.ToCamera)-camRtcQueueLimit:]}
 }
}
func camRtcEventsAfter(queue []camRtcMessage, after uint64) []camRtcMessage {
 events:=make([]camRtcMessage,0)
 for _,event:=range queue {if event.ID>after {events=append(events,event)}}
 return events
}
func camRtcAllowedQuality(value string) bool {
 switch value {
 case "auto","360p","480p","720p": return true
 }
 return false
}

func (m *TrafficManager) serveCameraRTC(conn net.Conn, r *http.Request, clientIP string) bool {
 p:=r.URL.Path
 if p!="/api/v1/cam/capabilities"&&p!="/api/v1/cam/hello"&&p!="/api/v1/cam/watch"&&p!="/api/v1/cam/events" {return false}
 if r.TLS==nil || !samePortalOrigin(r.Host) {
  writeJSONStatus(conn,"403 Forbidden",map[string]any{"ok":false,"error":"HTTPS required"})
  return true
 }
 if r.Method==http.MethodGet && p=="/api/v1/cam/capabilities" {
  writeJSONStatus(conn,"200 OK",map[string]any{
    "ok":true, "protocol":"shizzicam-relay-jpeg-pcm-v1",
    "video":"JPEG", "audio":"PCM16", "qualities":[]string{"360p","480p","720p","auto"},
    "requiresAccount":true,"requiresAdmin":true,
    "transport":"https-router-relay", "relayAvailable":true,
  })
  return true
 }
 side:=strings.TrimSpace(r.URL.Query().Get("side"))
 if side=="" && p=="/api/v1/cam/hello" {side="camera"}
 if side=="" && p=="/api/v1/cam/watch" {side="viewer"}
 isCamera:=side=="camera"
 account:=""
 if isCamera {
  valid,num:=m.mediaAccountIdentity(clientIP)
  if !valid || num=="" {writeJSONStatus(conn,"401 Unauthorized",map[string]any{"ok":false,"error":"Shizzi account required"});return true}
  account=num
 }else{
  m.mu.Lock()
  _,valid:=m.requireAdminTokenLocked(r,clientIP)
  m.mu.Unlock()
  if !valid {writeJSONStatus(conn,"401 Unauthorized",map[string]any{"ok":false,"error":"Shizzi admin login required"});return true}
  account=strings.TrimSpace(r.URL.Query().Get("account"))
 }
 var req struct {
  Account string `json:"account"`
  Session string `json:"session"`
  Type string `json:"type"`
  SDP string `json:"sdp"`
  Mid string `json:"mid"`
  MLine int `json:"mline"`
  Candidate string `json:"candidate"`
  Battery int `json:"battery"`
  Audio bool `json:"audio"`
  Quality string `json:"quality"`
  Command string `json:"command"`
  Value string `json:"value"`
  RefID uint64 `json:"refId"`
  OK *bool `json:"ok"`
  Error string `json:"error"`
 }
 if r.Method==http.MethodPost {
  if r.ContentLength<0||r.ContentLength>16*1024 {writeJSONStatus(conn,"413 Content Too Large",map[string]any{"ok":false});return true}
  b,err:=io.ReadAll(io.LimitReader(r.Body,16*1024+1))
  if err!=nil||len(b)>16*1024||json.Unmarshal(b,&req)!=nil {writeJSONStatus(conn,"400 Bad Request",map[string]any{"ok":false});return true}
  if !isCamera {account=strings.TrimSpace(req.Account)}
 }
 now:=time.Now()
 camRtc.Lock()
 defer camRtc.Unlock()
 camRtcPruneLocked(now)
 switch {
 case p=="/api/v1/cam/hello"&&r.Method==http.MethodPost&&isCamera:
  state:=camRtc.cameras[account]
  if state==nil {
   if len(camRtc.cameras)>=16 {writeJSONStatus(conn,"429 Too Many Requests",map[string]any{"ok":false});return true}
   state=&camRtcSession{}
   camRtc.cameras[account]=state
  }
  state.LastSeen=now
  state.Battery=req.Battery
  if state.Battery<0||state.Battery>100 {state.Battery=-1}
  state.Audio=req.Audio
  if camRtcAllowedQuality(req.Quality) {state.Quality=req.Quality}
  writeJSONStatus(conn,"200 OK",map[string]any{"ok":true})
  return true
 case p=="/api/v1/cam/watch"&&r.Method==http.MethodPost&&!isCamera:
  state:=camRtc.cameras[account]
  if state==nil {writeJSONStatus(conn,"404 Not Found",map[string]any{"ok":false,"error":"Camera not online"});return true}
  // Single active viewer per camera (a newer watch revokes the old viewer).
  state.ViewerSession=randomHex(24)
  state.ViewerIP=clientIP
  state.ViewerLastSeen=now
  state.ToCamera=nil
  state.ToViewer=nil
  camRtcPostMessage(state,false,camRtcMessage{Type:"watch",Session:state.ViewerSession,Quality:"auto"})
  writeJSONStatus(conn,"200 OK",map[string]any{"ok":true,"session":state.ViewerSession})
  return true
 case p=="/api/v1/cam/events"&&r.Method==http.MethodGet:
  if isCamera { /* account already derived from authenticated session */ }
  state:=camRtc.cameras[account]
  if state==nil {writeJSONStatus(conn,"404 Not Found",map[string]any{"ok":false});return true}
  if !isCamera {
   if state.ViewerIP!=clientIP || state.ViewerSession=="" || state.ViewerSession!=r.URL.Query().Get("session") {
    writeJSONStatus(conn,"403 Forbidden",map[string]any{"ok":false});return true
   }
   state.ViewerLastSeen=now
  }
  after,_:=strconv.ParseUint(r.URL.Query().Get("after"),10,64)
  queue:=state.ToCamera
  if !isCamera {queue=state.ToViewer}
  writeJSONStatus(conn,"200 OK",map[string]any{"ok":true,"events":camRtcEventsAfter(queue,after)})
  return true
 case p=="/api/v1/cam/events"&&r.Method==http.MethodPost:
  if isCamera { /* account derived from auth; ignore untrusted body Account */ }
  state:=camRtc.cameras[account]
  if state==nil {writeJSONStatus(conn,"404 Not Found",map[string]any{"ok":false});return true}
  if req.Session==""||req.Session!=state.ViewerSession ||
   (!isCamera && state.ViewerIP!=clientIP) {
   writeJSONStatus(conn,"403 Forbidden",map[string]any{"ok":false});return true
  }
  switch req.Type {
  case "offer","answer":
   if len(req.SDP)<10 || len(req.SDP)>12000 {writeJSONStatus(conn,"400 Bad Request",map[string]any{"ok":false});return true}
  case "ice":
   if len(req.Candidate)<10||len(req.Candidate)>2048 {writeJSONStatus(conn,"400 Bad Request",map[string]any{"ok":false});return true}
  case "quality":
   if isCamera || !camRtcAllowedQuality(req.Quality) {writeJSONStatus(conn,"400 Bad Request",map[string]any{"ok":false});return true}
  case "control":
   if isCamera || !camRtcValidControl(req.Command,req.Value) {writeJSONStatus(conn,"400 Bad Request",map[string]any{"ok":false});return true}
  case "status":
   if !isCamera || req.RefID==0 || req.OK==nil ||
      !camRtcValidControl(req.Command,req.Value) || len(req.Error)>256 {
      writeJSONStatus(conn,"400 Bad Request",map[string]any{"ok":false});return true
   }
  case "stop": // either side can stop its peer session
  default: writeJSONStatus(conn,"400 Bad Request",map[string]any{"ok":false});return true
  }
  camRtcPostMessage(state,isCamera,camRtcMessage{Type:req.Type,Session:state.ViewerSession,SDP:req.SDP,Mid:req.Mid,MLine:req.MLine,Candidate:req.Candidate,Quality:req.Quality,Command:req.Command,Value:req.Value,RefID:req.RefID,OK:req.OK,Error:req.Error})
  writeJSONStatus(conn,"200 OK",map[string]any{"ok":true,"eventId":state.Counter})
  return true
 default:
  writeJSONStatus(conn,"405 Method Not Allowed",map[string]any{"ok":false})
  return true
 }
}


// Restrict remote hardware controls to known actions and bounded values.
func camRtcValidControl(command,value string) bool {
 switch command {
 case "camera": return value=="front" || value=="back"
 case "torch","mic": return value=="on" || value=="off"
 case "quality": return camRtcAllowedQuality(value)
 case "zoom":
  n,err:=strconv.ParseFloat(value,64)
  return err==nil && n>=1 && n<=8
 }
 return false
}
