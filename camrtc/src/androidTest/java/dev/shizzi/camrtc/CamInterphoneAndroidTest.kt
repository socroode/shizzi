package dev.shizzi.camrtc

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import kotlin.math.*

/** Real Android encoder -> production Go TLS relay -> real Android decoder and AudioTrack.
 * A deterministic PCM tone replaces the physical microphone; this does not prove audible handset sound.
 */
@RunWith(AndroidJUnit4::class)
class CamInterphoneAndroidTest {
    @Test(timeout=60000) fun interphoneSignalSurvivesRouterAndReachesAndroidPlayback() {
        val raw=InstrumentationRegistry.getArguments().getString("fixture")?:error("Missing local Go fixture")
        val fixture=JSONObject(String(Base64.decode(raw,Base64.DEFAULT)))
        val der=Base64.decode(fixture.getString("certificateDerBase64"),Base64.DEFAULT)
        val cert=CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der))
        val store=KeyStore.getInstance(KeyStore.getDefaultType()).apply{load(null);setCertificateEntry("local-fixture",cert)}
        val trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply{init(store)}
        val tls=SSLContext.getInstance("TLS").apply{init(null,trust.trustManagers,null)}
        fun connection(camera:Boolean,path:String):HttpsURLConnection {
            val port=fixture.getInt(if(camera)"cameraPort" else "viewerPort")
            return (URL("https://10.0.2.2:$port$path").openConnection() as HttpsURLConnection).apply{
                sslSocketFactory=tls.socketFactory
                hostnameVerifier=HostnameVerifier{host,session->host=="10.0.2.2"&&session.peerCertificates.first().encoded.contentEquals(der)}
                setRequestProperty("Host","192.0.2.1")
                if(!camera)setRequestProperty("X-Shizzi-Admin-Token",fixture.getString("adminToken"))
                connectTimeout=5000;readTimeout=8000;instanceFollowRedirects=false
            }
        }
        val clock=CamClock()
        clock.synchronize{
            val c=connection(false,"/api/v1/cam/clock")
            try{check(c.responseCode==200);JSONObject(c.inputStream.bufferedReader().use{it.readText()})}finally{c.disconnect()}
        }
        val context=ApplicationProvider.getApplicationContext<Context>()
        val audio=context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)*2/3).coerceAtLeast(1),0)
        val receive=connection(true,"/api/v1/cam/talk-stream")
        val send=connection(false,"/api/v1/cam/talk-stream?account=${fixture.getString("account")}&session=${fixture.getString("session")}")
        send.requestMethod="POST";send.doOutput=true;send.readTimeout=0
        send.setRequestProperty("Content-Type","application/x-shizzicam-opus-v3");send.setChunkedStreamingMode(1024)
        val receiver=Executors.newSingleThreadExecutor()
        try{
            assertEquals("Camera subscription HTTP",200,receive.responseCode)
            val result=receiver.submit<DoubleArray>{
                val decoder=CamOpusDecoder()
                val player=CamAudioPlayback.create(context)
                var samples=0L;var squares=0.0;var peak=0;var media=0;var configs=0
                try{
                    player.play()
                    val input=DataInputStream(receive.inputStream)
                    while(samples<48000){
                        val p=CamAudioPacket.read(input)
                        if(p.flags==2)configs++ else media++
                        for(pcm in decoder.decode(p)){
                            val b=ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
                            while(b.remaining()>=2){val n=b.short.toInt();samples++;squares+=n.toDouble()*n;peak=max(peak,abs(n))}
                            CamAudioPlayback.write(player,pcm)
                        }
                    }
                    val until=SystemClock.elapsedRealtime()+2000
                    while(player.playbackHeadPosition<4800 && SystemClock.elapsedRealtime()<until)SystemClock.sleep(10)
                    doubleArrayOf(samples.toDouble(),sqrt(squares/samples),peak.toDouble(),media.toDouble(),configs.toDouble(),player.playbackHeadPosition.toDouble())
                }finally{decoder.close();runCatching{player.stop()};player.release()}
            }
            val encoder=CamOpusEncoder()
            val output=DataOutputStream(send.outputStream)
            try{
                val anchor=SystemClock.elapsedRealtime()
                for(frame in 0 until 110){
                    val pcm=ByteBuffer.allocate(1920).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(960){sample->pcm.putShort((12000*sin(2*PI*440*(frame*960+sample)/48000)).toInt().toShort())}
                    for(packet in encoder.encode(pcm.array(),clock.nowUs()-20000))packet.write(output)
                    val wait=anchor+(frame+1)*20-SystemClock.elapsedRealtime()
                    if(wait>0)SystemClock.sleep(wait)
                    if(result.isDone)break
                }
                val decoded=result.get(12,TimeUnit.SECONDS)
                println("INTERPHONE_ANDROID samples=${decoded[0]} rms=${decoded[1]} peak=${decoded[2]} packets=${decoded[3]} configs=${decoded[4]} playbackFrames=${decoded[5]}")
                assertTrue("Decoded one second of PCM",decoded[0]>=48000)
                assertTrue("Decoded tone must not be silence",decoded[1]>1000)
                assertTrue("Received config and independent media frames",decoded[4]>=1&&decoded[3]>=20)
                assertTrue("Android AudioTrack must consume frames",decoded[5]>=4800)
                val metrics=connection(false,"/api/v1/cam/metrics")
                try{
                    assertEquals(200,metrics.responseCode)
                    val cameras=JSONObject(metrics.inputStream.bufferedReader().use{it.readText()}).getJSONArray("cameras")
                    assertTrue((0 until cameras.length()).any{cameras.getJSONObject(it).optLong("intercomAcceptedRouterUs")>0})
                }finally{metrics.disconnect()}
            }finally{encoder.close();runCatching{output.close()}}
        }finally{send.disconnect();receive.disconnect();receiver.shutdownNow()}
    }
}
