package com.tinkercobble.ham.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tinkercobble.ham.data.PreferenceSyncConnection
import com.tinkercobble.ham.data.PreferenceSyncEndpoint
import com.tinkercobble.ham.data.PreferenceSyncHttps
import com.tinkercobble.ham.data.ProtectedPreferenceSyncConnection
import com.tinkercobble.ham.data.KeystorePreferenceSyncIdentity
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.ProtectedPreferenceSyncApprovals
import com.tinkercobble.ham.data.PreferenceSyncGlobalSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.security.MessageDigest

/** Pairing and global display setting status. Calendar mapping remains separately gated. */
@Composable
fun PreferenceSyncPage(offline: Boolean, sources: List<CalendarSource>,
    syncBusy: Boolean, syncMessage: String,
    onSync: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ProtectedPreferenceSyncConnection(context) }
    val approvals = remember { ProtectedPreferenceSyncApprovals(context) }
    val loaded = remember { runCatching { store.load() } }
    var connection by remember { mutableStateOf(loaded.getOrNull()) }
    val storageError = loaded.exceptionOrNull() != null
    var address by remember { mutableStateOf("") }
    var replacementAddress by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("This device") }
    var requestId by remember { mutableStateOf<String?>(null) }
    var requestKeyFingerprint by remember { mutableStateOf("") }
    var issuedCode by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf(JSONArray()) }
    var requests by remember { mutableStateOf(JSONArray()) }
    var revokeTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var approved by remember(connection, sources) { mutableStateOf(
        runCatching { approvals.resolved(sources) }.getOrDefault(emptyMap())) }

    fun launchAction(action: suspend () -> Unit) {
        if (busy || offline) return
        busy = true
        message = ""
        scope.launch {
            try { action() }
            catch (_: Exception) { message = "The sync service could not complete this request. Check the address, fingerprint, connection and approval." }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("‹ Settings") }
        Text("Calendar Settings Sync", style = MaterialTheme.typography.headlineMedium)
        Text("Optional: pair with a sync service you control. HAM has no built-in sync account. " +
            "Week start, calendar view, saved groups and ordering sync after pairing. Approved calendar visibility, favorites, colors, mirror display choices and Quick Add destination also sync. Appointments and sign-ins stay with their providers.",
            style = MaterialTheme.typography.bodySmall)
        if (offline) Text("Offline is on. Pairing and device checks are paused.")
        if (storageError) Text("Protected sync connection could not be read. Keep your existing app data and contact support before pairing again.")
        if (busy) CircularProgressIndicator()
        if (message.isNotBlank()) Text(message)
        if (syncBusy) CircularProgressIndicator()
        if (syncMessage.isNotBlank()) Text(syncMessage)

        val paired = connection
        if (paired == null) {
            OutlinedTextField(address, { address = it.take(256) }, label = { Text("Sync service HTTPS address") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(fingerprint, { fingerprint = it.filter(Char::isLetterOrDigit).take(64) },
                label = { Text("Verified certificate SHA-256 fingerprint") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("Compare this fingerprint with the value shown by your own server administrator. " +
                "Do not accept a changed fingerprint without checking it separately.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(code, { code = it.take(64) }, label = { Text("One-time pairing code") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                singleLine = true)
            OutlinedTextField(label, { label = it.take(80) }, label = { Text("Name this device") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { launchAction {
                val endpoint = PreferenceSyncEndpoint(address.trim(), fingerprint.trim())
                val requested = withContext(Dispatchers.IO) {
                    val key = KeystorePreferenceSyncIdentity().publicKeyPem()
                    val shortFingerprint = MessageDigest.getInstance("SHA-256")
                        .digest(key.toByteArray(Charsets.US_ASCII))
                        .joinToString("") { "%02x".format(it) }.take(16)
                    PreferenceSyncHttps(endpoint).requestPairing(code.trim(), label.trim()) to shortFingerprint
                }
                requestId = requested.first
                requestKeyFingerprint = requested.second
                code = ""
                message = "Pairing requested. Approve this device on an existing paired device if asked, then tap Complete pairing."
            } }, enabled = !offline && !busy && !storageError && code.isNotBlank()) {
                Text("Request pairing")
            }
            if (requestId != null) Button(onClick = { launchAction {
                val endpoint = PreferenceSyncEndpoint(address.trim(), fingerprint.trim())
                val enrolled = withContext(Dispatchers.IO) {
                    PreferenceSyncHttps(endpoint).completePairing(requestId!!)
                }
                val result = PreferenceSyncConnection(endpoint, enrolled.profileId, enrolled.deviceId)
                withContext(Dispatchers.IO) { store.save(result) }
                connection = result
                requestId = null
                message = "Device paired. Checking shared display settings."
                onSync()
            } }, enabled = !offline && !busy) { Text("Complete pairing") }
            if (requestId != null) Text("Confirm this device's key fingerprint on the approving device: $requestKeyFingerprint",
                style = MaterialTheme.typography.bodySmall)
            if (requestId != null) Text("If this screen closes before approval, request a fresh code. " +
                "The earlier request expires automatically.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("This device is paired to a user-configured service.")
            OutlinedTextField(replacementAddress, { replacementAddress = it.take(256) },
                label = { Text("New HTTPS sync address") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("Use this when your service moves to your private network. HAM checks the existing " +
                "certificate and your device authorization before saving the address.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { launchAction {
                val moved = PreferenceSyncConnection(
                    PreferenceSyncEndpoint(replacementAddress.trim(),
                        paired.endpoint.certificateSha256), paired.profileId, paired.deviceId)
                withContext(Dispatchers.IO) {
                    PreferenceSyncHttps(moved.endpoint).devices(moved.profileId, moved.deviceId)
                    store.save(moved)
                }
                connection = moved
                replacementAddress = ""
                message = "Private sync address verified and saved."
                onSync()
            } }, enabled = !offline && !busy && replacementAddress.isNotBlank()) {
                Text("Verify and use new address")
            }
            Text("Shared choices sync when changed and when HAM opens. Each calendar needs an exact source approval on this device before its choices can sync.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onSync, enabled = !offline && !busy && !syncBusy) {
                Text("Sync display settings now")
            }
            val conflictRows = remember(syncMessage, message) {
                runCatching { PreferenceSyncGlobalSettings(context).conflicts() }
                    .getOrDefault(emptyList())
            }
            if (conflictRows.isNotEmpty()) {
                Text("Changes needing review", style = MaterialTheme.typography.titleMedium)
                for (conflict in conflictRows) {
                    val key = conflict.local.key
                    val source = sources.firstOrNull { item ->
                        item.logicalIdentity != null && key.startsWith("calendar:${item.logicalIdentity}:") &&
                            approved[item.logicalIdentity]?.id == item.id
                    }
                    val label = when (key) {
                        "global:week_start" -> "Start Week On"
                        "global:view" -> "Calendar view"
                        "global:quick_add_default" -> "Quick Add destination"
                        "global:group_order" -> "Saved group order"
                        "global:active_group" -> "Active saved group"
                        else -> source?.let { "${it.name} ${key.substringAfterLast(':')}" }
                            ?: if (key.startsWith("group:")) "Saved group name"
                                else "Unmatched calendar choice"
                    }
                    Text(label)
                    Text("This device and another device changed this setting. Choose which value to keep.",
                        style = MaterialTheme.typography.bodySmall)
                    if (source != null || !key.startsWith("calendar:")) Row {
                        TextButton(onClick = {
                            runCatching { PreferenceSyncGlobalSettings(context)
                                .resolveConflict(conflict.local.id, keepLocal = false) }
                                .onSuccess { message = "Other device's choice selected"; onSync() }
                                .onFailure { message = "Conflict could not be resolved; refresh and try again" }
                        }, enabled = !offline && !busy && !syncBusy) { Text("Use other device") }
                        TextButton(onClick = {
                            runCatching { PreferenceSyncGlobalSettings(context)
                                .resolveConflict(conflict.local.id, keepLocal = true) }
                                .onSuccess { message = "This device's choice queued"; onSync() }
                                .onFailure { message = "Conflict could not be resolved; refresh and try again" }
                        }, enabled = !offline && !busy && !syncBusy) { Text("Keep this device") }
                    }
                }
            }
            OutlinedButton(onClick = { launchAction {
                val result = withContext(Dispatchers.IO) {
                    val https = PreferenceSyncHttps(paired.endpoint)
                    https.devices(paired.profileId, paired.deviceId) to
                        https.pendingPairings(paired.profileId, paired.deviceId)
                }
                devices = result.first
                requests = result.second
                message = "Device status refreshed."
            } }, enabled = !offline && !busy) { Text("Refresh paired devices") }
            for (index in 0 until devices.length()) {
                val item = devices.getJSONObject(index)
                val deviceName = item.optString("label").take(80)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (item.getString("id") == paired.deviceId) "$deviceName (this device)" else deviceName)
                    if (item.getString("id") != paired.deviceId && item.isNull("revoked_at"))
                        TextButton(onClick = {
                            revokeTarget = item.getString("id") to deviceName
                        }, enabled = !offline && !busy) { Text("Revoke") }
                }
            }
            HorizontalDivider()
            OutlinedButton(onClick = { launchAction {
                issuedCode = withContext(Dispatchers.IO) { PreferenceSyncHttps(paired.endpoint)
                    .issuePairingCode(paired.profileId, paired.deviceId) }
                message = "Use this one-time code on the other device within ten minutes."
            } }, enabled = !offline && !busy) { Text("Pair another device") }
            if (issuedCode != null) {
                Text("One-time pairing code: ${issuedCode!!}")
                Text("Keep this code private. It will expire automatically.", style = MaterialTheme.typography.bodySmall)
            }
            for (index in 0 until requests.length()) {
                val item = requests.getJSONObject(index)
                Column {
                    Text("Pairing request: ${item.optString("label").take(80)}")
                    Text("Key fingerprint: ${item.optString("key_fingerprint")}",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { launchAction {
                        withContext(Dispatchers.IO) { PreferenceSyncHttps(paired.endpoint)
                            .approvePairing(paired.profileId, paired.deviceId,
                                item.getString("request_id")) }
                        requests = JSONArray()
                        message = "Pairing approved. The other device can complete enrollment."
                    } }, enabled = !offline && !busy) { Text("Approve this device") }
                }
            }
            HorizontalDivider()
            Text("Review calendar matches", style = MaterialTheme.typography.titleMedium)
            Text("Calendar names and device row numbers are not shared. Approve each exact provider collection on this device before its choices can sync. A calendar without a stable identity stays local.",
                style = MaterialTheme.typography.bodySmall)
            for (source in sources.sortedWith(compareBy({ it.provider.name }, { it.name }))) {
                val identity = source.logicalIdentity
                val eligible = identity != null && sources.count { it.logicalIdentity == identity } == 1
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(source.name)
                        Text(source.provider.name.replace('_', ' ') + " · " + source.source,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        try {
                            if (identity in approved) approvals.remove(source.id)
                            else approvals.approve(source, sources)
                            approved = approvals.resolved(sources)
                            message = "Calendar match saved on this device."
                            onSync()
                        } catch (_: Exception) {
                            message = "This calendar match could not be saved."
                        }
                    }, enabled = eligible && !busy && !syncBusy) {
                        Text(if (identity in approved) "Stop" else "Approve")
                    }
                }
            }
        }
    }
    val target = revokeTarget
    val paired = connection
    if (target != null && paired != null) AlertDialog(
        onDismissRequest = { revokeTarget = null },
        title = { Text("Revoke paired device?") },
        text = { Text("${target.second} will no longer be able to sync settings. Its existing local calendar data will remain on that device.") },
        confirmButton = { Button(onClick = {
            revokeTarget = null
            launchAction {
                withContext(Dispatchers.IO) { PreferenceSyncHttps(paired.endpoint)
                    .revokeDevice(paired.profileId, paired.deviceId, target.first) }
                devices = JSONArray()
                message = "Device revoked. Refresh the list to verify."
            }
        }) { Text("Revoke") } },
        dismissButton = { TextButton(onClick = { revokeTarget = null }) { Text("Cancel") } })
}
