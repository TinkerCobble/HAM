package com.tinkercobble.ham

import android.os.Bundle
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import com.tinkercobble.ham.auth.HomeAssistantAuth
import com.tinkercobble.ham.auth.ConnectionPolicy
import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.RepeatFrequency
import com.tinkercobble.ham.data.RepeatSpec
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.CalendarReadDiagnostic
import com.tinkercobble.ham.data.CalendarReadDiagnosticStore
import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.CalendarSelection
import com.tinkercobble.ham.data.CalendarView
import com.tinkercobble.ham.data.CalendarDisplay
import com.tinkercobble.ham.data.EventGroups
import com.tinkercobble.ham.data.DailyOverview
import com.tinkercobble.ham.data.DavKind
import com.tinkercobble.ham.data.DavOperation
import com.tinkercobble.ham.data.DavPendingState
import com.tinkercobble.ham.data.DavConnection
import com.tinkercobble.ham.data.DateRange
import com.tinkercobble.ham.data.IcsSingleEventExport
import com.tinkercobble.ham.data.IcsDayExport
import com.tinkercobble.ham.data.IcsSingleEventImport
import com.tinkercobble.ham.data.IcsImportDraft
import com.tinkercobble.ham.data.EventCopyDraft
import com.tinkercobble.ham.data.FindTime
import com.tinkercobble.ham.data.SchedulingConflicts
import com.tinkercobble.ham.data.ConflictKind
import com.tinkercobble.ham.data.EventTemplate
import com.tinkercobble.ham.data.EventTemplateStore
import com.tinkercobble.ham.data.QuickAddDraft
import com.tinkercobble.ham.data.QuickAddDraftStore
import com.tinkercobble.ham.data.LocalTask
import com.tinkercobble.ham.data.LocalTaskStore
import com.tinkercobble.ham.data.AndroidCalendarProvider
import com.tinkercobble.ham.data.AndroidReminder
import com.tinkercobble.ham.data.AndroidReminders
import com.tinkercobble.ham.data.LocalReminderPlanner
import com.tinkercobble.ham.data.LocalReminderRecord
import com.tinkercobble.ham.data.LocalReminderStore
import com.tinkercobble.ham.data.LocalReminderWhen
import com.tinkercobble.ham.ui.HamViewModel
import com.tinkercobble.ham.ui.HamTheme
import com.tinkercobble.ham.ui.LocalBackupPage
import com.tinkercobble.ham.updates.AvailableUpdate
import com.tinkercobble.ham.updates.UpdateService
import com.tinkercobble.ham.updates.AutoUpdateScheduler
import com.tinkercobble.ham.updates.UpdateSessionGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var sharedScreenshot by mutableStateOf<Uri?>(null)
    private fun acceptShare(incoming: Intent?) {
        if (incoming?.action == Intent.ACTION_SEND && incoming.type?.startsWith("image/") == true)
            sharedScreenshot = (if (android.os.Build.VERSION.SDK_INT >= 33)
                incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") incoming.getParcelableExtra(Intent.EXTRA_STREAM))
                ?: incoming.clipData?.getItemAt(0)?.uri
                ?: (incoming.extras?.get(Intent.EXTRA_STREAM) as? String)?.let(Uri::parse)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { LocalReminderAlarms(this@MainActivity).reschedule() }
        }
        acceptShare(intent)
        setContent {
            val model: HamViewModel = viewModel()
            if (BuildConfig.DEBUG && intent.getBooleanExtra("ham_demo_fixture", false)) {
                remember(model) {
                    Class.forName("com.tinkercobble.ham.DemoFixture")
                        .getMethod("seed", HamViewModel::class.java).invoke(null, model)
                    true
                }
            }
            HamTheme { HamApp(model, sharedScreenshot) { sharedScreenshot = null } }
        }
    }
    override fun onStart() {
        super.onStart()
        AutoUpdateScheduler.foreground(this)
        AutoUpdateScheduler.schedule(this)
    }
    override fun onStop() {
        AutoUpdateScheduler.background(this)
        super.onStop()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptShare(intent)
    }
}

