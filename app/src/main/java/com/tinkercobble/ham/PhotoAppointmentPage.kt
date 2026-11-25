package com.tinkercobble.ham

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.PhotoAppointment
import com.tinkercobble.ham.data.PhotoAppointmentMatches
import com.tinkercobble.ham.data.PhotoAppointmentOcr
import com.tinkercobble.ham.data.PhotoAppointmentProfile
import com.tinkercobble.ham.data.PhotoAppointmentProfileStore
import com.tinkercobble.ham.data.PhotoAppointments
import com.tinkercobble.ham.data.PhotoCalendarScan
import com.tinkercobble.ham.data.PhotoMatchKind
import com.tinkercobble.ham.ui.HamViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

private data class AppointmentReviewRow(
    val id: UUID, val detected: PhotoAppointment, val date: String, val start: String,
    val duration: String, val title: String, val location: String, val description: String,
    val calendarId: String? = null, val selected: Boolean = false,
    val match: PhotoMatchKind? = null, val matchEvent: AgendaEvent? = null,
    val outcome: String = "", val showOriginal: Boolean = false,
    val separateConfirmed: Boolean = false,
    val sourcePhotoKey: String? = null,
)

private fun CalendarProviderKind.label(): String = when (this) {
    CalendarProviderKind.HOME_ASSISTANT -> "Home Assistant"
    CalendarProviderKind.ANDROID -> "Android"
    CalendarProviderKind.CALDAV -> "CalDAV"
    CalendarProviderKind.ICS -> "ICS subscription"
}

