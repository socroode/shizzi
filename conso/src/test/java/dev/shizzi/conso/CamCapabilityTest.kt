package dev.shizzi.conso
import org.junit.Assert.*
import org.junit.Test
class CamCapabilityTest {
 @Test fun oldHotspotMaintainsLegacyShizziWithoutCamera(){
   assertFalse(CamCapability.supports(404,""))
   assertFalse(CamCapability.supports(200,"{}"))
   assertFalse(CamCapability.supports(200,"<html>Shizzi 0.4.3.5.11</html>"))
 }
 @Test fun cameraModeRequiresExactRouterRelayProtocol(){
   assertTrue(CamCapability.supports(200,"""{"ok":true,"protocol":"shizzicam-relay-jpeg-pcm-v1","video":"JPEG","audio":"PCM16","transport":"https-router-relay","relayAvailable":true}"""))
   assertFalse(CamCapability.supports(200,"""{"ok":true,"protocol":"other","video":"JPEG","audio":"PCM16","transport":"https-router-relay","relayAvailable":true}"""))
   assertFalse(CamCapability.supports(401,"""{"ok":true,"protocol":"shizzicam-relay-jpeg-pcm-v1","video":"JPEG","audio":"PCM16","transport":"https-router-relay","relayAvailable":true}"""))
 }
}
