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

func TestCamControlAckCommandID(t *testing.T) {
 session:=&camRtcSession{}
 camRtcPostMessage(session,false,camRtcMessage{Type:"control",Command:"camera",Value:"front",CommandID:"req-24"})
 camRtcPostMessage(session,true,camRtcMessage{Type:"status",Command:"camera",Value:"front",CommandID:"req-24",Accepted:true})
 if len(session.ToCamera)!=1||session.ToCamera[0].CommandID!="req-24" {t.Fatal("command lost request ID")}
 if len(session.ToViewer)!=1||session.ToViewer[0].CommandID!="req-24"||!session.ToViewer[0].Accepted {t.Fatal("camera acknowledgement lost")}
}
