package dev.shizzi.conso
import org.json.JSONObject
/** Non-camera functions remain unchanged on older Shizzi hotspots. */
internal object CamCapability {
    fun supports(status:Int,json:String):Boolean {
        if(status!=200)return false
        return runCatching{
            val payload=JSONObject(json)
            payload.optBoolean("ok") && payload.optString("protocol")=="shizzicam-webrtc-v1" &&
                payload.optString("audio")=="Opus" && payload.optString("video")=="H264"
        }.getOrDefault(false)
    }
}