private val pages = listOf("Today", "Calendar", "Quick Add", "Find Time", "Tasks", "Search", "Settings", "About")
private val dayFormat = DateTimeFormatter.ofPattern("EEE, MMM d")
private data class EventCopyRequest(val id: java.util.UUID, val draft: IcsImportDraft,
    val sourceId: String, val sourceName: String, val moveSource: AgendaEvent? = null)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HamApp(model: HamViewModel = viewModel(), sharedScreenshot: Uri?, clearShare: () -> Unit) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(if (model.hasAgendaSource) "Today" else "Settings") }
    LaunchedEffect(model.message.value) {
        val notice = model.message.value
        if (notice == "Calendars updated" || notice == "Loaded information cleared") {
            delay(2_500)
            if (model.message.value == notice) model.message.value = ""
        }
    }
    var quickAddSlot by remember { mutableStateOf<Pair<String, String>?>(null) }
    var eventCopy by remember { mutableStateOf<EventCopyRequest?>(null) }
    var screenshotUri by remember { mutableStateOf<Uri?>(null) }
    val screenshotPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { screenshotUri = uri; page = "Screenshot" }
    }
    LaunchedEffect(sharedScreenshot) {
        if (sharedScreenshot != null) { screenshotUri = sharedScreenshot; page = "Screenshot"; clearShare() }
    }
    var returnPage by remember { mutableStateOf("Today") }
    var manageId by remember { mutableStateOf<String?>(null) }
    val calendarScroll = rememberLazyListState()
    fun navigate(destination: String) {
        when (destination) {
            "Today" -> model.enterToday()
            "Calendar" -> if (page != "Calendar" && page != "Calendars") model.enterCalendar()
        }
        page = destination
    }
    fun openCalendars(id: String? = null) { returnPage = page; manageId = id; page = "Calendars" }
    var availableUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }
    var showUpdatePrompt by remember { mutableStateOf(false) }
    var downloadingUpdate by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf(0) }
    var updateError by remember { mutableStateOf("") }
    var pendingInstall by remember { mutableStateOf<File?>(null) }
    var permissionNeeded by remember { mutableStateOf(false) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    val updateSession = remember { UpdateSessionGate() }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP)
                updateSession.stopped(android.os.SystemClock.elapsedRealtime())
            if (event == Lifecycle.Event.ON_RESUME) HamAgendaWidget.requestUpdate(activity)
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
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
        pendingInstall = null
        downloadJob = scope.launch {
            try {
                val file = UpdateService.downloadAndVerify(context, update) { updateProgress = it }
                pendingInstall = file
                downloadingUpdate = false
                if (UpdateService.canInstall(context)) openInstaller(file)
                else permissionNeeded = true
            } catch (_: CancellationException) {
                downloadingUpdate = false
                updateError = "Download canceled."
            } catch (error: Exception) {
                downloadingUpdate = false
                updateError = error.message ?: "The update could not be verified."
            } finally {
                downloadJob = null
            }
        }
    }
    LaunchedEffect(Unit) {
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.updateActualToday()
            model.refreshIfStaleOnResume()
            model.syncDavPendingIfStaleOnResume()
            model.syncHaPendingIfStaleOnResume()
            while (true) {
                val now = java.time.ZonedDateTime.now()
                val until = java.time.Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone))
                    .toMillis().coerceAtLeast(1000)
                delay(until)
                model.updateActualToday()
            }
        }
    }
    LaunchedEffect(model.androidEnabled.value, model.androidPermissionGranted.value) {
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!model.androidEnabled.value || !AndroidCalendarProvider(activity).canRead())
                return@repeatOnLifecycle
            val changes = Channel<Unit>(Channel.CONFLATED)
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { changes.trySend(Unit) }
            }
            try {
                activity.contentResolver.registerContentObserver(
                    CalendarContract.Events.CONTENT_URI, true, observer)
            } catch (_: SecurityException) {
                // Permission can be revoked between the check and registration.
                changes.close()
                return@repeatOnLifecycle
            }
            try {
                var lastObservedRefresh = 0L
                for (change in changes) {
                    delay(2_000)
                    while (changes.tryReceive().isSuccess) Unit
                    val elapsed = android.os.SystemClock.elapsedRealtime()
                    delay((lastObservedRefresh + 30_000L - elapsed).coerceAtLeast(0L))
                    if (model.androidEnabled.value && model.androidPermissionGranted.value) {
                        model.refresh()
                        lastObservedRefresh = android.os.SystemClock.elapsedRealtime()
                    }
                }
            } finally {
                activity.contentResolver.unregisterContentObserver(observer)
                changes.close()
            }
        }
    }
    LaunchedEffect(Unit) {
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (updateSession.beginNormalLaunch(android.os.SystemClock.elapsedRealtime(),
                    pendingInstall != null || downloadingUpdate) && UpdateService.shouldAutoCheck(context)) {
                delay(1_500)
                val result = UpdateService.check(context, manual = false)
                result.update?.let {
                    if (updateSession.shouldPrompt(it.versionCode, BuildConfig.VERSION_CODE, manual = false)) {
                        if (AutoUpdateScheduler.canTrySilently(context, it.versionCode)) {
                            AutoUpdateScheduler.schedule(context)
                        } else {
                            availableUpdate = it
                            showUpdatePrompt = true
                        }
                    }
                }
            }
        }
    }
    val destinations = listOf("Today", "Calendar", "Quick Add", "More")
    Scaffold(topBar = {
        TopAppBar(title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(48.dp).clickable(onClickLabel = "Go to today") { navigate("Today") },
                    contentAlignment = Alignment.Center) {
                    Image(painterResource(R.mipmap.ic_launcher_foreground), contentDescription = "Go to today", modifier = Modifier.size(38.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Home Agenda Manager", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(model.actualToday.value.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                val iconColor = MaterialTheme.colorScheme.onSurface
                Box(Modifier.size(48.dp).semantics { contentDescription = "Search events" }
                    .combinedClickable(onClick = { page = "Search" },
                        onLongClick = { android.widget.Toast.makeText(context, "Search events", android.widget.Toast.LENGTH_SHORT).show() }),
                    contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
                        drawCircle(iconColor, radius = size.minDimension * 0.28f,
                            center = androidx.compose.ui.geometry.Offset(size.width * 0.42f, size.height * 0.4f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                        drawLine(iconColor,
                            androidx.compose.ui.geometry.Offset(size.width * 0.62f, size.height * 0.62f),
                            androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.9f),
                            strokeWidth = 2.dp.toPx())
                    }
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
                                NavigationChoice(destination, page == destination, { navigate(destination) }, Modifier.fillMaxWidth())
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
                            if (!model.hasAgendaSource && page !in listOf("Settings", "Screenshot", "Quick Add", "More", "Tasks")) {
                                Text("Connect a calendar source to see your agenda.")
                                TextButton(onClick = { page = "Settings" }) { Text("Open Settings") }
                            } else when (page) {
                                "Today" -> TodayPage(model, ::openCalendars) { eventCopy = it; page = "Quick Add" }
                                "Calendar" -> CalendarPage(model, calendarScroll, ::openCalendars) { eventCopy = it; page = "Quick Add" }
                                "Quick Add" -> QuickAddPage(model, quickAddSlot, { quickAddSlot = null },
                                    eventCopy, { eventCopy = null }, onScreenshot = {
                                    screenshotPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }, onResume = { screenshotUri = null; page = "Screenshot" })
                                "Find Time" -> FindTimePage(model) { start, end ->
                                    quickAddSlot = start to end
                                    page = "Quick Add"
                                }
                                "Screenshot" -> ScreenshotPage(model, screenshotUri,
                                    onCancel = { screenshotUri = null; page = "Quick Add" },
                                    onSaved = { screenshotUri = null; model.activeAgenda.value = "Calendar"; page = "Calendar" })
                                "Tasks" -> TasksPage(model)
                                "Search" -> SearchPage(model, ::openCalendars) { eventCopy = it; page = "Quick Add" }
                                "Calendars" -> ManageCalendarsPage(model, { page = returnPage }, manageId)
                                "About" -> AboutPage()
                                "More" -> MorePage { page = it }
                                else -> SettingsPage(model, onConnected = { navigate("Today") }, onUpdateAvailable = {
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
                                    (destination == "More" && page in listOf("Find Time", "Tasks", "Search", "Settings", "About", "Calendars")),
                                    { navigate(destination) }, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
    availableUpdate?.let { update ->
        if (showUpdatePrompt) AlertDialog(onDismissRequest = {
            updateSession.dismiss(update.versionCode); UpdateService.later(context, update)
            showUpdatePrompt = false; availableUpdate = null
        }, title = { Text("HAM Update Available") },
            text = { Text("Installed ${BuildConfig.VERSION_NAME} · Available ${update.versionName}" +
                update.releaseNotes.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()) },
            confirmButton = { Button(onClick = { showUpdatePrompt = false; beginDownload(update) }) { Text("Update now") } },
            dismissButton = { TextButton(onClick = {
                updateSession.dismiss(update.versionCode); UpdateService.later(context, update)
                showUpdatePrompt = false; availableUpdate = null
            }) { Text("Later") } })
    }
    if (downloadingUpdate) AlertDialog(onDismissRequest = {}, title = { Text("Downloading HAM update") },
        text = { Text(if (updateProgress > 0) "Downloading… $updateProgress%" else "Downloading…") },
        confirmButton = { TextButton(onClick = { downloadJob?.cancel() }) { Text("Cancel") } })
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
    listOf("Find Time" to "See open time with a travel buffer", "Tasks" to "Local tasks and Home Assistant items", "Search" to "Find an event",
        "Settings" to "Connection, calendars, and updates", "About" to "Version and app information")
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
private fun FindTimePage(model: HamViewModel, onChoose: (String, String) -> Unit) {
    var opening by remember { mutableStateOf("09:00") }
    var closing by remember { mutableStateOf("17:00") }
    var duration by remember { mutableStateOf("60") }
    var buffer by remember { mutableStateOf("15") }
    var calendarPickerOpen by remember { mutableStateOf(false) }
    var calendarQuery by remember { mutableStateOf("") }
    val time = remember { DateTimeFormatter.ofPattern("HH:mm") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Find Time", style = MaterialTheme.typography.headlineMedium)
        DayPicker(model)
        Text("Choose which connected calendars count as busy. These choices are separate from what HAM shows in your agenda. All-day events block the day. Refresh before booking.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { calendarPickerOpen = true }) {
            Text("Calendars considered: ${model.availabilityIds.value.size}")
        }
        RefreshStatus(model)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(opening, { opening = it }, label = { Text("From HH:mm") },
                singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(closing, { closing = it }, label = { Text("Until HH:mm") },
                singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(duration, { duration = it }, label = { Text("Minutes needed") },
                singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(buffer, { buffer = it }, label = { Text("Travel buffer (min)") },
                singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedButton(onClick = model::refresh, enabled = !model.loading.value) {
            Text("Refresh calendars")
        }
        val day = model.selectedDate.value
        if (model.availabilityIds.value.isEmpty() || model.availabilityStorageError.value ||
            model.availabilityIds.value.any { id -> model.calendars.value.none { it.id == id } } ||
            model.loading.value || model.loadedRange.value?.contains(day) != true ||
            model.failedCalendars.value.isNotEmpty() || model.haPending.value.isNotEmpty() ||
            model.davPending.value.isNotEmpty()) {
            Text("Availability is incomplete, no busy calendars are chosen, or changes are pending. Review calendars considered and pending changes before using Find Time.",
                color = MaterialTheme.colorScheme.error)
        } else {
            val slots = runCatching { FindTime.suggest(day, model.availabilityEvents,
                LocalTime.parse(opening.trim()), LocalTime.parse(closing.trim()),
                duration.toInt(), buffer.toInt()) }
            slots.fold(onSuccess = { suggestions ->
                if (suggestions.isEmpty()) Text("No open time in this window. Try another day or shorter duration.")
                suggestions.forEach { slot ->
                    val start = slot.start.format(time)
                    val end = slot.end.format(time)
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("$start–$end", modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { onChoose(start, end) }) { Text("Use in Quick Add") }
                        }
                    }
                }
                val conflicts = runCatching { SchedulingConflicts.detect(day, model.availabilityEvents,
                    buffer.toInt()) }
                conflicts.onSuccess { matches ->
                    Text("Potential conflicts today: ${matches.size}",
                        style = MaterialTheme.typography.titleMedium)
                    matches.take(10).forEach { match ->
                        val label = if (match.kind == ConflictKind.OVERLAP)
                            "Time overlap" else "Travel buffer"
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium)
                            Text("${match.first.first().summary} · ${match.second.first().summary}",
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        } }
                    }
                    if (matches.size > 10) Text("${matches.size - 10} more potential conflicts on this day.")
                }.onFailure { Text("Could not evaluate conflicts for this day.",
                    color = MaterialTheme.colorScheme.error) }
            }, onFailure = {
                Text("Enter a valid same-day window, duration in 15-minute steps, and a buffer of 0–180 minutes.",
                    color = MaterialTheme.colorScheme.error)
            })
        }
    }
    if (calendarPickerOpen) AlertDialog(onDismissRequest = { calendarPickerOpen = false },
        title = { Text("Calendars considered for Find Time") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("An unchecked calendar will not block suggestions, even if its events appear in your agenda. Checked calendars may count as busy while hidden from the agenda.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(calendarQuery, { calendarQuery = it },
                label = { Text("Search calendars") }, singleLine = true)
            CalendarSelection.search(model.calendars.value, calendarQuery).forEach { source ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = source.id in model.availabilityIds.value,
                        onCheckedChange = { model.setAvailabilityIncluded(source, it) })
                    Column {
                        Text(source.name, style = MaterialTheme.typography.bodyMedium)
                        Text(source.provider.name.replace('_', ' '),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (model.calendars.value.isEmpty()) Text("Connect a calendar source in Settings first.")
            if (model.availabilityStorageError.value) Text("Could not save Find Time calendar choices.",
                color = MaterialTheme.colorScheme.error)
        } }, confirmButton = { TextButton(onClick = { calendarPickerOpen = false }) {
            Text("Done")
        } })
}

@Composable
private fun AboutPage() {
    Text("About", style = MaterialTheme.typography.headlineMedium)
    Text("HAM · ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
    Text("Home Agenda Manager connects to your Home Assistant.")
}

@Composable
private fun DayPicker(model: HamViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { model.move(-1) }, modifier = Modifier.size(48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("‹") }
        Text(model.selectedDate.value.format(dayFormat), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        OutlinedButton(onClick = { model.move(1) }, modifier = Modifier.size(48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("›") }
    }
    if (model.selectedDate.value != model.actualToday.value)
        TextButton(onClick = { model.today() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Go to today") }
}

@Composable
private fun TodayPage(model: HamViewModel, openCalendars: (String?) -> Unit,
    onCopy: (EventCopyRequest) -> Unit) {
    val context = LocalContext.current
    var confirmDayExport by remember { mutableStateOf(false) }
    var dayExportError by remember { mutableStateOf("") }
    var dayExportBytes by remember { mutableStateOf<ByteArray?>(null) }
    val dayExportDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        val bytes = dayExportBytes
        dayExportBytes = null
        if (uri != null && bytes != null) {
            try {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: error("Could not open the chosen document")
                android.widget.Toast.makeText(context, "Day exported", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) { dayExportError = "Could not save the day. Choose another location." }
        }
    }
    val dayEvents = IcsDayExport.events(model.visibleEvents, model.selectedDate.value)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(CalendarDisplay.titleForDay(model.selectedDate.value, model.actualToday.value),
            style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = { openCalendars(null) }) { Text("Calendars") }
    }
    DayPicker(model)
    model.activeSavedGroup?.let { Text("Showing group: ${it.name}", style = MaterialTheme.typography.bodySmall) }
    RefreshStatus(model)
    if (dayEvents.isNotEmpty()) TextButton(onClick = { confirmDayExport = true }) {
        Text("Export displayed day as ICS (${dayEvents.size})")
    }
    if (dayExportError.isNotBlank()) Text(dayExportError, color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall)
    val overview = DailyOverview.forDay(model.visibleEvents, model.selectedDate.value)
    val countText = if (overview.total == 0) "No selected events" else
        "${overview.total} ${if (overview.total == 1) "event" else "events"} · " +
            "${overview.allDay} all-day · ${overview.timed} timed"
    Surface(color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Daily overview", style = MaterialTheme.typography.titleSmall)
            Text(countText, style = MaterialTheme.typography.bodyMedium)
            overview.nextTimed?.let { next ->
                Text("Next: ${CalendarDisplay.time(next.start)} · ${next.summary}",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
    AgendaContent(model, listOf(model.selectedDate.value), openCalendars, Modifier.fillMaxSize(), false, onCopy)
    if (confirmDayExport) AlertDialog(onDismissRequest = { confirmDayExport = false },
        title = { Text("Export displayed day") },
        text = { Text("Save ${dayEvents.size} visible ${if (dayEvents.size == 1) "event" else "events"} for ${model.selectedDate.value} as a standalone ICS file. Each selected calendar record is included, so overlapping sources may create duplicate-looking entries. Repeated events export only the displayed occurrence. Titles, times, locations and descriptions may be readable by the chosen storage provider. Refresh first if you need the latest server changes. HAM does not change the original calendars.") },
        confirmButton = { Button(onClick = {
            confirmDayExport = false
            try {
                dayExportBytes = IcsSingleEventExport.renderMany(dayEvents,
                    dayEvents.map { java.util.UUID.randomUUID() }, java.time.Instant.now())
                dayExportDocument.launch("ham-agenda-${model.selectedDate.value}.ics")
            } catch (_: Exception) { dayExportError = "This day could not be exported (limit: 50 events and 2 MiB)." }
        }) { Text("Choose save location") } },
        dismissButton = { TextButton(onClick = { confirmDayExport = false }) { Text("Cancel") } })
}

@Composable
private fun CalendarPage(model: HamViewModel, scroll: androidx.compose.foundation.lazy.LazyListState,
    openCalendars: (String?) -> Unit, onCopy: (EventCopyRequest) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Calendar", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f), maxLines = 1)
        ModeButton("▦", "Month view", model.calendarView.value == CalendarView.MONTH) { model.setView(CalendarView.MONTH) }
        ModeButton("▤", "Next 7 days", model.calendarView.value == CalendarView.WEEK) { model.setView(CalendarView.WEEK) }
        ModeButton("Ⅲ", "3 days", model.calendarView.value == CalendarView.THREE_DAYS) { model.setView(CalendarView.THREE_DAYS) }
    }
    val range = model.calendarRange()
    LaunchedEffect(range.start, model.calendarView.value) { scroll.scrollToItem(0) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { model.moveCalendar(-1) }, modifier = Modifier.size(48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("‹") }
        Text(if (model.calendarView.value == CalendarView.MONTH) range.start.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
            else "${model.calendarView.value.label}: ${range.start.format(dayFormat)} – ${range.endExclusive.minusDays(1).format(dayFormat)}",
            style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { model.moveCalendar(1) }, modifier = Modifier.size(48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("›") }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { openCalendars(null) }) { Text("Calendars") }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { model.enterCalendar() }) { Text("Today") }
    }
    model.activeSavedGroup?.let { Text("Showing group: ${it.name}", style = MaterialTheme.typography.bodySmall) }
    RefreshStatus(model)
    AgendaContent(model, range.days, openCalendars, Modifier.fillMaxSize(), model.calendarView.value == CalendarView.MONTH,
        onCopy, scroll)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModeButton(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Surface(modifier = Modifier.size(48.dp).semantics { contentDescription = label }
        .combinedClickable(onClick = onClick,
            onLongClick = { android.widget.Toast.makeText(context, label, android.widget.Toast.LENGTH_SHORT).show() }),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
        Box(contentAlignment = Alignment.Center) { Text(icon, style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
private fun RefreshStatus(model: HamViewModel) {
    if (model.failedCalendars.value.isNotEmpty()) {
        Text("Could not refresh: ${model.failedCalendars.value.keys.joinToString { id -> model.calendars.value.find { it.id == id }?.name ?: id }}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = model::refresh) { Text("Retry") }
    } else if (model.lastSync.value != null) {
        val formatter = DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault())
        Text("Updated ${formatter.format(model.lastSync.value)}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgendaContent(model: HamViewModel, days: List<LocalDate>, openCalendars: (String?) -> Unit,
    modifier: Modifier, month: Boolean, onCopy: (EventCopyRequest) -> Unit,
    scroll: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState()) {
    var selected by remember { mutableStateOf<AgendaEvent?>(null) }
    var selectedGroup by remember { mutableStateOf<List<AgendaEvent>?>(null) }
    var horizontalDrag by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val threshold = with(density) { 64.dp.toPx() }
    val slide = remember { Animatable(0f) }
    val context = LocalContext.current
    val displayKey = "${model.activeAgenda.value}-${model.calendarView.value}-${days.firstOrNull()}"
    LaunchedEffect(displayKey) {
        val scale = android.provider.Settings.Global.getFloat(context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val direction = model.navigationDirection.intValue
        if (direction != 0 && scale > 0f) {
            slide.snapTo(with(density) { 28.dp.toPx() } * direction)
            slide.animateTo(0f, tween((160 * scale).toInt().coerceAtLeast(1)))
        } else slide.snapTo(0f)
    }
    val body = modifier.pointerInput(month, model.calendarView.value, model.activeAgenda.value) {
        detectHorizontalDragGestures(onHorizontalDrag = { _, delta -> horizontalDrag += delta },
            onDragEnd = {
                if (kotlin.math.abs(horizontalDrag) >= threshold) {
                    if (model.activeAgenda.value == "Calendar") model.moveCalendar(if (horizontalDrag < 0) 1 else -1)
                    else model.move(if (horizontalDrag < 0) 1 else -1)
                }
                horizontalDrag = 0f
            }, onDragCancel = { horizontalDrag = 0f })
    }
    PullToRefreshBox(isRefreshing = model.loading.value, onRefresh = model::refresh, modifier = body) {
        LazyColumn(state = scroll, verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().graphicsLayer { translationX = slide.value }) {
            if (month) item(key = "month-grid") { MonthGrid(model) }
            val displayed = if (month) listOf(model.monthSelectedDate.value) else days
            displayed.forEach { day ->
                item(key = "heading-$day") {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha =
                        if (day.dayOfYear % 2 == 0) 0.58f else 0.32f),
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Text(if (month) "${CalendarDisplay.titleForDay(day, model.actualToday.value)} · ${day.format(dayFormat)}"
                            else day.format(DateTimeFormatter.ofPattern("EEEE, MMM d")),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(12.dp))
                    }
                }
                val events = CalendarDisplay.onDay(model.visibleEvents, day)
                if (events.isEmpty()) item(key = "empty-$day") {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Text("No events", Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                EventGroups.display(events).forEach { group ->
                    val event = group.first()
                    item(key = "${day}-${event.identity}") {
                        EventCard(event, model, group.size) {
                            if (group.size == 1) selected = event else selectedGroup = group
                        }
                    }
                }
            }
        }
    }
    selected?.let { event -> EventDetails(event, model.calendars.value.find { it.id == event.calendarId }, model,
        close = { selected = null }, manage = { id -> selected = null; openCalendars(id) },
        copy = { selected = null; onCopy(it) }) }
    selectedGroup?.let { group -> MirrorSourcePicker(group, { selectedGroup = null }) { event ->
        selectedGroup = null; selected = event
    } }
}

@Composable
private fun MonthGrid(model: HamViewModel) {
    val range = model.calendarRange()
    val first = range.start
    val offset = first.dayOfWeek.value - 1
    val cellCount = ((offset + range.days.size + 6) / 7) * 7
    val cells = (0 until cellCount).map { first.plusDays((it - offset).toLong()) }
    val days = listOf("M", "T", "W", "T", "F", "S", "S")
    Column {
        Row(Modifier.fillMaxWidth()) { days.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall) } }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) { week.forEach { day ->
                val inMonth = range.contains(day)
                val selected = day == model.monthSelectedDate.value
                val today = day == model.actualToday.value
                val events = if (inMonth) EventGroups.display(CalendarDisplay.onDay(model.visibleEvents, day)) else emptyList()
                Surface(onClick = { if (inMonth) model.selectMonthDay(day) }, enabled = inMonth,
                    modifier = Modifier.weight(1f).heightIn(min = 58.dp).padding(1.dp)
                        .semantics { contentDescription = "${day.format(dayFormat)}, ${events.size} events" },
                    shape = MaterialTheme.shapes.small,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (inMonth) 0.42f else 0.15f)) {
                    Column(Modifier.padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(day.dayOfMonth.toString(), fontWeight = if (today) FontWeight.ExtraBold else FontWeight.Normal,
                            color = if (today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                            events.take(3).forEach { group ->
                                Surface(color = eventColor(group.first(), model), shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.size(5.dp)) {}
                            }
                        }
                        if (events.size > 3) Text("+${events.size - 3}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            } }
        }
    }
}

@Composable
private fun eventColor(event: AgendaEvent, model: HamViewModel): Color {
    val source = model.calendars.value.find { it.id == event.calendarId }
    val color = model.calendarPreferences.value.colors[event.calendarId] ?: source?.haColor
    return color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
        ?: MaterialTheme.colorScheme.primary
}

@Composable
private fun EventCard(event: AgendaEvent, model: HamViewModel, groupSize: Int = 1, open: () -> Unit) {
    val stripe = eventColor(event, model)
    Card(Modifier.fillMaxWidth().clickable(onClick = open)) {
        Row(Modifier.fillMaxWidth()) {
            Surface(color = stripe, modifier = Modifier.size(width = 5.dp, height = 76.dp)) {}
            Column(Modifier.padding(12.dp)) {
                Text(event.summary, fontWeight = FontWeight.SemiBold)
                Text(event.calendarName, style = MaterialTheme.typography.bodySmall)
                if (groupSize > 1) Text("Also on another calendar · choose a source to open",
                    style = MaterialTheme.typography.labelSmall)
                when (model.pendingStateFor(event)) {
                    DavPendingState.QUEUED -> Text("Pending synchronization on this device",
                        style = MaterialTheme.typography.labelSmall)
                    DavPendingState.CONFLICT -> Text("Local draft has a server conflict",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    null -> Unit
                }
                if (event.calendarId in model.calendarPreferences.value.hidden)
                    Text("Hidden calendar", style = MaterialTheme.typography.labelSmall)
                Text(if (event.allDay) "All day" else "${CalendarDisplay.time(event.start)} – ${CalendarDisplay.time(event.end)}",
                    style = MaterialTheme.typography.bodySmall)
                if (event.location.isNotBlank() && event.location != "null") Text(event.location,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EventList(events: List<AgendaEvent>, model: HamViewModel, openCalendars: (String?) -> Unit,
    onCopy: (EventCopyRequest) -> Unit) {
    var selected by remember { mutableStateOf<AgendaEvent?>(null) }
    var selectedGroup by remember { mutableStateOf<List<AgendaEvent>?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(EventGroups.display(events), key = { it.first().identity }) { group ->
            EventCard(group.first(), model, group.size) {
                if (group.size == 1) selected = group.first() else selectedGroup = group
            }
        }
    }
    selected?.let { event -> EventDetails(event, model.calendars.value.find { it.id == event.calendarId }, model,
        close = { selected = null }, manage = { id -> selected = null; openCalendars(id) },
        copy = { selected = null; onCopy(it) }) }
    selectedGroup?.let { group -> MirrorSourcePicker(group, { selectedGroup = null }) { event ->
        selectedGroup = null; selected = event
    } }
}

@Composable
private fun MirrorSourcePicker(group: List<AgendaEvent>, close: () -> Unit, choose: (AgendaEvent) -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text("Choose calendar copy") },
        text = { Column {
            group.forEach { event ->
                TextButton(onClick = { choose(event) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${event.calendarName} · ${when (event.provider) {
                        CalendarProviderKind.ANDROID -> "Android"
                        CalendarProviderKind.CALDAV -> "CalDAV"
                        CalendarProviderKind.ICS -> "ICS subscription"
                        CalendarProviderKind.HOME_ASSISTANT -> "Home Assistant"
                    }}")
                }
            }
        } }, confirmButton = { TextButton(onClick = close) { Text("Close") } })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EventLocationLink(location: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(
                onClickLabel = "Open in Maps",
                onLongClickLabel = "Copy location",
                role = Role.Button,
                onClick = {
                    if (!EventLocationIntents.open(context, location)) {
                        android.widget.Toast.makeText(
                            context,
                            "No mapping app is available.",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                onLongClick = {
                    clipboard.setText(AnnotatedString(location))
                    android.widget.Toast.makeText(
                        context,
                        "Location copied.",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
            )
            .semantics { contentDescription = "$location. Opens in Maps. Long press to copy." }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_location_pin),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            location,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EventDetails(event: AgendaEvent, source: CalendarSource?, model: HamViewModel,
    close: () -> Unit, manage: (String) -> Unit, copy: (EventCopyRequest) -> Unit) {
    val context = LocalContext.current
    var confirmExport by remember(event.identity) { mutableStateOf(false) }
    var exportError by remember(event.identity) { mutableStateOf("") }
    var copyError by remember(event.identity) { mutableStateOf("") }
    var remindersOpen by remember(event.identity) { mutableStateOf(false) }
    var localRemindersOpen by remember(event.identity) { mutableStateOf(false) }
    var exportBytes by remember(event.identity) { mutableStateOf<ByteArray?>(null) }
    val exportDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        val bytes = exportBytes
        exportBytes = null
        if (uri != null && bytes != null) {
            try {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: error("Could not open the chosen document")
                android.widget.Toast.makeText(context, "Event exported", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) { exportError = "Could not save the event. Choose another location." }
        }
    }
    var title by remember(event.identity) { mutableStateOf(event.summary) }
    val simpleSeries = source != null && source.provider == event.provider &&
        (event.provider == CalendarProviderKind.ANDROID || event.provider == CalendarProviderKind.CALDAV) &&
        event.uid != null &&
        event.recurrenceId != null &&
        RepeatSpec.supported(event.rrule) != null && !model.hasPendingDavResource(event)
    val editableSeries = simpleSeries && source?.canUpdate == true
    val removableSeries = simpleSeries && source?.canDelete == true
    val editable = source?.canUpdate == true && event.uid != null && event.recurrenceId == null &&
        !model.hasPendingDavResource(event) &&
        (event.provider != CalendarProviderKind.HOME_ASSISTANT ||
            event.recurrenceKnown && event.rrule == null)
    val removable = source != null && event.uid != null && event.recurrenceId == null &&
        !model.hasPendingDavResource(event) &&
        (event.provider == CalendarProviderKind.ANDROID && editable ||
            event.provider == CalendarProviderKind.CALDAV && source.canDelete ||
            event.provider == CalendarProviderKind.HOME_ASSISTANT && source.canDelete &&
                event.recurrenceKnown && event.rrule == null)
    var confirmDelete by remember(event.identity) { mutableStateOf(false) }
    var editDetails by remember(event.identity) { mutableStateOf(false) }
    var detailDescription by remember(event.identity) { mutableStateOf(event.description) }
    var detailLocation by remember(event.identity) { mutableStateOf(event.location) }
    var confirmTimeChange by remember(event.identity) { mutableStateOf(false) }
    var confirmDateChange by remember(event.identity) { mutableStateOf(false) }
    val canEditAllDayDate = editable &&
        (event.provider == CalendarProviderKind.ANDROID || event.provider == CalendarProviderKind.CALDAV ||
            event.provider == CalendarProviderKind.HOME_ASSISTANT) &&
        event.allDay && runCatching { LocalDate.parse(event.end).isAfter(event.date) }
            .getOrDefault(false)
    var newDateText by remember(event.identity) { mutableStateOf(event.date.toString()) }
    val newDate = runCatching { LocalDate.parse(newDateText) }.getOrNull()
    val startLocal = remember(event.identity) { AgendaEvent.instant(event.start)
        ?.atZone(java.time.ZoneId.systemDefault()) }
    val endLocal = remember(event.identity) { AgendaEvent.instant(event.end)
        ?.atZone(java.time.ZoneId.systemDefault()) }
    val canEditTime = (editable || (editableSeries && (event.provider == CalendarProviderKind.ANDROID ||
        event.provider == CalendarProviderKind.CALDAV))) &&
        (event.provider == CalendarProviderKind.ANDROID ||
        event.provider == CalendarProviderKind.CALDAV ||
        event.provider == CalendarProviderKind.HOME_ASSISTANT) && !event.allDay &&
        startLocal != null && endLocal != null && startLocal.toLocalDate() == endLocal.toLocalDate()
    val canEditTimedDate = canEditTime && editable &&
        (event.provider == CalendarProviderKind.ANDROID || event.provider == CalendarProviderKind.CALDAV)
    val dateChanged = (canEditAllDayDate || canEditTimedDate) && newDateText != event.date.toString()
    val dateValid = !dateChanged || newDate != null
    val timeFormat = remember { java.time.format.DateTimeFormatter.ofPattern("HH:mm") }
    val originalStartText = startLocal?.format(timeFormat).orEmpty()
    val originalEndText = endLocal?.format(timeFormat).orEmpty()
    var startText by remember(event.identity) { mutableStateOf(originalStartText) }
    var endText by remember(event.identity) { mutableStateOf(originalEndText) }
    val timeChanged = canEditTime && (startText != originalStartText || endText != originalEndText)
    val timedValid = !(timeChanged || (canEditTimedDate && dateChanged)) || runCatching {
        com.tinkercobble.ham.data.TimedEventTime.parse(newDate ?: event.date, startText, endText)
    }.isSuccess
    val attachmentId = remember(event.identity) {
        if (event.provider == CalendarProviderKind.HOME_ASSISTANT)
            Regex("ham-attachment://([0-9a-f-]{36})").find(event.description)?.groupValues?.get(1)
        else null
    }
    var attachmentImage by remember(event.identity) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var attachmentError by remember(event.identity) { mutableStateOf("") }
    LaunchedEffect(attachmentId) {
        if (attachmentId != null) {
            try {
                val bytes = com.tinkercobble.ham.data.HomeAssistantClient(context).screenshot(attachmentId)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
                attachmentImage = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: error("Could not display screenshot")
            } catch (e: Exception) { attachmentError = e.message ?: "Screenshot unavailable" }
        }
    }
    AlertDialog(onDismissRequest = close, title = { Text("Event details") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (editable || editableSeries) OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") })
            else Text(event.summary, style = MaterialTheme.typography.titleMedium)
            if (editableSeries) Text("Changes here apply to the entire series, not just this date.",
                style = MaterialTheme.typography.bodySmall)
            when (model.pendingStateFor(event)) {
                DavPendingState.QUEUED -> Text("This event is saved on this device and awaits calendar synchronization.",
                    style = MaterialTheme.typography.bodySmall)
                DavPendingState.CONFLICT -> Text("This local draft has a server conflict. The server copy was not overwritten. Review pending changes in Settings.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                null -> Unit
            }
            when (model.pendingDeleteStateFor(event)) {
                DavPendingState.QUEUED -> Text("Removal is saved on this device and awaits calendar synchronization.",
                    style = MaterialTheme.typography.bodySmall)
                DavPendingState.CONFLICT -> Text("Removal has a server conflict. The server event was not removed. Review pending changes in Settings.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                null -> Unit
            }
            Text("Calendar: ${event.calendarName}")
            Text("Integration: ${source?.source ?: "Not provided by Home Assistant"}", style = MaterialTheme.typography.bodySmall)
            SelectionContainer { Text("Entity ID: ${event.calendarId}", style = MaterialTheme.typography.bodySmall) }
            Text(if (event.allDay) "${event.date.format(dayFormat)} · All day"
                else "${CalendarDisplay.dateTime(event.start)} – ${CalendarDisplay.dateTime(event.end)}")
            if (canEditAllDayDate || canEditTimedDate) {
                OutlinedTextField(newDateText, { newDateText = it },
                    label = { Text("Move to date (YYYY-MM-DD)") }, singleLine = true)
                if (!dateValid) Text("Enter a valid date as YYYY-MM-DD.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (canEditTime) {
                Text(if (editableSeries) "Change time for every occurrence (24-hour HH:mm)" else
                    "Change same-day time (24-hour HH:mm)", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(startText, { startText = it }, label = { Text("Start time") },
                        singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(endText, { endText = it }, label = { Text("End time") },
                        singleLine = true, modifier = Modifier.weight(1f))
                }
                if (!timedValid) Text("Choose valid times with the end later on the same day.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            EventLocationIntents.visibleLocation(event.location)?.let { EventLocationLink(it) }
            if (event.description.isNotBlank() && !event.description.equals("null", ignoreCase = true)) {
                Text("Description and provenance notes", style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(event.description) }
            }
            if (editable && (event.provider == CalendarProviderKind.ANDROID ||
                event.provider == CalendarProviderKind.HOME_ASSISTANT && attachmentId == null ||
                event.provider == CalendarProviderKind.CALDAV))
                TextButton(onClick = { editDetails = true }) {
                    Text("Edit location and description")
                }
            if (attachmentId != null) {
                Text("Original screenshot", style = MaterialTheme.typography.labelMedium)
                attachmentImage?.let { Image(it.asImageBitmap(), "Original event screenshot",
                    Modifier.fillMaxWidth().heightIn(max = 280.dp)) }
                if (attachmentError.isNotBlank()) Text(attachmentError, color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = { manage(event.calendarId) }) { Text("Manage this calendar") }
            TextButton(onClick = { confirmExport = true }) { Text("Export this event as ICS") }
            if (event.provider == CalendarProviderKind.ANDROID && !event.allDay &&
                editable && source?.canCreate == true) {
                TextButton(onClick = { remindersOpen = true }) { Text("Android calendar reminders") }
            }
            if (source != null && (source.provider == CalendarProviderKind.ICS || !source.canUpdate)) {
                TextButton(onClick = { localRemindersOpen = true }) { Text("HAM local reminders") }
            }
            TextButton(onClick = {
                runCatching {
                    EventCopyRequest(java.util.UUID.randomUUID(), EventCopyDraft.from(event),
                        event.calendarId, event.calendarName)
                }.onSuccess { copy(it) }
                    .onFailure { copyError = "This event cannot be copied safely with the current calendar format." }
            }) { Text("Copy to another calendar") }
            if (event.provider == CalendarProviderKind.ANDROID && removable && event.rrule == null)
                TextButton(onClick = {
                    runCatching {
                        EventCopyRequest(java.util.UUID.randomUUID(), EventCopyDraft.from(event),
                            event.calendarId, event.calendarName, event)
                    }.onSuccess { copy(it) }
                        .onFailure { copyError = "This event cannot be moved safely with the current calendar format." }
                }) { Text("Move to another Android calendar") }
            if (exportError.isNotBlank()) Text(exportError, color = MaterialTheme.colorScheme.error)
            if (copyError.isNotBlank()) Text(copyError, color = MaterialTheme.colorScheme.error)
            if (removable || removableSeries)
                TextButton(onClick = { confirmDelete = true }) {
                    Text(if (removableSeries) "Remove entire series" else "Remove event")
                }
            if (!editable && !editableSeries && !removable && !removableSeries)
                Text("This event can't be edited here.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        if ((editable || editableSeries) && source != null &&
            (title != event.summary || timeChanged || dateChanged))
            Button(onClick = { if (timeChanged || (canEditTimedDate && dateChanged)) confirmTimeChange = true else if (dateChanged)
                confirmDateChange = true else if (editableSeries) {
                when (event.provider) {
                    CalendarProviderKind.ANDROID -> model.editAndroidSeriesTitle(source, event, title, close)
                    CalendarProviderKind.CALDAV -> model.editDavSeriesTitle(source, event, title, close)
                    CalendarProviderKind.HOME_ASSISTANT -> Unit
                    CalendarProviderKind.ICS -> Unit
                }
            } else model.edit(source, event, title, close) },
                enabled = timedValid && dateValid && title.isNotBlank() && !model.loading.value) {
                Text(if (editableSeries && timeChanged) "Save series time" else if (editableSeries)
                    "Save series title" else "Save change")
            }
        else TextButton(onClick = close) { Text("Close") }
    }, dismissButton = { if (editable || editableSeries) TextButton(onClick = close) { Text("Cancel") } })
    if (confirmExport) AlertDialog(
        onDismissRequest = { confirmExport = false },
        title = { Text("Export event") },
        text = { Text("Save a standalone copy of this displayed event using Android's document picker. Its title, time, location and description may be readable by the chosen storage provider. The original calendar is unchanged. Repeated events export only this occurrence; HAM screenshot attachments are excluded.") },
        confirmButton = { Button(onClick = {
            confirmExport = false
            try {
                exportBytes = IcsSingleEventExport.render(event, java.util.UUID.randomUUID(),
                    java.time.Instant.now())
                exportDocument.launch("ham-event.ics")
            } catch (_: Exception) { exportError = "This event could not be exported." }
        }) { Text("Choose save location") } },
        dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("Cancel") } },
    )
    if (editDetails && source != null && editable &&
        (event.provider == CalendarProviderKind.ANDROID ||
            event.provider == CalendarProviderKind.HOME_ASSISTANT && attachmentId == null ||
            event.provider == CalendarProviderKind.CALDAV)) {
        val detailValid = runCatching { com.tinkercobble.ham.data.EventDraftDetails.of(
            detailDescription, detailLocation) }.isSuccess
        AlertDialog(onDismissRequest = { editDetails = false },
            title = { Text("Edit event details") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${event.summary} · ${source.name}")
                OutlinedTextField(detailLocation,
                    { if (it.length <= 8_192) detailLocation = it },
                    label = { Text("Location") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(detailDescription,
                    { if (it.length <= 65_536) detailDescription = it },
                    label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                Text(if (source.provider == CalendarProviderKind.ANDROID)
                    "Save to this Android calendar. Its account may sync the change to other devices."
                else "HAM saves this change encrypted on this device and sends it directly to the selected calendar server. Review pending changes in Settings if it cannot be confirmed.",
                    style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { Button(onClick = {
                model.editEventDetails(source, event, detailDescription, detailLocation) {
                    editDetails = false
                    close()
                }
            }, enabled = detailValid &&
                (detailDescription != event.description || detailLocation != event.location) &&
                !model.loading.value) { Text("Save details") } },
            dismissButton = { TextButton(onClick = { editDetails = false }) { Text("Cancel") } })
    }
    if (confirmDelete && source != null) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(if (removableSeries) "Remove entire series from calendar?" else "Remove event from calendar?") },
        text = { Text(if (removableSeries)
            "This removes all occurrences of this series from ${source.name}. The provider may sync that removal to other views. HAM targets only this selected calendar resource."
            else "This removes the event from ${source.name} and may sync the removal to its account. Connected calendars may show the same underlying event elsewhere.") },
        confirmButton = { Button(onClick = {
            confirmDelete = false
            when (event.provider) {
                CalendarProviderKind.ANDROID -> if (removableSeries)
                    model.deleteAndroidSeries(source, event, close) else model.deleteAndroidEvent(source, event, close)
                CalendarProviderKind.HOME_ASSISTANT -> model.deleteHomeAssistantEvent(source, event, close)
                CalendarProviderKind.CALDAV -> if (removableSeries)
                    model.deleteDavSeries(source, event, close) else model.deleteDavEvent(source, event, close)
                CalendarProviderKind.ICS -> Unit
            }
        }, enabled = !model.loading.value) { Text(if (removableSeries) "Remove series" else "Remove event") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    if (confirmTimeChange && source != null) AlertDialog(onDismissRequest = { confirmTimeChange = false },
        title = { Text(if (editableSeries) "Change entire series time?" else if (canEditTimedDate && dateChanged)
            "Move timed event?" else "Change event time?") },
        text = { Text("${event.summary}" + (if (title.trim() != event.summary) " → ${title.trim()}" else "") +
            " on ${event.date}: $originalStartText–$originalEndText → ${if (canEditTimedDate) newDate else event.date} $startText–$endText. Calendar: ${source.name}. " +
            (if (editableSeries) "This changes every occurrence. " else "") +
            (if (editableSeries && event.provider == CalendarProviderKind.CALDAV)
                "HAM checks the whole series. Named-zone clock edits require the device to use that calendar zone. "
            else "") +
            "Connected calendars may show the same underlying event.") },
        confirmButton = { Button(onClick = {
            confirmTimeChange = false
            when (event.provider) {
                CalendarProviderKind.ANDROID -> if (editableSeries)
                    model.editTimedAndroidSeries(source, event, title, startText, endText, close) else
                    model.editTimedAndroid(source, event, title, startText, endText,
                        close, newDate ?: event.date)
                CalendarProviderKind.HOME_ASSISTANT -> model.editTimedHomeAssistant(source, event, title, startText, endText, close)
                CalendarProviderKind.CALDAV -> if (editableSeries)
                    model.editTimedDavSeries(source, event, title, startText, endText, close) else
                    model.editTimedDav(source, event, title, startText, endText, close,
                        newDate ?: event.date)
                CalendarProviderKind.ICS -> Unit
            }
        }, enabled = timedValid && dateValid && !model.loading.value) {
            Text(if (editableSeries) "Save entire series" else if (canEditTimedDate && dateChanged)
                "Move event" else "Save time")
        } },
        dismissButton = { TextButton(onClick = { confirmTimeChange = false }) { Text("Cancel") } })
    if (confirmDateChange && source != null && newDate != null) AlertDialog(
        onDismissRequest = { confirmDateChange = false }, title = { Text("Move all-day event?") },
        text = { Text("${event.summary}" +
            (if (title.trim() != event.summary) " → ${title.trim()}" else "") +
            " from ${event.date} to $newDate on ${source.name}. The calendar account may sync this change to other devices.") },
        confirmButton = { Button(onClick = {
            confirmDateChange = false
            when (event.provider) {
                CalendarProviderKind.ANDROID -> model.editAllDayDateAndroid(source, event, title, newDate, close)
                CalendarProviderKind.CALDAV -> model.editAllDayDateDav(source, event, title, newDate, close)
                CalendarProviderKind.HOME_ASSISTANT ->
                    model.editAllDayDateHomeAssistant(source, event, title, newDate, close)
                else -> Unit
            }
        }, enabled = !model.loading.value) { Text("Move event") } },
        dismissButton = { TextButton(onClick = { confirmDateChange = false }) { Text("Cancel") } })
    if (remindersOpen && source != null) AndroidReminderDialog(source, event) {
        remindersOpen = false
    }
    if (localRemindersOpen) LocalReminderDialog(event) { localRemindersOpen = false }
}

@Composable
private fun LocalReminderDialog(event: AgendaEvent, close: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { LocalReminderStore(context) }
    val alarms = remember(context) { LocalReminderAlarms(context) }
    val scope = rememberCoroutineScope()
    val eventKey = remember(event.identity) {
        LocalReminderPlanner.plan(event, LocalReminderWhen.MORNING_OF).eventKey
    }
    var rows by remember(eventKey) { mutableStateOf<List<LocalReminderRecord>>(emptyList()) }
    var choice by remember(eventKey) { mutableStateOf(LocalReminderWhen.MORNING_OF) }
    var minutesText by remember(eventKey) { mutableStateOf("60") }
    var busy by remember(eventKey) { mutableStateOf(true) }
    var error by remember(eventKey) { mutableStateOf("") }
    var precision by remember(eventKey) { mutableStateOf("") }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        error = if (granted) "Notifications allowed. Tap Add local reminder again."
            else "HAM needs notification permission to deliver a local reminder."
    }
    LaunchedEffect(eventKey) {
        try {
            rows = withContext(Dispatchers.IO) { store.list().filter { it.eventKey == eventKey } }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Could not read protected local reminders on this device." }
        finally { busy = false }
    }
    AlertDialog(onDismissRequest = { if (!busy) close() },
        title = { Text("HAM local reminders") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("HAM stores these reminders on this device. The calendar provider is unchanged. Its own alerts may also fire. HAM notifications do not show event details on the lock screen.",
                style = MaterialTheme.typography.bodySmall)
            if (android.os.Build.VERSION.SDK_INT >= 31 &&
                !context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()) {
                Text("Android may delay local reminders until exact alarm access is allowed.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(
                        android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}"))) }
                        .onFailure { error = "Open Android settings to allow Alarms & reminders." }
                }) { Text("Open Alarms & reminders settings") }
            }
            if (busy) Text("Checking reminders…")
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            if (precision.isNotBlank()) Text(precision, style = MaterialTheme.typography.bodySmall)
            if (!busy && rows.isEmpty()) Text("No HAM local reminders for this event.")
            rows.sortedBy { it.triggerAt }.forEach { record ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val label = when (record.whenToNotify) {
                        LocalReminderWhen.PREVIOUS_EVENING -> "Previous evening"
                        LocalReminderWhen.MORNING_OF -> "Morning of event"
                        LocalReminderWhen.MINUTES_BEFORE -> "${record.minutesBefore} minutes before"
                    }
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        busy = true; error = ""; precision = ""
                        scope.launch {
                            try {
                                rows = withContext(Dispatchers.IO) {
                                    val removed = store.remove(record.id)
                                    alarms.cancel(removed)
                                    store.list().filter { it.eventKey == eventKey }
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { error = "Could not remove the local reminder." }
                            finally { busy = false }
                        }
                    }, enabled = !busy) { Text("Remove") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = choice == LocalReminderWhen.PREVIOUS_EVENING,
                    onClick = { choice = LocalReminderWhen.PREVIOUS_EVENING })
                Text("Previous evening, 6 PM")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = choice == LocalReminderWhen.MORNING_OF,
                    onClick = { choice = LocalReminderWhen.MORNING_OF })
                Text("Morning of event, 9 AM")
            }
            if (!event.allDay) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = choice == LocalReminderWhen.MINUTES_BEFORE,
                        onClick = { choice = LocalReminderWhen.MINUTES_BEFORE })
                    Text("Minutes before start")
                }
                if (choice == LocalReminderWhen.MINUTES_BEFORE)
                    OutlinedTextField(minutesText, { minutesText = it.take(5) },
                        label = { Text("Minutes (0–10080)") }, singleLine = true)
            }
            Button(onClick = {
                if (!alarms.notificationAllowed()) {
                    if (android.os.Build.VERSION.SDK_INT >= 33)
                        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    else error = "HAM notifications are disabled on this device."
                    return@Button
                }
                val minutes = if (choice == LocalReminderWhen.MINUTES_BEFORE)
                    minutesText.toIntOrNull() else 0
                if (minutes == null) { error = "Enter a valid minute offset."; return@Button }
                busy = true; error = ""; precision = ""
                scope.launch {
                    try {
                        val exact = withContext(Dispatchers.IO) {
                            val plan = LocalReminderPlanner.plan(event, choice, minutes)
                            val saved = store.add(plan)
                            try { alarms.schedule(saved) }
                            catch (failure: Exception) {
                                store.remove(saved.id)
                                throw failure
                            }
                        }
                        rows = withContext(Dispatchers.IO) {
                            store.list().filter { it.eventKey == eventKey }
                        }
                        precision = if (exact) "Reminder scheduled with Android's exact alarm service."
                            else "Reminder saved. Android may deliver it later than the chosen time."
                    } catch (e: CancellationException) { throw e }
                    catch (e: IllegalStateException) {
                        error = when (e.message) {
                            "This local reminder already exists" -> "This local reminder already exists."
                            "An equivalent local reminder exists on another calendar connection" ->
                                "An equivalent local reminder already exists on another calendar connection."
                            "Reminder time has already passed" -> "That reminder time has already passed."
                            else -> "Could not schedule this reminder. Check its permissions and time."
                        }
                    } catch (_: Exception) {
                        error = "Could not schedule this reminder. Check its time and permissions."
                    } finally { busy = false }
                }
            }, enabled = !busy) { Text("Add local reminder") }
        } },
        confirmButton = { TextButton(onClick = close, enabled = !busy) { Text("Close") } })
}

@Composable
private fun AndroidReminderDialog(source: CalendarSource, event: AgendaEvent, close: () -> Unit) {
    val context = LocalContext.current
    val provider = remember(context) { AndroidCalendarProvider(context) }
    val scope = rememberCoroutineScope()
    var rows by remember(event.identity) { mutableStateOf<List<AndroidReminder>>(emptyList()) }
    var minutesText by remember(event.identity) { mutableStateOf("60") }
    var busy by remember(event.identity) { mutableStateOf(true) }
    var error by remember(event.identity) { mutableStateOf("") }
    LaunchedEffect(event.identity) {
        try { rows = withContext(Dispatchers.IO) { provider.reminders(source, event) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Could not read this event's Android reminders. Refresh the event." }
        finally { busy = false }
    }
    AlertDialog(onDismissRequest = close, title = { Text("Android calendar reminders") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("These reminders belong to the Android calendar. Its account may sync them. HAM does not create a separate notification.",
                style = MaterialTheme.typography.bodySmall)
            if (busy) Text("Checking reminders…")
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            if (!busy && rows.isEmpty()) Text("No provider reminders on this event.")
            rows.sortedBy { it.minutes }.forEach { reminder ->
                val deviceAlert = reminder.method in setOf(
                    android.provider.CalendarContract.Reminders.METHOD_DEFAULT,
                    android.provider.CalendarContract.Reminders.METHOD_ALERT)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${reminder.minutes} minutes before · ${if (deviceAlert) "Device alert" else "Provider-managed reminder"}",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        busy = true; error = ""
                        scope.launch {
                            try {
                                rows = withContext(Dispatchers.IO) {
                                    provider.removeReminder(source, event, reminder)
                                    provider.reminders(source, event)
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) {
                                error = "Reminder changed or could not be removed. Refresh the event."
                            } finally { busy = false }
                        }
                    }, enabled = !busy && deviceAlert) { Text("Remove") }
                }
            }
            OutlinedTextField(minutesText, { minutesText = it.take(5) },
                label = { Text("Minutes before event (0–10080)") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { minutesText = "15" }) { Text("15 min") }
                TextButton(onClick = { minutesText = "60" }) { Text("1 hour") }
                TextButton(onClick = { minutesText = "1440" }) { Text("1 day") }
            }
            Button(onClick = {
                val minutes = runCatching { AndroidReminders.minutes(minutesText) }.getOrNull()
                if (minutes == null) { error = "Enter 0–10080 minutes before the event."; return@Button }
                busy = true; error = ""
                scope.launch {
                    try {
                        rows = withContext(Dispatchers.IO) {
                            provider.addReminder(source, event, minutes)
                            provider.reminders(source, event)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        error = "Reminder was not confirmed. Refresh the event before trying again."
                    } finally { busy = false }
                }
            }, enabled = !busy) { Text("Add provider reminder") }
        } }, confirmButton = { TextButton(onClick = close) { Text("Close") } })
}

@Composable
private fun QuickAddPage(model: HamViewModel, suggestedSlot: Pair<String, String>?,
    onSuggestionApplied: () -> Unit, copyRequest: EventCopyRequest?, onCopyConsumed: () -> Unit,
    onScreenshot: () -> Unit, onResume: () -> Unit) {
    val context = LocalContext.current
    val templateStore = remember(context) { EventTemplateStore(context) }
    val quickDraftStore = remember(context) { QuickAddDraftStore(context) }
    var templates by remember { mutableStateOf<List<EventTemplate>>(emptyList()) }
    var templateError by remember { mutableStateOf("") }
    LaunchedEffect(templateStore) {
        runCatching { templateStore.list() }
            .onSuccess { templates = it }
            .onFailure { templateError = "Could not read protected event templates on this device." }
    }
    val scope = rememberCoroutineScope()
    val formScroll = rememberScrollState()
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    var locationOpen by rememberSaveable { mutableStateOf(false) }
    var descriptionOpen by rememberSaveable { mutableStateOf(false) }
    var imported by remember { mutableStateOf<List<IcsImportDraft>?>(null) }
    var importIndex by remember { mutableStateOf(0) }
    var importError by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }
    var copyOrigin by remember { mutableStateOf<Pair<String, String>?>(null) }
    var moveSource by remember { mutableStateOf<AgendaEvent?>(null) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            imported = null
            copyOrigin = null
            moveSource = null
            importIndex = 0
            importError = ""
            importing = true
            scope.launch {
                try {
                    imported = withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)
                            ?.use(IcsSingleEventImport::readLimited)
                            ?: error("Could not open this file")
                        IcsSingleEventImport.previewAll(bytes)
                    }
                } catch (_: Exception) {
                    importError = "This ICS file could not be imported. Choose up to 50 nonrepeating events, or one finite daily, weekly or monthly series without exceptions. Timed events must be same-day, and timed series must preserve local time and duration."
                } finally { importing = false }
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(formScroll), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("Quick Add", style = MaterialTheme.typography.headlineMedium)
    TextButton(onClick = { toolsOpen = !toolsOpen }) {
        Text(if (toolsOpen) "Hide screenshot, import and templates" else "Screenshot, import or template")
    }
    if (toolsOpen) {
    OutlinedButton(onClick = onScreenshot) { Text("Add from Screenshot") }
    OutlinedButton(onClick = { importPicker.launch(arrayOf("*/*")) },
        enabled = !importing && !model.loading.value) {
        Text(if (importing) "Reading ICS file…" else "Open ICS event file")
    }
    if (importError.isNotBlank()) Text(importError, color = MaterialTheme.colorScheme.error)
    if (com.tinkercobble.ham.data.ScreenshotDraftStore(LocalContext.current).load() != null)
        TextButton(onClick = onResume) { Text("Resume screenshot draft") }
    }
    Text("Create an event on a calendar you choose.", Modifier.padding(vertical = 8.dp))
    EventDateField(model.selectedDate.value.toString()) { chosen ->
        runCatching { LocalDate.parse(chosen) }.getOrNull()?.let { model.selectedDate.value = it }
    }
    val prefs = model.calendarPreferences.value
    // Keep the chosen ID through list refreshes; never substitute an entry with the same name.
    var selectedId by rememberSaveable(model.server.value, prefs.defaultId) { mutableStateOf(prefs.defaultId) }
    LaunchedEffect(copyRequest?.id) {
        if (copyRequest != null) {
            imported = listOf(copyRequest.draft)
            importIndex = 0
            confirmImport = false
            importError = ""
            copyOrigin = copyRequest.sourceId to copyRequest.sourceName
            moveSource = copyRequest.moveSource
            selectedId = null
            onCopyConsumed()
        }
    }
    val choice = model.selectedWritable(selectedId).takeIf { it?.id != copyOrigin?.first &&
        (moveSource == null || it?.provider == CalendarProviderKind.ANDROID) }
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("") }
    var allDay by rememberSaveable { mutableStateOf(true) }
    var lastAllDayDateText by rememberSaveable { mutableStateOf("") }
    var repeatFrequency by rememberSaveable { mutableStateOf<RepeatFrequency?>(null) }
    var repeatCount by rememberSaveable { mutableStateOf("4") }
    var startTime by rememberSaveable { mutableStateOf("09:00") }
    var endTime by rememberSaveable { mutableStateOf("10:00") }
    var suggestedEnd by rememberSaveable { mutableStateOf(true) }
    var defaultMinutes by remember { mutableStateOf(EventEntryPreferences.defaultMinutes(context)) }
    var draftReady by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(quickDraftStore) {
        if (draftReady) return@LaunchedEffect
        val draft = withContext(Dispatchers.IO) { quickDraftStore.load() }
        if (draft != null) {
            title = draft.title; description = draft.description; location = draft.location
            allDay = draft.allDay; startTime = draft.start; endTime = draft.end
            lastAllDayDateText = draft.lastDay; selectedId = draft.calendarId
            repeatFrequency = draft.repeatFrequency?.let { name ->
                RepeatFrequency.entries.firstOrNull { it.name == name }
            }
            repeatCount = draft.repeatCount
            runCatching { java.time.LocalDate.parse(draft.date) }.getOrNull()?.let {
                model.selectedDate.value = it
            }
        }
        draftReady = true
    }
    LaunchedEffect(draftReady, title, description, location, allDay, startTime, endTime,
        lastAllDayDateText, selectedId, repeatFrequency, repeatCount, model.selectedDate.value) {
        if (!draftReady) return@LaunchedEffect
        delay(350)
        withContext(Dispatchers.IO) {
            if (title.isBlank() && description.isBlank() && location.isBlank() && allDay &&
                repeatFrequency == null && lastAllDayDateText.isBlank()) quickDraftStore.clear()
            else quickDraftStore.save(QuickAddDraft(title, description, location,
                model.selectedDate.value.toString(), allDay, startTime, endTime,
                lastAllDayDateText, selectedId, repeatFrequency?.name, repeatCount))
        }
    }
    var templatesOpen by remember { mutableStateOf(false) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var saveTemplateOpen by remember { mutableStateOf(false) }
    var templateName by remember { mutableStateOf("") }
    var deleteTemplateId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(suggestedSlot) {
        if (suggestedSlot != null) {
            startTime = suggestedSlot.first
            endTime = suggestedSlot.second
            allDay = false
            onSuggestionApplied()
        }
    }
    var chooser by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    if (toolsOpen) OutlinedButton(onClick = { templatesOpen = true }, enabled = templates.isNotEmpty()) {
        Text("Use event template (${templates.size})")
    }
    if (templateError.isNotBlank()) Text(templateError, color = MaterialTheme.colorScheme.error)
    if (templatesOpen) AlertDialog(onDismissRequest = { templatesOpen = false },
        title = { Text("Event templates") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Applying a template fills this form. Choose a calendar and review before creating.")
            templates.forEach { template ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(template.name, Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = {
                        title = template.title; description = template.description
                        location = template.location; allDay = template.allDay
                        lastAllDayDateText = ""
                        startTime = template.startTime; endTime = template.endTime
                        repeatFrequency = template.repeat?.frequency
                        repeatCount = template.repeat?.count?.toString() ?: "4"
                        templatesOpen = false
                    }) { Text("Use") }
                    TextButton(onClick = { templatesOpen = false; deleteTemplateId = template.id }) {
                        Text("Remove")
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = { templatesOpen = false }) { Text("Close") } })
    deleteTemplateId?.let { id ->
        val template = templates.firstOrNull { it.id == id }
        AlertDialog(onDismissRequest = { deleteTemplateId = null },
            title = { Text("Remove template?") },
            text = { Text("Remove ${template?.name ?: "this template"} from this device? Calendar events are unchanged.") },
            confirmButton = { Button(onClick = {
                runCatching { templateStore.remove(id); templateStore.list() }
                    .onSuccess { templates = it; templateError = "" }
                    .onFailure { templateError = "Could not remove this event template." }
                deleteTemplateId = null
            }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { deleteTemplateId = null }) { Text("Cancel") } })
    }
    imported?.getOrNull(importIndex)?.let { draft ->
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (moveSource != null) "Review Android calendar move" else if (copyOrigin != null) "Review event copy" else
                "Review ICS import · event ${importIndex + 1} of ${imported?.size ?: 0}",
                style = MaterialTheme.typography.titleMedium)
            if (copyOrigin != null) Text(if (moveSource != null)
                "Original calendar: ${copyOrigin?.second}. HAM removes the original only after Android confirms the destination copy."
                else "Original calendar: ${copyOrigin?.second}. The original event stays there.")
            Text("${draft.title} · ${draft.date}" +
                if (draft.allDay) "${if (draft.endExclusive != draft.date.plusDays(1))
                    " through ${draft.endExclusive.minusDays(1)}" else ""} · All day"
                else " · ${draft.startText}–${draft.endText}")
            if (draft.repeat != null) Text("Repeats: ${draft.repeat.label}")
            if (draft.location.isNotBlank()) Text("Location: ${draft.location}")
            if (draft.description.isNotBlank()) Text("Description: ${draft.description}")
            Text("Calendar: ${choice?.name ?: "Choose a writable calendar"}")
            OutlinedButton(onClick = { chooser = true }) { Text("Choose destination calendar") }
            val seriesSupported = draft.repeat == null || choice?.provider == CalendarProviderKind.ANDROID ||
                choice?.provider == CalendarProviderKind.CALDAV
            val multiDaySupported = !draft.allDay || draft.endExclusive == draft.date.plusDays(1) ||
                choice?.canCreate == true
            if (!seriesSupported) Text("This series needs a writable Android or CalDAV/iCloud calendar.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (!multiDaySupported) Text("This multi-day event needs a writable calendar.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (draft.repeat != null && !draft.allDay)
                Text("HAM checked that each occurrence keeps the same local time and duration in ${draft.deviceZoneId}. CalDAV/iCloud writes use a calendar time-zone definition when UTC would shift the schedule; the provider may still refuse the series.",
                    style = MaterialTheme.typography.bodySmall)
            Button(onClick = { confirmImport = true }, enabled = choice != null && seriesSupported &&
                multiDaySupported && !model.loading.value) {
                Text(if (moveSource != null) "Review move" else if (copyOrigin != null) "Review copy" else "Review import")
            }
            TextButton(onClick = { imported = null; importIndex = 0; confirmImport = false;
                copyOrigin = null; moveSource = null }, enabled = !model.loading.value) {
                Text(if (moveSource != null) "Discard move preview" else if (copyOrigin != null)
                    "Discard copy preview" else "Discard remaining file preview") }
        } }
    }
    if (confirmImport && imported?.getOrNull(importIndex) != null) {
        val draft = imported!![importIndex]
        val reviewed = model.selectedWritable(selectedId).takeIf { it?.id != copyOrigin?.first &&
            (moveSource == null || it?.provider == CalendarProviderKind.ANDROID) }
        val seriesSupported = draft.repeat == null || reviewed?.provider == CalendarProviderKind.ANDROID ||
            reviewed?.provider == CalendarProviderKind.CALDAV
        val multiDaySupported = !draft.allDay || draft.endExclusive == draft.date.plusDays(1) ||
            reviewed?.canCreate == true
        AlertDialog(onDismissRequest = { confirmImport = false },
            title = { Text(if (moveSource != null) "Move event to another Android calendar?"
                else if (copyOrigin != null) "Copy event to another calendar?"
                else if (draft.repeat == null) "Import event ${importIndex + 1} of ${imported?.size ?: 0}?"
                else if (draft.allDay) "Import all-day series?" else "Import timed series?") },
            text = { Text("Create ${draft.title} on ${reviewed?.name ?: "an unavailable calendar"} from ${draft.date}${if (draft.allDay && draft.endExclusive != draft.date.plusDays(1)) " through ${draft.endExclusive.minusDays(1)}" else ""}${draft.repeat?.let { " · ${it.label}" } ?: ""}. HAM applies the selected calendar's normal duplicate and write checks. Only title, date/time, supported recurrence, location and description transfer. Invitations, alarms, attachments, provider IDs and other metadata are excluded. ${if (moveSource != null) "After Android confirms the destination, HAM removes the original from ${copyOrigin?.second}. If removal fails, both events may remain for review. " else if (copyOrigin != null) "The original event stays on ${copyOrigin?.second}. " else ""}Connected calendar accounts may sync it to other devices. Repeating events copy only this displayed occurrence.") },
            confirmButton = { Button(onClick = {
                val id = reviewed?.id ?: return@Button
                confirmImport = false
                val done = {
                    val current = imported
                    if (current?.getOrNull(importIndex) == draft) {
                        importIndex += 1
                        if (importIndex >= current.size) { imported = null; importIndex = 0;
                            copyOrigin = null; moveSource = null }
                    }
                    Unit
                }
                val moving = moveSource
                if (moving != null) model.moveAndroidEvent(moving, id, draft, done)
                else if (draft.repeat != null && !draft.allDay) model.createRecurringTimed(id, draft.date,
                    draft.startText, draft.endText, draft.title, draft.repeat,
                    draft.description, draft.location, done, draft.deviceZoneId)
                else if (draft.repeat != null) model.createRecurringAllDay(id, draft.date, draft.title,
                    draft.repeat, draft.description, draft.location, done)
                else if (draft.allDay) model.create(id, draft.date, draft.title,
                    draft.description, draft.location, done,
                    endExclusive = draft.endExclusive)
                else model.createTimed(id, draft.date, draft.startText, draft.endText,
                    draft.title, draft.description, draft.location, done)
            }, enabled = reviewed != null && seriesSupported && multiDaySupported &&
                !model.loading.value) {
                Text(if (moveSource != null) "Copy, confirm, then remove original" else if (copyOrigin != null) "Create copy" else if (draft.repeat == null)
                    "Create on selected calendar" else "Create series on selected calendar")
            } },
            dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Cancel") } })
    }
    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Event title") }, modifier = Modifier.fillMaxWidth())
    TextButton(onClick = { scope.launch { formScroll.animateScrollTo(formScroll.maxValue) } }) {
        Text("Review and save event")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("All-day event")
        Switch(checked = allDay, onCheckedChange = { allDay = it })
    }
    if (allDay && repeatFrequency == null) {
        OutlinedTextField(lastAllDayDateText, { lastAllDayDateText = it },
            label = { Text("Last day (YYYY-MM-DD; blank = start day)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("For a multi-day event, choose its first day above and enter the last included day here.",
            style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = { advancedOpen = !advancedOpen }) {
        Text(if (advancedOpen) "Hide repeat and advanced options" else "Repeat and advanced options")
    }
    val repeatSpec = repeatFrequency?.let { frequency ->
        repeatCount.toIntOrNull()?.let { count -> runCatching { RepeatSpec(frequency, count) }.getOrNull() }
    }
    val repeatSupported = choice?.provider == CalendarProviderKind.ANDROID ||
        choice?.provider == CalendarProviderKind.CALDAV
    if (advancedOpen) {
        Text("Repeat", style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = repeatFrequency == null, onClick = { repeatFrequency = null })
            Text("Does not repeat")
        }
        RepeatFrequency.entries.forEach { frequency ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = repeatFrequency == frequency,
                    onClick = { repeatFrequency = frequency })
                Text(frequency.label)
            }
        }
    }
    if (repeatFrequency != null && advancedOpen) {
        OutlinedTextField(repeatCount, { repeatCount = it }, label = { Text("Occurrences (2–52)") },
            singleLine = true)
        if (!repeatSupported)
            Text("Repeating creation needs a writable Android or CalDAV/iCloud calendar.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        else if (repeatSpec == null)
            Text("Choose 2 to 52 occurrences.", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        else if (!allDay && choice?.provider == CalendarProviderKind.CALDAV)
            Text("Timed CalDAV/iCloud repeats are checked across every occurrence. HAM includes a calendar time-zone definition when needed to preserve local time; unsupported schedules are refused.",
                style = MaterialTheme.typography.bodySmall)
    }
    val repeatValid = repeatFrequency == null || (repeatSupported && repeatSpec != null)
    val lastAllDayDate = if (allDay && repeatFrequency == null && lastAllDayDateText.isNotBlank())
        runCatching { LocalDate.parse(lastAllDayDateText) }.getOrNull() else model.selectedDate.value
    val allDayEndExclusive = runCatching { lastAllDayDate?.plusDays(1) }.getOrNull()
    val allDayRangeValid = !allDay || repeatFrequency != null ||
        (lastAllDayDate != null && !lastAllDayDate.isBefore(model.selectedDate.value) &&
            allDayEndExclusive != null)
    val multiDay = allDay && repeatFrequency == null &&
        lastAllDayDate != null && lastAllDayDate.isAfter(model.selectedDate.value)
    val multiDaySupported = !multiDay || choice?.canCreate == true
    if (!allDayRangeValid) Text("Choose a last day on or after the first day.",
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    else if (!multiDaySupported) Text("Multi-day creation needs a writable calendar.",
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    val normalizedStart = com.tinkercobble.ham.data.EventEntryTime.parse(startTime)?.toString().orEmpty()
    val normalizedEnd = com.tinkercobble.ham.data.EventEntryTime.parse(endTime)?.toString().orEmpty()
    val timedValid = allDay || runCatching { com.tinkercobble.ham.data.TimedEventTime.parse(
        model.selectedDate.value, normalizedStart, normalizedEnd) }.isSuccess
    val detailsValid = runCatching { com.tinkercobble.ham.data.EventDraftDetails.of(
        description, location) }.isSuccess
    OutlinedButton(onClick = { templateName = title.trim().take(80); saveTemplateOpen = true },
        enabled = title.isNotBlank() && title.length <= 200 && timedValid && detailsValid &&
            (repeatFrequency == null || repeatSpec != null) && !multiDay) {
        Text("Save form as template")
    }
    if (saveTemplateOpen) AlertDialog(onDismissRequest = { saveTemplateOpen = false },
        title = { Text("Save event template") },
        text = { Column {
            OutlinedTextField(templateName, { if (it.length <= 80) templateName = it },
                label = { Text("Template name") }, singleLine = true)
            Text("The date and calendar are never saved. Applying this template does not create an event.",
                style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { Button(onClick = {
            runCatching {
                templateStore.save(EventTemplate(UUID.randomUUID().toString(), templateName,
                    title, description, location, allDay, startTime, endTime, repeatSpec))
                templateStore.list()
            }.onSuccess { templates = it; templateError = ""; saveTemplateOpen = false }
                .onFailure { templateError = "Could not save this protected event template." }
        }, enabled = templateName.isNotBlank() && templates.size < 20) { Text("Save") } },
        dismissButton = { TextButton(onClick = { saveTemplateOpen = false }) { Text("Cancel") } })
    if (!allDay) {
        EventTimeField(startTime, { value ->
            startTime = value
            if (suggestedEnd) {
                endTime = com.tinkercobble.ham.data.EventEntryTime.parse(value)?.let {
                    com.tinkercobble.ham.data.EventEntryTime.suggestedEnd(it, defaultMinutes)
                }?.toString().orEmpty()
            }
        }, "Starts", Modifier.fillMaxWidth())
        EventTimeField(endTime, { endTime = it; suggestedEnd = false },
            if (suggestedEnd) "Ends (suggested: $defaultMinutes min)" else "Ends", Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(15, 30, 60, 120).forEach { minutes ->
                TextButton(onClick = {
                    endTime = com.tinkercobble.ham.data.EventEntryTime.parse(startTime)?.let {
                        com.tinkercobble.ham.data.EventEntryTime.suggestedEnd(it, minutes)
                    }?.toString().orEmpty()
                    suggestedEnd = endTime.isNotBlank()
                }) { Text(if (minutes == 60) "1 hour" else if (minutes == 120) "2 hours" else "$minutes min") }
            }
        }
        if (advancedOpen) TextButton(onClick = {
            defaultMinutes = listOf(15, 30, 60, 120).let { options ->
                options[(options.indexOf(defaultMinutes) + 1) % options.size]
            }
            EventEntryPreferences.setDefaultMinutes(context, defaultMinutes)
        }) { Text("Default duration for new events: $defaultMinutes min · Change") }
        if (!timedValid) Text("Choose valid times with the end later on the same day. Times during a daylight-saving change are unavailable.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = { locationOpen = !locationOpen }) {
        Text(if (locationOpen) "Hide location" else if (location.isBlank()) "Add location (optional)" else "Edit location")
    }
    if (locationOpen) OutlinedTextField(value = location,
        onValueChange = { if (it.length <= 8_192) location = it },
        label = { Text("Location (optional)") }, modifier = Modifier.fillMaxWidth())
    TextButton(onClick = { descriptionOpen = !descriptionOpen }) {
        Text(if (descriptionOpen) "Hide description" else if (description.isBlank()) "Add description (optional)" else "Edit description")
    }
    if (descriptionOpen) OutlinedTextField(value = description,
        onValueChange = { if (it.length <= 65_536) description = it },
        label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth())
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
        Button(onClick = { confirm = true }, enabled = title.isNotBlank() && timedValid &&
            repeatValid && detailsValid && allDayRangeValid && multiDaySupported &&
            choice != null && !model.loading.value) {
            Text("Save Event")
        }
        if (confirm) {
            val reviewed = model.selectedWritable(selectedId)
            AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Create event?") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("${title.trim()} · ${model.selectedDate.value}" +
                        if (allDay) "${if (multiDay) " through $lastAllDayDate" else ""} · All day"
                        else " · $normalizedStart–$normalizedEnd")
                    if (repeatSpec != null) Text(repeatSpec.label)
                    if (location.isNotBlank()) Text("Location: ${location.trim()}")
                    if (description.isNotBlank()) Text("Description: ${description.trim()}")
                    Text(reviewed?.name ?: "Calendar unavailable")
                    Text(reviewed?.id ?: "Choose another calendar", style = MaterialTheme.typography.bodySmall)
                    if (reviewed?.provider == CalendarProviderKind.CALDAV ||
                        reviewed?.provider == CalendarProviderKind.HOME_ASSISTANT)
                        Text("HAM will save this change encrypted on your device and try to send it directly to this calendar server. If unavailable, review pending changes in Settings.",
                            style = MaterialTheme.typography.bodySmall)
                } },
                confirmButton = { Button(onClick = {
                    val id = reviewed?.id ?: return@Button
                    val clearDraft = { quickDraftStore.clear(); title = ""; description = ""; location = "";
                        lastAllDayDateText = ""; confirm = false }
                    if (repeatSpec != null && allDay) model.createRecurringAllDay(id,
                        model.selectedDate.value, title, repeatSpec, description, location, clearDraft)
                    else if (repeatSpec != null) model.createRecurringTimed(id,
                        model.selectedDate.value, normalizedStart, normalizedEnd, title, repeatSpec,
                        description, location, clearDraft)
                    else if (allDay) model.create(id, model.selectedDate.value, title,
                        description, location, clearDraft,
                        endExclusive = checkNotNull(allDayEndExclusive))
                    else model.createTimed(id, model.selectedDate.value, normalizedStart, normalizedEnd, title,
                        description, location, clearDraft)
                }, enabled = reviewed != null && timedValid && repeatValid && detailsValid &&
                    allDayRangeValid && multiDaySupported && !model.loading.value) {
                    Text(if (reviewed?.provider == CalendarProviderKind.CALDAV ||
                        reviewed?.provider == CalendarProviderKind.HOME_ASSISTANT) "Save and sync" else "Create")
                } },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
        }
    }
    if (chooser) CalendarPicker(model, onDismiss = { chooser = false }, excludeId = copyOrigin?.first,
        allowedProvider = if (moveSource != null) CalendarProviderKind.ANDROID else null) {
        selectedId = it
        confirm = false
        chooser = false
    }
    }
}

@Composable
internal fun CalendarPicker(model: HamViewModel, onDismiss: () -> Unit,
    excludeId: String? = null, allowedProvider: CalendarProviderKind? = null,
    onSelect: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    val prefs = model.calendarPreferences.value
    val writable = CalendarSelection.writable(model.calendars.value)
        .filter { it.id != excludeId && (allowedProvider == null || it.provider == allowedProvider) &&
            (showHidden || it.id !in prefs.hidden) }
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
    val context = LocalContext.current
    val store = remember(context) { LocalTaskStore(context) }
    val initial = remember(store) { runCatching { store.list() } }
    var localTasks by remember(store) { mutableStateOf(initial.getOrDefault(emptyList())) }
    var localError by remember(store) { mutableStateOf(if (initial.isFailure) "Local tasks could not be opened" else "") }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LocalTask?>(null) }
    var deleting by remember { mutableStateOf<LocalTask?>(null) }
    var undoChange by remember { mutableStateOf<Pair<LocalTask?, LocalTask?>?>(null) }
    var undoMessage by remember { mutableStateOf("") }
    fun applyChange(change: () -> Unit): Boolean = try {
        change()
        localTasks = store.list()
        localError = ""
        undoMessage = ""
        true
    } catch (_: Exception) {
        localError = "Local tasks could not be saved. Existing tasks were kept."
        false
    }
    Text("Tasks", style = MaterialTheme.typography.headlineMedium)
    Text("Unscheduled tasks stay on this device. Home Assistant to-do items appear separately below.")
    if (localError.isNotBlank()) Text(localError, color = MaterialTheme.colorScheme.error)
    if (undoMessage.isNotBlank()) Text(undoMessage, style = MaterialTheme.typography.bodySmall)
    Button(onClick = { creating = true }, enabled = localError.isBlank()) { Text("Add unscheduled task") }
    undoChange?.let { (before, after) ->
        Text("Last local task change can be undone while this page is open.",
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            try {
                if (store.undo(before, after)) {
                    localTasks = store.list()
                    undoChange = null
                    undoMessage = "Last local task change undone."
                } else {
                    undoChange = null
                    undoMessage = "This task changed again; the last action cannot be undone."
                }
            } catch (_: Exception) {
                undoMessage = "The last local task change could not be undone. Try again."
            }
        }) { Text("Undo last task change") }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("On this device", style = MaterialTheme.typography.titleMedium) }
        if (localTasks.isEmpty()) item { Text("No local tasks yet.") }
        items(localTasks.sortedWith(compareBy<LocalTask> { it.completed }.thenByDescending { it.createdAt }),
            key = { "local-${it.id}" }) { task ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = task.completed, onCheckedChange = { checked ->
                        val changed = task.copy(completed = checked)
                        if (applyChange { store.save(changed) }) undoChange = task to changed
                    }, enabled = localError.isBlank())
                    Text(task.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold,
                        textDecoration = if (task.completed) TextDecoration.LineThrough else null)
                }
                if (task.notes.isNotBlank()) Text(task.notes, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { editing = task }, enabled = localError.isBlank()) { Text("Edit") }
                    TextButton(onClick = { deleting = task }, enabled = localError.isBlank()) { Text("Remove") }
                }
            } }
        }
        item { Text("Home Assistant to-do lists", style = MaterialTheme.typography.titleMedium) }
        if (model.taskError.value.isNotBlank()) item { Text("Tasks could not load: ${model.taskError.value}") }
        else if (model.tasks.value.isEmpty()) item { Text("No open Home Assistant tasks are available.") }
        else items(model.tasks.value) { task ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
                Text(task.summary, fontWeight = FontWeight.SemiBold)
                Text(task.listName + (task.due?.let { " · Due $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
            } }
        }
    }
    val draft = editing
    if (creating || draft != null) {
        var title by remember(creating, draft?.id) { mutableStateOf(draft?.title ?: "") }
        var notes by remember(creating, draft?.id) { mutableStateOf(draft?.notes ?: "") }
        AlertDialog(onDismissRequest = { creating = false; editing = null },
            title = { Text(if (draft == null) "Add unscheduled task" else "Edit local task") },
            text = { Column {
                OutlinedTextField(title, { title = it.take(200) }, label = { Text("Task") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(notes, { notes = it.take(2000) }, label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth())
            } },
            confirmButton = { Button(onClick = {
                val next = if (draft == null) LocalTask.create(title, notes)
                    else draft.copy(title = title, notes = notes).validated()
                if (applyChange { store.save(next) }) {
                    undoChange = draft to next
                    creating = false; editing = null
                }
            }, enabled = title.trim().isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { creating = false; editing = null }) { Text("Cancel") } })
    }
    deleting?.let { task ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text("Remove local task?") },
            text = { Text("This removes this task from this device. Home Assistant items are unaffected.") },
            confirmButton = { Button(onClick = {
                if (applyChange { store.remove(task.id) }) {
                    undoChange = task to null
                    deleting = null
                }
            }) {
                Text("Remove")
            } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

@Composable
private fun SearchPage(model: HamViewModel, openCalendars: (String?) -> Unit,
    onCopy: (EventCopyRequest) -> Unit) {
    var query by remember { mutableStateOf("") }
    var includeHidden by remember { mutableStateOf(false) }
    Text("Search calendars", style = MaterialTheme.typography.headlineMedium)
    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Find events") }, modifier = Modifier.fillMaxWidth())
    Text("Search loaded events.", style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = includeHidden, onCheckedChange = { includeHidden = it })
        Text("Include hidden calendars")
    }
    if (query.isBlank()) Text("Type a name or place to search.", Modifier.padding(top = 16.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    EventList(model.searchableEvents(includeHidden).filter {
        query.isNotBlank() && (it.summary.contains(query, true) || it.description.contains(query, true) || it.location.contains(query, true)) },
        model, openCalendars, onCopy)
}

@Composable
private fun SettingsPage(model: HamViewModel, onConnected: () -> Unit, onUpdateAvailable: (AvailableUpdate) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var updateMessage by remember { mutableStateOf("") }
    var checkingUpdate by remember { mutableStateOf(false) }
    var autoInstallUpdates by remember { mutableStateOf(AutoUpdateScheduler.isEnabled(context)) }
    var privacyOpen by remember { mutableStateOf(false) }
    var backupOpen by remember { mutableStateOf(false) }
    var manageOpen by remember { mutableStateOf(false) }
    var showHttpWarning by remember { mutableStateOf(false) }
    var removeLocalOpen by remember { mutableStateOf(false) }
    var davKind by remember { mutableStateOf<DavKind?>(null) }
    var removeDavId by remember { mutableStateOf<String?>(null) }
    var icsConnectOpen by remember { mutableStateOf(false) }
    var removeIcsId by remember { mutableStateOf<String?>(null) }
    var discardDavId by remember { mutableStateOf<java.util.UUID?>(null) }
    var discardHaId by remember { mutableStateOf<java.util.UUID?>(null) }
    var retryHaId by remember { mutableStateOf<java.util.UUID?>(null) }
    val androidCalendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { model.enableAndroidCalendars(); onConnected() }
        else error = "Android calendar access was not granted. You can enable it later."
    }
    val androidWritePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.onAndroidWritePermissionResult()
        if (!granted) error = "Android calendar editing was not granted. Calendar reading still works."
    }
    BackHandler(privacyOpen) { privacyOpen = false }
    BackHandler(backupOpen) { backupOpen = false }
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
        } else {
            result.data?.getStringExtra(HomeAssistantLoginActivity.EXTRA_AUTH_ERROR)?.let { error = it }
            model.cancelAuth()
        }
    }
    if (manageOpen) {
        ManageCalendarsPage(model, onBack = { manageOpen = false })
        return
    }
    if (privacyOpen) {
        PrivacyConnectionPage(model, onBack = { privacyOpen = false })
        return
    }
    if (backupOpen) {
        LocalBackupPage(onBack = { backupOpen = false })
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Connection", style = MaterialTheme.typography.headlineMedium)
    OutlinedButton(onClick = { backupOpen = true }) { Text("Encrypted local content backup") }
    if (model.connected.value) {
        Text(if (BuildConfig.DEBUG && model.demoPreview.value)
            "Demo preview — no Home Assistant connection" else "Connected to ${model.server.value}")
        Button(onClick = model::refresh) { Text("Refresh") }
        OutlinedButton(onClick = { privacyOpen = true }) { Text("Privacy & Connection") }
        Text("Reconnect if Home Assistant asks you to sign in again. Your current connection stays active until sign-in succeeds.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { model.beginAuth(model.server.value) }, enabled = !model.loading.value) {
            Text("Reconnect Home Assistant")
        }
    } else {
        Text("Moving from the previous HAM installation? Copy its calendar choices here before signing in. The previous app stays installed.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            try {
                model.importLegacySettings()
                address = model.server.value
                error = ""
            } catch (_: Exception) {
                error = "Could not copy settings. Keep the previous HAM app installed and try again after its migration update."
            }
        }) { Text("Copy settings from previous HAM") }
        OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Home Assistant address") },
            placeholder = { Text("https://your-home-assistant.example") }, modifier = Modifier.fillMaxWidth())
        Text("Use HTTPS away from home.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            if (address.trim().startsWith("http://", ignoreCase = true)) showHttpWarning = true
            else model.beginAuth(address)
        }, enabled = !model.loading.value) { Text("Connect HAM to Home Assistant") }
    }
    Text("Android calendars", style = MaterialTheme.typography.titleMedium)
    Text("Use calendars already synchronized to this device, including Google calendars. HAM reads events only from calendars you choose; editing needs separate permission and a writable calendar.",
        style = MaterialTheme.typography.bodySmall)
    if (model.androidEnabled.value) {
        OutlinedButton(onClick = model::disableAndroidCalendars) { Text("Disconnect Android calendars") }
        if (!model.androidWritePermissionGranted.value) {
            Text("To create events in writable device calendars, allow Android calendar editing. HAM checks each calendar's access level before writing.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { androidWritePermission.launch(Manifest.permission.WRITE_CALENDAR) }) {
                Text("Allow Android calendar editing")
            }
        } else {
            Text("Create an optional HAM calendar stored by Android without a calendar sync account. Other calendar apps with permission may see it. Android backup behavior depends on device settings.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = model::createLocalCalendar,
                enabled = !model.loading.value) { Text("Create or find HAM local calendar") }
            if (model.hamLocalCalendarId.value != null) {
                TextButton(onClick = { removeLocalOpen = true }, enabled = !model.loading.value) {
                    Text("Remove empty HAM local calendar")
                }
            }
        }
    } else OutlinedButton(onClick = {
        if (model.androidPermissionGranted.value) { model.enableAndroidCalendars(); onConnected() }
        else androidCalendarPermission.launch(Manifest.permission.READ_CALENDAR)
    }) { Text("Connect Android calendars") }
    if (model.hasAgendaSource) {
        Text(com.tinkercobble.ham.data.CalendarSourceOverview.counts(model.calendars.value).label(),
            Modifier.padding(top = 12.dp))
    }
    OutlinedButton(onClick = { manageOpen = true }) { Text("Manage calendars and diagnostics") }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Other calendar accounts", style = MaterialTheme.typography.titleMedium)
    Text("Connect iCloud with an Apple app-specific password, or connect another HTTPS CalDAV server. Enter credentials only on this device.",
        style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { davKind = DavKind.ICLOUD }) { Text("Connect iCloud Calendar") }
    OutlinedButton(onClick = { davKind = DavKind.CALDAV }) { Text("Connect CalDAV server") }
    model.davAccounts.value.forEach { account ->
        Text("${if (account.kind == DavKind.ICLOUD) "iCloud" else "CalDAV"} · ${account.username}")
        TextButton(onClick = { removeDavId = account.id }, enabled = !model.davBusy.value) {
            Text("Remove this connection")
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("ICS subscriptions", style = MaterialTheme.typography.titleMedium)
    Text("Subscribe to a public or private HTTPS calendar feed. Feeds are read-only; private URLs and optional sign-in details stay encrypted on this device. Each feed remains hidden until you choose it in Manage calendars.",
        style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { icsConnectOpen = true }) { Text("Add ICS subscription") }
    model.icsSubscriptions.value.forEach { subscription ->
        Text(subscription.name)
        TextButton(onClick = { removeIcsId = subscription.id }, enabled = !model.icsBusy.value) {
            Text("Remove this subscription")
        }
    }
    if (model.icsMessage.value.isNotBlank()) Text(model.icsMessage.value,
        style = MaterialTheme.typography.bodySmall)
    if (model.haPending.value.isNotEmpty()) {
        Text("Pending Home Assistant changes", style = MaterialTheme.typography.titleMedium)
        Text("Creations, edits and removals stay encrypted on this device. HAM rechecks the calendar before sending. An uncertain request is never resent automatically.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = model::syncHaPending, enabled = !model.haBusy.value) {
            Text("Check pending Home Assistant changes")
        }
        model.haPending.value.forEach { pending ->
            val calendar = model.calendars.value.find { it.id == pending.calendarId }
            Text("${pending.operation.name.lowercase().replaceFirstChar { it.uppercase() }} · ${calendar?.name ?: "Unavailable calendar"} · ${pending.title} · ${pending.state.name.lowercase().replace('_', ' ')}",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { discardHaId = pending.id }, enabled = !model.haBusy.value) {
                Text("Discard this pending change")
            }
            if (pending.state == com.tinkercobble.ham.data.HaPendingState.AWAITING_CONFIRMATION) {
                TextButton(onClick = { retryHaId = pending.id }, enabled = !model.haBusy.value) {
                    Text("Review and retry this change")
                }
            }
        }
    }
    if (model.haMessage.value.isNotBlank()) Text(model.haMessage.value,
        style = MaterialTheme.typography.bodySmall)
    if (model.davPending.value.isNotEmpty()) {
        Text("Pending calendar changes", style = MaterialTheme.typography.titleMedium)
        Text("Changes stay encrypted on this device until the calendar server confirms them. HAM retries queued changes when Android allows network work, even while the app is closed. Conflicts stay for your review.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = model::syncDavPending, enabled = !model.davBusy.value) {
            Text("Synchronize pending changes")
        }
        model.davPending.value.forEach { pending ->
            val calendar = model.calendars.value.find { it.id == pending.calendarId }
            Text("${pending.operation.name.lowercase().replaceFirstChar { it.uppercase() }} · ${calendar?.name ?: "Unavailable calendar"} · ${pending.state.name.lowercase()}",
                style = MaterialTheme.typography.bodySmall)
            if (pending.operation == DavOperation.CREATE && pending.state == DavPendingState.CONFLICT)
                Text("HAM could not safely confirm this creation. Check the calendar for a matching event or an incomplete server read before discarding it or creating another.",
                    style = MaterialTheme.typography.bodySmall)
            if (pending.canReview) TextButton(onClick = {
                if (pending.operation == DavOperation.DELETE) model.reviewDavDeleteConflict(pending.id)
                else model.reviewDavConflict(pending.id)
            }, enabled = !model.davBusy.value) {
                Text(if (pending.operation == DavOperation.DELETE) "Review removal conflict"
                    else "Review title conflict")
            }
            if (pending.canReviewDetails) TextButton(onClick = {
                model.reviewDavDetailsConflict(pending.id)
            }, enabled = !model.davBusy.value) { Text("Review detail conflict") }
            TextButton(onClick = { discardDavId = pending.id }, enabled = !model.davBusy.value) {
                Text("Discard this pending change")
            }
        }
    }
    model.davConflictReview.value?.let { review ->
        AlertDialog(onDismissRequest = model::dismissDavConflictReview,
            title = { Text("Review calendar title conflict") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(review.calendarName)
                Text("Original: ${review.originalTitle}")
                Text("Server now: ${review.serverTitle}")
                Text("Your title: ${review.proposedTitle}")
                Text("Apply your title to the current server event? HAM will retain its other current details and will only write if it has not changed since this review.")
            } },
            confirmButton = { Button(onClick = { model.applyDavConflictReview(review.id) },
                enabled = !model.davBusy.value) { Text("Apply my title") } },
            dismissButton = { TextButton(onClick = model::dismissDavConflictReview) {
                Text("Keep pending") } })
    }
    model.davDetailsConflictReview.value?.let { review ->
        AlertDialog(onDismissRequest = model::dismissDavDetailsConflictReview,
            title = { Text("Review calendar detail conflict") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${review.eventTitle} · ${review.calendarName}")
                Text("Original location: ${review.originalLocation.ifBlank { "(empty)" }}")
                Text("Server location: ${review.serverLocation.ifBlank { "(empty)" }}")
                Text("Your location: ${review.proposedLocation.ifBlank { "(empty)" }}")
                Text("Original description: ${review.originalDescription.ifBlank { "(empty)" }}")
                Text("Server description: ${review.serverDescription.ifBlank { "(empty)" }}")
                Text("Your description: ${review.proposedDescription.ifBlank { "(empty)" }}")
                Text("Apply your location and description to the current server event? HAM will retain its other current fields and write only if the resource has not changed since this review.")
            } },
            confirmButton = { Button(onClick = { model.applyDavDetailsConflictReview(review.id) },
                enabled = !model.davBusy.value) { Text("Apply my details") } },
            dismissButton = { TextButton(onClick = model::dismissDavDetailsConflictReview) {
                Text("Keep pending") } })
    }
    model.davDeleteConflictReview.value?.let { review ->
        AlertDialog(onDismissRequest = model::dismissDavDeleteConflictReview,
            title = { Text("Review calendar removal conflict") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(review.calendarName)
                Text(review.eventTitle)
                Text("The server's event content still matches the copy HAM saved when you requested removal, but its ETag changed. Remove this exact item now? HAM will check it again and use a conditional request. If its content changes, the removal stays pending.")
            } },
            confirmButton = { Button(onClick = { model.applyDavDeleteConflict(review.id) },
                enabled = !model.davBusy.value) { Text("Remove this item") } },
            dismissButton = { TextButton(onClick = model::dismissDavDeleteConflictReview) {
                Text("Keep pending") } })
    }
    if (model.davMessage.value.isNotBlank()) Text(model.davMessage.value,
        style = MaterialTheme.typography.bodySmall)
    davKind?.let { kind -> DavConnectDialog(kind, model, onClose = { davKind = null }) }
    if (icsConnectOpen) IcsConnectDialog(model, onClose = { icsConnectOpen = false })
    removeIcsId?.let { id -> AlertDialog(onDismissRequest = { removeIcsId = null },
        title = { Text("Remove ICS subscription from HAM?") },
        text = { Text("HAM will remove this feed address, its encrypted local access details, and its cached events. Other calendars remain intact. The remote calendar is not changed.") },
        confirmButton = { Button(onClick = { model.removeIcs(id); removeIcsId = null }) {
            Text("Remove") } },
        dismissButton = { TextButton(onClick = { removeIcsId = null }) { Text("Cancel") } }) }
    removeDavId?.let { id -> AlertDialog(onDismissRequest = { removeDavId = null },
        title = { Text("Remove calendar account from HAM?") },
        text = { Text("HAM will remove this account's encrypted local credential, loaded events, and pending calendar changes. Other accounts stay intact. Revoke an iCloud app-specific password separately in your Apple Account if needed.") },
        confirmButton = { Button(onClick = { model.removeDav(id); removeDavId = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removeDavId = null }) { Text("Cancel") } }) }
    discardDavId?.let { id -> AlertDialog(onDismissRequest = { discardDavId = null },
        title = { Text("Discard pending calendar change?") },
        text = { Text("HAM will remove only this unsynchronized change from this device. A change already accepted by a calendar server cannot be undone here.") },
        confirmButton = { Button(onClick = { model.discardDavPending(id); discardDavId = null }) {
            Text("Discard") } },
        dismissButton = { TextButton(onClick = { discardDavId = null }) { Text("Cancel") } }) }
    discardHaId?.let { id -> AlertDialog(onDismissRequest = { discardHaId = null },
        title = { Text("Discard pending Home Assistant change?") },
        text = { Text("HAM will remove only its encrypted pending copy. A request that already reached Home Assistant may still take effect; discarding here cannot undo it.") },
        confirmButton = { Button(onClick = { model.discardHaPending(id); discardHaId = null }) {
            Text("Discard") } },
        dismissButton = { TextButton(onClick = { discardHaId = null }) { Text("Cancel") } }) }
    retryHaId?.let { id ->
        val pending = model.haPending.value.firstOrNull { it.id == id }
        if (pending != null && pending.state ==
            com.tinkercobble.ham.data.HaPendingState.AWAITING_CONFIRMATION) AlertDialog(onDismissRequest = { retryHaId = null },
            title = { Text("Retry this Home Assistant change?") },
            text = { Text(buildString {
                append(model.calendars.value.find { it.id == pending.calendarId }?.name ?: "Unavailable calendar")
                append("\n\n")
                append(if (pending.operation == com.tinkercobble.ham.data.HaOperation.CREATE)
                    "Proposed new event: " else "Original event: ")
                append(pending.title)
                append(" · ")
                append(com.tinkercobble.ham.data.CalendarDisplay.dateTime(pending.originalStart))
                append(" – ")
                append(com.tinkercobble.ham.data.CalendarDisplay.dateTime(pending.originalEnd))
                if (pending.originalLocation.isNotBlank()) append("\nLocation: ${pending.originalLocation}")
                if (pending.originalDescription.isNotBlank()) append("\nDescription: ${pending.originalDescription}")
                when (pending.operation) {
                    com.tinkercobble.ham.data.HaOperation.CREATE -> Unit
                    com.tinkercobble.ham.data.HaOperation.UPDATE -> pending.target?.let { target ->
                        append("\n\nProposed edit: ")
                        append(target.summary)
                        append(" · ")
                        append(com.tinkercobble.ham.data.CalendarDisplay.dateTime(target.start))
                        append(" – ")
                        append(com.tinkercobble.ham.data.CalendarDisplay.dateTime(target.end))
                        if (target.location != pending.originalLocation)
                            append("\nLocation: ${target.location.ifBlank { "(cleared)" }}")
                        if (target.description != pending.originalDescription)
                            append("\nDescription: ${target.description.ifBlank { "(cleared)" }}")
                    }
                    com.tinkercobble.ham.data.HaOperation.DELETE -> append("\n\nProposed: remove this event")
                }
                append("\n\nHAM did not receive a reliable result from the earlier request. Check the calendar in Home Assistant first. If you continue, HAM will recheck the same account, calendar permission and matching events before sending again. A delayed earlier request could still take effect.")
            }, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { Button(onClick = {
                model.retryUncertainHaPending(id); retryHaId = null
            }, enabled = !model.haBusy.value) { Text("Retry after review") } },
            dismissButton = { TextButton(onClick = { retryHaId = null }) { Text("Cancel") } })
    }
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    model.authRequest.value?.let { auth ->
        AlertDialog(onDismissRequest = model::cancelAuth,
            title = { Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AndroidView(factory = { deviceContext -> ImageView(deviceContext).apply {
                    setImageResource(R.mipmap.ic_launcher)
                    contentDescription = "HAM icon"
                } }, modifier = Modifier.size(48.dp))
                Text("Connect Home Agenda Manager (HAM)", style = MaterialTheme.typography.titleLarge)
            } }, text = { Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You're about to allow Home Agenda Manager to connect to your Home Assistant instance.")
                Text("HAM communicates directly with Home Assistant. Authorization tokens and cached calendar data are encrypted in HAM's private storage on this device.")
                Text("Your password is entered on Home Assistant's own sign-in page. HAM does not store your password or send your Home Assistant credentials or calendar data to TinkerCobble.")
                if (!auth.usesPkce) Text("This Home Assistant version does not advertise PKCE S256. HAM will use its compatible sign-in flow and validate the one-time response. Your current connection stays active until sign-in succeeds.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }, confirmButton = { Button(onClick = {
                login.launch(Intent(context, HomeAssistantLoginActivity::class.java)
                    .putExtra(HomeAssistantLoginActivity.EXTRA_AUTHORIZE_URL, auth.authorizeUrl)
                    .putExtra(HomeAssistantLoginActivity.EXTRA_REDIRECT_URI, auth.redirect))
            }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = model::cancelAuth) { Text("Cancel") } })
    }
    if (removeLocalOpen) AlertDialog(onDismissRequest = { removeLocalOpen = false },
        title = { Text("Remove HAM local calendar?") },
        text = { Text("HAM will remove only its own local Android calendar, and only if it has no events. Other calendars and events stay unchanged.") },
        confirmButton = { Button(onClick = {
            removeLocalOpen = false
            model.removeEmptyLocalCalendar()
        }) { Text("Remove empty calendar") } },
        dismissButton = { TextButton(onClick = { removeLocalOpen = false }) { Text("Cancel") } })
    if (showHttpWarning) AlertDialog(onDismissRequest = { showHttpWarning = false },
        title = { Text("Unencrypted local connection") },
        text = { Text("HTTP traffic is not protected by HTTPS. Continue only if you trust this local network.") },
        confirmButton = { Button(onClick = { showHttpWarning = false; model.beginAuth(address) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { showHttpWarning = false }) { Text("Cancel") } })
    if (!model.connected.value) TextButton(onClick = { privacyOpen = true }) { Text("Privacy & Connection") }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Updates", style = MaterialTheme.typography.titleMedium)
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Install verified updates automatically on Wi-Fi", modifier = Modifier.weight(1f))
            Switch(checked = autoInstallUpdates, onCheckedChange = {
                autoInstallUpdates = it
                AutoUpdateScheduler.setEnabled(context, it)
            })
        }
        Text("HAM installs when it is in the background. Android may still ask for approval. " +
            "Automatic installation needs Allow from this source enabled for HAM.",
            style = MaterialTheme.typography.bodySmall)
    } else {
        Text("HAM checks for updates automatically. This Android version requires approval to install them.",
            style = MaterialTheme.typography.bodySmall)
    }
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
    }
}

@Composable
private fun DavConnectDialog(kind: DavKind, model: HamViewModel, onClose: () -> Unit) {
    var address by remember(kind) { mutableStateOf(if (kind == DavKind.ICLOUD) DavConnection.ICLOUD_URL else "") }
    var username by remember(kind) { mutableStateOf("") }
    var secret by remember(kind) { mutableStateOf("") }
    fun close() { secret = ""; onClose() }
    AlertDialog(onDismissRequest = ::close,
        title = { Text(if (kind == DavKind.ICLOUD) "Connect iCloud Calendar" else "Connect CalDAV calendar") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (kind == DavKind.ICLOUD) Text("Use an app-specific password created in your Apple Account. Do not enter your main Apple Account password.",
                style = MaterialTheme.typography.bodySmall)
            else OutlinedTextField(address, { address = it }, label = { Text("HTTPS CalDAV server address") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(username, { username = it }, label = { Text("Account email or username") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(secret, { secret = it }, label = { Text("App-specific password") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("HAM checks the server directly over HTTPS and encrypts the credential on this device.",
                style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { Button(onClick = {
            model.connectDav(kind, address, username, secret, ::close)
        }, enabled = username.isNotBlank() && secret.isNotBlank() &&
            (kind == DavKind.ICLOUD || address.isNotBlank()) && !model.davBusy.value) { Text("Verify and save") } },
        dismissButton = { TextButton(onClick = ::close) { Text("Cancel") } })
}

@Composable
private fun IcsConnectDialog(model: HamViewModel, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    fun close() { password = ""; address = ""; onClose() }
    AlertDialog(onDismissRequest = ::close, title = { Text("Add ICS subscription") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Enter the feed address on this device. HAM checks it directly over HTTPS and stores it encrypted. Some private feeds use a token in their URL; optional username and password are for feeds that require Basic authentication.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it }, label = { Text("Calendar name") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(address, { address = it }, label = { Text("HTTPS ICS URL") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(username, { username = it }, label = { Text("Username (optional)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text("Password (optional)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("Subscriptions are read-only. HAM does not follow redirects carrying a private feed URL.",
                style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { Button(onClick = {
            model.connectIcs(name, address, username, password, ::close)
        }, enabled = name.isNotBlank() && address.isNotBlank() &&
            (username.isBlank() == password.isBlank()) && !model.icsBusy.value) {
            Text("Verify and save")
        } }, dismissButton = { TextButton(onClick = ::close) { Text("Cancel") } })
}

@Composable
private fun ManageCalendarsPage(model: HamViewModel, onBack: () -> Unit, focusId: String? = null) {
    var query by remember(focusId) { mutableStateOf(focusId.orEmpty()) }
    var colorTarget by remember { mutableStateOf<CalendarSource?>(null) }
    var diagnosticsOpen by remember { mutableStateOf(false) }
    BackHandler(diagnosticsOpen) { diagnosticsOpen = false }
    if (diagnosticsOpen) {
        CalendarDiagnosticsPage(model, onBack = { diagnosticsOpen = false })
        return
    }
    val prefs = model.calendarPreferences.value
    val possibleOverlaps = com.tinkercobble.ham.data.CalendarSourceOverview
        .possibleNameGroups(model.calendars.value)
    LazyColumn(Modifier.fillMaxSize()) {
        item { Column {
        TextButton(onClick = onBack) { Text("‹ Settings") }
        Text("Calendars", style = MaterialTheme.typography.headlineMedium)
        Text("Visibility, colors, favorites, and your Quick Add default are stored on this device.",
            style = MaterialTheme.typography.bodySmall)
        if (model.calendars.value.isEmpty()) Text("No calendars discovered. Connect a source in Settings to review its calendars here.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { diagnosticsOpen = true }) { Text("Calendar Connection Diagnostics") }
        SavedGroupsSection(model)
        if (model.androidIdentityReviewNeeded.value) {
            Text("An Android calendar changed or could not be identified. HAM cleared its saved choices to avoid showing or writing to a different calendar. Review your selections and Quick Add default. Your calendar events were not changed.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = model::acknowledgeAndroidIdentityReview) {
                Text("I reviewed my calendars")
            }
        }
        Text(com.tinkercobble.ham.data.CalendarSourceOverview.counts(model.calendars.value).label(),
            style = MaterialTheme.typography.bodySmall)
        if (possibleOverlaps.isNotEmpty()) {
            Text("${possibleOverlaps.size} possible same-name group${if (possibleOverlaps.size == 1) "" else "s"}. Names alone do not prove the calendars are identical. HAM keeps every source and choice separate.",
                style = MaterialTheme.typography.bodySmall)
            possibleOverlaps.take(5).forEach { group ->
                val providers = group.groupingBy { it.provider }.eachCount().entries
                    .joinToString(", ") { "${it.value} ${it.key.name.replace('_', ' ')}" }
                Text("${group.first().name.trim()}: ${group.size} separate calendars ($providers)",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (possibleOverlaps.size > 5) Text("Search the remaining names below to review their sources.",
                style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(query, { query = it }, label = { Text("Search name, source, or entity ID") },
            modifier = Modifier.fillMaxWidth())
        Text("All calendars visibility", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { model.setAllCalendarsVisible(true) }) { Text("Show all") }
            TextButton(onClick = { model.setAllCalendarsVisible(false) }) { Text("Hide all") }
        }
        if (prefs.defaultId != null) TextButton(onClick = {
            model.setCalendarPreferences(prefs.copy(defaultId = null))
        }) { Text("Clear Quick Add default") }
        } }
        val matches = CalendarSelection.search(model.calendars.value, query)
        items(matches, key = { it.id }) { source ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = model.isCalendarVisible(source), onCheckedChange = { visible ->
                            model.setCalendarVisible(source, visible)
                        })
                        Text(source.name, fontWeight = FontWeight.SemiBold)
                    }
                    Text("Source: ${source.source} · ${if (source.canCreate) "Writable" else "Read-only"}",
                        style = MaterialTheme.typography.bodySmall)
                    Text(source.id, style = MaterialTheme.typography.bodySmall)
                    Text("Color: ${prefs.colors[source.id] ?: source.haColor ?: "No color supplied by source"}",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { colorTarget = source }) { Text("Color") }
                        TextButton(onClick = {
                            model.setCalendarPreferences(prefs.copy(favorites =
                                if (source.id in prefs.favorites) prefs.favorites - source.id else prefs.favorites + source.id))
                        }) { Text(if (source.id in prefs.favorites) "★ Favorite" else "☆ Favorite") }
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
    colorTarget?.let { source ->
        val choices = listOf("#007F86", "#B7602A", "#385EA7", "#7753A5", "#3D7E4B", "#B64052")
        AlertDialog(onDismissRequest = { colorTarget = null }, title = { Text("${source.name} color") },
            text = { Column {
                Text("HAM only. The source calendar is not changed.")
                choices.chunked(3).forEach { row -> Row { row.forEach { color ->
                    Surface(onClick = {
                        model.setCalendarPreferences(model.calendarPreferences.value.setColor(source.id, color))
                        colorTarget = null
                    }, color = Color(android.graphics.Color.parseColor(color)),
                        modifier = Modifier.size(52.dp).padding(4.dp), shape = MaterialTheme.shapes.medium) {}
                } } }
                TextButton(onClick = {
                    model.setCalendarPreferences(model.calendarPreferences.value.setColor(source.id, null))
                    colorTarget = null
                }) { Text("Reset to source color") }
            } }, confirmButton = { TextButton(onClick = { colorTarget = null }) { Text("Close") } })
    }
}

@Composable
private fun SavedGroupsSection(model: HamViewModel) {
    var editing by remember { mutableStateOf<com.tinkercobble.ham.data.SavedCalendarGroup?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<com.tinkercobble.ham.data.SavedCalendarGroup?>(null) }
    var error by remember { mutableStateOf(false) }
    val state = model.savedGroups.value
    Text("Saved calendar groups", style = MaterialTheme.typography.titleMedium)
    Text("Choose a group to show only its calendars in Today and Calendar. Group choices do not change Find Time availability or source calendars.",
        style = MaterialTheme.typography.bodySmall)
    if (model.savedGroupStorageError.value) Text("Saved groups could not be opened or saved on this device.",
        color = MaterialTheme.colorScheme.error)
    TextButton(onClick = { model.setActiveSavedGroup(null) },
        enabled = !model.savedGroupStorageError.value) {
        Text(if (state.activeId == null) "✓ All calendars" else "All calendars")
    }
    Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
    state.groups.forEach { group ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { model.setActiveSavedGroup(group.id) },
                enabled = !model.savedGroupStorageError.value, modifier = Modifier.weight(1f)) {
                Text((if (state.activeId == group.id) "✓ " else "") + group.name +
                    " (${group.members.size})", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { editing = group; editorOpen = true; error = false }) { Text("Edit") }
            TextButton(onClick = { removing = group }) { Text("Remove") }
        }
    }
    }
    OutlinedButton(onClick = { editing = null; editorOpen = true; error = false },
        enabled = model.calendars.value.isNotEmpty() && !model.savedGroupStorageError.value &&
            state.groups.size < 20) { Text("Create group") }
    if (editorOpen) {
        var name by remember(editing?.id, editorOpen) { mutableStateOf(editing?.name.orEmpty()) }
        var selected by remember(editing?.id, editorOpen) {
            val available = model.calendars.value.map { it.id }.toSet()
            mutableStateOf(editing?.members?.map { it.sourceId }?.toSet().orEmpty() intersect available)
        }
        AlertDialog(onDismissRequest = { editorOpen = false },
            title = { Text(if (editing == null) "Create calendar group" else "Edit calendar group") },
            text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Group name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Select only the calendars this group should display. New calendars are never added automatically.",
                    style = MaterialTheme.typography.bodySmall)
                model.calendars.value.forEach { source ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(source.id in selected, onCheckedChange = { checked ->
                            selected = if (checked) selected + source.id else selected - source.id
                        })
                        Column { Text(source.name); Text(source.source, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (error) Text("Could not save this group. Check its name and selected calendars.",
                    color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { Button(onClick = {
                val available = model.calendars.value.map { it.id }.toSet()
                if (model.saveSavedGroup(name, selected intersect available, editing?.id))
                    editorOpen = false else error = true
            }, enabled = name.isNotBlank() && selected.isNotEmpty()) { Text("Save group") } },
            dismissButton = { TextButton(onClick = { editorOpen = false }) { Text("Cancel") } })
    }
    removing?.let { group -> AlertDialog(onDismissRequest = { removing = null },
        title = { Text("Remove saved group?") },
        text = { Text("The group will be removed from HAM. Source calendars and events stay intact.") },
        confirmButton = { Button(onClick = {
            if (!model.removeSavedGroup(group.id)) error = true
            removing = null
        }) { Text("Remove group") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }) }
}

@Composable
private fun CalendarDiagnosticsPage(model: HamViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val sources = model.calendars.value
    val revision = model.readDiagnosticsRevision.intValue
    var query by remember { mutableStateOf("") }
    var history by remember { mutableStateOf<Map<String, CalendarReadDiagnostic>>(emptyMap()) }
    LaunchedEffect(sources, revision) {
        history = withContext(Dispatchers.IO) {
            runCatching {
                val store = CalendarReadDiagnosticStore(context)
                sources.associate { it.id to store.read(it.id) }
            }.getOrDefault(emptyMap())
        }
    }
    val dateTime = remember { DateTimeFormatter.ofPattern("MMM d, h:mm a")
        .withZone(ZoneId.systemDefault()) }
    val matches = CalendarSelection.search(sources, query)
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("‹ Calendars") }
        Text("Calendar Connection Diagnostics", style = MaterialTheme.typography.headlineSmall)
        Text("Status is recorded on this device for each calendar. Loaded event counts cover HAM's current date range; they are not server totals.",
            style = MaterialTheme.typography.bodySmall)
        if (sources.isEmpty()) Text("No calendars discovered yet. Connect a source in Settings, then return to review its read status.",
            style = MaterialTheme.typography.bodySmall)
        if (model.readDiagnosticsUnavailable.value) Text("Recent read status could not be saved.",
            color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = model::refresh, enabled = sources.isNotEmpty() && !model.loading.value) {
            Text("Refresh selected calendars")
        }
        OutlinedTextField(query, { query = it }, label = { Text("Search calendars") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        LazyColumn {
            items(matches, key = { it.id }) { source ->
                val diagnostic = history[source.id]
                val selected = model.isCalendarVisible(source)
                val considered = source.id in model.availabilityIds.value
                val failed = source.id in model.failedCalendars.value
                val cached = model.events.value.count { it.calendarId == source.id }
                val pending = model.haPending.value.count { it.calendarId == source.id } +
                    model.davPending.value.count { it.calendarId == source.id }
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(source.name, style = MaterialTheme.typography.titleMedium)
                    Text("${source.provider.name.replace('_', ' ')} · ${if (selected) "Shown" else "Hidden"} · ${if (considered) "Counts as busy" else "Not used for Find Time"} · ${if (source.canCreate) "Writable" else "Read-only"}")
                    Text("Read: " + when {
                        !selected && !considered -> "Not checked while hidden"
                        source.provider == CalendarProviderKind.ANDROID && !model.androidPermissionGranted.value -> "Android permission required"
                        failed || diagnostic?.latestAttemptSucceeded == false -> "Needs attention"
                        diagnostic?.latestAttemptSucceeded == true -> "Last attempt succeeded"
                        else -> "No read result recorded"
                    })
                    Text("Last successful read: ${diagnostic?.lastSuccess?.let(dateTime::format) ?: "Not recorded"}")
                    if (diagnostic?.lastError != null) Text("Last issue: ${diagnostic.lastError.label}" +
                        (diagnostic.lastErrorAt?.let { " · ${dateTime.format(it)}" } ?: ""))
                    Text("Loaded events: $cached · Pending changes: $pending")
                }
                HorizontalDivider()
            }
            if (matches.isEmpty() && sources.isNotEmpty()) item { Text("No matching calendars.") }
        }
    }
}

@Composable
private fun PrivacyConnectionPage(model: HamViewModel, onBack: () -> Unit) {
    var pendingAction by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var widgetDetails by remember { mutableStateOf(HamAgendaWidget.detailsEnabled(context)) }
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
        Text("Calendar diagnostics", style = MaterialTheme.typography.titleMedium)
        Text(com.tinkercobble.ham.data.CalendarSourceOverview.counts(model.calendars.value).label())
        Text("Android calendar access: " + when {
            !model.androidPermissionGranted.value -> "read permission off"
            !model.androidEnabled.value -> "read permission on; connection off"
            model.androidWritePermissionGranted.value -> "read and write permissions on"
            else -> "read permission on; write permission off"
        })
        Text("Configured CalDAV/iCloud accounts: ${model.davAccounts.value.size}; ICS feeds: ${model.icsSubscriptions.value.size}")
        Text("Calendar reads needing attention: ${model.failedCalendars.value.size}; pending Home Assistant changes: ${model.haPending.value.size}; pending CalDAV/iCloud changes: ${model.davPending.value.size}")
        Text("These counts describe HAM's current device view. A granted Android permission does not make every calendar writable, and a cached source does not prove that its server is reachable.",
            style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("On this device", style = MaterialTheme.typography.titleMedium)
        Text("HAM stores your server address and encrypted authorization tokens in private app storage. Android app backup is off.")
        Text("HAM keeps an encrypted copy of loaded calendars and events for offline viewing. Local unscheduled tasks use separate encrypted device storage; Home Assistant tasks stay in memory. Android app backup is off.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = widgetDetails, onCheckedChange = { enabled ->
                runCatching { HamAgendaWidget.setDetailsEnabled(context, enabled) }
                    .onSuccess { widgetDetails = enabled }
                    .onFailure { model.message.value = "Widget preference could not be saved" }
            })
            Text("Show selected event titles and times on the home-screen widget")
        }
        Text("The widget shows cached counts by default. If details are enabled, anyone who can see your home screen can read up to three selected events.",
            style = MaterialTheme.typography.bodySmall)
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
                "clear" -> "This clears loaded calendars, events, Home Assistant tasks, and the offline calendar copy on this device. Local unscheduled tasks and Home Assistant records stay intact."
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
