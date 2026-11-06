package ch.bojovic.mimizanlab.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.bojovic.mimizanlab.store.Entitlements
import kotlinx.coroutines.delay

/**
 * The purchase, in one place: a section in the settings and the dialog a
 * locked export opens. What is free stays said beside it, so nobody buys
 * what they already have.
 */
@Composable
fun UnlockSection(entitlements: Entitlements) {
    val owned by entitlements.fullResolution.collectAsStateWithLifecycle()
    val price by entitlements.price.collectAsStateWithLifecycle()
    val busy by entitlements.busy.collectAsStateWithLifecycle()
    val message by entitlements.message.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        message?.let { msg ->
            LaunchedEffect(msg) { delay(4000); entitlements.message.value = null }
            Text(msg, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFFC880))
        }
        if (owned) {
            Text(
                "Full resolution is unlocked: the JPEG at full size, the 16-bit TIFF and the linear negative.",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            )
        } else {
            Text(
                "The camera, the finder, the developer, Mono and Color, the roll and the JPEG at half size are free. " +
                    "One purchase, once, unlocks the print at full resolution: the JPEG at full size, the 16-bit TIFF " +
                    "and the linear negative for the desktop darkroom.",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { activity?.let { entitlements.purchase(it) } },
                    enabled = !busy && activity != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) {
                    Text(price?.let { "Unlock full resolution · $it" } ?: "Unlock full resolution")
                }
                TextButton(onClick = { entitlements.restore() }, enabled = !busy) { Text("Restore") }
            }
        }
    }
}

/** The dialog the developer shows for a locked export; it closes itself once the purchase is through. */
@Composable
fun UnlockDialog(entitlements: Entitlements, onDismiss: () -> Unit) {
    val owned by entitlements.fullResolution.collectAsStateWithLifecycle()
    LaunchedEffect(owned) { if (owned) onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Full resolution") },
        text = {
            Column {
                UnlockSection(entitlements)
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
