package dev.shizzi.conso
import org.junit.Assert.*
import org.junit.Test
class CamCapabilityTest {
 @Test fun oldHotspotMaintainsLegacyShizziWithoutCamera(){
   assertFalse(CamCapability.supports(404,""))
   assertFalse(CamCapability.supports(200,"{}"))
   assertFalse(CamCapability.supports(200,"<html>Shizzi 0.4.3.5.11</html>"))
 }
 @Test fun cameraModeRequiresExactAuthenticatedProtocol(){
   assertTrue(CamCapability.supports(200,"""{"ok":true,"protocol":"shizzicam-webrtc-v1","video":"H264","audio":"Opus"}"""))
   assertFalse(CamCapability.supports(200,"""{"ok":true,"protocol":"other","video":"H264","audio":"Opus"}"""))
   assertFalse(CamCapability.supports(401,"""{"ok":true,"protocol":"shizzicam-webrtc-v1","video":"H264","audio":"Opus"}"""))
 }
}
