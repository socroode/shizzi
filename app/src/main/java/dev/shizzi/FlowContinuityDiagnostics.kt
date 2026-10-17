package dev.shizzi

fun flowEndReasonLabel(reason: String): String = when (reason) {
    "read-eof" -> "fin normale de l’envoi"
    "authorization-ended" -> "autorisation terminée"
    "service-stopped" -> "routeur arrêté"
    "peer-direction-ended" -> "connexion opposée terminée"
    "pacing-cancelled" -> "attente annulée"
    "udp-idle" -> "connexion inactive"
    "quota-exhausted" -> "quota épuisé"
    "transfer-not-authorized-or-quota" -> "autorisation ou quota indisponible"
    "read-error" -> "erreur de réception"
    "write-error", "short-write" -> "erreur d’envoi"
    "half-close-error", "half-close-unavailable" -> "fin partielle de connexion indisponible"
    "deadline-error" -> "erreur de délai réseau"
    else -> reason
}
