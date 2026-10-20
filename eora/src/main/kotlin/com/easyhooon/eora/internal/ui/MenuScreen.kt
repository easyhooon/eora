package com.easyhooon.eora.internal.ui

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.easyhooon.eora.R
import com.easyhooon.eora.internal.EoraRuntime

/**
 * Shown after a shake. The host screen was captured before this appeared, so the menu itself is never in the
 * evidence; the dimmed capture behind the card only makes it look like a dialog over the app.
 */
@Composable
internal fun MenuScreen(
    controller: ReportController,
    runtime: EoraRuntime,
    onFeedback: () -> Unit,
    onDrafts: () -> Unit,
    onClose: () -> Unit,
) {
    LaunchedEffect(Unit) { controller.loadOriginalIfNeeded() }
    val original by controller.original.collectAsState()
    var shakeEnabled by remember { mutableStateOf(runtime.isShakeEnabled) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        original?.let { bitmap ->
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Image(image, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                // Tapping outside the card behaves like a dialog: it closes the menu.
                .clickable(onClick = onClose),
        )
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .safeDrawingPadding()
                .padding(horizontal = 24.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(vertical = 20.dp)) {
                Text(
                    "Eora",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                MenuRow(stringResource(R.string.eora_menu_feedback), onFeedback)
                if (runtime.isRecordingAvailable) {
                    val activity = LocalContext.current as? Activity
                    MenuRow(stringResource(R.string.eora_menu_record)) {
                        // The shake capture is not needed for a recording; the consent screen opens over the host.
                        runtime.startRecording(from = activity)
                        onClose()
                    }
                }
                MenuRow(stringResource(R.string.eora_menu_drafts), onDrafts)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.eora_shake_to_open), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = shakeEnabled,
                        onCheckedChange = {
                            runtime.isShakeEnabled = it
                            shakeEnabled = it
                        },
                    )
                }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End).padding(end = 12.dp)) {
                    Text(stringResource(R.string.eora_close))
                }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}
