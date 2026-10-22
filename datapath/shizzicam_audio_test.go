package datapath
import ("bytes";"testing";"time")
func TestCamAudioRelay(t *testing.T) {
 now:=time.Now()
 data:=bytes.Repeat([]byte{42},6400)
 seq:=camAudioStore("camera-a",data,now)
 if seq==0 {t.Fatal("audio sequence zero")}
 packet,ok:=camAudioAfter("camera-a",0,now)
 if !ok||!bytes.Equal(packet.data,data) {t.Fatal("audio relay missing")}
 if _,ok:=camAudioAfter("camera-a",seq,now);ok {t.Fatal("duplicate audio")}
 if _,ok:=camAudioAfter("camera-a",0,now.Add(camAudioTTL+time.Millisecond));ok {t.Fatal("stale audio")}
}
func TestCamRemoteControlValidation(t *testing.T) {
 for _,v:=range [][2]string{{"camera","front"},{"camera","back"},{"torch","on"},{"mic","off"},{"zoom","2.5"},{"quality","360p"}} {
  if !camRtcValidControl(v[0],v[1]) {t.Fatalf("rejected: %v",v)}
 }
 for _,v:=range [][2]string{{"camera","other"},{"torch","boom"},{"zoom","100"},{"quality","1080p"},{"delete","all"}} {
  if camRtcValidControl(v[0],v[1]) {t.Fatalf("accepted: %v",v)}
 }
}

func TestCamAudioPreservesPacketsInOrder(t *testing.T) {
 now:=time.Now()
 a:=camAudioStore("ring-test",[]byte{1,1},now)
 b:=camAudioStore("ring-test",[]byte{2,2},now)
 c:=camAudioStore("ring-test",[]byte{3,3},now)
 if p,ok:=camAudioAfter("ring-test",0,now);!ok||p.sequence!=c {t.Fatal("live edge should be newest")}
 if p,ok:=camAudioAfter("ring-test",a,now);!ok||p.sequence!=b {t.Fatal("lost intermediate PCM packet")}
 if p,ok:=camAudioAfter("ring-test",b,now);!ok||p.sequence!=c {t.Fatal("lost last PCM packet")}
 if p,ok:=camAudioAfter("ring-test",c,now);ok {t.Fatalf("duplicate packet %d",p.sequence)}
 for i:=0;i<camAudioQueueLimit+10;i++ {camAudioStore("bounded-test",[]byte{4,4},now)}
 camAudioPackets.Lock()
 size:=len(camAudioPackets.entries["bounded-test"])
 camAudioPackets.Unlock()
 if size!=camAudioQueueLimit {t.Fatalf("unbounded buffer: %d",size)}
}
