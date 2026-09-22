package org.neurox.patient

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Blue = Color(0xFF416A48)
private val Ink = Color(0xFF1E2B22)
private val SafeGreen = Color(0xFF4D9654)

// ──────────────────────────────────────────────────────────────
// Sync banner (Phase 5: pending count + last-synced label)
// ──────────────────────────────────────────────────────────────

@Composable
internal fun SyncBanner(
    state: SyncState,
    pendingCount: Int = 0,
    failedCount: Int = 0,
    lastSyncedLabel: String? = null,
    onRetry: () -> Unit
) {
    // Synced state: show a brief confirmation label if available, then nothing.
    if (state == SyncState.Synced && lastSyncedLabel == null) return
    val (bg, message) = when (state) {
        SyncState.Loading -> Color(0xFFE8F0FF) to "Loading your NeuroX data…"
        SyncState.Synced  -> Color(0xFFE8F5EE) to "Synced · $lastSyncedLabel"
        SyncState.Offline -> Color(0xFFFFF0ED) to (
            if (pendingCount > 0)
                "Working offline · $pendingCount item${if (pendingCount == 1) "" else "s"} saved — will sync when connected"
            else
                "Working offline · Showing saved activities"
        )
        SyncState.Error   -> Color(0xFFFFF0ED) to if (failedCount > 0) "$failedCount saved records need attention. Ask your caregiver for help." else "Could not sync your data"
    }
    Surface(color = bg, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, modifier = Modifier.weight(1f), color = Ink)
            if (state != SyncState.Loading && state != SyncState.Synced)
                TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Home screen — now wires the voice button
// ──────────────────────────────────────────────────────────────

@Composable
internal fun Home(
    modifier: Modifier,
    patientName: String,
    reminders: List<ReminderItem>,
    activities: List<ActivityItem>,
    history: List<ActivityHistoryItem>,
    onStart: (ActivityItem) -> Unit,
    onReminders: () -> Unit,
    onActivities: () -> Unit,
) = Column(
    modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp)
) {
    Text("Hello, ${patientName.substringBefore(' ').ifBlank { "there" }}!", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink)
    Text("Take things at your own pace today.", fontSize = 17.sp, color = Color(0xFF6C786E))

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE5F0E1)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Blue, shape = RoundedCornerShape(16.dp), modifier = Modifier.size(58.dp)) {
                Icon(Icons.Default.Favorite, null, tint = Color.White, modifier = Modifier.padding(14.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Today's gentle plan", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("A short activity and your reminders are ready when you are.", color = Color(0xFF4D5B50), fontSize = 15.sp)
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Upcoming reminders", modifier = Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.Bold)
        TextButton(onClick = onReminders) { Text("View all") }
    }
    val upcoming = reminders.filter { it.enabled && !it.completed && it.status !in setOf("missed", "done") }
        .sortedBy { it.snoozedUntil ?: it.scheduledTime }.take(2)
    if (upcoming.isEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = Blue)
                Spacer(Modifier.width(12.dp))
                Text("You're all caught up. Your next reminders will appear here.", color = Color(0xFF4D5B50))
            }
        }
    } else {
        upcoming.forEach { reminder ->
            HomeReminderCard(reminder)
        }
    }

    val recentIds = history.filter { it.completedAt != null }.sortedByDescending { it.completedAt }
        .map { it.activityId }.distinct().take(2)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (recentIds.isEmpty()) "Try an activity" else "Recently played", modifier = Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.Bold)
        TextButton(onClick = onActivities) { Text("View all") }
    }
    val recent = recentIds.mapNotNull { id -> activities.firstOrNull { it.id == id } }
        .ifEmpty { activities.take(2) }
    recent.forEach { activity ->
        HomeActivityCard(activity = activity, onClick = { onStart(activity) })
    }
}

