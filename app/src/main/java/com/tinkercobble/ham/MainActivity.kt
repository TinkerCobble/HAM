package com.tinkercobble.ham

import android.os.Bundle
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import com.tinkercobble.ham.auth.HomeAssistantAuth
import com.tinkercobble.ham.auth.ConnectionPolicy
import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.CalendarSelection
import com.tinkercobble.ham.data.CalendarView
import com.tinkercobble.ham.data.CalendarDisplay
import com.tinkercobble.ham.data.DateRange
import com.tinkercobble.ham.ui.HamViewModel
import com.tinkercobble.ham.ui.HamTheme
import com.tinkercobble.ham.updates.AvailableUpdate
import com.tinkercobble.ham.updates.UpdateService
import com.tinkercobble.ham.updates.UpdateSessionGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptShare(intent)
    }
}

private val pages = listOf("Today", "Calendar", "Quick Add", "Tasks", "Search", "Settings", "About")
private val dayFormat = DateTimeFormatter.ofPattern("EEE, MMM d")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HamApp(model: HamViewModel = viewModel(), sharedScreenshot: Uri?, clearShare: () -> Unit) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(if (model.connected.value) "Today" else "Settings") }
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
            while (true) {
                val now = java.time.ZonedDateTime.now()
                val until = java.time.Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone))
                    .toMillis().coerceAtLeast(1000)
                delay(until)
                model.updateActualToday()
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
                        availableUpdate = it
                        showUpdatePrompt = true
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
                            if (!model.connected.value && page !in listOf("Settings", "Screenshot", "Quick Add")) {
                                Text("Connect to Home Assistant to see your calendar.")
                                TextButton(onClick = { page = "Settings" }) { Text("Open Settings") }
                            } else when (page) {
                                "Today" -> TodayPage(model, ::openCalendars)
                                "Calendar" -> CalendarPage(model, calendarScroll, ::openCalendars)
                                "Quick Add" -> QuickAddPage(model, onScreenshot = {
                                    screenshotPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }, onResume = { screenshotUri = null; page = "Screenshot" })
                                "Screenshot" -> ScreenshotPage(model, screenshotUri,
                                    onCancel = { screenshotUri = null; page = "Quick Add" },
                                    onSaved = { screenshotUri = null; model.activeAgenda.value = "Calendar"; page = "Calendar" })
                                "Tasks" -> TasksPage(model)
                                "Search" -> SearchPage(model, ::openCalendars)
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
                                    (destination == "More" && page in listOf("Tasks", "Search", "Settings", "About", "Calendars")),
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
    listOf("Tasks" to "Open items from Home Assistant", "Search" to "Find an event",
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
private fun AboutPage() {
    Text("About", style = MaterialTheme.typography.headlineMedium)
    Text("${if (BuildConfig.FLAVOR == "dev") "Experimental DEV" else "Stable"} · ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
    Text("Package: ${BuildConfig.APPLICATION_ID}", style = MaterialTheme.typography.bodySmall)
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
private fun TodayPage(model: HamViewModel, openCalendars: (String?) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(CalendarDisplay.titleForDay(model.selectedDate.value, model.actualToday.value),
            style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = { openCalendars(null) }) { Text("Calendars") }
    }
    DayPicker(model)
    RefreshStatus(model)
    AgendaContent(model, listOf(model.selectedDate.value), openCalendars, Modifier.fillMaxSize(), false)
}

@Composable
private fun CalendarPage(model: HamViewModel, scroll: androidx.compose.foundation.lazy.LazyListState,
    openCalendars: (String?) -> Unit) {
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
    RefreshStatus(model)
    AgendaContent(model, range.days, openCalendars, Modifier.fillMaxSize(), model.calendarView.value == CalendarView.MONTH,
        scroll)
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
    modifier: Modifier, month: Boolean, scroll: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState()) {
    var selected by remember { mutableStateOf<AgendaEvent?>(null) }
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
                events.forEach { event -> item(key = "${day}-${event.identity}") { EventCard(event, model) { selected = event } } }
            }
        }
    }
    selected?.let { event -> EventDetails(event, model.calendars.value.find { it.id == event.calendarId }, model,
        close = { selected = null }, manage = { id -> selected = null; openCalendars(id) }) }
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
                val events = if (inMonth) CalendarDisplay.onDay(model.visibleEvents, day) else emptyList()
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
                            events.take(3).forEach { event ->
                                Surface(color = eventColor(event, model), shape = MaterialTheme.shapes.small,
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
private fun EventCard(event: AgendaEvent, model: HamViewModel, open: () -> Unit) {
    val stripe = eventColor(event, model)
    Card(Modifier.fillMaxWidth().clickable(onClick = open)) {
        Row(Modifier.fillMaxWidth()) {
            Surface(color = stripe, modifier = Modifier.size(width = 5.dp, height = 76.dp)) {}
            Column(Modifier.padding(12.dp)) {
                Text(event.summary, fontWeight = FontWeight.SemiBold)
                Text(event.calendarName, style = MaterialTheme.typography.bodySmall)
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
private fun EventList(events: List<AgendaEvent>, model: HamViewModel, openCalendars: (String?) -> Unit) {
    var selected by remember { mutableStateOf<AgendaEvent?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(events, key = { it.identity }) { event -> EventCard(event, model) { selected = event } }
    }
    selected?.let { event -> EventDetails(event, model.calendars.value.find { it.id == event.calendarId }, model,
        close = { selected = null }, manage = { id -> selected = null; openCalendars(id) }) }
}

@Composable
private fun EventDetails(event: AgendaEvent, source: CalendarSource?, model: HamViewModel,
    close: () -> Unit, manage: (String) -> Unit) {
    var title by remember(event.identity) { mutableStateOf(event.summary) }
    val editable = source?.canUpdate == true && event.uid != null && event.recurrenceId == null
    val attachmentId = remember(event.identity) {
        Regex("ham-attachment://([0-9a-f-]{36})").find(event.description)?.groupValues?.get(1)
    }
    val context = LocalContext.current
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
            if (editable) OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") })
            else Text(event.summary, style = MaterialTheme.typography.titleMedium)
            Text("Calendar: ${event.calendarName}")
            Text("Integration: ${source?.source ?: "Not provided by Home Assistant"}", style = MaterialTheme.typography.bodySmall)
            SelectionContainer { Text("Entity ID: ${event.calendarId}", style = MaterialTheme.typography.bodySmall) }
            Text(if (event.allDay) "${event.date.format(dayFormat)} · All day"
                else "${CalendarDisplay.dateTime(event.start)} – ${CalendarDisplay.dateTime(event.end)}")
            if (event.location.isNotBlank() && !event.location.equals("null", ignoreCase = true)) Text(event.location)
            if (event.description.isNotBlank() && !event.description.equals("null", ignoreCase = true)) {
                Text("Description and provenance notes", style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(event.description) }
            }
            if (attachmentId != null) {
                Text("Original screenshot", style = MaterialTheme.typography.labelMedium)
                attachmentImage?.let { Image(it.asImageBitmap(), "Original event screenshot",
                    Modifier.fillMaxWidth().heightIn(max = 280.dp)) }
                if (attachmentError.isNotBlank()) Text(attachmentError, color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = { manage(event.calendarId) }) { Text("Manage this calendar") }
            if (!editable) Text("This event can't be edited here.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        if (editable && source != null && title != event.summary)
            Button(onClick = { model.edit(source, event, title, close) }) { Text("Save change") }
        else TextButton(onClick = close) { Text("Close") }
    }, dismissButton = { if (editable) TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
private fun QuickAddPage(model: HamViewModel, onScreenshot: () -> Unit, onResume: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("Quick Add", style = MaterialTheme.typography.headlineMedium)
    OutlinedButton(onClick = onScreenshot) { Text("Add from Screenshot") }
    if (com.tinkercobble.ham.data.ScreenshotDraftStore(LocalContext.current).load() != null)
        TextButton(onClick = onResume) { Text("Resume screenshot draft") }
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
internal fun CalendarPicker(model: HamViewModel, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
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
private fun SearchPage(model: HamViewModel, openCalendars: (String?) -> Unit) {
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
    EventList(model.events.value.filter { (includeHidden || it.calendarId !in model.calendarPreferences.value.hidden) &&
        query.isNotBlank() && (it.summary.contains(query, true) || it.description.contains(query, true) || it.location.contains(query, true)) },
        model, openCalendars)
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
        ManageCalendarsPage(model, onBack = { manageOpen = false })
        return
    }
    if (privacyOpen) {
        PrivacyConnectionPage(model, onBack = { privacyOpen = false })
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Connection", style = MaterialTheme.typography.headlineMedium)
    if (model.connected.value) {
        Text(if (BuildConfig.DEBUG && model.demoPreview.value)
            "Demo preview — no Home Assistant connection" else "Connected to ${model.server.value}")
        Button(onClick = model::refresh) { Text("Refresh") }
        OutlinedButton(onClick = { privacyOpen = true }) { Text("Privacy & Connection") }
        Text("${model.calendars.value.size} calendars available", Modifier.padding(top = 12.dp))
        OutlinedButton(onClick = { manageOpen = true }) { Text("Calendars") }
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
    }
}

@Composable
private fun ManageCalendarsPage(model: HamViewModel, onBack: () -> Unit, focusId: String? = null) {
    var query by remember(focusId) { mutableStateOf(focusId.orEmpty()) }
    var colorTarget by remember { mutableStateOf<CalendarSource?>(null) }
    val prefs = model.calendarPreferences.value
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("‹ Settings") }
        Text("Calendars", style = MaterialTheme.typography.headlineMedium)
        Text("Visibility, colors, favorites, and your Quick Add default are stored on this device.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(query, { query = it }, label = { Text("Search name, source, or entity ID") },
            modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { model.setCalendarPreferences(prefs.showAll(model.calendars.value)) }) { Text("Show all") }
            TextButton(onClick = { model.setCalendarPreferences(prefs.hideAll(model.calendars.value)) }) { Text("Hide all") }
        }
        val matches = CalendarSelection.search(model.calendars.value, query)
        LazyColumn {
            items(matches, key = { it.id }) { source ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = source.id !in prefs.hidden, onCheckedChange = { visible ->
                            model.setCalendarPreferences(prefs.copy(hidden = if (visible) prefs.hidden - source.id
                                else prefs.hidden + source.id))
                        })
                        Text(source.name, fontWeight = FontWeight.SemiBold)
                    }
                    Text("Source: ${source.source} · ${if (source.canCreate) "Writable" else "Read-only"}",
                        style = MaterialTheme.typography.bodySmall)
                    Text(source.id, style = MaterialTheme.typography.bodySmall)
                    Text("Color: ${prefs.colors[source.id] ?: source.haColor ?: "No color supplied by Home Assistant"}",
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
    }
    colorTarget?.let { source ->
        val choices = listOf("#007F86", "#B7602A", "#385EA7", "#7753A5", "#3D7E4B", "#B64052")
        AlertDialog(onDismissRequest = { colorTarget = null }, title = { Text("${source.name} color") },
            text = { Column {
                Text("HAM only. Home Assistant is not changed.")
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
                }) { Text("Reset to Home Assistant color") }
            } }, confirmButton = { TextButton(onClick = { colorTarget = null }) { Text("Close") } })
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
