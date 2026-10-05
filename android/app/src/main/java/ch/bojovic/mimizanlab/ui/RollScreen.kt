package ch.bojovic.mimizanlab.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.bojovic.mimizanlab.engine.Darkroom
import ch.bojovic.mimizanlab.engine.ShotStatus

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RollScreen(onOpen: (String) -> Unit, onBack: () -> Unit) {
    val darkroom = Darkroom.get(LocalContext.current)
    val shots by darkroom.shots.collectAsStateWithLifecycle()
    val cacheBytes by darkroom.cacheBytes.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Roll · ${shots.size}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text(Format.bytes(cacheBytes), style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(end = 12.dp))
        }
        if (shots.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No frames. Pictures you exported are in Pictures/Mimizan Lab.", color = Color.Gray)
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shots, key = { it.name }) { shot ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { onOpen(shot.name) }, onLongClick = { toDelete = shot.name })
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(72.dp).background(Color(0xFF202020)), contentAlignment = Alignment.Center) {
                        val t = shot.thumbnail
                        if (t != null) Image(t.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        when (shot.status) {
                            ShotStatus.Saving, ShotStatus.Queued, ShotStatus.Developing ->
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                            else -> Unit
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(shot.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        val m = shot.meta
                        Text(
                            "${m.lens} · ${Format.iso(m.iso)} · ${Format.shutter(m.exposureNs)} · ${Format.filterShort(shot.settings.filter)}",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                        )
                        Text(
                            when (val st = shot.status) {
                                ShotStatus.Saving -> "saving frame"
                                ShotStatus.Cached -> "in cache · tap to develop"
                                ShotStatus.Queued -> "queued"
                                ShotStatus.Developing -> "developing"
                                is ShotStatus.Ready -> "developed · ${st.info.timing.totalMs} ms" + if (shot.jpegUri != null) " · JPEG saved" else ""
                                is ShotStatus.Failed -> st.message
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (shot.status is ShotStatus.Failed) Color(0xFFFF8080) else Color.Gray,
                        )
                    }
                    IconButton(
                        onClick = { toDelete = shot.name },
                        enabled = shot.status != ShotStatus.Saving && shot.status != ShotStatus.Developing,
                    ) { Icon(Icons.Default.Delete, contentDescription = "Delete frame", tint = Color.Gray) }
                }
            }
        }
    }

    toDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete $name?") },
            text = { Text("The raw frame leaves the app for good; it can no longer be developed again. Pictures already exported to the gallery stay.") },
            confirmButton = { TextButton(onClick = { darkroom.delete(name); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}
