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
import androidx.compose.runtime.Composable
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
import com.tinkercobble.ham.data.HomeAssistantClient
import com.tinkercobble.ham.data.ScreenshotExtraction
import com.tinkercobble.ham.data.ScreenshotOcr
import com.tinkercobble.ham.data.ScreenshotDraft
import com.tinkercobble.ham.data.ScreenshotDraftStore
import com.tinkercobble.ham.data.VenueCandidate
import com.tinkercobble.ham.data.VenueLookup
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
    var allDay by remember(uri) { mutableStateOf(false) }
    var start by remember(uri) { mutableStateOf("") }
    var end by remember(uri) { mutableStateOf("") }
    var venue by remember(uri) { mutableStateOf("") }
    var address by remember(uri) { mutableStateOf("") }
    var addressVerified by remember(uri) { mutableStateOf(false) }
    var description by remember(uri) { mutableStateOf("") }
    var warnings by remember(uri) { mutableStateOf<List<String>>(emptyList()) }
    var candidates by remember { mutableStateOf<List<VenueCandidate>>(emptyList()) }
    var calendarId by remember(uri) { mutableStateOf<String?>(null) }
    var chooser by remember { mutableStateOf(false) }
    var attachmentId by remember(uri) { mutableStateOf<String?>(null) }
    var writeAttempted by remember(uri) { mutableStateOf(false) }
    var duplicatePrompt by remember { mutableStateOf(false) }
    val selectedCalendar = model.selectedWritable(calendarId)

    LaunchedEffect(uri) {
        val draft = draftStore.load()
        if (draft != null) {
            image = draftStore.image(draft)
            title = draft.title; date = draft.date; start = draft.start; end = draft.end
            allDay = draft.allDay; venue = draft.venue; address = draft.address
            description = draft.description; calendarId = draft.calendarId; attachmentId = draft.attachmentId
            writeAttempted = draft.writeAttempted
            dateChoices = draft.dateChoices.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            savedDates = draft.savedDates
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

    LaunchedEffect(image, ocrDone, title, date, start, end, allDay, venue, address, description, calendarId, attachmentId, writeAttempted, dateChoices, savedDates) {
        val file = image
        if (ocrDone && file != null && file.isFile) {
            draftStore.save(ScreenshotDraft(file.name, title, date, start, end, allDay,
                venue, address, description, calendarId, attachmentId, writeAttempted,
                dateChoices.map(LocalDate::toString), savedDates))
        }
    }

    fun save(allowDuplicate: Boolean) {
        val file = image ?: return
        val day = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
        val begins = if (allDay) null else runCatching { LocalTime.parse(start.trim()) }.getOrNull()
        val finishes = if (allDay) null else runCatching { LocalTime.parse(end.trim()) }.getOrNull()
        if (day == null || title.isBlank() || (!allDay && (begins == null || finishes == null || !finishes.isAfter(begins)))) {
            error = "Check the title, ISO date, and both times (HH:mm), or choose all-day."
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
                        dateChoices.map(LocalDate::toString), savedDates), durable = true)
                }
                require(client.screenshot(stored).contentEquals(file.readBytes())) {
                    "Stored screenshot read-back differs from the selected image"
                }
                val location = ScreenshotExtraction.eventLocation(venue, address)
                writeAttempted = true
                draftStore.save(ScreenshotDraft(file.name, title, date, start, end, allDay,
                    venue, address, description, id, stored, true,
                    dateChoices.map(LocalDate::toString), savedDates), durable = true)
                client.createScreenshotEvent(id, day, title, begins, finishes, location, description, stored, allowDuplicate)
                model.setCalendarPreferences(model.calendarPreferences.value.used(id))
                model.activeAgenda.value = "Calendar"
                model.calendarAnchor.value = day
                model.refresh()
                savedDates = (savedDates + date).distinct()
                val next = dateChoices.firstOrNull { it.toString() !in savedDates }
                if (next != null) {
                    date = next.toString()
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
                        dateChoices = suggestion.dates
                        date = suggestion.dates.firstOrNull()?.toString().orEmpty()
                        start = suggestion.start?.toString().orEmpty()
                        end = suggestion.end?.toString().orEmpty()
                        allDay = false
                        venue = suggestion.venue
                        address = suggestion.address
                        description = suggestion.description
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
                Text("Multiple dates found. Save one entry at a time; choose the intended date.")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    dateChoices.filterNot { it.toString() in savedDates }.take(3).forEach { candidate ->
                        TextButton(onClick = { date = candidate.toString() }) { Text(candidate.toString()) }
                    }
                }
            }
            OutlinedTextField(date, { date = it }, label = { Text("Date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { allDay = !allDay }) { Text(if (allDay) "All-day ✓" else "Make all-day") }
            }
            if (!allDay) {
                OutlinedTextField(start, { start = it }, label = { Text("Start HH:mm") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(end, { end = it }, label = { Text("End HH:mm") }, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(venue, { venue = it; addressVerified = false }, label = { Text("Venue") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(address, { address = it; addressVerified = false },
                label = { Text(if (addressVerified) "Verified address" else "Address (unverified)") }, modifier = Modifier.fillMaxWidth())
            Text("Address lookup sends only the venue/search words to OpenStreetMap Nominatim. © OpenStreetMap contributors.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                working = true
                scope.launch {
                    try { candidates = VenueLookup.search(listOf(venue, address).filter { it.isNotBlank() }.joinToString(" ")) }
                    catch (e: Exception) { error = e.message ?: "Address search failed" }
                    finally { working = false }
                }
            }, enabled = !working && (venue.length + address.length >= 4)) { Text("Verify address") }
            candidates.forEach { match ->
                TextButton(onClick = { venue = match.name; address = match.address; addressVerified = true; candidates = emptyList() }) {
                    Text(match.address)
                }
            }
            if (address.isNotBlank()) TextButton(onClick = {
                val query = Uri.encode(address)
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$query")))
            }) { Text("Maps") }
            OutlinedTextField(description, { description = it }, label = { Text("Description and flyer details") },
                modifier = Modifier.fillMaxWidth(), minLines = 4)
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
