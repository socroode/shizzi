package dev.shizzi.cammonitor

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CamMonitorMetricTest {
 @Test fun formatsActualVideoRatesWithoutFakingLinkCapacity() {
  val j=JSONObject().put("account","3001").put("videoInMbps",2.125)
   .put("videoInFps",21.5).put("videoOutMbps",1.75)
  val t=CamMonitorMetric.render(j)
  assertTrue(t.contains("Caméra 3001"))
  assertTrue(t.contains("2.13 Mbps"))
  assertTrue(t.contains("21.5 FPS"))
  assertFalse(t.contains("débit disponible"))
 }
}
