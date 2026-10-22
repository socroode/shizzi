package datapath

// ShizziCam local audio relay: PCM16 mono 16 kHz, bounded ephemeral queue,
// no Internet/voucher accounting, no persistent audio storage.
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

const camAudioMaxBytes = 16384
const camAudioTTL = 10*time.Second
const camAudioQueueLimit = 48
type camAudioPacket struct { data []byte; sequence uint64; updated time.Time }
var camAudioPackets = struct{
 sync.Mutex
 entries map[string][]camAudioPacket
 counter uint64
}{entries:make(map[string][]camAudioPacket)}

func camAudioStore(account string,pcm []byte,now time.Time) uint64 {
 camAudioPackets.Lock()
 defer camAudioPackets.Unlock()
 for name,queue:=range camAudioPackets.entries {
  if len(queue)==0 || now.Sub(queue[len(queue)-1].updated)>camAudioTTL {
   delete(camAudioPackets.entries,name)
  }
 }
 camAudioPackets.counter++
 packet:=camAudioPacket{data:append([]byte(nil),pcm...),sequence:camAudioPackets.counter,updated:now}
 queue:=append(camAudioPackets.entries[account],packet)
 if len(queue)>camAudioQueueLimit {queue=queue[len(queue)-camAudioQueueLimit:]}
 camAudioPackets.entries[account]=queue
 return packet.sequence
}
func camAudioAfter(account string,after uint64,now time.Time) (camAudioPacket,bool) {
 camAudioPackets.Lock()
 defer camAudioPackets.Unlock()
 queue:=camAudioPackets.entries[account]
 if len(queue)==0 {return camAudioPacket{},false}
 if now.Sub(queue[len(queue)-1].updated)>camAudioTTL {
  delete(camAudioPackets.entries,account);return camAudioPacket{},false
 }
 // New listener starts near live edge. Subsequent polls always receive the
 // oldest not-yet-consumed packet, rather than skipping all but the latest.
 if after==0 {
  p:=queue[len(queue)-1]
  return camAudioPacket{data:append([]byte(nil),p.data...),sequence:p.sequence,updated:p.updated},true
 }
 for _,p:=range queue {
  if p.sequence>after {
   return camAudioPacket{data:append([]byte(nil),p.data...),sequence:p.sequence,updated:p.updated},true
  }
 }
 return camAudioPacket{},false
}
func (m *TrafficManager) serveCameraAudio(conn net.Conn,req *http.Request,clientIP string) bool {
 if req.URL.Path!="/api/v1/cam/audio" {return false}
 if req.TLS==nil || !samePortalOrigin(req.Host) {
  writeJSONStatus(conn,"403 Forbidden",map[string]any{"ok":false,"error":"HTTPS required"});return true
 }
 if req.Method==http.MethodPost {
  valid,account:=m.mediaAccountIdentity(clientIP)
  if !valid||account=="" {writeJSONStatus(conn,"401 Unauthorized",map[string]any{"ok":false});return true}
  if req.ContentLength<=0||req.ContentLength>camAudioMaxBytes||
    !strings.EqualFold(req.Header.Get("Content-Type"),"application/octet-stream") {
   writeJSONStatus(conn,"413 Content Too Large",map[string]any{"ok":false});return true
  }
  body,err:=io.ReadAll(io.LimitReader(req.Body,camAudioMaxBytes+1))
  if err!=nil||len(body)!=int(req.ContentLength)||len(body)%2!=0 {
   writeJSONStatus(conn,"400 Bad Request",map[string]any{"ok":false});return true
  }
  camAudioStore(account,body,time.Now())
  writeJSONStatus(conn,"200 OK",map[string]any{"ok":true});return true
 }
 if req.Method!=http.MethodGet {writeJSONStatus(conn,"405 Method Not Allowed",map[string]any{"ok":false});return true}
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
 _,_=fmt.Fprintf(conn,"HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nCache-Control: no-store\r\nX-Shizzi-Cam-Audio-Seq: %d\r\nContent-Length: %d\r\nConnection: close\r\n\r\n",packet.sequence,len(packet.data))
 for off:=0;off<len(packet.data);{
  n,err:=conn.Write(packet.data[off:]);if n<=0||err!=nil{return true};off+=n
 }
 return true
}
