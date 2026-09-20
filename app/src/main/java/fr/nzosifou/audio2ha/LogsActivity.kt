package fr.nzosifou.audio2ha

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import fr.nzosifou.audio2ha.ui.theme.Audio2HATheme

/** Écran de consultation des journaux, filtrable par catégorie. */
class LogsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogStore.init(this)
        setContent {
            Audio2HATheme {
                // Surface plutôt que Box : elle fixe aussi la couleur du texte par défaut.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    LogsScreen()
                }
            }
        }
    }
}

private enum class Filter(val label: String) {
    ALL("Tout"),
    AUDIO("Changements TV"),
    HA("Envois Home Assistant"),
}

@Composable
private fun LogsScreen() {
    val entries by LogStore.entries.collectAsState()
    var filter by remember { mutableStateOf(Filter.ALL) }

    val shown = remember(entries, filter) {
        when (filter) {
            Filter.ALL -> entries
            Filter.AUDIO -> entries.filter { it.type == LogType.AUDIO }
            Filter.HA -> entries.filter { it.type == LogType.HA }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        Text("Journaux", style = MaterialTheme.typography.headlineSmall)

        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Filter.entries.forEach { f ->
                TvButton(text = f.label, primary = f == filter, onClick = { filter = f })
            }
            TvButton(
                text = "Effacer",
                contentColor = MaterialTheme.colorScheme.error,
                onClick = { LogStore.clear() },
            )
        }

        if (shown.isEmpty()) {
            Text(
                "Aucun événement pour le moment.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown) { entry -> LogRow(entry) }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val accent = when {
        entry.error -> MaterialTheme.colorScheme.error
        entry.type == LogType.AUDIO -> Color(0xFF6FA8FF)
        else -> Color(0xFF9BE8B4)
    }
    // Les lignes sont focusables pour que la liste puisse défiler à la télécommande.
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                if (focused) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface,
            )
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) MaterialTheme.colorScheme.onBackground else Color.Transparent,
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                entry.timeString(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "[" + entry.type.label + "]",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = accent,
            )
            Text(entry.message, style = MaterialTheme.typography.bodyMedium)
        }
        entry.detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
