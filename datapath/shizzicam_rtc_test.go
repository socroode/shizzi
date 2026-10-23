package datapath

import (
	"testing"
	"time"
)

func TestCamRtcQueueAndQuality(t *testing.T) {
	if camRtcAllowedQuality("1080p") || !camRtcAllowedQuality("360p") || !camRtcAllowedQuality("auto") {
		t.Fatal("invalid quality rules")
	}
	s := &camRtcSession{}
	for i := 0; i < 70; i++ {
		camRtcPostMessage(s, true, camRtcMessage{Type: "ice"})
	}
	if len(s.ToViewer) != camRtcQueueLimit {
		t.Fatalf("queue len: %d", len(s.ToViewer))
	}
	if len(camRtcEventsAfter(s.ToViewer, 68)) != 2 {
		t.Fatal("cursor paging")
	}
	if len(camRtcEventsAfter(s.ToViewer, 70)) != 0 {
		t.Fatal("cursor repeated event")
	}
	camRtc.Lock()
	camRtc.cameras["expired"] = &camRtcSession{LastSeen: time.Now().Add(-time.Minute)}
	camRtcPruneLocked(time.Now())
	_, exists := camRtc.cameras["expired"]
	camRtc.Unlock()
	if exists {
		t.Fatal("camera not expired")
	}
}

func TestCamControlAckFieldsRoundTrip(t *testing.T) {
	s := &camRtcSession{}
	camRtcPostMessage(s, false, camRtcMessage{Type: "control", Session: "sess", Command: "camera", Value: "front", RequestID: "control-123456"})
	if s.ToCamera[0].RequestID != "control-123456" {
		t.Fatal("request lost")
	}
	camRtcPostMessage(s, true, camRtcMessage{Type: "status", Session: "sess", Command: "camera", Value: "front", RequestID: "control-123456", Success: true, Detail: "ok"})
	if !s.ToViewer[0].Success || s.ToViewer[0].RequestID != s.ToCamera[0].RequestID {
		t.Fatal("ack lost")
	}
}
