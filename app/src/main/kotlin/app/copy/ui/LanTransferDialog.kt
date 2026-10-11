package app.copy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.copy.domain.Clip
import app.copy.domain.LanTransferPhase
import app.copy.domain.LanTransferState
import java.util.Locale

@Composable
fun LanTransferDialog(
    clip: Clip,
    state: LanUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onAddDevice: (String) -> Unit,
    onSend: (String, String) -> Unit,
    onCancel: () -> Unit,
) {
    var selectedDeviceId by rememberSaveable(clip.id) { mutableStateOf<String?>(null) }
    // The receiver PIN is kept in memory only and is never saved across process death.
    var pin by remember(clip.id) { mutableStateOf("") }
    var showManual by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    val selected = state.devices.firstOrNull { it.id == selectedDeviceId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send to computer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(clip.name, style = MaterialTheme.typography.titleSmall)
                Text("Open Copig Receiver on Windows. Connect your phone and computer to the same Wi-Fi, then select the computer and enter its PIN.")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.discovering) "Looking for computers…" else "Nearby computers", Modifier.weight(1f))
                    TextButton(onClick = onRefresh, enabled = !state.transfer.active) { Text("Refresh") }
                }
                if (state.devices.isEmpty()) Text("No computer found yet. Check Windows Firewall and the receiver, or add its IP address.")
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                    items(state.devices, key = { it.id }) { device ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !state.transfer.active) { selectedDeviceId = device.id },
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selectedDeviceId == device.id, onClick = { selectedDeviceId = device.id }, enabled = !state.transfer.active)
                            Column(Modifier.padding(vertical = 8.dp)) {
                                Text(device.name, style = MaterialTheme.typography.titleSmall)
                                Text("${device.address}:${device.port}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                state.discoveryMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { showManual = !showManual }, enabled = !state.transfer.active) { Text("Add computer by IP address") }
                if (showManual) {
                    OutlinedTextField(address, onValueChange = { address = it.take(64) }, label = { Text("IP address") },
                        placeholder = { Text("192.168.1.20") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        enabled = !state.addingDevice && !state.transfer.active, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                    TextButton(onClick = { onAddDevice(address.trim()) }, enabled = address.isNotBlank() && !state.addingDevice && !state.transfer.active) {
                        Text(if (state.addingDevice) "Connecting…" else "Add computer")
                    }
                }
                OutlinedTextField(pin, onValueChange = { pin = it.filter(Char::isDigit).take(6) }, label = { Text("Receiver PIN") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.transfer.active,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation())
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (state.transfer.phase != LanTransferPhase.Idle) TransferProgress(state.transfer, onCancel)
                Text("Files travel directly over your local network. Keep the receiver running. You can close this panel while a transfer continues.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = { selected?.let { onSend(it.id, pin) } }, enabled = selected != null && pin.length == 6 && !state.transfer.active) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun TransferProgress(state: LanTransferState, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(when (state.phase) {
            LanTransferPhase.Idle -> "Ready to transfer"
            LanTransferPhase.Hashing -> "Checking file…"
            LanTransferPhase.WaitingForReceiver -> "Waiting for ${state.deviceName}…"
            LanTransferPhase.Sending -> "Sending to ${state.deviceName}"
            LanTransferPhase.Complete -> "Sent to ${state.deviceName}"
            LanTransferPhase.Failed -> "Transfer failed"
            LanTransferPhase.Cancelled -> "Transfer cancelled"
        }, color = if (state.phase == LanTransferPhase.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        if (state.phase == LanTransferPhase.Sending) {
            val progress = if (state.totalBytes > 0) (state.bytesSent.toFloat() / state.totalBytes).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(String.format(Locale.getDefault(), "%.1f / %.1f MB", state.bytesSent / 1_000_000.0, state.totalBytes / 1_000_000.0),
                style = MaterialTheme.typography.bodySmall)
        } else if (state.active) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state.active) TextButton(onClick = onCancel) { Text("Cancel transfer") }
    }
}
