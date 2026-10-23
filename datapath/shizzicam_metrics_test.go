package datapath

import (
 "math"
 "testing"
 "time"
)

func TestCamTelemetryRollingWindow(t *testing.T) {
 camMeters.Lock()
 camMeters.records=map[string]*camMeterEntry{}
 camMeters.Unlock()
 now:=time.Unix(10_000,0)
 for i:=0;i<44;i++ {
  camMeasure("camera-a",camVideoIn,100_000,now.Add(-time.Second))
 }
 camMeasure("camera-a",camVideoOut,50_000,now.Add(-time.Second))
 camMeasure("camera-a",camAudioIn,8000,now.Add(-time.Second))
 items:=camMetricsSnapshot(now)
 if len(items)!=1 {t.Fatalf("expected one camera: %+v",items)}
 camera:=items[0]
 if math.Abs(camera.VideoInFPS-22)>0.01 {t.Fatalf("bad video fps: %+v",camera)}
 if math.Abs(camera.VideoInMbps-17.6)>0.01 {t.Fatalf("bad video rate: %+v",camera)}
 if camera.VideoOutFPS!=0.5 {t.Fatalf("bad video output count: %+v",camera)}
 if camera.AudioInKbps!=32 {t.Fatalf("bad audio kbps: %+v",camera)}
 if len(camMetricsSnapshot(now.Add(31*time.Second)))!=0 {t.Fatal("stale camera not expired")}
}
func TestCamTelemetryNeverReportsLinkSpeedFromTraffic(t *testing.T) {
 camMeters.Lock()
 camMeters.records=map[string]*camMeterEntry{}
 camMeters.Unlock()
 now:=time.Unix(20_000,0)
 camMeasure("cam-b",camVideoIn,1000,now)
 items:=camMetricsSnapshot(now.Add(time.Second))
 if len(items)!=1 || items[0].Account!="cam-b" {t.Fatalf("snapshot: %+v",items)}
 if items[0].VideoInMbps!=0.004 {t.Fatalf("expected measured transport only: %+v",items)}
}
