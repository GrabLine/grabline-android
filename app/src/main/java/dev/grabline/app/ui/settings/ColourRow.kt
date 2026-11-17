package dev.grabline.app.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.grabline.app.R
import dev.grabline.app.ui.theme.Accent

/**
 * The accent colour, as swatches: the desktop app's presets (blue, violet, green, orange, rose, teal) and,
 * on Android 12+, the phone's wallpaper colours. Stores the `colours` setting.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColourRow(title: String, selected: String, onSelect: (String) -> Unit) {
    val wallpaper = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val options: List<Pair<String, String>> =
        Accent.entries.map { it.key to stringResource(it.label) } +
            (if (wallpaper) listOf("wallpaper" to stringResource(R.string.colours_wallpaper)) else emptyList())
    val current = options.firstOrNull { it.first == selected }?.second ?: stringResource(Accent.Blue.label)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(current, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(
            Modifier.padding(top = 8.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { (key, label) ->
                val isSelected = key == selected || (key == Accent.Blue.key && options.none { it.first == selected })
                Box(
                    Modifier
                        .size(48.dp)
                        .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(key) }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    val fill: Modifier = Accent.entries.firstOrNull { it.key == key }?.let { Modifier.background(it.fill, CircleShape) }
                        ?: Modifier.background(
                            Brush.sweepGradient(listOf(Color(0xFF2563EB), Color(0xFF059669), Color(0xFFEA580C), Color(0xFFE11D48), Color(0xFF2563EB))),
                            CircleShape,
                        )
                    Box(
                        Modifier
                            .size(36.dp)
                            .then(
                                if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape).padding(4.dp)
                                else Modifier,
                            )
                            .clip(CircleShape)
                            .then(fill),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Icon(
                                painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
