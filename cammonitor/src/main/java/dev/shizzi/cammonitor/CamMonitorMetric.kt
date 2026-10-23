package dev.shizzi.cammonitor

import org.json.JSONObject

internal object CamMonitorMetric {
 fun render(json:JSONObject):String {
  val name=json.optString("account","?")
  fun two(key:String)= "%.2f".format(java.util.Locale.US,json.optDouble(key,0.0))
  fun one(key:String)= "%.1f".format(java.util.Locale.US,json.optDouble(key,0.0))
  return """
  Caméra $name
  Entrée locale : ${two("videoInMbps")} Mbps · ${one("videoInFps")} FPS
  Sortie vers visionneuse(s) : ${two("videoOutMbps")} Mbps · ${one("videoOutFps")} FPS
  Micro → routeur : ${one("audioInKbps")} kbps
  Routeur → visionneuse(s) : ${one("audioOutKbps")} kbps
  Voix visionneuse → routeur : ${one("intercomInKbps")} kbps
  Voix routeur → caméra : ${one("intercomOutKbps")} kbps
  """.trimIndent()
 }
}
