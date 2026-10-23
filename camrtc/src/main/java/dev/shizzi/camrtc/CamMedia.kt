package dev.shizzi.camrtc

import android.os.SystemClock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Authenticated router time, independent of handset wall clock settings. */
class CamClock {
    @Volatile private var offsetUs=0L
    @Volatile var uncertaintyUs=Long.MAX_VALUE
        private set
    val ready get()=uncertaintyUs!=Long.MAX_VALUE
    fun nowUs()=SystemClock.elapsedRealtimeNanos()/1000+offsetUs
    fun synchronize(fetch:()->JSONObject) {
        var best=Long.MAX_VALUE
        var offset=0L
        repeat(3) {
            val a=SystemClock.elapsedRealtimeNanos()/1000
            val response=fetch()
            val b=SystemClock.elapsedRealtimeNanos()/1000
            if(b-a<best){best=b-a;offset=response.getLong("routerUs")-(a+b)/2}
        }
        offsetUs=offset;uncertaintyUs=best/2
    }
}
data class CamAudioPacket(val data:ByteArray,val ptsUs:Long,val flags:Int=0,val sequence:Long=0) {
    fun write(out:DataOutputStream) {
        require(data.size in 1..4096)
        out.writeInt(data.size);out.writeLong(ptsUs);out.writeLong(sequence)
        out.writeByte(flags);out.write(data);out.flush()
    }
    companion object {
        fun read(input:DataInputStream):CamAudioPacket {
            val n=input.readInt();require(n in 1..4096)
            val pts=input.readLong();val seq=input.readLong();val flags=input.readUnsignedByte()
            require(flags==0||flags==2)
            return CamAudioPacket(ByteArray(n).also{input.readFully(it)},pts,flags,seq)
        }
    }
}
object CamQuality {
    data class Profile(val name:String,val width:Int,val height:Int,val bitrate:Int)
    val profiles=listOf(Profile("144p",256,144,180000),Profile("240p",426,240,350000),
        Profile("360p",640,360,650000),Profile("480p",854,480,1100000),Profile("720p",1280,720,2200000))
    val names=listOf("auto")+profiles.map{it.name}
    fun profile(name:String)=profiles.firstOrNull{it.name==name}?:profiles[2]
}
class CamRate {
    private val count=AtomicLong()
    private var last=SystemClock.elapsedRealtimeNanos()
    fun event(){count.incrementAndGet()}
    @Synchronized fun sample():Double {
        val now=SystemClock.elapsedRealtimeNanos()
        val value=count.getAndSet(0)*1e9/(now-last).coerceAtLeast(1)
        last=now;return value
    }
}

/** A previous interphone conversation cannot confirm playback of a new one. */
object CamPlaybackAcknowledgement{
    fun confirmed(status:JSONObject,startedRouterUs:Long):Boolean=
        startedRouterUs>0&&status.optBoolean("audioPlaybackVerified")&&
            status.optLong("audioPlaybackRouterUs",0)>=startedRouterUs
}
