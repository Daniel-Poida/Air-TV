package io.github.jqssun.airplay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

/** One bottom navigation panel for both receiver screens. Settings never moves. */
@Composable
fun AirTvNavigation(
    section: String,
    running: Boolean,
    onSettings: () -> Unit,
    onAirPlay: () -> Unit,
    onAirDrop: () -> Unit,
    onToggleReceiver: () -> Unit,
    modifier: Modifier = Modifier,
    initialFocus: FocusRequester? = null,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onSettings,
            modifier = (if (initialFocus != null) Modifier.focusRequester(initialFocus) else Modifier).dpadFocus(),
            shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)) {
            Icon(Icons.Default.Settings, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text("Настройки")
        }
        SectionButton("AirPlay", section == "AirPlay", onAirPlay)
        SectionButton("AirDrop", section == "AirDrop", onAirDrop)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onToggleReceiver, modifier = Modifier.dpadFocus(),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)) {
            Text(if (running) "Выключить приём" else "Включить приём")
        }
    }
}

@Composable
private fun SectionButton(label: String, selected: Boolean, action: () -> Unit) {
    val modifier = Modifier.dpadFocus()
    val shape = RoundedCornerShape(12.dp)
    val padding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)
    if (selected) Button(onClick = action, modifier = modifier, shape = shape, contentPadding = padding) { Text(label) }
    else OutlinedButton(onClick = action, modifier = modifier, shape = shape, contentPadding = padding) { Text(label) }
}
