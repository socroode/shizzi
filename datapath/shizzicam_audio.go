package datapath

// Ephemeral LAN-only microphone relay. Audio and frames are independently authorized.
// Packetized Opus (48 kHz mono, nominal encoder bitrate 64 kbps).
import (
 "fmt"
 "io"
 "net"
 "net/http"
 "strconv"
 "strings"
 "sync"
 "time"
)

const camAudioMaxBytes = 4096
const camAudioTTL = 8*time.Second
const camAudioQueueLimit = 64
type camAudioPacket struct { data []byte; sequence uint64; updated time.Time }
var camAudioPackets = struct{
 sync.Mutex
 entries map[string][]camAudioPacket
 counter uint64
}{entries: make(map[string][]camAudioPacket)}
// Keep each camera's recent packets, not just the last packet. Never replay
// a full old buffer when a new viewer joins: after=0 starts at the live edge.
func camAudioStore(account string,pcm []byte,now time.Time) uint64 {
 camAudioPackets.Lock()
 defer camAudioPackets.Unlock()
 for name,packets:=range camAudioPackets.entries {
  index:=0
  for index<len(packets) && now.Sub(packets[index].updated)>camAudioTTL {index++}
  if index==len(packets) {delete(camAudioPackets.entries,name)} else if index>0 {
   camAudioPackets.entries[name]=append([]camAudioPacket(nil),packets[index:]...)
  }
 }
 camAudioPackets.counter++
 pkt:=camAudioPacket{data:append([]byte(nil),pcm...),sequence:camAudioPackets.counter,updated:now}
 list:=append(camAudioPackets.entries[account],pkt)
 if len(list)>camAudioQueueLimit {list=list[len(list)-camAudioQueueLimit:]}
 camAudioPackets.entries[account]=list
 return pkt.sequence
}
func camAudioAfter(account string,after uint64,now time.Time) (camAudioPacket,bool) {
 camAudioPackets.Lock()
 defer camAudioPackets.Unlock()
 list:=camAudioPackets.entries[account]
 if after==0 {
  if len(list)>0 {
   p:=list[len(list)-1]
   if now.Sub(p.updated)<=camAudioTTL {
    return camAudioPacket{data:append([]byte(nil),p.data...),sequence:p.sequence,updated:p.updated},true
   }
  }
  return camAudioPacket{},false
 }
 for _,p:=range list {
  if p.sequence>after && now.Sub(p.updated)<=camAudioTTL {
   return camAudioPacket{data:append([]byte(nil),p.data...),sequence:p.sequence,updated:p.updated},true
  }
 }
 return camAudioPacket{},false
}
func (m *TrafficManager) serveCameraAudio(conn net.Conn,req *http.Request,clientIP string) bool {
 if req.URL.Path!="/api/v1/cam/audio" {return false}
 if req.TLS==nil || !samePortalOrigin(req.Host) {
  writeJSONStatus(conn,"403 Forbidden",map[string]any{"ok":false,"error":"HTTPS required"})
  return true
 }
 if req.Method==http.MethodPost {
  valid,account:=m.mediaAccountIdentity(clientIP)
  if !valid||account=="" {writeJSONStatus(conn,"401 Unauthorized",map[string]any{"ok":false});return true}
  if req.ContentLength<=0||req.ContentLength>camAudioMaxBytes||
    !strings.EqualFold(req.Header.Get("Content-Type"),"audio/opus") {
   writeJSONStatus(conn,"413 Content Too Large",map[string]any{"ok":false});return true
  }
  body,err:=io.ReadAll(io.LimitReader(req.Body,camAudioMaxBytes+1))
  if err!=nil||len(body)!=int(req.ContentLength) {
   writeJSONStatus(conn,"400 Bad Request",map[string]any{"ok":false});return true
  }
  camAudioStore(account,body,time.Now())
  camMeasure(account,camAudioIn,len(body),time.Now())
  writeJSONStatus(conn,"200 OK",map[string]any{"ok":true})
  return true
 }
 if req.Method!=http.MethodGet {
  writeJSONStatus(conn,"405 Method Not Allowed",map[string]any{"ok":false});return true
 }
 m.mu.Lock()
 _,allowed:=m.requireAdminTokenLocked(req,clientIP)
 m.mu.Unlock()
 if !allowed {writeJSONStatus(conn,"401 Unauthorized",map[string]any{"ok":false});return true}
 account:=strings.TrimSpace(req.URL.Query().Get("account"))
 if account=="" {writeJSONStatus(conn,"400 Bad Request",map[string]any{"ok":false});return true}
 after,_:=strconv.ParseUint(req.URL.Query().Get("after"),10,64)
 packet,ok:=camAudioAfter(account,after,time.Now())
 if !ok {
  _,_=fmt.Fprint(conn,"HTTP/1.1 204 No Content\r\nCache-Control: no-store\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
  return true
 }
 _,_=fmt.Fprintf(conn,"HTTP/1.1 200 OK\r\nContent-Type: audio/opus\r\nCache-Control: no-store\r\nX-Shizzi-Cam-Audio-Seq: %d\r\nContent-Length: %d\r\nConnection: close\r\n\r\n",packet.sequence,len(packet.data))
 if n,err:=conn.Write(packet.data); n>0&&err==nil {camMeasure(account,camAudioOut,n,time.Now())}
 return true
}
