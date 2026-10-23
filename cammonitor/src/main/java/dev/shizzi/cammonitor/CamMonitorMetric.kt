package dev.shizzi.cammonitor
import org.json.JSONObject
internal object CamMonitorMetric{
 fun render(j:JSONObject):String{
  fun num(key:String,digits:Int=1):String=if(j.isNull(key)||!j.has(key))"indisponible"
    else ("%."+digits+"f").format(java.util.Locale.US,j.optDouble(key))
  val battery=if(j.optInt("battery",-1)>=0)"${j.optInt("battery")}%" else "indisponible"
  val resolution=if(j.optInt("width")>0)"${j.optInt("width")} × ${j.optInt("height")}" else "indisponible"
  val latency=if(j.isNull("latencyMillis"))"indisponible" else
    "${num("latencyMillis")} ms, estimation ± ${num("clockUncertaintyMillis")} ms"
  return """
  Caméra ${j.optString("account","?")} · ${if(j.optBoolean("connected"))"connectée" else "état non confirmé"}
  Batterie : $battery · $resolution · ${j.optString("quality","indisponible")}
  Codecs : ${j.optString("videoCodec","indisponible")} / ${j.optString("audioCodec","indisponible")}
  Vidéo entrante : ${num("videoInMbps",2)} Mbps · ${num("videoInFps")} FPS relayés
  Vidéo sortante : ${num("videoOutMbps",2)} Mbps · ${num("videoOutFps")} FPS relayés cumulés
  FPS capturés : ${num("capturedFps")} · encodés : ${num("encodedFps")}
  FPS des mises à jour de la vignette Visionneuse : ${num("renderedFps")}
  Audio caméra → routeur : ${num("audioInKbps")} kbps
  Audio routeur → Visionneuse : ${num("audioOutKbps")} kbps
  Interphone Visionneuse → routeur : ${num("intercomInKbps")} kbps
  Interphone routeur → caméra : ${num("intercomOutKbps")} kbps
  Latence : $latency
  Rejets vidéo : ${j.optLong("videoDrops")} · audio : ${j.optLong("audioDrops")}
  Pertes radio : indisponibles (les rejets ci-dessus sont applicatifs)
  Interruptions détectées : ${j.optLong("interruptions")}
  Audio : ${j.optString("audioState","indisponible")}
  """.trimIndent()
 }
}
