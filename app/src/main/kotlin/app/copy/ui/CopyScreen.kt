package app.copy.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.copy.R
import app.copy.domain.CapturePhase
import app.copy.domain.Clip

@Composable
fun CopyTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFFFFB2CD), onPrimary = Color(0xFF5B1432),
        primaryContainer = Color(0xFF7A2749), onPrimaryContainer = Color(0xFFFFD9E6),
        secondary = Color(0xFFE6BDC9), onSecondary = Color(0xFF432A33),
        secondaryContainer = Color(0xFF5B404A), onSecondaryContainer = Color(0xFFFFD9E6),
        tertiary = Color(0xFFF7B7A7), onTertiary = Color(0xFF532C22),
        tertiaryContainer = Color(0xFF6F4337), onTertiaryContainer = Color(0xFFFFDBD1),
        background = Color(0xFF21151B), onBackground = Color(0xFFF8E5EC),
        surface = Color(0xFF21151B), onSurface = Color(0xFFF8E5EC),
        surfaceVariant = Color(0xFF514048), onSurfaceVariant = Color(0xFFDFC0CC),
        surfaceContainerLowest = Color(0xFF1A1015), surfaceContainerLow = Color(0xFF291B22),
        surfaceContainer = Color(0xFF2E2027), surfaceContainerHigh = Color(0xFF392A32),
        surfaceContainerHighest = Color(0xFF44353D), outline = Color(0xFFAA8997),
    ) else lightColorScheme(
        primary = Color(0xFFAA315F), onPrimary = Color.White,
        primaryContainer = Color(0xFFFFD9E6), onPrimaryContainer = Color(0xFF421126),
        secondary = Color(0xFF785460), onSecondary = Color.White,
        secondaryContainer = Color(0xFFFFD9E6), onSecondaryContainer = Color(0xFF2E1520),
        tertiary = Color(0xFF8B5140), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFDBD1), onTertiaryContainer = Color(0xFF36140A),
        background = Color(0xFFFFF8FA), onBackground = Color(0xFF2E1B24),
        surface = Color(0xFFFFF8FA), onSurface = Color(0xFF2E1B24),
        surfaceVariant = Color(0xFFF5DFE7), onSurfaceVariant = Color(0xFF634A55),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFF0F5),
        surfaceContainer = Color(0xFFFDE7EF), surfaceContainerHigh = Color(0xFFF6E0E8),
        surfaceContainerHighest = Color(0xFFF0D9E2), outline = Color(0xFF967883),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

/** Pure UI: immutable state down, event callbacks up. */
@Composable
fun CopyScreen(
    state: CopyUiState,
    floatingVisible: Boolean,
    onFloating: () -> Unit,
    onRecord: () -> Unit,
    onScreenshot: () -> Unit,
    onStop: () -> Unit,
    onImport: () -> Unit,
    onPreview: (Clip) -> Unit,
    onShare: (Clip) -> Unit,
    onExport: (Clip) -> Unit,
    onDelete: (Clip) -> Unit,
    onCopyPrompt: () -> Unit,
    onSendToComputer: (Clip) -> Unit,
    onDismissTransfer: () -> Unit,
    onRefreshDevices: () -> Unit,
    onAddDevice: (String) -> Unit,
    onSendClip: (String, String) -> Unit,
    onCancelTransfer: () -> Unit,
) {
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val deletingClip = state.clips.firstOrNull { it.id == deletingId }
    val sendingClip = state.clips.firstOrNull { it.id == state.lan.selectedClipId }
    if (sendingClip != null) {
        LanTransferDialog(sendingClip, state.lan, onDismissTransfer, onRefreshDevices, onAddDevice, onSendClip, onCancelTransfer)
    }
    if (deletingClip != null) {
        AlertDialog(onDismissRequest = { deletingId = null },
            title = { Text(stringResource(R.string.delete_title)) }, text = { Text(deletingClip.name) },
            confirmButton = { TextButton(onClick = { onDelete(deletingClip); deletingId = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text(stringResource(R.string.keep)) } })
    }
    Scaffold { insets ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
                    Text(stringResource(R.string.mission), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(when (state.capturePhase) {
                        CapturePhase.Idle -> R.string.ready
                        CapturePhase.Preparing -> R.string.preparing
                        CapturePhase.Ready -> R.string.floating_ready
                        CapturePhase.Recording -> R.string.recording
                        CapturePhase.Saving -> R.string.saving
                    }), color = MaterialTheme.colorScheme.primary)
                    state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (state.lan.transfer.phase != app.copy.domain.LanTransferPhase.Idle) {
                        TransferProgress(state.lan.transfer, onCancelTransfer)
                    }
                    if (state.capturePhase == CapturePhase.Saving || state.importing || state.exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Button(onClick = onFloating, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (floatingVisible) R.string.hide_floating else R.string.enable_floating))
                    }
                    Text(stringResource(R.string.floating_help), style = MaterialTheme.typography.bodyMedium)
                    if (state.capturePhase == CapturePhase.Ready) {
                        Button(onClick = onRecord, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.record_now)) }
                        TextButton(onClick = onStop) { Text(stringResource(R.string.cancel_setup)) }
                    } else if (state.capturePhase.active) {
                        Button(onClick = onStop, enabled = state.capturePhase.canStop, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.stop_save))
                        }
                    } else {
                        Button(onClick = onRecord, enabled = !state.requestingConsent, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.record_clip))
                        }
                    }
                    OutlinedButton(onClick = onScreenshot,
                        enabled = !state.requestingConsent && state.capturePhase in listOf(CapturePhase.Idle, CapturePhase.Ready),
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (state.capturePhase == CapturePhase.Ready) R.string.take_screenshot else R.string.setup_screenshot))
                    }
                    Text(stringResource(R.string.screenshot_help), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onImport, enabled = !state.importing, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.import_media))
                    }
                    Text(stringResource(R.string.capture_help), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onCopyPrompt) { Text(stringResource(R.string.copy_prompt)) }
                    Text(stringResource(R.string.your_clips), style = MaterialTheme.typography.headlineSmall)
                }
            }
            if (state.clips.isEmpty()) item { Text(stringResource(R.string.empty_library)) }
            items(state.clips, key = { it.id }) { clip ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(clip.name, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.clip_size, clip.sizeBytes / 1_000_000.0), style = MaterialTheme.typography.bodySmall)
                        if (clip.audioAppearsSilent) Text(stringResource(R.string.silent_warning), color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { onPreview(clip) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(if (clip.mimeType.startsWith("image/")) R.string.preview_image else R.string.preview_clip))
                        }
                        Button(onClick = { onShare(clip) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.share_clip)) }
                        OutlinedButton(onClick = { onSendToComputer(clip) }, enabled = !state.lan.transfer.active,
                            modifier = Modifier.fillMaxWidth()) { Text("Send to computer") }
                        TextButton(onClick = { onExport(clip) }, enabled = !state.exporting) { Text(stringResource(R.string.save_files)) }
                        TextButton(onClick = { deletingId = clip.id }, enabled = !state.lan.transfer.active) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CopyPreview() { CopyTheme { CopyScreen(CopyUiState(), false, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}) } }
