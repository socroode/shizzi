package dev.shizzi.camrtc

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface

/** Hardware H.264 encoder: 22 FPS target, 360p/480p/720p with bitrate limits.
 * Camera frames arrive as rotated NV21 and are converted to NV12 for the codec.
 * Unsupported devices fail explicitly; JPEG fallback must never be called 22 FPS H.264.
 */
class CamH264Encoder(val width:Int,val height:Int):AutoCloseable {
 private val codec:MediaCodec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
 private val output=MediaCodec.BufferInfo()
 private var presentationUs=0L
 private var started=false
 init {
  try {
   require(width>0&&height>0&&width%2==0&&height%2==0)
   val colors=codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats
   check(colors.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)){
    "Encodage H.264 NV12 non pris en charge sur ce téléphone"
   }
   val rate=when(minOf(width,height)){
    in 0..360 -> 800_000
    in 361..480 -> 1_500_000
    else -> 2_500_000
   }
   val format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height).apply{
    setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
    setInteger(MediaFormat.KEY_BIT_RATE,rate)
    setInteger(MediaFormat.KEY_FRAME_RATE,22)
    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,2)
    setInteger(MediaFormat.KEY_BITRATE_MODE,MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
   }
   codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
   codec.start()
   started=true
  }catch(e:Exception){codec.release();throw e}
 }
 fun encode(nv21:ByteArray,pts:Long,emit:(ByteArray,Int,Long)->Unit):Int {
  if(!started)return 0
  require(nv21.size==width*height*3/2)
  val index=codec.dequeueInputBuffer(0)
  if(index>=0) {
   val input=codec.getInputBuffer(index)?:error("H264 input absent")
   input.clear()
   check(input.remaining()>=nv21.size)
   val luma=width*height
   input.put(nv21,0,luma)
   for(p in luma until nv21.size step 2){
    input.put(nv21[p+1]) // NV12 U
    input.put(nv21[p])   // NV12 V
   }
   presentationUs=maxOf(pts,presentationUs+1)
   codec.queueInputBuffer(index,0,nv21.size,presentationUs,0)
  }
  var count=0
  repeat(8){
   val out=codec.dequeueOutputBuffer(output,0)
   if(out>=0){
    if(output.size>0){
     val bytes=codec.getOutputBuffer(out)?:error("H264 output absent")
     bytes.position(output.offset)
     bytes.limit(output.offset+output.size)
     val data=ByteArray(output.size)
     bytes.get(data)
     var flags=0
     if(output.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME !=0)flags=flags or 1
     if(output.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG !=0)flags=flags or 2
     emit(data,flags,output.presentationTimeUs)
     count++
    }
    codec.releaseOutputBuffer(out,false)
   }
  }
  return count
 }
 override fun close(){if(started)runCatching{codec.stop()};codec.release()}
}

/** Decode AVC access units on a Surface owned by a ShizziCam mosaic tile. */
class CamH264Decoder(private val surface:Surface,val width:Int,val height:Int):AutoCloseable {
 private val codec:MediaCodec=MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
 private val output=MediaCodec.BufferInfo()
 private var ready=false
 init {
  try {
   codec.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height),surface,null,0)
   codec.start()
   ready=true
  }catch(e:Exception){codec.release();throw e}
 }
 fun decode(bytes:ByteArray,flags:Int,ptsUs:Long):Boolean {
  if(!ready||bytes.isEmpty()||bytes.size>262144)return false
  val inputIndex=codec.dequeueInputBuffer(5000)
  if(inputIndex>=0){
   val buffer=codec.getInputBuffer(inputIndex)?:error("H264 decoder input absent")
   buffer.clear()
   if(buffer.remaining()<bytes.size)return false
   buffer.put(bytes)
   codec.queueInputBuffer(inputIndex,0,bytes.size,ptsUs,
     if(flags and 2!=0)MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
  }else{return false}
  var rendered=false
  repeat(8){
   val index=codec.dequeueOutputBuffer(output,0)
   if(index>=0){
    codec.releaseOutputBuffer(index,true)
    rendered=true
   }
  }
  return rendered
 }
 override fun close(){if(ready)runCatching{codec.stop()};codec.release()}
}
