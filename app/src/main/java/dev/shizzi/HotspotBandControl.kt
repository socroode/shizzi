package dev.shizzi

import android.content.Context
import android.os.Build

data class HotspotBandApplyResult(
    val applied: Boolean,
    val detail: String,
)

class HotspotBandControl(private val context: Context) {

    fun apply(mode: HotspotBand): HotspotBandApplyResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return HotspotBandApplyResult(false, "SoftApConfiguration requires Android 11+")
        }

        return runCatching {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE)
                ?: error("wifi service unavailable")

            val configClass = Class.forName("android.net.wifi.SoftApConfiguration")
            val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")

            val current = wifiManager.javaClass
                .getMethod("getSoftApConfiguration")
                .invoke(wifiManager)
                ?: error("getSoftApConfiguration returned null")

            val targetBand = when (mode) {
                HotspotBand.AUTO -> BAND_2GHZ or BAND_5GHZ
                HotspotBand.BAND_2_4_GHZ -> BAND_2GHZ
                HotspotBand.BAND_5_GHZ -> BAND_5GHZ
            }

            val currentBand = runCatching {
                configClass.getMethod("getBand").invoke(current) as Int
            }.getOrNull()

            if (currentBand == targetBand) {
                return@runCatching HotspotBandApplyResult(
                    true,
                    "hotspot band already ${mode.name} (mask=$targetBand)",
                )
            }

            val builder = builderClass
                .getConstructor(configClass)
                .newInstance(current)

            builderClass
                .getMethod("setBand", Int::class.javaPrimitiveType)
                .invoke(builder, targetBand)

            val updated = builderClass.getMethod("build").invoke(builder)

            val accepted = wifiManager.javaClass
                .getMethod("setSoftApConfiguration", configClass)
                .invoke(wifiManager, updated) as? Boolean ?: false

            HotspotBandApplyResult(
                accepted,
                if (accepted) {
                    "hotspot band set to ${mode.name} (mask=$targetBand)"
                } else {
                    "setSoftApConfiguration returned false for ${mode.name}"
                },
            )
        }.getOrElse { failure ->
            HotspotBandApplyResult(
                false,
                "${failure.javaClass.simpleName}: ${failure.cause?.message ?: failure.message}",
            )
        }
    }

    private companion object {
        const val BAND_2GHZ = 1
        const val BAND_5GHZ = 2
    }
}
