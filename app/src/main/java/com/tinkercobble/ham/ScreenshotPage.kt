package com.tinkercobble.ham

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tinkercobble.ham.data.HomeAssistantClient
import com.tinkercobble.ham.data.ScreenshotExtraction
import com.tinkercobble.ham.data.ScreenshotOcr
import com.tinkercobble.ham.data.ScreenshotDraft
import com.tinkercobble.ham.data.ScreenshotDraftStore
import com.tinkercobble.ham.data.VenueCandidate
import com.tinkercobble.ham.data.EventEntryTime
import com.tinkercobble.ham.data.AddressVerification
import com.tinkercobble.ham.ui.HamViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

@Composable
internal fun ScreenshotPage(model: HamViewModel, uri: Uri?, onCancel: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(model.server.value) { HomeAssistantClient(context) }
    val draftStore = remember { ScreenshotDraftStore(context) }
    var image by remember(uri) { mutableStateOf<File?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var ocrDone by remember(uri) { mutableStateOf(false) }
    var title by remember(uri) { mutableStateOf("") }
    var date by remember(uri) { mutableStateOf("") }
    var dateChoices by remember(uri) { mutableStateOf<List<LocalDate>>(emptyList()) }
    var savedDates by remember(uri) { mutableStateOf<List<String>>(emptyList()) }
    var dateConfirmed by remember(uri) { mutableStateOf(false) }
    var suggestedEnd by remember(uri) { mutableStateOf(false) }
    var defaultMinutes by remember { mutableStateOf(EventEntryPreferences.defaultMinutes(context)) }
    var allDay by remember(uri) { mutableStateOf(false) }
    var start by remember(uri) { mutableStateOf("") }
    var end by remember(uri) { mutableStateOf("") }
    var venue by remember(uri) { mutableStateOf("") }
    var address by remember(uri) { mutableStateOf("") }
    var locationOpen by remember(uri) { mutableStateOf(false) }
    var addressVerified by remember(uri) { mutableStateOf(false) }
    val lookupPrefs = remember(context) { context.getSharedPreferences("ham-address-lookup", 0) }
    var lookupOptIn by remember { mutableStateOf(lookupPrefs.getBoolean("enabled", false)) }
    var lookupRequest by remember { mutableStateOf("") }
    var description by remember(uri) { mutableStateOf("") }
    var originalText by remember(uri) { mutableStateOf("") }
    var descriptionOpen by remember(uri) { mutableStateOf(false) }
    var warnings by remember(uri) { mutableStateOf<List<String>>(emptyList()) }
    var candidates by remember { mutableStateOf<List<VenueCandidate>>(emptyList()) }
    var calendarId by remember(uri) { mutableStateOf<String?>(null) }
    var chooser by remember { mutableStateOf(false) }
    var attachmentId by remember(uri) { mutableStateOf<String?>(null) }
    var writeAttempted by remember(uri) { mutableStateOf(false) }
    var duplicatePrompt by remember { mutableStateOf(false) }
    val selectedCalendar = model.selectedWritable(calendarId)

    LaunchedEffect(lookupRequest, lookupOptIn) {
        val query = lookupRequest
        if (query.isBlank() || !lookupOptIn || !AddressVerification.complete(query)) return@LaunchedEffect
        try {
            val matches = AddressVerification.lookup(context, query)
            if (lookupOptIn && address == query) candidates = matches
        } catch (_: Exception) {
            if (address == query) candidates = emptyList()
        }
    }

    LaunchedEffect(uri) {
        val draft = draftStore.load()
        if (draft != null) {
            image = draftStore.image(draft)
            title = draft.title; date = draft.date; start = draft.start; end = draft.end
            allDay = draft.allDay; venue = draft.venue; address = draft.address
            locationOpen = draft.venue.isNotBlank() || draft.address.isNotBlank()
            description = draft.description; calendarId = draft.calendarId; attachmentId = draft.attachmentId
            originalText = draft.originalText
            writeAttempted = draft.writeAttempted
            dateChoices = draft.dateChoices.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            savedDates = draft.savedDates
            suggestedEnd = draft.suggestedEnd
            ocrDone = true
            if (uri != null) error = "Your unsent draft was restored. Cancel it before importing a different image."
            return@LaunchedEffect
        }
        if (uri == null) { error = "No screenshot draft to resume"; return@LaunchedEffect }
        working = true
        try { image = ScreenshotOcr.copy(context, uri) }
        catch (e: Exception) { error = e.message ?: "Could not open screenshot" }
        finally { working = false }
    }

    LaunchedEffect(image, ocrDone, title, date, start, end, allDay, venue, address, description, originalText, calendarId, attachmentId, writeAttempted, dateChoices, savedDates) {
        val file = image
        if (ocrDone && file != null && file.isFile) {
            draftStore.save(ScreenshotDraft(file.name, title, date, start, end, allDay,
                venue, address, description, calendarId, attachmentId, writeAttempted,
                dateChoices.map(LocalDate::toString), savedDates, suggestedEnd, originalText))
        }
    }

    fun save(allowDuplicate: Boolean) {
        val file = image ?: return
        val day = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
        val begins = if (allDay) null else EventEntryTime.parse(start)
        val finishes = if (allDay) null else EventEntryTime.parse(end)
        if (dateChoices.size > 1 && !dateConfirmed) {
            error = "Choose the intended date before saving."
            return
        }
        if (day == null || title.isBlank() || (!allDay && (begins == null || finishes == null || !finishes.isAfter(begins)))) {
            error = "Check the title, date, and time range."
            return
        }
        val id = selectedCalendar?.id ?: run { error = "Choose a writable Home Assistant calendar"; return }
        working = true
        error = ""
        scope.launch {
            try {
                val stored = attachmentId ?: client.uploadScreenshot(file).also {
                    attachmentId = it
                    draftStore.save(ScreenshotDraft(file.name, title, date, start, end, allDay,
                        venue, address, description, id, it, false,
                        dateChoices.map(LocalDate::toString), savedDates, suggestedEnd, originalText), durable = true)
                }
                require(client.screenshot(stored).contentEquals(file.readBytes())) {
                    "Stored screenshot read-back differs from the selected image"
                }
                val location = ScreenshotExtraction.eventLocation(venue, address)
                writeAttempted = true
                draftStore.save(ScreenshotDraft(file.name, title, date, start, end, allDay,
                    venue, address, description, id, stored, true,
                    dateChoices.map(LocalDate::toString), savedDates, suggestedEnd, originalText), durable = true)
                client.createScreenshotEvent(id, day, title, begins, finishes, location, description, stored, allowDuplicate)
                model.setCalendarPreferences(model.calendarPreferences.value.used(id))
                model.activeAgenda.value = "Calendar"
                model.calendarAnchor.value = day
                model.refresh()
                savedDates = (savedDates + date).distinct()
                val next = dateChoices.firstOrNull { it.toString() !in savedDates }
                if (next != null) {
                    date = next.toString()
                    dateConfirmed = false
                    error = "Saved on $day. Review the next date before saving another event."
                } else {
                    draftStore.clear()
                    onSaved()
                }
            } catch (e: Exception) {
                error = e.message ?: "Could not save; your reviewed event remains here."
                if (error.contains("similar event", true)) duplicatePrompt = true
            } finally { working = false }
        }
    }

    fun cancel() {
        working = true
        scope.launch {
            try {
                if (!writeAttempted) attachmentId?.let { client.deleteScreenshot(it) }
                draftStore.clear()
                image?.delete()
                onCancel()
            } catch (e: Exception) {
                error = "Could not remove the stored screenshot. Retry Cancel when connected."
            } finally { working = false }
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("New Calendar Event", style = MaterialTheme.typography.headlineMedium)
        image?.let { file ->
            val preview = remember(file.path) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(file.path, opts)
            }
            if (preview != null) Image(preview.asImageBitmap(), "Selected screenshot",
                Modifier.fillMaxWidth().heightIn(max = 220.dp))
        }
        if (!ocrDone) {
            Text("Review the screenshot, then read its text on this phone. The image is not sent to an AI service.")
            Button(onClick = {
                val file = image ?: return@Button
                working = true
                scope.launch {
                    try {
                        val suggestion = ScreenshotExtraction.parse(ScreenshotOcr.read(context, file))
                        title = suggestion.title
                        dateChoices = suggestion.dates.sortedWith(compareBy<LocalDate> { it.isBefore(LocalDate.now()) }.thenBy { it })
                        date = dateChoices.firstOrNull()?.toString().orEmpty()
                        start = suggestion.start?.toString().orEmpty()
                        suggestedEnd = suggestion.start != null && suggestion.end == null
                        end = (suggestion.end ?: suggestion.start?.let { EventEntryTime.suggestedEnd(it, defaultMinutes) })?.toString().orEmpty()
                        allDay = false
                        venue = suggestion.venue
                        address = suggestion.address
                        locationOpen = venue.isNotBlank() || address.isNotBlank()
                        description = suggestion.description
                        originalText = suggestion.description
                        warnings = suggestion.warnings
                        ocrDone = true
                    } catch (e: Exception) { error = e.message ?: "Could not read image" }
                    finally { working = false }
                }
            }, enabled = image != null && !working) { Text("Read screenshot") }
        } else {
            warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(title, { title = it }, label = { Text("Event title") }, modifier = Modifier.fillMaxWidth())
            if (dateChoices.size > 1) {
                Text("Multiple dates found. Choose the intended date before saving each event.")
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    dateChoices.filterNot { it.toString() in savedDates }.take(3).forEach { candidate ->
                        TextButton(onClick = { date = candidate.toString(); dateConfirmed = true }) {
                            Text((if (dateConfirmed && date == candidate.toString()) "Selected: " else "") + readableDate(candidate))
                        }
                    }
                }
            }
            EventDateField(date, { date = it; dateConfirmed = true })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("All-day")
                Switch(checked = allDay, onCheckedChange = { allDay = it })
            }
            if (!allDay) {
                EventTimeField(start, { value ->
                    start = value
                    if (end.isBlank() || suggestedEnd) {
                        end = EventEntryTime.parse(value)?.let { EventEntryTime.suggestedEnd(it, defaultMinutes) }?.toString().orEmpty()
                        suggestedEnd = end.isNotBlank()
                    }
                }, "Starts", Modifier.fillMaxWidth())
                EventTimeField(end, { end = it; suggestedEnd = false },
                    if (suggestedEnd) "Ends (suggested: $defaultMinutes min)" else "Ends", Modifier.fillMaxWidth())
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(15, 30, 60, 120).forEach { minutes ->
                        TextButton(onClick = {
                            end = EventEntryTime.parse(start)?.let { EventEntryTime.suggestedEnd(it, minutes) }?.toString().orEmpty()
                            suggestedEnd = end.isNotBlank()
                        }) { Text(if (minutes == 60) "1 hour" else if (minutes == 120) "2 hours" else "$minutes min") }
                    }
                }
                TextButton(onClick = {
                    defaultMinutes = listOf(15, 30, 60, 120).let { options ->
                        options[(options.indexOf(defaultMinutes) + 1) % options.size]
                    }
                    EventEntryPreferences.setDefaultMinutes(context, defaultMinutes)
                }) { Text("Default duration for new events: $defaultMinutes min · Change") }
            }
            TextButton(onClick = { locationOpen = !locationOpen }) {
                Text(if (locationOpen) "Hide location" else "Add location (optional)")
            }
            if (locationOpen) {
            OutlinedTextField(venue, { venue = it; addressVerified = false }, label = { Text("Venue (optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(address, { address = it; addressVerified = false; candidates = emptyList() },
                label = { Text(if (addressVerified) "Verified address" else "Street address (optional)") },
                modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                    if (!focus.isFocused && lookupOptIn && AddressVerification.complete(address))
                        lookupRequest = address
                })
            Row {
                Switch(checked = lookupOptIn, onCheckedChange = {
                    lookupOptIn = it
                    lookupPrefs.edit().putBoolean("enabled", it).apply()
                    if (it && AddressVerification.complete(address)) lookupRequest = address
                    if (!it) candidates = emptyList()
                })
                Text("Verify completed addresses using Android's location service (may send the address to its provider)",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (addressVerified) Text("Address match chosen", style = MaterialTheme.typography.bodySmall)
            candidates.forEach { match ->
                TextButton(onClick = { address = match.address; addressVerified = true; candidates = emptyList() }) {
                    Text(match.address)
                }
            }
            if (address.isNotBlank()) TextButton(onClick = {
                val query = Uri.encode(address)
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$query")))
            }) { Text("Maps") }
            }
            TextButton(onClick = { descriptionOpen = !descriptionOpen }) {
                Text(if (descriptionOpen) "Hide description and original text" else "Edit description and original text")
            }
            if (descriptionOpen) OutlinedTextField(description, { description = it },
                label = { Text("Description and original extracted text") },
                modifier = Modifier.fillMaxWidth(), minLines = 4)
            if (descriptionOpen && originalText.isNotBlank()) Text("Original screenshot text (kept on this device for review):\n$originalText",
                style = MaterialTheme.typography.bodySmall)
            Text("Screenshot: ${if (attachmentId == null) "ready to store securely on Save" else "stored in Home Assistant"}")
            Text("Destination calendar: ${selectedCalendar?.name ?: "Choose calendar"}")
            if (selectedCalendar != null) Text(selectedCalendar.id, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { chooser = true }) { Text("Change Calendar") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save(false) }, enabled = !working && selectedCalendar != null) { Text("Save Event") }
                OutlinedButton(onClick = ::cancel, enabled = !working) { Text("Cancel") }
            }
            Text("Edit Details above before saving.", style = MaterialTheme.typography.bodySmall)
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (working) Text("Working…")
    }
    if (chooser) CalendarPicker(model, onDismiss = { chooser = false }) { calendarId = it; chooser = false }
    if (duplicatePrompt) AlertDialog(onDismissRequest = { duplicatePrompt = false },
        title = { Text("Similar event found") }, text = { Text("A matching title and date already exist on this calendar. Create another only if it is a separate event.") },
        confirmButton = { Button(onClick = { duplicatePrompt = false; save(true) }) { Text("Create another") } },
        dismissButton = { TextButton(onClick = { duplicatePrompt = false }) { Text("Review") } })
}
