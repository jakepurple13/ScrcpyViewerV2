package com.programmersbox.scrcpyviewer.ui.devices

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.programmersbox.scrcpyviewer.adb.MdnsService
import com.programmersbox.scrcpyviewer.adb.WirelessParsers
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

/** The pairing dialog's callbacks, so [WifiPairingDialog] stays stateless (screenshots pass no-ops). */
data class WifiPairingActions(
    val selectTab: (PairingTab) -> Unit = {},
    val choose: (String) -> Unit = {},
    val setCode: (String) -> Unit = {},
    val setIp: (String) -> Unit = {},
    val setPort: (String) -> Unit = {},
    val setPairPort: (String) -> Unit = {},
    val setPairCode: (String) -> Unit = {},
    val submit: () -> Unit = {},
    val restartAdb: () -> Unit = {},
    val startScan: () -> Unit = {},
)

fun WifiPairingViewModel.actions() = WifiPairingActions(
    selectTab = ::selectTab, choose = ::choose, setCode = ::setCode, setIp = ::setIp, setPort = ::setPort,
    setPairPort = ::setPairPort, setPairCode = ::setPairCode, submit = ::submit, restartAdb = ::restartAdb,
    startScan = ::startScan,
)

private val PairingTabLabels = mapOf(PairingTab.Qr to "QR code", PairingTab.Code to "Pairing code", PairingTab.Ip to "IP address")

/** "Pair device over Wi-Fi": QR code, pairing code and IP address tabs. */
@Composable
fun WifiPairingDialog(state: WifiPairingState, actions: WifiPairingActions, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = false,
        ),
        title = { Text("Pair device over Wi-Fi") },
        text = {
            Column(Modifier.width(500.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PrimaryTabRow(selectedTabIndex = state.tab.ordinal, containerColor = Color.Transparent) {
                    PairingTab.entries.forEach { tab ->
                        Tab(
                            selected = tab == state.tab,
                            onClick = { actions.selectTab(tab) },
                            text = { Text(PairingTabLabels.getValue(tab)) },
                        )
                    }
                }
                AnimatedContent(
                    targetState = state.tab,
                    transitionSpec = {
                        // Slide toward the tab that was picked: right-hand tabs enter from the right.
                        val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (slideInHorizontally { width -> direction * width } + fadeIn()) togetherWith
                            (slideOutHorizontally { width -> -direction * width } + fadeOut()) using
                            SizeTransform(clip = true)
                    },
                    label = "pairing tab",
                ) { tab ->
                    Column(Modifier.fillMaxWidth().heightIn(min = 250.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (tab) {
                            PairingTab.Qr -> QrTab(state)
                            PairingTab.Code -> CodeTab(state, actions)
                            PairingTab.Ip -> IpTab(state, actions)
                        }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                if (state.canRestartAdb) {
                    OutlinedButton(onClick = actions.restartAdb) { Text("Restart adb") }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Button(onClick = actions.submit, enabled = state.primaryEnabled) { Text(state.primaryLabel) }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Steps(vararg steps: List<Pair<String, Boolean>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEachIndexed { i, parts ->
            Text(
                buildAnnotatedString {
                    append("${i + 1}. ")
                    parts.forEach { (text, bold) ->
                        if (bold) withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)) { append(text) }
                        else append(text)
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QrTab(state: WifiPairingState) {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        // White behind the code in both themes: phone scanners expect dark modules on a light ground.
        Box(Modifier.size(176.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp)) {
            Image(rememberQrCodePainter(state.qrPayload) {}, contentDescription = "Pairing QR code", Modifier.size(156.dp))
        }
        Box(Modifier.weight(1f)) {
            Steps(
                listOf("On the phone, open " to false, "Developer options › Wireless debugging" to true, "." to false),
                listOf("Tap " to false, "Pair device with QR code" to true, "." to false),
                listOf("Scan this code. The phone and this computer must be on the same Wi-Fi network." to false),
            )
        }
    }
    StatusLine(if (state.busy) "Pairing…" else "Waiting for a phone to scan…", trailing = state.qrName)
}

@Composable
private fun StatusLine(text: String, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CodeTab(state: WifiPairingState, actions: WifiPairingActions) {
    Steps(
        listOf("On the phone, open " to false, "Wireless debugging › Pair device with pairing code" to true, "." to false),
        listOf("Pick the phone below, then type the 6-digit code it shows." to false),
    )
    if (state.pairingServices.isEmpty()) {
        StatusLine("Looking for phones on this network…")
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.pairingServices.forEach { service -> ServiceRow(service, service.address == state.chosen) { actions.choose(service.address) } }
        }
    }
    OutlinedTextField(
        value = state.code,
        onValueChange = actions.setCode,
        singleLine = true,
        label = { Text("Pairing code") },
        textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.width(200.dp),
    )
}

@Composable
private fun ServiceRow(service: MdnsService, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Column {
            Text(WirelessParsers.serialFromServiceName(service.name), fontWeight = FontWeight.Medium)
            Text(
                "${service.address} · found on network",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IpTab(state: WifiPairingState, actions: WifiPairingActions) {
    var pairOpen by rememberSaveable { mutableStateOf(state.pairPort.isNotEmpty() || state.pairCode.isNotEmpty()) }
    Text(
        "For phones already paired, or older phones where you ran adb tcpip 5555. " +
            "To pair a new phone manually, also fill in the pairing port and code.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ScanSection(state, actions)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            state.ip, actions.setIp, singleLine = true, label = { Text("IP address") },
            isError = state.ipError != null, supportingText = state.ipError?.let { { Text(it) } },
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            state.port, actions.setPort, singleLine = true, label = { Text("Port") },
            isError = state.portError != null, supportingText = state.portError?.let { { Text(it) } },
            modifier = Modifier.width(140.dp),
        )
    }
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable { pairOpen = !pairOpen }.padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (pairOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, Modifier.size(20.dp))
        Text("Pair first (Android 11+)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (pairOpen) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(state.pairPort, actions.setPairPort, singleLine = true, label = { Text("Pairing port") }, modifier = Modifier.weight(1f))
            OutlinedTextField(state.pairCode, actions.setPairCode, singleLine = true, label = { Text("Code") }, modifier = Modifier.width(140.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanSection(state: WifiPairingState, actions: WifiPairingActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Scan network for devices",
            style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
            color = if (state.scanning) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = !state.scanning, onClick = actions.startScan)
                .padding(horizontal = 2.dp, vertical = 2.dp),
        )
        if (state.scanning) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                "${state.scanDone} / ${state.scanTotal}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val results = state.scanResults ?: return
    if (results.isEmpty()) {
        if (!state.scanning) {
            Text(
                "Nothing answered on port ${state.port}. The device must be on this Wi-Fi network with " +
                    "adb listening on that port (adb tcpip 5555).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        results.forEach { ip ->
            val shape = RoundedCornerShape(8.dp)
            Text(
                ip,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .then(if (ip == state.ip) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                    .clickable { actions.setIp(ip) }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}
