package dev.shizzi.camrtc

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface

/** Select a real supported format; dimensions report alignment adjustments instead of lying. */
class CamH264Encoder(requestedWidth:Int,requestedHeight:Int,bitrate:Int):AutoCloseable{
    val width:Int
    val height:Int
    val hardware:Boolean
    private val codec:MediaCodec
    private val color:Int
    private val output=MediaCodec.BufferInfo()
    init{
        val candidate=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter{it.isEncoder&&it.supportedTypes.any{t->t.equals(MediaFormat.MIMETYPE_VIDEO_AVC,true)}}
            .sortedBy{!it.isHardwareAccelerated}
            .firstOrNull{info->
                val caps=info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val video=caps.videoCapabilities
                val w=(requestedWidth+video.widthAlignment-1)/video.widthAlignment*video.widthAlignment
                val h=(requestedHeight+video.heightAlignment-1)/video.heightAlignment*video.heightAlignment
                caps.colorFormats.any{it==21||it==19}&&video.isSizeSupported(w,h)
            }?:error("Aucun encodeur H.264 compatible avec cette résolution")
        val caps=candidate.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val video=caps.videoCapabilities
        width=(requestedWidth+video.widthAlignment-1)/video.widthAlignment*video.widthAlignment
        height=(requestedHeight+video.heightAlignment-1)/video.heightAlignment*video.heightAlignment
        hardware=candidate.isHardwareAccelerated
        color=if(21 in caps.colorFormats)21 else 19
        codec=MediaCodec.createByCodecName(candidate.name)
        try{
            val format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height).apply{
                setInteger(MediaFormat.KEY_COLOR_FORMAT,color)
                setInteger(MediaFormat.KEY_BIT_RATE,bitrate.coerceIn(video.bitrateRange.lower,video.bitrateRange.upper))
                setInteger(MediaFormat.KEY_FRAME_RATE,22)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES,0)
                setInteger(MediaFormat.KEY_PRIORITY,0)
                if(caps.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))
                    setInteger(MediaFormat.KEY_BITRATE_MODE,MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start()
        }catch(e:Exception){codec.release();throw e}
    }
    fun requestKeyFrame(){codec.setParameters(Bundle().apply{putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0)})}
    fun encode(nv21:ByteArray,pts:Long,emit:(ByteArray,Int,Long)->Unit):Int{
        require(nv21.size==width*height*3/2)
        val index=codec.dequeueInputBuffer(0)
        if(index>=0){
            val input=codec.getInputBuffer(index)?:error("Entrée H.264 absente")
            input.clear();val luma=width*height;input.put(nv21,0,luma)
            if(color==21)for(p in luma until nv21.size step 2){input.put(nv21[p+1]);input.put(nv21[p])}
            else{
                for(p in luma until nv21.size step 2)input.put(nv21[p+1])
                for(p in luma until nv21.size step 2)input.put(nv21[p])
            }
            codec.queueInputBuffer(index,0,nv21.size,pts,0)
        }
        var count=0
        repeat(16){
            val out=codec.dequeueOutputBuffer(output,0)
            if(out>=0){
                if(output.size>0){
                    val buffer=codec.getOutputBuffer(out)?:error("Sortie H.264 absente")
                    buffer.position(output.offset);buffer.limit(output.offset+output.size)
                    var flags=0
                    if(output.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME!=0)flags=flags or 1
                    if(output.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG!=0)flags=flags or 2
                    emit(ByteArray(output.size).also{buffer.get(it)},flags,output.presentationTimeUs)
                    if(flags and 2==0)count++
                }
                codec.releaseOutputBuffer(out,false)
            }else if(out!=MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)return count
        }
        return count
    }
    override fun close(){runCatching{codec.stop()};codec.release()}
}

/** Render timestamps and callbacks are distinct from packet reception. */
class CamH264Decoder(private val surface:Surface,val width:Int,val height:Int,
    private val clock:CamClock,private val rendered:(Long,Long)->Unit):AutoCloseable{
    private val codec=MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val output=MediaCodec.BufferInfo()
    init{
        try{
            val format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height)
            val caps=codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            if(caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))
                format.setInteger(MediaFormat.KEY_LOW_LATENCY,1)
            codec.configure(format,surface,null,0)
            codec.setOnFrameRenderedListener({_,pts,nanos->rendered(pts,clock.nowUs()-(System.nanoTime()-nanos)/1000)},Handler(Looper.getMainLooper()))
            codec.start()
        }catch(e:Exception){codec.release();throw e}
    }
    private fun drain():Boolean{
        var submitted=false
        repeat(16){
            val out=codec.dequeueOutputBuffer(output,0)
            if(out>=0){
                val age=clock.nowUs()-output.presentationTimeUs
                if(clock.ready&&age>500000)codec.releaseOutputBuffer(out,false)
                else{
                    val delay=if(clock.ready)(150000-age).coerceIn(0,150000) else 0
                    codec.releaseOutputBuffer(out,System.nanoTime()+delay*1000)
                    submitted=true
                }
            }else if(out!=MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)return submitted
        }
        return submitted
    }
    fun decode(bytes:ByteArray,flags:Int,ptsUs:Long):Boolean{
        require(bytes.isNotEmpty()&&bytes.size<=262144)
        val drained=drain()
        val index=codec.dequeueInputBuffer(5000)
        if(index<0)return drained
        val input=codec.getInputBuffer(index)?:error("Entrée décodeur absente")
        input.clear();check(input.remaining()>=bytes.size);input.put(bytes)
        codec.queueInputBuffer(index,0,bytes.size,ptsUs,if(flags and 2!=0)MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
        return drain()||drained
    }
    override fun close(){runCatching{codec.stop()};codec.release()}
}
