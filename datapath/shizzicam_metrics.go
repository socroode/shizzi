package datapath

// Local-only measured camera traffic. Video is JPEG in this candidate;
// these counters are observations, not an invented estimate of Wi-Fi capacity.
import (
 "net"
 "net/http"
 "sort"
 "sync"
 "time"
)

type camTrafficDirection int
const (
 camVideoIn camTrafficDirection = iota
 camVideoOut
 camAudioIn
 camAudioOut
 camIntercomIn
 camIntercomOut
 camMetricKinds
)
type camMeterBucket struct {
 second int64
 bytes [camMetricKinds]uint64
 packets [camMetricKinds]uint32
}
type camMeterEntry struct {
 last time.Time
 buckets [5]camMeterBucket
}
var camMeters = struct{
 sync.Mutex
 records map[string]*camMeterEntry
}{records:map[string]*camMeterEntry{}}

func camMeasure(account string, direction camTrafficDirection, bytes int, at time.Time) {
 if account=="" || bytes<=0 || direction<0 || direction>=camMetricKinds {return}
 second:=at.Unix()
 camMeters.Lock()
 defer camMeters.Unlock()
 for key,value:=range camMeters.records {
  if at.Sub(value.last)>30*time.Second {delete(camMeters.records,key)}
 }
 entry:=camMeters.records[account]
 if entry==nil {
  if len(camMeters.records)>=camMaxCameras {return}
  entry=&camMeterEntry{}
  camMeters.records[account]=entry
 }
 entry.last=at
 bucket:=&entry.buckets[int(second%int64(len(entry.buckets)))]
 if bucket.second!=second {
  *bucket=camMeterBucket{second:second}
 }
 bucket.bytes[direction]+=uint64(bytes)
 bucket.packets[direction]++
}
type camMetricItem struct {
 Account string `json:"account"`
 VideoInMbps float64 `json:"videoInMbps"`
 VideoOutMbps float64 `json:"videoOutMbps"`
 AudioInKbps float64 `json:"audioInKbps"`
 AudioOutKbps float64 `json:"audioOutKbps"`
 IntercomInKbps float64 `json:"intercomInKbps"`
 IntercomOutKbps float64 `json:"intercomOutKbps"`
 VideoInFPS float64 `json:"videoInFps"`
 VideoOutFPS float64 `json:"videoOutFps"`
 AgeMillis int64 `json:"ageMillis"`
}
func camMetricsSnapshot(now time.Time) []camMetricItem {
 camMeters.Lock()
 defer camMeters.Unlock()
 result:=[]camMetricItem{}
 current:=now.Unix()
 for account,entry:=range camMeters.records {
  if now.Sub(entry.last)>30*time.Second {
   delete(camMeters.records,account)
   continue
  }
  var bytes [camMetricKinds]uint64
  var packets [camMetricKinds]uint64
  for _,bucket:=range entry.buckets {
   // The two full preceding seconds, avoiding partially filled current second.
   if bucket.second>=current-2 && bucket.second<current {
    for i:=0;i<int(camMetricKinds);i++ {
     bytes[i]+=bucket.bytes[i]
     packets[i]+=uint64(bucket.packets[i])
    }
   }
  }
  result=append(result,camMetricItem{
   Account:account,
   VideoInMbps:float64(bytes[camVideoIn])*8/2_000_000,
   VideoOutMbps:float64(bytes[camVideoOut])*8/2_000_000,
   AudioInKbps:float64(bytes[camAudioIn])*8/2000,
   AudioOutKbps:float64(bytes[camAudioOut])*8/2000,
   IntercomInKbps:float64(bytes[camIntercomIn])*8/2000,
   IntercomOutKbps:float64(bytes[camIntercomOut])*8/2000,
   VideoInFPS:float64(packets[camVideoIn])/2,
   VideoOutFPS:float64(packets[camVideoOut])/2,
   AgeMillis:now.Sub(entry.last).Milliseconds(),
  })
 }
 sort.Slice(result,func(i,j int)bool{return result[i].Account<result[j].Account})
 return result
}
func (m *TrafficManager) serveCameraMetrics(conn net.Conn,req *http.Request,clientIP string) bool {
 if req.URL.Path!="/api/v1/cam/metrics" {return false}
 if req.TLS==nil || !samePortalOrigin(req.Host) {
  writeJSONStatus(conn,"403 Forbidden",map[string]any{"ok":false,"error":"HTTPS required"})
  return true
 }
 if req.Method!=http.MethodGet {
  writeJSONStatus(conn,"405 Method Not Allowed",map[string]any{"ok":false})
  return true
 }
 m.mu.Lock()
 _,allowed:=m.requireAdminTokenLocked(req,clientIP)
 m.mu.Unlock()
 if !allowed {
  writeJSONStatus(conn,"401 Unauthorized",map[string]any{"ok":false})
  return true
 }
 writeJSONStatus(conn,"200 OK",map[string]any{
  "ok":true,"scope":"local-camera-relay","sampleSeconds":2,
  "availableLinkMbps":nil,"availableLinkMeasured":false,
  "videoCodec":"JPEG","audioCodec":"PCM16","cameras":camMetricsSnapshot(time.Now()),
 })
 return true
}
