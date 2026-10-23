package datapath

// Admin-authorized, LAN-only Opus talk-back relay from viewer to one camera.
// No peer connection and no Internet/TUN forwarding is involved.
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
const camTalkTTL=2*time.Second
const camTalkLimit=32
const camTalkPacketLimit=4096
type camTalkPacket struct {data []byte; seq uint64; at time.Time}
var camTalk = struct {
 sync.Mutex
 queues map[string][]camTalkPacket
 next uint64
}{queues:map[string][]camTalkPacket{}}

func camTalkStore(account string, encoded []byte, now time.Time) uint64 {
 camTalk.Lock()
 defer camTalk.Unlock()
 for name,packets:=range camTalk.queues {
  if len(packets)==0 || now.Sub(packets[len(packets)-1].at)>camTalkTTL {
   delete(camTalk.queues,name)
  }
 }
 camTalk.next++
 entry:=camTalkPacket{data:append([]byte(nil),encoded...),seq:camTalk.next,at:now}
 queue:=append(camTalk.queues[account],entry)
 if len(queue)>camTalkLimit {queue=queue[len(queue)-camTalkLimit:]}
 camTalk.queues[account]=queue
 return entry.seq
}
func camTalkAfter(account string,after uint64,now time.Time)(camTalkPacket,bool){
 camTalk.Lock()
 defer camTalk.Unlock()
 queue:=camTalk.queues[account]
 if after==0 && len(queue)>0 {
  pkt:=queue[len(queue)-1]
  if now.Sub(pkt.at)<=camTalkTTL {
   return camTalkPacket{data:append([]byte(nil),pkt.data...),seq:pkt.seq,at:pkt.at},true
  }
 }
 for _,pkt:=range queue {
  if pkt.seq>after&&now.Sub(pkt.at)<=camTalkTTL {
   return camTalkPacket{data:append([]byte(nil),pkt.data...),seq:pkt.seq,at:pkt.at},true
  }
 }
 return camTalkPacket{},false
}
func (m *TrafficManager) serveCamTalk(conn net.Conn,r *http.Request,clientIP string) bool {
 if r.URL.Path!="/api/v1/cam/talk" {return false}
 if r.TLS==nil||!samePortalOrigin(r.Host) {
  writeJSONStatus(conn,"403 Forbidden",map[string]any{"ok":false,"error":"HTTPS required"})
  return true
 }
 if r.Method==http.MethodPost {
  m.mu.Lock()
  _,admin:=m.requireAdminTokenLocked(r,clientIP)
  m.mu.Unlock()
  if !admin {writeJSONStatus(conn,"401 Unauthorized",map[string]any{"ok":false});return true}
  account:=strings.TrimSpace(r.URL.Query().Get("account"))
  session:=r.URL.Query().Get("session")
  camRtc.Lock()
  camRtcPruneLocked(time.Now())
  state:=camRtc.cameras[account]
  authorized:=state!=nil&&session!=""&&session==state.ViewerSession&&clientIP==state.ViewerIP
  camRtc.Unlock()
  if !authorized {writeJSONStatus(conn,"403 Forbidden",map[string]any{"ok":false,"error":"camera viewer session required"});return true}
  if r.ContentLength<=0||r.ContentLength>camTalkPacketLimit||!strings.EqualFold(r.Header.Get("Content-Type"),"audio/opus") {
   writeJSONStatus(conn,"413 Content Too Large",map[string]any{"ok":false,"error":"Opus packet required"});return true
  }
  payload,err:=io.ReadAll(io.LimitReader(r.Body,camTalkPacketLimit+1))
  if err!=nil||len(payload)!=int(r.ContentLength) {
   writeJSONStatus(conn,"400 Bad Request",map[string]any{"ok":false});return true
  }
  camTalkStore(account,payload,time.Now())
  camMeasure(account,camIntercomIn,len(payload),time.Now())
  writeJSONStatus(conn,"200 OK",map[string]any{"ok":true})
  return true
 }
 if r.Method!=http.MethodGet {
  writeJSONStatus(conn,"405 Method Not Allowed",map[string]any{"ok":false});return true
 }
 allowed,account:=m.mediaAccountIdentity(clientIP)
 if !allowed||account=="" {
  writeJSONStatus(conn,"401 Unauthorized",map[string]any{"ok":false});return true
 }
 camRtc.Lock()
 camRtcPruneLocked(time.Now())
 state:=camRtc.cameras[account]
 active:=state!=nil&&state.ViewerSession!=""
 camRtc.Unlock()
 if !active {
  _,_=fmt.Fprint(conn,"HTTP/1.1 204 No Content\r\nCache-Control: no-store\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
  return true
 }
 after,_:=strconv.ParseUint(r.URL.Query().Get("after"),10,64)
 packet,ok:=camTalkAfter(account,after,time.Now())
 if !ok {
  _,_=fmt.Fprint(conn,"HTTP/1.1 204 No Content\r\nCache-Control: no-store\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
  return true
 }
 _,_=fmt.Fprintf(conn,"HTTP/1.1 200 OK\r\nContent-Type: audio/opus\r\nCache-Control: no-store\r\nX-Shizzi-Cam-Talk-Seq: %d\r\nContent-Length: %d\r\nConnection: close\r\n\r\n",packet.seq,len(packet.data))
 if n,err:=conn.Write(packet.data); n>0&&err==nil {camMeasure(account,camIntercomOut,n,time.Now())}
 return true
}
