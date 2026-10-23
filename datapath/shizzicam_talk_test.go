package datapath

import (
 "bytes"
 "testing"
 "time"
)
func TestCamTalkBoundedOrderedAndScoped(t *testing.T) {
 now:=time.Now()
 camTalk.Lock()
 camTalk.queues=map[string][]camTalkPacket{}
 camTalk.next=0
 camTalk.Unlock()
 var first uint64
 for i:=0;i<50;i++ {
  id:=camTalkStore("cam-a",[]byte{byte(i)},now.Add(time.Duration(i)*time.Millisecond))
  if i==0 {first=id}
 }
 if len(camTalk.queues["cam-a"])!=camTalkLimit {t.Fatal("queue unbounded")}
 pkt,ok:=camTalkAfter("cam-a",first,now.Add(50*time.Millisecond))
 if !ok||pkt.data[0]!=18 {t.Fatalf("lost ordering: %+v",pkt)}
 if _,ok:=camTalkAfter("cam-b",0,now);ok {t.Fatal("cross-camera talk leak")}
 live,ok:=camTalkAfter("cam-a",0,now.Add(50*time.Millisecond))
 if !ok||!bytes.Equal(live.data,[]byte{49}){t.Fatal("live edge not selected")}
 if _,ok:=camTalkAfter("cam-a",0,now.Add(camTalkTTL+time.Second));ok {t.Fatal("stale talk packet replayed")}
}
