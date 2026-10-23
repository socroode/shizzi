package dev.shizzi.camrtc

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Opus 48 kHz mono, 128 kbps target; transmit the real codec-specific data. */
class CamOpusEncoder:AutoCloseable {
    private val codec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info=MediaCodec.BufferInfo()
    @Volatile var configuration:CamAudioPacket?=null
        private set
    init {
        try {
            val format=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS,48000,1).apply{
                setInteger(MediaFormat.KEY_BIT_RATE,128000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,3840)
            }
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start()
        }catch(e:Exception){codec.release();throw e}
    }
    @Synchronized fun encode(pcm:ByteArray,ptsUs:Long):List<CamAudioPacket>{
        require(pcm.isNotEmpty()&&pcm.size%2==0&&pcm.size<=3840)
        val index=codec.dequeueInputBuffer(10000)
        if(index>=0){
            val input=codec.getInputBuffer(index)?:error("Entrée Opus absente")
            input.clear();input.put(pcm)
            codec.queueInputBuffer(index,0,pcm.size,ptsUs,0)
        }
        val result=ArrayList<CamAudioPacket>()
        repeat(16){
            val out=codec.dequeueOutputBuffer(info,0)
            if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                val format=codec.outputFormat
                val bytes=ByteArrayOutputStream()
                var identification=ByteArray(0)
                DataOutputStream(bytes).use{stream->
                    for(i in 0..2){
                        val b=format.getByteBuffer("csd-$i")?.duplicate()
                        var data=if(b!=null)ByteArray(b.remaining()).also{b.get(it)}
                            else if(i==0)error("En-tête Opus absent") else {
                                val nanos=if(i==1&&identification.size>=12)
                                    ((identification[10].toInt() and 255)+((identification[11].toInt() and 255) shl 8))*1000000000L/48000
                                    else if(i==2)80000000L else 0L
                                ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(nanos).array()
                            }
                        if(i==0)identification=data
                        require(data.size in 1..2048)
                        if(i>0){
                            require(data.size==8)
                            data=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                                .putLong(ByteBuffer.wrap(data).order(ByteOrder.nativeOrder()).long).array()
                        }
                        stream.writeInt(data.size);stream.write(data)
                    }
                }
                configuration=CamAudioPacket(bytes.toByteArray(),ptsUs,2)
                result.add(configuration!!)
            }else if(out>=0){
                if(info.size>0&&info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0){
                    val b=codec.getOutputBuffer(out)?:error("Sortie Opus absente")
                    b.position(info.offset);b.limit(info.offset+info.size)
                    result.add(CamAudioPacket(ByteArray(info.size).also{b.get(it)},info.presentationTimeUs))
                }
                codec.releaseOutputBuffer(out,false)
            }else return result
        }
        return result
    }
    override fun close(){runCatching{codec.stop()};codec.release()}
}
class CamOpusDecoder:AutoCloseable{
    private var codec:MediaCodec?=null
    private val info=MediaCodec.BufferInfo()
    private var configuration:ByteArray?=null
    @Synchronized fun decode(packet:CamAudioPacket):List<ByteArray>{
        if(packet.flags and 2!=0){
            if(configuration?.contentEquals(packet.data)==true)return emptyList()
            close()
            val format=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS,48000,1)
            val input=DataInputStream(ByteArrayInputStream(packet.data))
            for(i in 0..2){
                val n=input.readInt();require(n in 1..2048)
                var data=ByteArray(n).also{input.readFully(it)}
                if(i>0){
                    require(n==8)
                    data=ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
                        .putLong(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).long).array()
                }
                format.setByteBuffer("csd-$i",ByteBuffer.wrap(data))
            }
            require(input.available()==0)
            val created=MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            try{created.configure(format,null,null,0);created.start();codec=created}
            catch(e:Exception){created.release();throw e}
            configuration=packet.data.copyOf()
            return emptyList()
        }
        val active=codec?:return emptyList()
        val index=active.dequeueInputBuffer(10000)
        if(index>=0){
            val input=active.getInputBuffer(index)?:error("Entrée Opus absente")
            input.clear();input.put(packet.data)
            active.queueInputBuffer(index,0,packet.data.size,packet.ptsUs,0)
        }
        val result=ArrayList<ByteArray>()
        repeat(16){
            val out=active.dequeueOutputBuffer(info,0)
            if(out>=0){
                if(info.size>0){
                    val b=active.getOutputBuffer(out)?:error("PCM absent")
                    b.position(info.offset);b.limit(info.offset+info.size)
                    result.add(ByteArray(info.size).also{b.get(it)})
                }
                active.releaseOutputBuffer(out,false)
            }else if(out!=MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)return result
        }
        return result
    }
    override fun close(){codec?.let{runCatching{it.stop()};it.release()};codec=null;configuration=null}
}