@Composable
private fun HomeReminderCard(reminder: ReminderItem) = Card(
    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFEFB)),
    modifier = Modifier.fillMaxWidth(),
) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = Color(0xFFFFF1E6), shape = RoundedCornerShape(14.dp), modifier = Modifier.size(50.dp)) {
            Icon(Icons.Default.Notifications, null, tint = Color(0xFFB65A38), modifier = Modifier.padding(13.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(reminder.title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text(displayReminderTime(reminder.snoozedUntil ?: reminder.scheduledTime), color = Color(0xFF59665C), fontSize = 14.sp)
        }
        Text(if (reminder.status == "snoozed") "Snoozed" else "Upcoming", color = Blue, fontSize = 13.sp)
    }
}

@Composable
private fun HomeActivityCard(activity: ActivityItem, onClick: () -> Unit) = Card(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    colors = CardDefaults.cardColors(containerColor = Color.White),
    shape = RoundedCornerShape(22.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFDDE7D9)),
) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.PlayCircle, null, tint = Blue, modifier = Modifier.size(38.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(activity.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(activity.description, color = Color(0xFF59665C), fontSize = 15.sp)
        }
        Icon(Icons.Default.ChevronRight, "Open activity", tint = Blue)
    }
}

private fun displayReminderTime(value: String): String = runCatching {
    java.time.OffsetDateTime.parse(value)
        .atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
}.getOrElse { value.take(16).replace("T", " ") }

// ──────────────────────────────────────────────────────────────
// Activities screen
// ──────────────────────────────────────────────────────────────

@Composable
internal fun Activities(modifier: Modifier, activities: List<ActivityItem>, onStart: (ActivityItem) -> Unit) =
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Activities", fontSize = 31.sp, fontWeight = FontWeight.Bold)
        Text("Choose one activity for today.", color = Color.Gray, fontSize = 17.sp)
        if (activities.isEmpty()) Text("No activities are available right now.", color = Color.Gray)
        activities.forEach { activity ->
            ActivityCard(
                title = activity.title,
                detail = activity.description,
                icon = when (activity.id) {
                    "memory-match"  -> Icons.Default.GridView
                    "object-recall" -> Icons.Default.Visibility
                    else            -> Icons.Default.Extension
                },
                level = "Level ${activity.difficulty}"
            ) { onStart(activity) }
        }
    }

// ──────────────────────────────────────────────────────────────
// Memory Match game
// ──────────────────────────────────────────────────────────────

