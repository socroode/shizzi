package dev.shizzi.admin

/** Presentation must distinguish "trying" from "router accepted packets". */
internal enum class IntercomVisualMode { OFF, CONNECTING, TRANSMITTING, ERROR }
internal data class IntercomVisualModel(
    val description:String,
    val buttonLabel:String,
    val buttonColor:Int,
    val indicatorColor:Int
)
internal object IntercomVisualPalette {
    fun model(mode:IntercomVisualMode):IntercomVisualModel=when(mode){
        IntercomVisualMode.OFF -> IntercomVisualModel(
            "● Micro coupé","Parler",0xFF555D66.toInt(),0xFFD2D8DE.toInt())
        IntercomVisualMode.CONNECTING -> IntercomVisualModel(
            "● Connexion au relais…","Annuler",0xFFF4A340.toInt(),0xFFFFC86A.toInt())
        IntercomVisualMode.TRANSMITTING -> IntercomVisualModel(
            "● Micro actif · transmission Shizzi","Couper mon micro",0xFF14823B.toInt(),0xFF51E18B.toInt())
        IntercomVisualMode.ERROR -> IntercomVisualModel(
            "● Transmission impossible","Réessayer",0xFFB3261E.toInt(),0xFFFF7B73.toInt())
    }
}
