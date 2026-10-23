package dev.shizzi.camrtc
import java.io.*
import org.junit.Assert.*
import org.junit.Test
class CamMediaTest{
 @Test fun previousConversationNeverConfirmsNewAudio(){
  val state=org.json.JSONObject().put("audioPlaybackVerified",true).put("audioPlaybackRouterUs",1000)
  assertFalse(CamPlaybackAcknowledgement.confirmed(state,2000))
  state.put("audioPlaybackRouterUs",2500)
  assertTrue(CamPlaybackAcknowledgement.confirmed(state,2000))
  state.remove("audioPlaybackRouterUs")
  assertFalse(CamPlaybackAcknowledgement.confirmed(state,2000))
 }
 @Test fun fiveProfilesHaveExactTargets(){
  assertEquals(listOf("144p","240p","360p","480p","720p"),CamQuality.profiles.map{it.name})
  assertEquals(listOf(180000,350000,650000,1100000,2200000),CamQuality.profiles.map{it.bitrate})
  assertEquals(256,CamQuality.profile("144p").width)
 }
 @Test fun opusPacketPreservesCaptureTimeSequenceAndConfiguration(){
  val original=CamAudioPacket(byteArrayOf(1,2,3),123456789L,2,25)
  val bytes=ByteArrayOutputStream()
  original.write(DataOutputStream(bytes))
  val actual=CamAudioPacket.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
  assertArrayEquals(original.data,actual.data)
  assertEquals(original.ptsUs,actual.ptsUs);assertEquals(25L,actual.sequence);assertEquals(2,actual.flags)
 }
 @Test(expected=IllegalArgumentException::class) fun rejectsOversizedPackets(){
  val bytes=ByteArrayOutputStream();DataOutputStream(bytes).writeInt(4097)
  CamAudioPacket.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
 }
}
