package dev.shizzi.camrtc
import android.media.*
object CamAudioPlayback{
    fun create(context:android.content.Context):AudioTrack{
        val manager=context.getSystemService(AudioManager::class.java)
        check(manager.getStreamVolume(AudioManager.STREAM_MUSIC)>0){"Volume multimédia à zéro sur cet appareil"}
        val min=AudioTrack.getMinBufferSize(48000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT)
        check(min>0){"Haut-parleur 48 kHz indisponible"}
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(min,3840))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build().also{
                check(it.state==AudioTrack.STATE_INITIALIZED){"Sortie audio non initialisée"}
                manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull{device->
                    device.type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }?.let{device->it.setPreferredDevice(device)}
            }
    }
    fun write(track:AudioTrack,pcm:ByteArray){
        var offset=0
        while(offset<pcm.size){
            val n=track.write(pcm,offset,pcm.size-offset,AudioTrack.WRITE_BLOCKING)
            check(n>0){"Lecture audio interrompue ($n)"};offset+=n
        }
    }
}
