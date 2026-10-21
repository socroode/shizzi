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
