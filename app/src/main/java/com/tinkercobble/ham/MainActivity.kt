package com.tinkercobble.ham

import android.os.Bundle
import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.tinkercobble.ham.auth.HomeAssistantAuth
import com.tinkercobble.ham.auth.ConnectionPolicy
import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.CalendarSelection
import com.tinkercobble.ham.ui.HamViewModel
import com.tinkercobble.ham.ui.HamTheme
import com.tinkercobble.ham.updates.AvailableUpdate
import com.tinkercobble.ham.updates.UpdateService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HamTheme { HamApp() } }
    }
}

private val pages = listOf("Today", "Calendar", "Quick Add", "Tasks", "Search", "Settings")
private val dayFormat = DateTimeFormatter.ofPattern("EEE, MMM d")
private val eventTimeFormat = DateTimeFormatter.ofPattern("MMM d, h:mm a")

private fun displayEventTime(value: String): String =
    runCatching { OffsetDateTime.parse(value).format(eventTimeFormat) }.getOrDefault(value)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HamApp(model: HamViewModel = viewModel()) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(if (model.connected.value) "Today" else "Settings") }
    var availableUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }
    var showUpdatePrompt by remember { mutableStateOf(false) }
    var downloadingUpdate by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf(0) }
    var updateError by remember { mutableStateOf("") }
    var pendingInstall by remember { mutableStateOf<File?>(null) }
    var permissionNeeded by remember { mutableStateOf(false) }
    fun openInstaller(file: File) {
        runCatching { context.startActivity(UpdateService.installerIntent(context, file)) }
            .onFailure { updateError = "Android could not open the installer. Try again." }
    }
    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val file = pendingInstall
        if (file != null && UpdateService.canInstall(context)) openInstaller(file)
        else updateError = "Installation permission is still off. Try again when ready."
    }
    fun beginDownload(update: AvailableUpdate) {
        updateError = ""
        downloadingUpdate = true
        updateProgress = 0
        scope.launch {
            try {
                val file = UpdateService.downloadAndVerify(context, update) { updateProgress = it }
                pendingInstall = file
                downloadingUpdate = false
                if (UpdateService.canInstall(context)) openInstaller(file)
                else permissionNeeded = true
            } catch (error: Exception) {
                downloadingUpdate = false
                updateError = error.message ?: "The update could not be verified."
            }
        }
    }
    LaunchedEffect(Unit) {
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay(1_500)
            while (true) {
                if (UpdateService.shouldAutoCheck(context)) {
                    val result = UpdateService.check(context, manual = false)
                    result.update?.let { availableUpdate = it; showUpdatePrompt = true }
                }
                delay(6L * 60L * 60L * 1000L)
            }
        }
    }
    val destinations = listOf("Today", "Calendar", "Quick Add", "More")
    Scaffold(topBar = {
        TopAppBar(title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(painterResource(R.mipmap.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(38.dp))
                Column {
                    Text("HAM", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (BuildConfig.FLAVOR == "dev") "$page · DEV" else page,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        })
    }) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
            val wide = maxWidth >= 600.dp
            Row(Modifier.fillMaxSize()) {
                if (wide) {
                    Surface(tonalElevation = 2.dp) {
                        Column(Modifier.widthIn(min = 156.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            pages.forEach { destination ->
                                NavigationChoice(destination, page == destination, { page = destination }, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Column(Modifier.widthIn(max = 900.dp).fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            if (model.loading.value) Text("Updating…", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                            if (model.message.value.isNotBlank() && model.message.value != "Updated from Home Assistant")
                                Text(model.message.value, style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!model.connected.value && page != "Settings") {
                                Text("Connect to Home Assistant to see your calendar.")
                                TextButton(onClick = { page = "Settings" }) { Text("Open Settings") }
                            } else when (page) {
                                "Today" -> TodayPage(model)
                                "Calendar" -> CalendarPage(model)
                                "Quick Add" -> QuickAddPage(model)
                                "Tasks" -> TasksPage(model)
                                "Search" -> SearchPage(model)
                                "More" -> MorePage { page = it }
                                else -> SettingsPage(model, onConnected = { page = "Today" }, onUpdateAvailable = {
                                    availableUpdate = it
                                    showUpdatePrompt = true
                                })
                            }
                        }
                    }
                    if (!wide) Surface(tonalElevation = 3.dp) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            destinations.forEach { destination ->
                                NavigationChoice(destination, page == destination ||
                                    (destination == "More" && page in listOf("Tasks", "Search", "Settings")),
                                    { page = destination }, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
    availableUpdate?.let { update ->
        if (showUpdatePrompt) AlertDialog(onDismissRequest = {
            UpdateService.later(context, update); showUpdatePrompt = false; availableUpdate = null
        }, title = { Text("HAM Update Available") },
            text = { Text("Version ${update.versionName} is ready to install.") },
            confirmButton = { Button(onClick = { showUpdatePrompt = false; beginDownload(update) }) { Text("Update Now") } },
            dismissButton = { TextButton(onClick = {
                UpdateService.later(context, update); showUpdatePrompt = false; availableUpdate = null
            }) { Text("Later") } })
    }
    if (downloadingUpdate) AlertDialog(onDismissRequest = {}, title = { Text("Downloading HAM update") },
        text = { Text("Downloading… $updateProgress%") }, confirmButton = {})
    if (permissionNeeded) AlertDialog(onDismissRequest = { permissionNeeded = false },
        title = { Text("Allow HAM updates") },
        text = { Text("Allow HAM to install this verified update in Android settings.") },
        confirmButton = { Button(onClick = {
            permissionNeeded = false
            runCatching { installPermission.launch(UpdateService.permissionIntent(context)) }
                .onFailure { updateError = "Could not open Android installation settings." }
        }) { Text("Open Settings") } },
        dismissButton = { TextButton(onClick = { permissionNeeded = false }) { Text("Later") } })
    if (updateError.isNotBlank()) AlertDialog(onDismissRequest = { updateError = "" },
        title = { Text("HAM update could not continue") }, text = { Text(updateError) },
        confirmButton = { Button(onClick = {
            updateError = ""
            val file = pendingInstall
            if (file != null && file.isFile && UpdateService.canInstall(context)) openInstaller(file)
            else availableUpdate?.let(::beginDownload)
        }) { Text("Retry") } },
        dismissButton = { TextButton(onClick = { updateError = "" }) { Text("Later") } })
}

@Composable
private fun NavigationChoice(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val display = if (label == "Quick Add") "+ Add" else label
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 52.dp),
        shape = MaterialTheme.shapes.medium,
        color = when { label == "Quick Add" -> MaterialTheme.colorScheme.secondary; selected -> MaterialTheme.colorScheme.primary; else -> Color.Transparent },
        contentColor = when { label == "Quick Add" -> MaterialTheme.colorScheme.onSecondary; selected -> MaterialTheme.colorScheme.onPrimary; else -> MaterialTheme.colorScheme.onSurface }) {
        Box(contentAlignment = Alignment.Center) {
            Text(display, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        }
    }
}

@Composable
private fun MorePage(open: (String) -> Unit) {
    Text("More", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    listOf("Tasks" to "Open items from Home Assistant", "Search" to "Find an event", "Settings" to "Connection, calendars, and updates")
        .forEach { (name, detail) ->
            Card(onClick = { open(name) }, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
}

@Composable
private fun DayPicker(model: HamViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { model.move(-1) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) { Text("‹") }
        Text(model.selectedDate.value.format(dayFormat), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { model.move(1) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) { Text("›") }
    }
    if (model.selectedDate.value != LocalDate.now())
        TextButton(onClick = { model.today() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Go to today") }
}

@Composable
private fun TodayPage(model: HamViewModel) {
    Text("Your day", style = MaterialTheme.typography.headlineMedium)
    DayPicker(model)
    val items = model.visibleEvents.filter { it.occursOn(model.selectedDate.value) }
    if (items.isEmpty()) Text("No calendar events for this day.", Modifier.padding(top = 20.dp))
    EventList(items, model)
}

@Composable
private fun CalendarPage(model: HamViewModel) {
    Text("Calendar", style = MaterialTheme.typography.headlineMedium)
    DayPicker(model)
    val weekStart = model.selectedDate.value.minusDays(model.selectedDate.value.dayOfWeek.value.toLong() - 1)
    Text("Week of ${weekStart.format(dayFormat)}", Modifier.padding(vertical = 12.dp))
    val items = model.visibleEvents.filter { (0..6).any { offset -> it.occursOn(weekStart.plusDays(offset.toLong())) } }
    if (items.isEmpty()) Text("No events this week.")
    EventList(items, model)
}

@Composable
private fun EventList(events: List<AgendaEvent>, model: HamViewModel) {
    var selected by remember { mutableStateOf<AgendaEvent?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(events, key = { it.identity }) { event ->
            Card(Modifier.fillMaxWidth().clickable { selected = event }) {
                Column(Modifier.padding(14.dp)) {
                    Text(event.summary, fontWeight = FontWeight.SemiBold)
                    Text("${event.date.format(dayFormat)} · ${event.calendarName}", style = MaterialTheme.typography.bodySmall)
                    if (!event.allDay) Text(event.start.substringAfter('T').take(5), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    selected?.let { event -> EventDetails(event, model.calendars.value.find { it.id == event.calendarId }, model) { selected = null } }
}

@Composable
private fun EventDetails(event: AgendaEvent, source: CalendarSource?, model: HamViewModel, close: () -> Unit) {
    var title by remember(event.identity) { mutableStateOf(event.summary) }
    val editable = source?.canUpdate == true && event.uid != null && event.recurrenceId == null
    AlertDialog(onDismissRequest = close, title = { Text("Event details") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (editable) OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") })
            else Text(event.summary, style = MaterialTheme.typography.titleMedium)
            Text(event.calendarName)
            Text(if (event.allDay) "${event.date.format(dayFormat)} · All day"
                else "${displayEventTime(event.start)} – ${displayEventTime(event.end)}")
            if (event.location.isNotBlank() && !event.location.equals("null", ignoreCase = true)) Text(event.location)
            if (event.description.isNotBlank() && !event.description.equals("null", ignoreCase = true)) Text(event.description)
            if (!editable) Text("This event can't be edited here.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        if (editable && source != null && title != event.summary)
            Button(onClick = { model.edit(source, event, title, close) }) { Text("Save change") }
        else TextButton(onClick = close) { Text("Close") }
    }, dismissButton = { if (editable) TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
private fun QuickAddPage(model: HamViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("Quick Add", style = MaterialTheme.typography.headlineMedium)
    Text("Create an all-day event.", Modifier.padding(vertical = 8.dp))
    DayPicker(model)
    val prefs = model.calendarPreferences.value
    // Keep the chosen ID through list refreshes; never substitute an entry with the same name.
    var selectedId by remember(model.server.value, prefs.defaultId) { mutableStateOf(prefs.defaultId) }
    val choice = model.selectedWritable(selectedId)
    var title by remember { mutableStateOf("") }
    var chooser by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Event title") }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    if (model.calendars.value.none { it.canCreate }) Text("No writable calendars available.")
    else {
        if (selectedId != null && choice == null)
            Text("The selected calendar is unavailable or read-only. Choose another calendar.", color = MaterialTheme.colorScheme.error)
        Text("Selected calendar", style = MaterialTheme.typography.labelMedium)
        Text(choice?.name ?: "Choose a calendar", style = MaterialTheme.typography.titleMedium)
        if (choice != null) {
            Text("Source: ${choice.source}", style = MaterialTheme.typography.bodySmall)
            Text(choice.id, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { chooser = true }) { Text(if (choice == null) "Choose calendar" else "Change calendar") }
        Button(onClick = { confirm = true }, enabled = title.isNotBlank() && choice != null && !model.loading.value) {
            Text("Review event")
        }
        if (confirm) {
            val reviewed = model.selectedWritable(selectedId)
            AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Create event?") },
                text = { Column {
                    Text("${title.trim()} · ${model.selectedDate.value}")
                    Text(reviewed?.name ?: "Calendar unavailable")
                    Text(reviewed?.id ?: "Choose another calendar", style = MaterialTheme.typography.bodySmall)
                } },
                confirmButton = { Button(onClick = {
                    val id = reviewed?.id ?: return@Button
                    model.create(id, model.selectedDate.value, title) { title = ""; confirm = false }
                }, enabled = reviewed != null && !model.loading.value) { Text("Create") } },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
        }
    }
    if (chooser) CalendarPicker(model, onDismiss = { chooser = false }) {
        selectedId = it
        confirm = false
        chooser = false
    }
    }
}

@Composable
private fun CalendarPicker(model: HamViewModel, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    val prefs = model.calendarPreferences.value
    val writable = CalendarSelection.writable(model.calendars.value)
        .filter { showHidden || it.id !in prefs.hidden }
    val groups = CalendarSelection.groups(CalendarSelection.search(writable, query), prefs)
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    AlertDialog(onDismissRequest = onDismiss, title = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Choose calendar", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
        }
    },
        text = { Column {
            OutlinedTextField(query, { query = it }, label = { Text("Search calendars") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { showHidden = !showHidden }) { Text(if (showHidden) "Hide hidden calendars" else "Show hidden calendars") }
            LazyColumn(Modifier.height(if (keyboardVisible) 160.dp else 360.dp)) {
                groups.forEach { (heading, entries) ->
                    item { Text(heading, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp)) }
                    items(entries, key = { it.id }) { source ->
                        Column(Modifier.fillMaxWidth().clickable { onSelect(source.id) }.padding(vertical = 8.dp)) {
                            Text(source.name, fontWeight = FontWeight.SemiBold)
                            Text("Source: ${source.source}", style = MaterialTheme.typography.bodySmall)
                            Text(source.id, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (groups.isEmpty()) item { Text("No matching writable calendars.") }
            }
        } },
        confirmButton = {})
}

@Composable
private fun TasksPage(model: HamViewModel) {
    Text("Tasks", style = MaterialTheme.typography.headlineMedium)
    Text("Open items from your Home Assistant to-do lists.")
    if (model.taskError.value.isNotBlank()) Text("Tasks could not load: ${model.taskError.value}", Modifier.padding(top = 12.dp))
    else if (model.tasks.value.isEmpty()) Text("No open tasks are available.", Modifier.padding(top = 12.dp))
    else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(model.tasks.value) { task ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
                Text(task.summary, fontWeight = FontWeight.SemiBold)
                Text(task.listName + (task.due?.let { " · Due $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
            } }
        }
    }
}

@Composable
private fun SearchPage(model: HamViewModel) {
    var query by remember { mutableStateOf("") }
    Text("Search calendars", style = MaterialTheme.typography.headlineMedium)
    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Find events") }, modifier = Modifier.fillMaxWidth())
    Text("Search loaded events.", style = MaterialTheme.typography.bodySmall)
    if (query.isBlank()) Text("Type a name or place to search.", Modifier.padding(top = 16.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    EventList(model.visibleEvents.filter { query.isNotBlank() && (it.summary.contains(query, true) || it.description.contains(query, true) || it.location.contains(query, true)) }, model)
}

@Composable
private fun SettingsPage(model: HamViewModel, onConnected: () -> Unit, onUpdateAvailable: (AvailableUpdate) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var updateMessage by remember { mutableStateOf("") }
    var checkingUpdate by remember { mutableStateOf(false) }
    var privacyOpen by remember { mutableStateOf(false) }
    var manageOpen by remember { mutableStateOf(false) }
    var showHttpWarning by remember { mutableStateOf(false) }
    BackHandler(privacyOpen) { privacyOpen = false }
    BackHandler(manageOpen) { manageOpen = false }
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val auth = model.authRequest.value
        val callback = result.data?.getStringExtra(HomeAssistantLoginActivity.EXTRA_CALLBACK_URL)
        if (result.resultCode == Activity.RESULT_OK && auth != null && callback != null) {
            try {
                val code = HomeAssistantAuth.callback(callback, auth)
                model.cancelAuth()
                model.connect(auth, code, onConnected)
            } catch (e: Exception) {
                error = e.message ?: "Sign-in failed"
                model.cancelAuth()
            }
        } else model.cancelAuth()
    }
    if (manageOpen) {
        ManageCalendarsPage(model) { manageOpen = false }
        return
    }
    if (privacyOpen) {
        PrivacyConnectionPage(model, onBack = { privacyOpen = false })
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Connection", style = MaterialTheme.typography.headlineMedium)
    if (model.connected.value) {
        Text("Connected to ${model.server.value}")
        Button(onClick = model::refresh) { Text("Refresh") }
        OutlinedButton(onClick = { privacyOpen = true }) { Text("Privacy & Connection") }
        Text("${model.calendars.value.size} calendars available", Modifier.padding(top = 12.dp))
        OutlinedButton(onClick = { manageOpen = true }) { Text("Manage Calendars") }
        if (model.legacyIdentity) {
            Text("Reconnect to authorize HAM. Your current connection stays active until sign-in succeeds.")
            Button(onClick = { model.beginAuth(model.server.value) }, enabled = !model.loading.value) { Text("Reconnect HAM identity") }
        }
    } else {
        OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Home Assistant address") },
            placeholder = { Text("https://your-home-assistant.example") }, modifier = Modifier.fillMaxWidth())
        Text("Use HTTPS away from home.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            if (address.trim().startsWith("http://", ignoreCase = true)) showHttpWarning = true
            else model.beginAuth(address)
        }, enabled = !model.loading.value) { Text("Connect HAM to Home Assistant") }
    }
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    model.authRequest.value?.let { auth ->
        AlertDialog(onDismissRequest = model::cancelAuth,
            title = { Text("Connect HAM to Home Assistant") }, text = { Column {
                Text("HAM will connect directly to your Home Assistant installation to display and manage the information you choose to access.")
                Text("Your Home Assistant credentials are entered into Home Assistant, not into a TinkerCobble website.", Modifier.padding(top = 8.dp))
                Text("TinkerCobble develops HAM but does not host your calendar data.", Modifier.padding(top = 8.dp))
            } }, confirmButton = { Button(onClick = {
                login.launch(Intent(context, HomeAssistantLoginActivity::class.java)
                    .putExtra(HomeAssistantLoginActivity.EXTRA_AUTHORIZE_URL, auth.authorizeUrl)
                    .putExtra(HomeAssistantLoginActivity.EXTRA_REDIRECT_SCHEME, auth.redirect.substringBefore(':')))
            }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = model::cancelAuth) { Text("Cancel") } })
    }
    if (showHttpWarning) AlertDialog(onDismissRequest = { showHttpWarning = false },
        title = { Text("Unencrypted local connection") },
        text = { Text("HTTP traffic is not protected by HTTPS. Continue only if you trust this local network.") },
        confirmButton = { Button(onClick = { showHttpWarning = false; model.beginAuth(address) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { showHttpWarning = false }) { Text("Cancel") } })
    if (!model.connected.value) TextButton(onClick = { privacyOpen = true }) { Text("Privacy & Connection") }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Updates", style = MaterialTheme.typography.titleMedium)
    OutlinedButton(onClick = {
        checkingUpdate = true
        updateMessage = ""
        scope.launch {
            val result = UpdateService.check(context, manual = true)
            updateMessage = result.message
            result.update?.let(onUpdateAvailable)
            checkingUpdate = false
        }
    }, enabled = !checkingUpdate) { Text(if (checkingUpdate) "Checking…" else "Check for updates") }
    if (updateMessage.isNotBlank()) Text(updateMessage)
    Text("${if (BuildConfig.FLAVOR == "dev") "HAM DEV" else "HAM"} · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ManageCalendarsPage(model: HamViewModel, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val prefs = model.calendarPreferences.value
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("‹ Settings") }
        Text("Manage Calendars", style = MaterialTheme.typography.headlineMedium)
        Text("Favorites, hidden calendars, and your Quick Add default are stored on this device.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(query, { query = it }, label = { Text("Search name, source, or entity ID") },
            modifier = Modifier.fillMaxWidth())
        val matches = CalendarSelection.search(model.calendars.value, query)
        LazyColumn {
            items(matches, key = { it.id }) { source ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(source.name, fontWeight = FontWeight.SemiBold)
                    Text("Source: ${source.source} · ${if (source.canCreate) "Writable" else "Read-only"}",
                        style = MaterialTheme.typography.bodySmall)
                    Text(source.id, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            model.setCalendarPreferences(prefs.copy(favorites =
                                if (source.id in prefs.favorites) prefs.favorites - source.id else prefs.favorites + source.id))
                        }) { Text(if (source.id in prefs.favorites) "★ Favorite" else "☆ Favorite") }
                        TextButton(onClick = {
                            model.setCalendarPreferences(prefs.copy(hidden =
                                if (source.id in prefs.hidden) prefs.hidden - source.id else prefs.hidden + source.id))
                        }) { Text(if (source.id in prefs.hidden) "Show" else "Hide") }
                    }
                    if (source.canCreate) TextButton(onClick = {
                        model.setCalendarPreferences(prefs.copy(defaultId =
                            if (prefs.defaultId == source.id) null else source.id))
                    }) { Text(if (prefs.defaultId == source.id) "✓ Quick Add default (clear)" else "Set Quick Add default") }
                }
                HorizontalDivider()
            }
            if (matches.isEmpty()) item { Text("No matching calendars.") }
        }
    }
}

@Composable
private fun PrivacyConnectionPage(model: HamViewModel, onBack: () -> Unit) {
    var pendingAction by remember { mutableStateOf<String?>(null) }
    val dateTime = DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault())
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("‹ Settings") }
        Text("Privacy & Connection", style = MaterialTheme.typography.headlineMedium)
        Text("Your Home Assistant. Your information.", style = MaterialTheme.typography.titleMedium)
        Text("HAM connects to your Home Assistant to show calendars and tasks. TinkerCobble does not host those records.")
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Connection", style = MaterialTheme.typography.titleMedium)
        Text(if (model.connected.value) "Connected" else "Disconnected")
        if (model.connected.value) {
            Text("Server: ${model.server.value}")
            Text(if (ConnectionPolicy.isLocal(model.server.value)) "Local address" else "Remote address")
            Text(if (model.server.value.startsWith("https://")) "HTTPS encrypted connection" else "Local HTTP connection · unencrypted")
            Text("Authorization: ${model.clientId.value}")
            Text("Home Assistant account: not available to HAM")
        }
        Text("Last successful connection: ${model.lastConnection.value?.let(dateTime::format) ?: "Not recorded"}")
        Text("Last data refresh: ${model.lastSync.value?.let(dateTime::format) ?: "Not recorded"}")
        if (model.connected.value) {
            Button(onClick = model::refreshConnectionStatus, enabled = !model.loading.value) { Text("Refresh connection status") }
            OutlinedButton(onClick = { onBack(); model.beginAuth(model.server.value) }, enabled = !model.loading.value) { Text("Reconnect Home Assistant") }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("On this device", style = MaterialTheme.typography.titleMedium)
        Text("HAM stores your server address and encrypted authorization tokens in private app storage. Android app backup is off.")
        Text("Loaded calendars, events, and tasks stay in memory while the app runs. HAM has no offline cache or unsent drafts yet.")
        if (model.connected.value || model.lastSync.value != null) {
            OutlinedButton(onClick = { pendingAction = "clear" }) { Text("Clear loaded information") }
            OutlinedButton(onClick = { pendingAction = "local" }) { Text("Disconnect on this device") }
            OutlinedButton(onClick = { pendingAction = "revoke" }) { Text("Disconnect & request revocation") }
        }
        Text("HAM has no TinkerCobble account, ads, or analytics. Home Assistant integrations and remote-access services may handle data under their own policies.")
        Text("Use HTTPS away from home. Private local HTTP is unencrypted.")
    }
    pendingAction?.let { action ->
        AlertDialog(onDismissRequest = { pendingAction = null },
            title = { Text(if (action == "clear") "Clear loaded information?" else "Disconnect Home Assistant?") },
            text = { Text(when (action) {
                "clear" -> "This clears calendars and tasks loaded on this device. Home Assistant records stay intact."
                "revoke" -> "HAM will request token revocation from Home Assistant, then remove this device's connection."
                else -> "This removes this device's connection. The Home Assistant authorization may remain active."
            }) },
            confirmButton = { Button(onClick = {
                when (action) {
                    "clear" -> { model.clearLoadedInformation(); model.message.value = "Loaded information cleared" }
                    "revoke" -> model.disconnect(true)
                    else -> model.disconnect(false)
                }
                pendingAction = null
            }) { Text(if (action == "clear") "Clear" else "Disconnect") } },
            dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("Cancel") } })
    }
}