@Composable
internal fun PhotoAppointmentPage(model: HamViewModel, initialUri: Uri?,
    onCancel: () -> Unit, onOpenCalendar: (LocalDate) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profileStore = remember(context) { PhotoAppointmentProfileStore(context) }
    val defaults = remember(context) { context.getSharedPreferences("ham-photo-import", 0) }
    var profile by remember { mutableStateOf(profileStore.load()) }
    var photos by remember(initialUri) { mutableStateOf(listOfNotNull(initialUri)) }
    var photoIndex by remember { mutableStateOf(0) }
    var angle by remember { mutableStateOf(0f) }
    var crop by remember { mutableStateOf(0) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var rows by remember { mutableStateOf<List<AppointmentReviewRow>>(emptyList()) }
    var scan by remember { mutableStateOf<PhotoCalendarScan?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var choosingFor by remember { mutableStateOf<UUID?>(null) }
    var choosingForAll by remember { mutableStateOf(false) }
    val cameraFiles = remember { mutableListOf<File>() }
    var cameraTarget by remember { mutableStateOf<Uri?>(null) }
    fun update(id: UUID, edit: (AppointmentReviewRow) -> AppointmentReviewRow) {
        rows = rows.map { if (it.id == id) edit(it) else it }
    }
    fun reviewed(row: AppointmentReviewRow): PhotoAppointment? {
        val date = runCatching { LocalDate.parse(row.date) }.getOrNull() ?: return null
        val start = runCatching { LocalTime.parse(row.start) }.getOrNull() ?: return null
        val minutes = row.duration.toIntOrNull()?.takeIf { it in 1..720 }
        return row.detected.copy(date = date, start = start, durationMinutes = minutes)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { selected ->
        if (selected.isNotEmpty()) {
            photos = photos + selected; photoIndex = photos.lastIndex
            angle = 0f; crop = 0; scan = null
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraTarget
        if (saved && uri != null) {
            photos = photos + uri; photoIndex = photos.lastIndex
            angle = 0f; crop = 0; scan = null
        }
        else cameraFiles.lastOrNull()?.delete()
        cameraTarget = null
    }
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 100_000) { "Directory file is too large" }
                    output.write(buffer, 0, count)
                }
                String(output.toByteArray(), Charsets.UTF_8)
            } ?: error("Could not open directory file")
            profileStore.save(text)
            profile = profileStore.load()
            scan = null
            message = "Private directory loaded on this device. Review the suggested titles before importing."
        } catch (_: Exception) { message = "Could not read the directory JSON. Check its format and try again." }
    }
    DisposableEffect(Unit) { onDispose { cameraFiles.forEach(File::delete) } }
    val current = photos.getOrNull(photoIndex)
    LaunchedEffect(current, angle, crop) {
        preview = null
        if (current != null) try { preview = PhotoAppointmentOcr.preview(context, current, angle, crop) }
        catch (_: Exception) { message = "Could not preview this photo" }
    }
    fun assess(detected: PhotoAppointment, title: String, currentScan: PhotoCalendarScan): Pair<PhotoMatchKind, AgendaEvent?> {
        val pending = PhotoAppointmentMatches.assess(detected, title, profile?.facility.orEmpty(),
            currentScan.pendingEvents, emptySet(), profile?.zoneId() ?: ZoneId.systemDefault())
        if (pending.kind == PhotoMatchKind.ALREADY_EXISTS || pending.kind == PhotoMatchKind.POSSIBLE_DUPLICATE)
            return PhotoMatchKind.PENDING to pending.event
        val match = PhotoAppointmentMatches.assess(detected, title, profile?.facility.orEmpty(),
            currentScan.events, currentScan.unverifiedCalendars, profile?.zoneId() ?: ZoneId.systemDefault())
        return match.kind to match.event
    }
    fun scanCalendars() {
        if (rows.isEmpty()) { message = "Read a photo first"; return }
        busy = true; message = "Checking all connected calendars…"
        scope.launch {
            try {
                require(rows.all { reviewed(it) != null }) { "Review invalid date or time" }
                val result = model.scanPhotoAppointments(rows.mapNotNull { reviewed(it)?.date })
                scan = result
                rows = rows.map { row ->
                    val (kind, event) = assess(reviewed(row)!!, row.title, result)
                    // A fresh scan is required before retrying a failed write. Preserve
                    // confirmed and uncertain outcomes so they cannot be submitted twice.
                    row.copy(match = kind, matchEvent = event, selected = false,
                        // A local validation failure cannot have written an event. Provider failures
                        // may be ambiguous, so keep those locked against accidental duplicate retries.
                        outcome = if (row.outcome == "Failed: check date, time, duration, title and writable destination") "" else row.outcome)
                }
                message = if (result.unverifiedCalendars.isEmpty()) "Calendar comparison complete"
                    else "Unable to verify ${result.unverifiedCalendars.size} calendar(s). Resolve access before importing."
            } catch (_: Exception) { message = "Calendar comparison failed. No events were changed."; scan = null }
            finally { busy = false }
        }
    }
    fun importSelected() {
        val checked = rows.filter { it.selected && it.outcome.isBlank() }
        if (checked.isEmpty()) { message = "Select appointments to import"; return }
        if (scan == null || scan!!.unverifiedCalendars.isNotEmpty()) {
            message = "Check every connected calendar before importing; some cannot be verified."
            return
        }
        val zone = profile?.zoneId() ?: ZoneId.systemDefault()
        if (zone != ZoneId.systemDefault()) {
            message = "This import needs the device time zone to match the directory time zone. No events were changed."
            return
        }
        busy = true
        scope.launch {
            try {
                for (row in checked) {
                    val day = runCatching { LocalDate.parse(row.date) }.getOrNull()
                    val start = runCatching { LocalTime.parse(row.start) }.getOrNull()
                    val minutes = row.duration.toIntOrNull()
                    val source = row.calendarId?.let(model::selectedWritable)
                    if (day == null || start == null || minutes == null || minutes !in 1..720 ||
                        !start.plusMinutes(minutes.toLong()).isAfter(start) || row.title.isBlank() || source == null) {
                        update(row.id) { it.copy(outcome = "Failed: check date, time, duration, title and writable destination", selected = false) }
                        continue
                    }
                    // Re-read immediately before each write. A prior import or queued create may have appeared.
                    val before = model.scanPhotoAppointments(listOf(day))
                    val (kind, _) = assess(row.detected.copy(date = day, start = start, durationMinutes = minutes),
                        row.title, before)
                    if ((kind != PhotoMatchKind.NEW && !(kind == PhotoMatchKind.POSSIBLE_DUPLICATE && row.separateConfirmed)) ||
                        before.unverifiedCalendars.isNotEmpty()) {
                        update(row.id) { it.copy(outcome = "Needs review: an event or pending change may already exist", selected = false) }
                        continue
                    }
                    val result = CompletableDeferred<Triple<Boolean, Boolean, String>>()
                    model.createTimed(source.id, day, start.toString(), start.plusMinutes(minutes.toLong()).toString(),
                        row.title.trim(), row.description.trim(), row.location.trim(), done = {},
                        onResult = { ok, confirmed, note -> result.complete(Triple(ok, confirmed, note)) })
                    val (accepted, confirmed, note) = withTimeout(120_000) { result.await() }
                    if (!accepted) {
                        update(row.id) { it.copy(outcome = "Failed: $note", selected = false) }
                        continue
                    }
                    if (!confirmed) {
                        update(row.id) { it.copy(outcome = "Pending synchronization; do not retry yet", selected = false) }
                        continue
                    }
                    val after = runCatching { model.scanPhotoAppointments(listOf(day)) }.getOrNull()
                    val visible = after?.events?.any { event ->
                        event.calendarId == source.id && event.summary == row.title.trim() &&
                            event.location == row.location.trim() &&
                            AgendaEvent.instant(event.start, zone)?.atZone(zone)?.toLocalDateTime() == LocalDateTime.of(day, start) &&
                            AgendaEvent.instant(event.end, zone)?.atZone(zone)?.toLocalDateTime() ==
                                LocalDateTime.of(day, start.plusMinutes(minutes.toLong()))
                    } == true
                    update(row.id) { it.copy(outcome = if (visible) "Confirmed on destination calendar"
                        else "Accepted; calendar read-back needs review before retry", selected = false) }
                    defaults.edit().putString("destination", source.id).apply()
                }
                message = "Import pass finished. Review each row's result before another attempt."
            } catch (_: Exception) { message = "Import stopped with an uncertain result. Check the calendar before retrying."
                rows = rows.map { if (it.selected) it.copy(outcome = "Awaiting confirmation; check calendar before retry", selected = false) else it }
            }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Import Appointments from Photo", style = MaterialTheme.typography.headlineSmall)
        Text("Photos are read on this device. HAM will not save any appointment until you review and select it.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !busy) { Text("Choose photos") }
            OutlinedButton(onClick = {
                val file = File(context.cacheDir, "appointment_photos/${UUID.randomUUID()}.jpg")
                file.parentFile?.mkdirs(); cameraFiles += file
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.photo_import", file)
                cameraTarget = uri
                camera.launch(uri)
            }, enabled = !busy) { Text("Take photo") }
        }
        OutlinedButton(onClick = { directoryPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy) {
            Text(if (profile == null) "Load private clinic directory" else "Replace private clinic directory")
        }
        Text(profile?.let { "Directory: ${it.facility} · ${it.zone}" } ?: "No clinic directory loaded; titles and locations need manual review.")
        if (current != null) {
            Text("Photo ${photoIndex + 1} of ${photos.size}")
            preview?.let { Image(it.asImageBitmap(), "Appointment photo preview",
                Modifier.fillMaxWidth().heightIn(max = 230.dp)) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { angle -= 90f }, enabled = !busy) { Text("Rotate left") }
                TextButton(onClick = { angle += 90f }, enabled = !busy) { Text("Rotate right") }
                TextButton(onClick = { angle -= 2f }, enabled = !busy) { Text("Straighten −2°") }
                TextButton(onClick = { angle += 2f }, enabled = !busy) { Text("+2°") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { crop = (crop + 5).coerceAtMost(40) }, enabled = !busy) { Text("Crop edges +5%") }
                TextButton(onClick = { crop = 0; angle = 0f }, enabled = !busy) { Text("Reset image") }
                Text("Crop $crop% · Rotate ${angle.toInt()}°")
            }
            Button(onClick = {
                busy = true; message = "Reading photo on this device…"
                scope.launch {
                    try {
                        val text = PhotoAppointmentOcr.read(context, current, angle, crop)
                        val result = PhotoAppointments.parse(text)
                        val defaultId = defaults.getString("destination", null)
                        val next = (profile?.reconcileAll(result.appointments) ?: result.appointments).map { item ->
                            AppointmentReviewRow(UUID.randomUUID(), item, item.date.toString(), item.start.toString(),
                                item.durationMinutes?.toString().orEmpty(), profile?.title(item) ?: item.originalCode,
                                profile?.location(item) ?: item.printedLocation,
                                profile?.description(item) ?: "Printed code: ${item.originalCode}\nPrinted location: ${item.printedLocation}",
                                calendarId = defaultId, sourcePhotoKey = current.toString())
                        }
                        // Re-reading the same selected image replaces its unsaved proposals.
                        // Other sheets in this session retain their own reviewed rows.
                        rows = rows.filterNot { it.sourcePhotoKey == current.toString() } + next
                        scan = null
                        message = "Found ${result.appointments.size} appointment row(s) in this photo. ${result.warnings.joinToString(" ")} Review every row."
                    } catch (_: Exception) { message = "Could not read this photo. Adjust it or choose another image." }
                    finally { busy = false }
                }
            }, enabled = !busy) { Text("Read this photo") }
            if (photos.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { photoIndex = (photoIndex - 1).coerceAtLeast(0); angle = 0f; crop = 0 }) { Text("Previous") }
                    TextButton(onClick = { photoIndex = (photoIndex + 1).coerceAtMost(photos.lastIndex); angle = 0f; crop = 0 }) { Text("Next") }
                }
            }
        }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = {
            val item = PhotoAppointment(LocalDate.now(), LocalTime.NOON, null, "", "", "Entered manually",
                listOf("Confirm all appointment details against the photo."))
            rows = rows + AppointmentReviewRow(UUID.randomUUID(), item, "", "", "", "", "", "")
            scan = null
        }, enabled = !busy) { Text("Add a row the photo reader missed") }
        if (rows.isNotEmpty()) {
            Text("${rows.size} appointment row(s) to review. No calendar event has been created by reading photos.")
            Button(onClick = ::scanCalendars, enabled = !busy) { Text("Check all connected calendars") }
            OutlinedButton(onClick = { choosingForAll = true }, enabled = !busy) {
                Text("Choose destination for all appointments")
            }
            scan?.unverifiedCalendars?.takeIf(Set<String>::isNotEmpty)?.let { missing ->
                Text("Unable to verify this calendar: ${missing.joinToString()}", color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = { rows = rows.map { row -> row.copy(selected = row.match == PhotoMatchKind.NEW &&
                row.detected.warnings.isEmpty() && profile?.rule(row.detected.originalCode)?.needsConfirmation == false &&
                runCatching { LocalDateTime.of(LocalDate.parse(row.date), LocalTime.parse(row.start))
                    .atZone(profile?.zoneId() ?: ZoneId.systemDefault()).toInstant().isAfter(Instant.now()) }.getOrDefault(false)) } },
                enabled = !busy && scan?.unverifiedCalendars?.isEmpty() == true) { Text("Select all missing appointments") }
            rows.forEach { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        val past = runCatching { LocalDateTime.of(LocalDate.parse(row.date), LocalTime.parse(row.start))
                            .atZone(profile?.zoneId() ?: ZoneId.systemDefault()).toInstant().isBefore(Instant.now()) }.getOrDefault(false)
                        val needsReview = row.detected.warnings.isNotEmpty() ||
                            profile?.rule(row.detected.originalCode)?.needsConfirmation != false
                        val status = when {
                            row.outcome.isNotBlank() -> row.outcome
                            past -> "Past appointment — select individually if needed"
                            row.match == PhotoMatchKind.ALREADY_EXISTS -> "Already exists"
                            row.match == PhotoMatchKind.POSSIBLE_DUPLICATE -> "Possible duplicate — inspect before any write"
                            row.match == PhotoMatchKind.PENDING -> "Pending change — do not submit again"
                            row.match == PhotoMatchKind.UNVERIFIED -> "Unable to verify all calendars"
                            needsReview -> "Needs review"
                            row.match == PhotoMatchKind.NEW -> "New"
                            else -> "Not checked"
                        }
                        Row { Checkbox(row.selected, { checked -> update(row.id) { it.copy(selected = checked) } },
                            enabled = !busy && row.outcome.isBlank() && scan?.unverifiedCalendars?.isEmpty() == true &&
                (row.match == PhotoMatchKind.NEW || row.match == PhotoMatchKind.POSSIBLE_DUPLICATE && row.separateConfirmed))
                            Text(status, Modifier.padding(top = 12.dp)) }
                        row.matchEvent?.let { Text("Matching event: ${it.summary} · ${it.calendarName}") }
                        if (row.match == PhotoMatchKind.POSSIBLE_DUPLICATE) {
                            TextButton(onClick = { update(row.id) { it.copy(separateConfirmed = !it.separateConfirmed) } },
                                enabled = !busy) {
                                Text(if (row.separateConfirmed) "✓ I verified this is a separate appointment"
                                    else "Mark as a separate appointment after checking the match")
                            }
                        }
                        OutlinedTextField(row.date, { value -> update(row.id) { it.copy(date = value, match = null, selected = false) }; scan = null },
                            label = { Text("Date YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(row.start, { value -> update(row.id) { it.copy(start = value, match = null, selected = false) }; scan = null },
                                label = { Text("Start HH:MM") }, modifier = Modifier.weight(1f))
                            OutlinedTextField(row.duration, { value -> update(row.id) { it.copy(duration = value, match = null, selected = false) }; scan = null },
                                label = { Text("Minutes") }, modifier = Modifier.weight(1f))
                        }
                        val endTime = runCatching { LocalTime.parse(row.start).plusMinutes(row.duration.toLong()) }.getOrNull()
                        Text("End: ${endTime?.toString() ?: "check start and duration"}")
                        OutlinedTextField(row.title, { value -> update(row.id) { it.copy(title = value, match = null, selected = false) }; scan = null },
                            label = { Text("Event title") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(row.location, { value -> update(row.id) { it.copy(location = value) } },
                            label = { Text("Location") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(row.description, { value -> update(row.id) { it.copy(description = value) } },
                            label = { Text("Details") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        val destination = model.calendars.value.firstOrNull { it.id == row.calendarId }
                        Text("Destination: ${destination?.let { "${it.name} · ${it.provider.label()}" } ?: "Choose a writable calendar"}")
                        OutlinedButton(onClick = { choosingFor = row.id }, enabled = !busy) { Text("Choose destination calendar") }
                        if (row.detected.warnings.isNotEmpty()) Text(row.detected.warnings.joinToString(" "))
                        if (row.detected.privateTranscriptMatched) Text("Matched a private, verified transcription on this device")
                        TextButton(onClick = { update(row.id) { it.copy(showOriginal = !it.showOriginal) } }) {
                            Text(if (row.showOriginal) "Hide recognized text" else "Show recognized text") }
                        if (row.showOriginal) Text(row.detected.originalText)
                    }
                }
            }
            Button(onClick = ::importSelected, enabled = !busy && rows.any { it.selected }) { Text("Import selected appointments") }
            rows.firstOrNull { it.outcome.startsWith("Confirmed") }?.let { row ->
                TextButton(onClick = { runCatching { LocalDate.parse(row.date) }.getOrNull()?.let(onOpenCalendar) }) {
                    Text("Open calendar on imported date") }
            }
        }
        OutlinedButton(onClick = onCancel, enabled = !busy) { Text("Close without importing more") }
    }
    choosingFor?.let { rowId ->
        AlertDialog(onDismissRequest = { choosingFor = null },
            title = { Text("Choose a writable calendar") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                model.calendars.value.filter { it.canCreate }.forEach { source ->
                    TextButton(onClick = { update(rowId) { it.copy(calendarId = source.id) }; choosingFor = null },
                        modifier = Modifier.fillMaxWidth()) { Text("${source.name} · ${source.provider.label()}") }
                }
            } }, confirmButton = { TextButton(onClick = { choosingFor = null }) { Text("Close") } })
    }
    if (choosingForAll) AlertDialog(onDismissRequest = { choosingForAll = false },
        title = { Text("Choose a writable calendar for this batch") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            model.calendars.value.filter { it.canCreate }.forEach { source ->
                TextButton(onClick = {
                    rows = rows.map { it.copy(calendarId = source.id) }
                    choosingForAll = false
                }, modifier = Modifier.fillMaxWidth()) { Text("${source.name} · ${source.provider.label()}") }
            }
        } }, confirmButton = { TextButton(onClick = { choosingForAll = false }) { Text("Close") } })
}
