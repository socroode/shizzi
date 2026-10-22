package datapath
import ("testing";"time")
func TestCamRtcQueueAndQuality(t *testing.T){
 if camRtcAllowedQuality("1080p") || !camRtcAllowedQuality("360p") || !camRtcAllowedQuality("auto") {t.Fatal("invalid quality rules")}
 s:= &camRtcSession{}
 for i:=0;i<70;i++ { camRtcPostMessage(s,true,camRtcMessage{Type:"ice"}) }
 if len(s.ToViewer)!=camRtcQueueLimit {t.Fatalf("queue len: %d",len(s.ToViewer))}
 if len(camRtcEventsAfter(s.ToViewer,68))!=2 {t.Fatal("cursor paging")}
 if len(camRtcEventsAfter(s.ToViewer,70))!=0 {t.Fatal("cursor repeated event")}
 camRtc.Lock()
 camRtc.cameras["expired"]= &camRtcSession{LastSeen:time.Now().Add(-time.Minute)}
 camRtcPruneLocked(time.Now())
 _,exists:=camRtc.cameras["expired"]
 camRtc.Unlock()
 if exists {t.Fatal("camera not expired")}
}

func TestCamRemoteControlStatusRouting(t *testing.T) {
 s:=&camRtcSession{}
 camRtcPostMessage(s,false,camRtcMessage{Type:"control",Session:"one",Command:"camera",Value:"front"})
 if len(s.ToCamera)!=1 || s.ToCamera[0].ID==0 {t.Fatal("command not queued to camera")}
 ok:=true
 camRtcPostMessage(s,true,camRtcMessage{Type:"status",Session:"one",Command:"camera",Value:"front",RefID:s.ToCamera[0].ID,OK:&ok})
 if len(s.ToViewer)!=1 || s.ToViewer[0].RefID!=s.ToCamera[0].ID || s.ToViewer[0].OK==nil || !*s.ToViewer[0].OK {
    t.Fatal("camera command acknowledgement not routed to viewer")
 }
}
