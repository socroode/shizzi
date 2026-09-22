package dev.shizzi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shizzi.HotspotBand
import dev.shizzi.ui.theme.ShizziTheme

private val HotspotCheckSize = 20.dp

fun hotspotBandLabel(mode: HotspotBand): String = when (mode) {
    HotspotBand.AUTO -> "Auto"
    HotspotBand.BAND_2_4_GHZ -> "2.4 GHz"
    HotspotBand.BAND_5_GHZ -> "5 GHz"
}

private fun hotspotBandDescription(mode: HotspotBand): String = when (mode) {
    HotspotBand.AUTO -> "Let Android choose between 2.4 and 5 GHz"
    HotspotBand.BAND_2_4_GHZ -> "Maximum compatibility and longer range"
    HotspotBand.BAND_5_GHZ -> "Higher throughput when the device supports 5 GHz SoftAP"
}

@Composable
fun HotspotBandSection(selected: HotspotBand, onSelect: (HotspotBand) -> Unit) {
    var isPickerOpen by remember { mutableStateOf(false) }

    SettingsChoice(
        label = SettingsText(
            title = "Hotspot band",
            subtitle = "Applied the next time Shizzi starts tethering",
        ),
        value = hotspotBandLabel(selected),
        onClick = { isPickerOpen = true },
    )

    if (!isPickerOpen) return

    ThemedBottomSheet(onDismiss = { isPickerOpen = false }) {
        Text(
            text = "Hotspot band",
            style = ShizziTheme.typography.heading,
            color = ShizziTheme.colors.onSurface,
        )

        HotspotBand.entries.forEach { mode ->
            HotspotBandOption(
                mode = mode,
                isSelected = mode == selected,
                onSelect = {
                    onSelect(mode)
                    isPickerOpen = false
                },
            )
        }

        Spacer(Modifier.height(ShizziTheme.spacing.lg))
    }
}

@Composable
private fun HotspotBandOption(
    mode: HotspotBand,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = ShizziTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = hotspotBandLabel(mode),
                style = ShizziTheme.typography.subheading,
                color = ShizziTheme.colors.onSurface,
            )

            Text(
                text = hotspotBandDescription(mode),
                style = ShizziTheme.typography.body,
                color = ShizziTheme.colors.onSurfaceMuted,
            )
        }

        if (!isSelected) return@Row

        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = ShizziTheme.colors.primary,
            modifier = Modifier.size(HotspotCheckSize),
        )
    }
}