@Composable
internal fun MemoryMatch(
    modifier: Modifier,
    patientName: String,
    onInterrupted: () -> Unit,
    onFinished: (Int, Float, Float) -> Unit,
) {
    val startedAt = rememberSaveable { System.currentTimeMillis() }
    var first by rememberSaveable { mutableStateOf<Int?>(null) }
    var matched by rememberSaveable { mutableStateOf(listOf<Int>()) }
    var attempts by rememberSaveable { mutableIntStateOf(0) }
    val symbols = listOf("☀", "☀", "☕", "☕")
    val complete = matched.size == 4
    ActivityFrame(modifier, "Memory Match", "Find the two pairs. Take all the time you need.", onInterrupted) {
        LinearProgressIndicator(progress = { matched.size / 4f }, modifier = Modifier.fillMaxWidth())
        Text(if (complete) "Well done, $patientName!" else "Find the matching pictures.", fontSize = 19.sp, color = Color.Gray)
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            (0..1).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    (0..1).forEach { col ->
                        val index = row * 2 + col
                        val revealed = index in matched || index == first
                        Card(
                            modifier = Modifier.weight(1f).height(125.dp).clickable(enabled = !complete && index !in matched) {
                                if (first == null) first = index
                                else {
                                    attempts++
                                    val selected = first!!
                                    if (symbols[selected] == symbols[index] && selected != index) matched = matched + selected + index
                                    first = null
                                }
                            },
                            colors = CardDefaults.cardColors(containerColor = if (revealed) Color(0xFFE8F0FF) else Blue),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(if (revealed) symbols[index] else "?", fontSize = 46.sp, color = if (revealed) Ink else Color.White)
                            }
                        }
                    }
                }
            }
        }
        if (complete) {
            val responseSeconds = ((System.currentTimeMillis() - startedAt) / 1000f).coerceAtLeast(.1f)
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5EE)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CheckCircle, null, tint = SafeGreen, modifier = Modifier.size(38.dp))
                    Text("Activity complete", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("Attempts: $attempts · Time: ${"%.1f".format(responseSeconds)} sec", color = Color.DarkGray, textAlign = TextAlign.Center)
                    Text("Your next activity is adjusted to your performance.", color = Color.DarkGray, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                    Button(onClick = { onFinished(attempts, responseSeconds, 1f) }, modifier = Modifier.padding(top = 10.dp)) { Text("Continue") }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Object Recall game
// ──────────────────────────────────────────────────────────────

@Composable
internal fun ObjectRecall(
    modifier: Modifier,
    patientName: String,
    onInterrupted: () -> Unit,
    onFinished: (Int, Float, Float) -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var result by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val startedAt = rememberSaveable { System.currentTimeMillis() }
    val objects = listOf("☕ Cup", "🔑 Key", "📚 Book")
    ActivityFrame(modifier, "Remember the Objects", "Look at three familiar objects, then choose one you saw.", onInterrupted) {
        LinearProgressIndicator(progress = { if (step == 0) .35f else .75f }, modifier = Modifier.fillMaxWidth())
        if (step == 0) {
            Text("Please look at these objects.", fontSize = 20.sp, color = Color.Gray)
            objects.forEach { objectName ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(objectName, modifier = Modifier.padding(19.dp), fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth().height(65.dp), shape = RoundedCornerShape(18.dp)) {
                Text("I am ready", fontSize = 19.sp)
            }
        } else if (result == null) {
            Text("Which object did you see?", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("Choose the cup.", color = Color.Gray, fontSize = 17.sp)
            listOf("☕ Cup" to true, "🌼 Flower" to false, "🚲 Bicycle" to false).forEach { (choice, correct) ->
                OutlinedButton(
                    onClick = { result = correct },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text(choice, fontSize = 20.sp) }
            }
        } else {
            val responseSeconds = ((System.currentTimeMillis() - startedAt) / 1000f).coerceAtLeast(.1f)
            Card(
                colors = CardDefaults.cardColors(containerColor = if (result == true) Color(0xFFE8F5EE) else Color(0xFFFFF0ED)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (result == true) Icons.Default.CheckCircle else Icons.Default.Info, null, tint = if (result == true) SafeGreen else Color(0xFFB65A38), modifier = Modifier.size(42.dp))
                    Text(if (result == true) "Well done, $patientName!" else "That is okay. Thank you for trying.", fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 9.dp))
                    Text("Response time: ${"%.1f".format(responseSeconds)} sec", color = Color.DarkGray, modifier = Modifier.padding(top = 6.dp))
                    Text("Your next activity is adjusted to your performance.", color = Color.DarkGray, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    Button(onClick = { onFinished(1, responseSeconds, if (result == true) 1f else 0f) }, modifier = Modifier.padding(top = 14.dp)) { Text("Continue") }
                }
            }
        }
    }
}

private data class ChoiceActivityContent(
    val title: String,
    val introduction: String,
    val studyText: String,
    val question: String,
    val choices: List<String>,
    val answer: Int,
)

private val choiceActivities = mapOf(
    "pattern" to ChoiceActivityContent(
        "Pattern Completion", "Look at a simple pattern and choose what comes next.",
        "2  ·  4  ·  6  ·  ?", "What comes next?", listOf("8", "5", "10"), 0,
    ),
    "sequence-recall" to ChoiceActivityContent(
        "Sequence Recall", "Remember the order of three familiar things.",
        "Tea  →  Key  →  Book", "Which item was in the middle?", listOf("Book", "Key", "Tea"), 1,
    ),
    "daily-routine" to ChoiceActivityContent(
        "Daily Routine Recall", "Choose a helpful order for an everyday morning routine.",
        "Think about getting ready in the morning.", "Which order makes sense?",
        listOf("Wake up, wash, have breakfast", "Have breakfast, sleep, wake up", "Wash, sleep, wake up"), 0,
    ),
    "story-recall" to ChoiceActivityContent(
        "Story Recall", "Read a short everyday story, then remember one detail.",
        "Rina went to the market in the morning and brought home oranges.",
        "What did Rina bring home?", listOf("Oranges", "A book", "Flowers"), 0,
    ),
)

@Composable
internal fun GuidedChoiceActivity(
    modifier: Modifier,
    activityId: String,
    patientName: String,
    onInterrupted: () -> Unit,
    onFinished: (Int, Float, Float) -> Unit,
) {
    val content = choiceActivities.getValue(activityId)
    var studying by rememberSaveable(activityId) { mutableStateOf(true) }
    var attempts by rememberSaveable(activityId) { mutableIntStateOf(0) }
    var feedback by rememberSaveable(activityId) { mutableStateOf<String?>(null) }
    var complete by rememberSaveable(activityId) { mutableStateOf(false) }
    val startedAt = rememberSaveable(activityId) { System.currentTimeMillis() }
    ActivityFrame(modifier, content.title, content.introduction, onInterrupted) {
        LinearProgressIndicator(
            progress = { if (complete) 1f else if (studying) .45f else .75f },
            modifier = Modifier.fillMaxWidth(),
        )
        if (studying) {
            Text(content.studyText, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
            Button(onClick = { studying = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("I am ready", fontSize = 19.sp)
            }
        } else if (!complete) {
            Text(content.question, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            content.choices.forEachIndexed { index, choice ->
                OutlinedButton(
                    onClick = {
                        attempts++
                        if (index == content.answer) {
                            complete = true
                            feedback = "That fits. Well done, $patientName!"
                        } else {
                            feedback = "That is okay. Look again and choose when you are ready."
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text(choice, fontSize = 19.sp, textAlign = TextAlign.Center) }
            }
            feedback?.let { Text(it, color = if (complete) SafeGreen else Color.DarkGray, fontSize = 18.sp) }
        } else {
            val responseSeconds = ((System.currentTimeMillis() - startedAt) / 1000f).coerceAtLeast(.1f)
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5EE)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CheckCircle, "Activity complete", tint = SafeGreen, modifier = Modifier.size(42.dp))
                    Text(checkNotNull(feedback), fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("You completed this activity at your own pace.", color = Color.DarkGray, textAlign = TextAlign.Center)
                    Button(
                        onClick = { onFinished(attempts.coerceAtLeast(1), responseSeconds, (1f / attempts.coerceAtLeast(1)).coerceAtLeast(.25f)) },
                        modifier = Modifier.padding(top = 14.dp).heightIn(min = 56.dp),
                    ) { Text("Continue") }
                }
            }
        }
    }
}

@Composable
private fun ActivityFrame(
    modifier: Modifier,
    title: String,
    introduction: String,
    onInterrupted: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var opening by rememberSaveable(title) { mutableIntStateOf(0) }
    var paused by rememberSaveable(title) { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(title, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        when {
            opening == 0 -> {
                Text(introduction, fontSize = 20.sp, color = Color.DarkGray)
                Text("There is no countdown and no penalty. You can pause whenever you want.", fontSize = 17.sp)
                Button(onClick = { opening = 1 }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Try a practice step", fontSize = 19.sp) }
            }
            opening == 1 -> {
                Text("Practice", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Tap the button below. That is all you need to do to make a choice.", fontSize = 19.sp)
                Button(onClick = { opening = 2 }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Start activity", fontSize = 19.sp) }
            }
            paused -> {
                Text("Activity paused", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Your place is saved. Continue whenever you feel ready.", fontSize = 19.sp)
                Button(onClick = { paused = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Continue") }
            }
            else -> {
                OutlinedButton(
                    onClick = { paused = true; onInterrupted() },
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Icon(Icons.Default.Pause, "Pause activity"); Spacer(Modifier.width(8.dp)); Text("Pause") }
                content()
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Reminders screen
// ──────────────────────────────────────────────────────────────

@Composable
internal fun Reminders(
    modifier: Modifier,
    reminders: List<ReminderItem>,
    onComplete: (String) -> Unit,
    onSnooze: (String) -> Unit,
) =
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Today's schedule", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("Your reminders, in a simple order.", color = Color(0xFF6C786E), fontSize = 17.sp)
        if (reminders.isEmpty()) Text("No reminders for today.", color = Color.Gray)
        else reminders.forEach { reminder ->
            Row(verticalAlignment = Alignment.Top) {
                Text(displayReminderTime(reminder.snoozedUntil ?: reminder.scheduledTime), modifier = Modifier.width(76.dp).padding(top = 17.dp), fontWeight = FontWeight.SemiBold, color = Ink)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(26.dp).padding(top = 18.dp)) {
                    Surface(color = if (reminder.completed) SafeGreen else Blue, shape = RoundedCornerShape(50), modifier = Modifier.size(14.dp)) {}
                    Spacer(Modifier.height(72.dp))
                }
                Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFEFB))) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Notifications, null, tint = if (reminder.completed) SafeGreen else Blue)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(reminder.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                Text(reminder.type.replaceFirstChar { it.uppercase() }, color = Color.Gray, fontSize = 14.sp)
                            }
                            Text(if (reminder.completed) "Done" else reminder.status.replaceFirstChar { it.uppercase() },
                                color = if (reminder.completed) SafeGreen else Blue, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                        if (!reminder.completed && reminder.status != "missed") {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = { onSnooze(reminder.id) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                                    Text("Snooze")
                                }
                                Button(onClick = { onComplete(reminder.id) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                                    Text("Done")
                                }
                            }
                        }
                        if (reminder.type == "medication") {
                            Text("Schedule reminder only — no dosage advice or consumption confirmation.", color = Color.DarkGray, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

// ──────────────────────────────────────────────────────────────
// Safety screen
// ──────────────────────────────────────────────────────────────

@Composable
internal fun Safety(modifier: Modifier, safety: SafetyState?, onHelp: () -> Unit, onSos: () -> Unit) = Column(
    modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp)
) {
    Text("Safety", fontSize = 31.sp, fontWeight = FontWeight.Bold)
    StatusCard(safety?.status ?: "Safety status unavailable", "Last reported status", Icons.Default.Shield, Blue)
    val expectedReturn = safety?.settings?.expectedReturnAt?.let { value ->
        runCatching {
            java.time.OffsetDateTime.parse(value).atZoneSameInstant(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT))
        }.getOrDefault("Unavailable")
    } ?: "Not set"
    StatusCard("Expected return", expectedReturn, Icons.Default.Schedule, Blue)
    OutlinedButton(onClick = onHelp, modifier = Modifier.fillMaxWidth().height(62.dp)) {
        Icon(Icons.Default.Phone, null)
        Spacer(Modifier.width(10.dp))
        Text("I Need Help", fontSize = 18.sp)
    }
    Button(onClick = onSos, modifier = Modifier.fillMaxWidth().height(70.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBD3E39))) {
        Icon(Icons.Default.Warning, null)
        Spacer(Modifier.width(10.dp))
        Text("SOS", fontSize = 22.sp)
    }
    safety?.contacts?.take(2)?.forEach { contact ->
        StatusCard(contact.name, "${contact.relationship} · ${contact.phone}", Icons.Default.Phone, Blue)
    }
}

// ──────────────────────────────────────────────────────────────
// Profile screen — shows language capability
// ──────────────────────────────────────────────────────────────

@Composable
internal fun Profile(
    modifier: Modifier,
    patientName: String,
    patientAge: Int?,
    languageConfig: LanguageConfig,
    privacy: PrivacyState = PrivacyState(),
    caregivers: List<CaregiverAccess> = emptyList(),
    privacyBusy: Boolean = false,
    privacyMessage: String? = null,
    onLocationSharingChanged: (Boolean) -> Unit = {},
    onRevokeCaregiver: (String) -> Unit = {},
    onConsentChanged: (String, Boolean) -> Unit = { _, _ -> },
) = Column(
    modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
) {
    Text("My Profile", fontSize = 31.sp, fontWeight = FontWeight.Bold)
    val ageLabel = patientAge?.let { "$it years · " } ?: ""
    StatusCard(patientName.ifBlank { "Profile unavailable" }, "${ageLabel}Preferred language: ${languageConfig.languageName}", Icons.Default.Person, Blue)

    // Language capability card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F5FA)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Language, null, tint = Blue, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Language & Voice", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
            Text(languageConfig.languageName, fontSize = 15.sp, color = Ink)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CapabilityChip("Speech", languageConfig.speechSupported)
                CapabilityChip("Voice guides", languageConfig.ttsSupported)
            }
            if (languageConfig.ttsFallbackNote != null) {
                Text(languageConfig.ttsFallbackNote, fontSize = 13.sp, color = Color(0xFF7A6A3A))
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F5FA)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Privacy & sharing", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("You can stop location sharing or remove caregiver access at any time.", fontSize = 13.sp, color = Color(0xFF657189))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Share my location", Modifier.weight(1f), color = Ink)
                Switch(
                    checked = privacy.locationSharingEnabled,
                    onCheckedChange = onLocationSharingChanged,
                    enabled = !privacyBusy,
                )
            }
            Text(
                if (privacy.locationSharingEnabled) "Location sharing is on." else "Location sharing is off. No new locations are shared.",
                fontSize = 13.sp, color = if (privacy.locationSharingEnabled) Color(0xFF218567) else Color(0xFF657189),
            )
            Text("Caregivers with access", fontWeight = FontWeight.SemiBold, color = Ink)
            if (caregivers.isEmpty()) Text("No caregiver currently has access.", fontSize = 13.sp, color = Color(0xFF657189))
            caregivers.forEach { caregiver ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(caregiver.name, fontWeight = FontWeight.SemiBold)
                        Text(caregiver.email, fontSize = 12.sp, color = Color(0xFF657189))
                    }
                    TextButton(onClick = { onRevokeCaregiver(caregiver.id) }, enabled = !privacyBusy) {
                        Text("Revoke access")
                    }
                }
            }
            Text("Other consent choices", fontWeight = FontWeight.SemiBold, color = Ink)
            listOf(
                "voice_recording" to "Store voice recordings",
                "notifications" to "Send caregiver notifications",
                "personalization" to "Personalize activities",
                "cognitive_screening" to "Allow research cognitive estimates",
            ).forEach { (purpose, label) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), fontSize = 14.sp)
                    Switch(
                        checked = privacy.consents[purpose]?.granted == true,
                        onCheckedChange = { onConsentChanged(purpose, it) },
                        enabled = !privacyBusy,
                    )
                }
            }
            privacyMessage?.let { Text(it, fontSize = 13.sp, color = Color(0xFF657189)) }
        }
    }
}

@Composable
private fun CapabilityChip(label: String, supported: Boolean) {
    val bg = if (supported) Color(0xFFD4EDDA) else Color(0xFFFFF3CD)
    val text = if (supported) Color(0xFF218567) else Color(0xFF7A6A3A)
    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(
            "$label: ${if (supported) "✓ Available" else "⚠ Fallback"}",
            fontSize = 13.sp,
            color = text,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

// ──────────────────────────────────────────────────────────────
// Shared UI components
// ──────────────────────────────────────────────────────────────

@Composable
private fun ActivityCard(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, level: String, onClick: () -> Unit = {}) =
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(19.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Blue, modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) { Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold); Text(detail, color = Color.Gray) }
            Text(level, color = Blue, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }

@Composable
private fun ReminderCard(title: String, time: String, status: String, color: Color, onClick: () -> Unit = {}) =
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Notifications, null, tint = color)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text(time, color = Color.Gray) }
            Text(status, color = color, fontWeight = FontWeight.SemiBold)
        }
    }

@Composable
private fun StatusCard(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) =
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F5FA)), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color)
            Spacer(Modifier.width(12.dp))
            Column { Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold); Text(detail, color = Color.DarkGray) }
        }
    }
