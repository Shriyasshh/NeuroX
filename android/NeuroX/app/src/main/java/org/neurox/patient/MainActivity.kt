package org.neurox.patient

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        setContent { NeuroXApp() }
    }
}

private val Blue = Color(0xFF416A48)

// ──────────────────────────────────────────────────────────────
// Root application composable
// ──────────────────────────────────────────────────────────────

@Composable
fun NeuroXApp() {
    val auth: PatientAuthViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val state by auth.state.collectAsState()
    MaterialTheme(colorScheme = lightColorScheme(primary = Blue, background = Color(0xFFF4F8F1), surface = Color(0xFFFFFEFB))) {
        when {
            state.checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            !state.signedIn -> PatientSignInScreen(state, auth::signIn)
            else -> PatientApp(auth.repository)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PatientApp(repository: PatientRepository) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showVoiceScreen by rememberSaveable { mutableStateOf(false) }
    val viewModel: PatientViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = repository.patientId(),
        factory = PatientViewModel.factory(repository),
    )
    val ui by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.refresh() }
    val context = LocalContext.current
    val locationScope = rememberCoroutineScope()
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) ReminderScheduler.scheduleAll(context, ui.reminders) }
    LaunchedEffect(ui.reminders) { ReminderScheduler.scheduleAll(context, ui.reminders) }
    LaunchedEffect(ui.reminders.isNotEmpty()) {
        if (ui.reminders.isNotEmpty() && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showVoiceScreen = true else viewModel.reportError()
    }
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            viewModel.setLocationSharing(true)
            locationScope.launch {
                DeviceLocationCapture(context).current()?.let(viewModel::publishLocation)
            }
        } else viewModel.reportError()
    }

    fun changeLocationSharing(enabled: Boolean) {
        if (!enabled) {
            viewModel.setLocationSharing(false)
            return
        }
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            viewModel.setLocationSharing(true)
            locationScope.launch {
                DeviceLocationCapture(context).current()?.let(viewModel::publishLocation)
            }
        } else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    val speechProvider = remember(repository) {
        buildSpeechProvider(
            context,
            demoMode = false,
            remoteTranscriber = repository::transcribeSpeech,
        )
    }
    val languageCode = remember(ui.preferredLanguage) { languageNameToCode(ui.preferredLanguage) }
    val languageConfig = remember(languageCode, speechProvider) {
        languageConfigFor(languageCode).copy(
            speechSupported = languageConfigFor(languageCode).speechSupported &&
                speechProvider.isSupported(languageCode)
        )
    }

    if (showVoiceScreen) {
        VoiceListeningScreen(
            modifier = Modifier.fillMaxSize(),
            speechProvider = speechProvider,
            languageConfig = languageConfig,
            patientName = ui.patientName,
            activities = ui.activities,
            reminders = ui.reminders,
            onStartActivity = { activityId ->
                showVoiceScreen = false
                viewModel.start(
                    ui.activities.find { it.id == activityId }
                        ?: ui.activities.firstOrNull()
                        ?: defaultActivities.first()
                )
            },
            onNavigateToSafety = {
                showVoiceScreen = false
                tab = 4
            },
            onClose = { showVoiceScreen = false },
        )
        return
    }

    Scaffold(
        topBar = {
            if (ui.activeActivity == null) {
                TopAppBar(
                    title = {
                        Image(
                            painter = painterResource(R.drawable.neurox_logo),
                            contentDescription = "NeuroX",
                            modifier = Modifier.size(64.dp),
                            contentScale = ContentScale.Fit,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF4F8F1)),
                    actions = {
                        FilledIconButton(
                            onClick = { tab = 5 },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFDDEBD9), contentColor = Blue),
                        ) {
                            Icon(Icons.Default.Person, "Open profile")
                        }
                    },
                )
            }
        },
        bottomBar = {
            PatientBottomNavigation(
                selectedTab = tab,
                onNavigate = { destination ->
                    tab = destination
                    viewModel.leaveActivity()
                },
                onOpenVoice = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        showVoiceScreen = true
                    } else {
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SyncBanner(
                ui.syncState,
                pendingCount = ui.pendingCount,
                failedCount = ui.failedCount,
                lastSyncedLabel = ui.lastSyncedLabel,
                onRetry = viewModel::retrySync,
            )
            when {
                ui.activeActivity == "memory-match" -> MemoryMatch(
                    Modifier.weight(1f),
                    patientName = ui.patientName,
                    onInterrupted = viewModel::recordInterruption,
                    onFinished = { attempts, responseTime, accuracy ->
                        viewModel.finish("memory-match", accuracy, responseTime, attempts)
                        tab = 1
                    },
                )
                ui.activeActivity == "object-recall" -> ObjectRecall(
                    Modifier.weight(1f),
                    patientName = ui.patientName,
                    onInterrupted = viewModel::recordInterruption,
                    onFinished = { attempts, responseTime, accuracy ->
                        viewModel.finish("object-recall", accuracy, responseTime, attempts)
                        tab = 1
                    },
                )
                ui.activeActivity in setOf("pattern", "sequence-recall", "daily-routine", "story-recall") -> GuidedChoiceActivity(
                    modifier = Modifier.weight(1f),
                    activityId = checkNotNull(ui.activeActivity),
                    patientName = ui.patientName,
                    onInterrupted = viewModel::recordInterruption,
                    onFinished = { attempts, responseTime, accuracy ->
                        val activityId = checkNotNull(ui.activeActivity)
                        viewModel.finish(activityId, accuracy, responseTime, attempts)
                        tab = 1
                    },
                )
                tab == 0 -> Home(
                    Modifier.weight(1f),
                    patientName = ui.patientName,
                    reminders = ui.reminders,
                    activities = ui.activities,
                    history = ui.history,
                    onStart = viewModel::start,
                    onReminders = { tab = 3 },
                    onActivities = { tab = 1 },
                )
                tab == 1 -> Activities(
                    Modifier.weight(1f), activities = ui.activities, onStart = viewModel::start
                )
                tab == 3 -> Reminders(
                    Modifier.weight(1f),
                    reminders = ui.reminders,
                    onComplete = viewModel::completeReminder,
                    onSnooze = viewModel::snoozeReminder,
                )
                tab == 4 -> Safety(
                    Modifier.weight(1f),
                    safety = ui.safety,
                    onHelp = { viewModel.sendHelp("I need help. Please check on me.") },
                    onSos = { viewModel.sendHelp() },
                )
                else -> Profile(Modifier.weight(1f), patientName = ui.patientName,
                    patientAge = ui.patientAge, languageConfig = languageConfig,
                    privacy = ui.privacy, caregivers = ui.caregivers,
                    privacyBusy = ui.privacyBusy, privacyMessage = ui.privacyMessage,
                    onLocationSharingChanged = ::changeLocationSharing,
                    onRevokeCaregiver = viewModel::revokeCaregiver,
                    onConsentChanged = viewModel::setConsent)
            }
        }
    }
}

