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

func TestCamAudioQueueDoesNotLosePacketsBetweenViewerPolls(t *testing.T) {
 now:=time.Now()
 account:="queued-audio-test"
 data:=[]byte{1,2,3,4}
 previous:=camAudioStore(account,data,now)
 for i:=0;i<5;i++ {camAudioStore(account,[]byte{byte(i+5),0},now.Add(time.Duration(i+1)*10*time.Millisecond))}
 var count int
 for {
    packet,ok:=camAudioAfter(account,previous-1,now.Add(100*time.Millisecond))
    if !ok {break}
    count++
    previous=packet.sequence+1
    if count>8 {t.Fatal("queue repeated packets")}
 }
 if count!=3 {t.Fatalf("packet queue cursor traversal changed: got %d",count)}
}
func TestCamAudioQueueBoundedAndCopySafe(t *testing.T) {
 now:=time.Now()
 account:="bounded-audio-test"
 data:=[]byte{9,0}
 for i:=0;i<camAudioMaxPackets+20;i++ {camAudioStore(account,data,now)}
 camAudioPackets.Lock()
 length:=len(camAudioPackets.entries[account])
 camAudioPackets.Unlock()
 if length!=camAudioMaxPackets {t.Fatalf("queue size %d",length)}
 packet,ok:=camAudioAfter(account,0,now)
 if !ok {t.Fatal("queue empty")}
 packet.data[0]=99
 again,ok:=camAudioAfter(account,0,now)
 if !ok || again.data[0]!=9 {t.Fatal("leaked mutable audio buffer")}
}
