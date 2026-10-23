package dev.shizzi.camrtc

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * Audio/Opus 48 kHz mono at 64 kbit/s.
 * Never silently label PCM or G.711 as Opus. If unsupported, callers report failure.
 */
class CamOpusEncoder : AutoCloseable {
 private val codec:MediaCodec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
 private val info=MediaCodec.BufferInfo()
 private var ptsUs=0L
 init {
  try {
   val format=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS,48000,1).apply {
    setInteger(MediaFormat.KEY_BIT_RATE,64000)
    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16384)
   }
   codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
   codec.start()
  } catch(e:Exception){codec.release();throw e}
 }
 @Synchronized fun encode(pcm:ByteArray):List<ByteArray> {
  require(pcm.isNotEmpty()&&pcm.size%2==0&&pcm.size<=16384)
  val index=codec.dequeueInputBuffer(20000)
  if(index>=0){
   val input=codec.getInputBuffer(index)?:error("Opus encoder input absent")
   input.clear()
   check(input.remaining()>=pcm.size)
   input.put(pcm)
   codec.queueInputBuffer(index,0,pcm.size,ptsUs,0)
   ptsUs+=pcm.size.toLong()*1_000_000/(48000*2)
  }
  val packets=ArrayList<ByteArray>()
  repeat(8){
   val out=codec.dequeueOutputBuffer(info,0)
   if(out<0)return@repeat
   if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
    val buffer=codec.getOutputBuffer(out)?:error("Opus output absent")
    buffer.position(info.offset);buffer.limit(info.offset+info.size)
    packets.add(ByteArray(info.size).also{buffer.get(it)})
   }
   codec.releaseOutputBuffer(out,false)
  }
  return packets
 }
 override fun close(){runCatching{codec.stop()};codec.release()}
}

class CamOpusDecoder : AutoCloseable {
 private val codec:MediaCodec=MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
 private val info=MediaCodec.BufferInfo()
 private var ptsUs=0L
 init {
  try {
   val format=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS,48000,1)
   // RFC 7845 Opus identification header: mono, 48 kHz, no channel mapping.
   val opusHead=byteArrayOf(
    79,112,117,115,72,101,97,100,1,1,0,0,
    0x80.toByte(),0xBB.toByte(),0,0,0,0,0
   )
   format.setByteBuffer("csd-0",ByteBuffer.wrap(opusHead))
   codec.configure(format,null,null,0)
   codec.start()
  }catch(e:Exception){codec.release();throw e}
 }
 @Synchronized fun decode(packet:ByteArray):List<ByteArray> {
  require(packet.isNotEmpty()&&packet.size<=4096)
  val inputIndex=codec.dequeueInputBuffer(20000)
  if(inputIndex>=0){
   val input=codec.getInputBuffer(inputIndex)?:error("Opus decoder input absent")
   input.clear();check(input.remaining()>=packet.size)
   input.put(packet)
   codec.queueInputBuffer(inputIndex,0,packet.size,ptsUs,0)
   ptsUs+=20000
  }
  val frames=ArrayList<ByteArray>()
  repeat(8){
   val out=codec.dequeueOutputBuffer(info,0)
   if(out<0)return@repeat
   if(info.size>0){
    val buffer=codec.getOutputBuffer(out)?:error("Opus PCM output absent")
    buffer.position(info.offset);buffer.limit(info.offset+info.size)
    frames.add(ByteArray(info.size).also{buffer.get(it)})
   }
   codec.releaseOutputBuffer(out,false)
  }
  return frames
 }
 override fun close(){runCatching{codec.stop()};codec.release()}
}
