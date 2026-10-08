package dev.shizzi

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

data class RouterBatteryState(
    val available: Boolean = false,
    val percent: Int = -1,
    val charging: Boolean = false,
)

object RouterBatteryReader {
    fun read(context: Context): RouterBatteryState {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return RouterBatteryState()

        return fromRaw(
            level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
        )
    }

    internal fun fromRaw(level: Int, scale: Int, status: Int): RouterBatteryState {
        if (level < 0 || scale <= 0) return RouterBatteryState()

        val percent = ((level * 100.0) / scale)
            .toInt()
            .coerceIn(0, 100)
        val charging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return RouterBatteryState(
            available = true,
            percent = percent,
            charging = charging,
        )
    }
}
