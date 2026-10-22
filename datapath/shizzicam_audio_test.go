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
 after:=uint64(0)
 first:=camAudioStore(account,[]byte{1,2,3,4},now)
 if first==0 {t.Fatal("missing sequence")}
 for i:=0;i<5;i++ {camAudioStore(account,[]byte{byte(i+5),0},now.Add(time.Duration(i+1)*10*time.Millisecond))}
 count:=0
 for {
    packet,ok:=camAudioAfter(account,after,now.Add(100*time.Millisecond))
    if !ok {break}
    if packet.sequence<=after {t.Fatal("duplicate or reversed sequence")}
    count++
    after=packet.sequence
    if count>7 {t.Fatal("queue repeated packets")}
 }
 if count!=6 {t.Fatalf("audio packet queue lost data: got %d, expected 6",count)}
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
