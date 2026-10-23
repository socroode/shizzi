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

func TestCamAudioOrderAndPerCameraIsolation(t *testing.T) {
 now:=time.Now()
 var ids []uint64
 for i:=0;i<75;i++ {ids=append(ids,camAudioStore("order-test",[]byte{byte(i),1},now.Add(time.Duration(i)*time.Millisecond)))}
 if len(camAudioPackets.entries["order-test"])!=camAudioQueueLimit {t.Fatal("audio queue not bounded")}
 first,ok:=camAudioAfter("order-test",ids[0],now.Add(75*time.Millisecond))
 if !ok || first.sequence!=ids[11] {t.Fatalf("expected first retained packet %d, got %+v",ids[11],first)}
 second,ok:=camAudioAfter("order-test",first.sequence,now.Add(75*time.Millisecond))
 if !ok || second.sequence!=ids[12] {t.Fatal("audio packet skipped")}
 live,ok:=camAudioAfter("order-test",0,now.Add(75*time.Millisecond))
 if !ok || live.sequence!=ids[74] {t.Fatal("initial listener not at live edge")}
 camAudioStore("other-cam",[]byte{8,9},now)
 if pkt,ok:=camAudioAfter("other-cam",0,now);!ok || !bytes.Equal(pkt.data,[]byte{8,9}) {t.Fatal("cam mixup")}
}
