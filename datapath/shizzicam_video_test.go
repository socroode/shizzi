package datapath

import (
 "bytes"
 "testing"
 "time"
)
func TestH264RelayPacketRoundTripAndBoundedIsolation(t *testing.T){
 camVideoHub.Lock()
 camVideoHub.cameras=map[string]*camH264Channel{}
 camVideoHub.Unlock()
 now:=time.Now()
 config:=camH264Packet{data:[]byte{0,0,0,1,0x67},width:640,height:480,flags:2}
 key:=camH264Packet{data:[]byte{0,0,0,1,0x65},width:640,height:480,flags:1}
 if !camH264Put("100",config,now)||!camH264Put("100",key,now){t.Fatal("frame rejected")}
 ch,history,ok:=camH264Subscribe("100")
 if !ok||len(history)!=2 {t.Fatalf("missing stream bootstrap %+v",history)}
 defer camH264Unsubscribe("100",ch)
 if _,_,ok:=camH264Subscribe("101");ok{t.Fatal("cross-camera subscriber accepted")}
 for i:=0;i<100;i++ {
  if !camH264Put("100",camH264Packet{data:[]byte{byte(i)},width:640,height:480},now) {t.Fatal("frame lost")}
 }
 if len(ch)>cap(ch){t.Fatal("subscriber queue unbounded")}
 var b bytes.Buffer
 if err:=writeVideoTestPacket(&b,key);err!=nil{t.Fatal(err)}
 pkt,err:=camH264Read(&b)
 if err!=nil||pkt.flags!=1||!bytes.Equal(pkt.data,key.data){t.Fatalf("roundtrip %+v %v",pkt,err)}
}
func writeVideoTestPacket(w *bytes.Buffer,p camH264Packet) error{
 var h [17]byte
 h[0]=byte(len(p.data)>>24);h[1]=byte(len(p.data)>>16);h[2]=byte(len(p.data)>>8);h[3]=byte(len(p.data))
 h[4]=byte(p.width>>8);h[5]=byte(p.width)
 h[6]=byte(p.height>>8);h[7]=byte(p.height)
 h[16]=p.flags
 _,e:=w.Write(h[:]);if e!=nil{return e}
 _,e=w.Write(p.data);return e
}
func TestH264StreamRejectsOversizedFrame(t *testing.T){
 invalid:=bytes.NewBuffer([]byte{0,5,0,0})
 invalid.Write(bytes.Repeat([]byte{0},13))
 if _,err:=camH264Read(invalid);err==nil {t.Fatal("oversized frame accepted")}
}