@Composable
private fun PatientBottomNavigation(
    selectedTab: Int,
    onNavigate: (Int) -> Unit,
    onOpenVoice: () -> Unit,
) {
    NavigationBar(containerColor = Color(0xFFFFFEFB), tonalElevation = 5.dp) {
        BottomNavItem("Home", Icons.Default.Home, selectedTab == 0) { onNavigate(0) }
        BottomNavItem("Activities", Icons.Default.Favorite, selectedTab == 1) { onNavigate(1) }
        // Scaffold measures its bottom bar with the available screen height.
        // A fillMaxHeight child expands the entire bar and consumes the content area.
        Box(Modifier.weight(1f).height(80.dp), contentAlignment = Alignment.Center) {
            FloatingActionButton(
                onClick = onOpenVoice,
                modifier = Modifier.offset(y = (-10).dp),
                containerColor = Blue,
                contentColor = Color.White,
            ) { Icon(Icons.Default.Mic, "Speak to NeuroX") }
        }
        BottomNavItem("Reminders", Icons.Default.Notifications, selectedTab == 3) { onNavigate(3) }
        BottomNavItem("SOS", Icons.Default.Warning, selectedTab == 4) { onNavigate(4) }
    }
}

@Composable
private fun RowScope.BottomNavItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, label) },
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Blue,
            selectedTextColor = Blue,
            indicatorColor = Color(0xFFDDEBD9),
        ),
    )
}
