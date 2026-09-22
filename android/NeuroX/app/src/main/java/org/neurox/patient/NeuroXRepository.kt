package org.neurox.patient

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import com.google.gson.reflect.TypeToken
import java.util.UUID
import java.time.Instant

class NeuroXRepository internal constructor(
    private val sessions: SecureSessionStore,
    private val local: PatientLocalDataSource,
    private val remote: PatientRemoteDataSource,
    private val enqueueSync: () -> Unit,
) : PatientRepository {
    private val gson = com.google.gson.Gson()
    private val offlineDatabase get() = local.database
    private val api get() = remote.api

    fun hasSession(): Boolean = sessions.read()?.accessToken != null
    fun configuredServer(): String = sessions.read()?.server ?: BuildConfig.DEFAULT_API_URL

    suspend fun signIn(server: String, email: String, password: String) = withContext(Dispatchers.IO) {
        val address = validatedServerAddress(server, BuildConfig.DEBUG)
        val previous = sessions.read()
        require(previous == null || previous.server == address) {
            "This device is linked to a different server. Use its original server to preserve offline records."
        }
        val auth = patientApi(address).login(LoginRequest(email.trim(), password))
        require(auth.user.role == "PATIENT") { "Use a patient account. Caregivers sign in on the web." }
        val cached = offlineDatabase.offlineCacheDao().patient()
        require((previous == null || previous.patientId == auth.user.id) && (cached == null || cached.id == auth.user.id)) {
            "This device belongs to another patient. Sign in with that patient's account."
        }
        require(previous != null || (cached == null && offlineDatabase.pendingSyncDao().countAll() == 0)) {
            "Offline records need recovery before this device can connect to a server."
        }
        val profile = patientApi(address).patientWithToken("Bearer ${auth.accessToken}")
        require(profile.id == auth.user.id) { "The patient profile does not match this account." }
        sessions.save(PatientSession(address, auth.user.id, auth.accessToken, auth.refreshToken))
    }

    override suspend fun load(): RemoteData = withContext(Dispatchers.IO) {
        check(hasSession()) { "Please sign in first." }
        val outcome = syncPendingEvents()
        if (outcome is SyncOutcome.Retry) throw java.io.IOException(outcome.reason)
        val fresh = remote.load()
        local.save(fresh)
        // Screens always observe the same representation that is available offline.
        local.load() ?: fresh
    }

    override suspend fun cachedData(): RemoteData? = local.load()

    override suspend fun markReminderComplete(reminderId: String) = api.updateReminder(reminderId, mapOf("status" to "done"))
    override suspend fun markReminderCompletedLocally(reminderId: String) = withContext(Dispatchers.IO) {
        offlineDatabase.offlineCacheDao().markReminderCompleted(reminderId)
        offlineDatabase.checkpointBackup()
    }
    override suspend fun updateReminderState(reminderId: String, status: String, snoozedUntil: String?) =
        api.updateReminder(reminderId, mapOf("status" to status, "snoozed_until" to snoozedUntil))
    override suspend fun updateReminderStateLocally(reminderId: String, status: String, snoozedUntil: String?) = withContext(Dispatchers.IO) {
        offlineDatabase.offlineCacheDao().updateReminderState(reminderId, status == "done", status, snoozedUntil)
        offlineDatabase.checkpointBackup()
    }
    override suspend fun queueReminderState(reminderId: String, status: String, snoozedUntil: String?) =
        queueReminderChange(reminderId, mapOf("status" to status, "snoozed_until" to snoozedUntil), status, snoozedUntil)
    override suspend fun startActivity(activity: ActivityItem, eventId: String, startedAt: String, offline: Boolean) = api.startActivity(activity.id, ActivityStartRequest(userId = patientId(), difficultyLevel = activity.difficulty, startedAt = startedAt, eventId = eventId, offlineCreated = offline, contentVersion = activity.contentVersion))
    override suspend fun completeActivity(activity: ActivityItem, request: ActivityCompletionRequest) = api.completeActivity(activity.id, request)
    override suspend fun queueActivityCompletion(request: ActivityCompletionRequest) = queue(
        SyncEventRequest(request.eventId, "activity_completion", patientId(), gson.fromJson(gson.toJson(request), object : TypeToken<Map<String, Any>>() {}.type))
    )
    override suspend fun queueReminderUpdate(reminderId: String) =
        queueReminderChange(reminderId, mapOf("completed" to true), "done", null)
    override suspend fun queueLocation(request: LocationUpdateRequest) = queue(
        SyncEventRequest(UUID.randomUUID().toString(), "location_update", patientId(), gson.fromJson(gson.toJson(request), object : TypeToken<Map<String, Any>>() {}.type))
    )
    override suspend fun publishLocation(request: LocationUpdateRequest) = api.location(patientId(), request)
    override suspend fun queueSos(request: SosRequest, eventId: String) = queue(
        SyncEventRequest(eventId, "sos_event", patientId(), gson.fromJson(gson.toJson(request), object : TypeToken<Map<String, Any>>() {}.type))
    )
    override suspend fun sendSos(request: SosRequest) = api.sos(patientId(), request)
    override suspend fun privacy() = api.privacy()
    override suspend fun caregivers() = api.caregivers()
    override suspend fun setLocationSharing(enabled: Boolean) = api.setLocationSharing(LocationSharingRequest(enabled))
    override suspend fun revokeCaregiver(caregiverId: String) = api.revokeCaregiver(caregiverId)
    override suspend fun setConsent(purpose: String, granted: Boolean) = api.setConsent(ConsentUpdateRequest(purpose, granted))
    override suspend fun transcribeSpeech(audioBase64: String, languageCode: String) =
        api.transcribeSpeech(SpeechTranscriptionRequest(audioBase64, languageCode))
    suspend fun loadSafety() = api.safety(patientId())
    override fun schedulePendingSync() = enqueueSync()
    override suspend fun retryFailedSync() = withContext(Dispatchers.IO) {
        offlineDatabase.pendingSyncDao().retryDeadLetters()
        enqueueSync()
    }
    private suspend fun queue(event: SyncEventRequest) = withContext(Dispatchers.IO) {
        offlineDatabase.pendingSyncDao().insert(
            PendingSyncEntity(
                eventId = event.eventId,
                eventType = event.eventType,
                patientId = event.patientId,
                payloadJson = gson.toJson(event.payload),
                createdAt = System.currentTimeMillis(),
                status = "pending",
                schemaVersion = event.schemaVersion,
                deviceTime = event.deviceTime ?: Instant.now().toString(),
                origin = event.origin,
            )
        )
        offlineDatabase.checkpointBackup()
        enqueueSync()
    }

    /** One Room transaction prevents a reminder's local state and outbox event diverging. */
    private suspend fun queueReminderChange(
        reminderId: String,
        changes: Map<String, Any?>,
        status: String,
        snoozedUntil: String?,
    ) = withContext(Dispatchers.IO) {
        val event = SyncEventRequest(
            UUID.randomUUID().toString(), "reminder_update", patientId(),
            mapOf("reminder_id" to reminderId, "changes" to changes.filterValues { it != null }),
            deviceTime = Instant.now().toString(),
        )
        offlineDatabase.withTransaction {
            offlineDatabase.offlineCacheDao().updateReminderState(
                reminderId, status == "done", status, snoozedUntil
            )
            offlineDatabase.pendingSyncDao().insert(
                PendingSyncEntity(
                    eventId = event.eventId, eventType = event.eventType,
                    patientId = event.patientId, payloadJson = gson.toJson(event.payload),
                    createdAt = System.currentTimeMillis(), schemaVersion = event.schemaVersion,
                    deviceTime = checkNotNull(event.deviceTime), origin = event.origin,
                )
            )
        }
        offlineDatabase.checkpointBackup()
        enqueueSync()
    }
    suspend fun syncPendingEvents(): SyncOutcome {
        if (!hasSession()) return SyncOutcome.Retry("Sign in to sync your saved records.")
        val entities = offlineDatabase.pendingSyncDao().getAll()
        val pending = entities.map { entity ->
            SyncEventRequest(
                eventId = entity.eventId,
                eventType = entity.eventType,
                patientId = entity.patientId,
                payload = gson.fromJson(entity.payloadJson, object : TypeToken<Map<String, Any>>() {}.type),
                schemaVersion = entity.schemaVersion,
                deviceTime = entity.deviceTime.ifBlank { null },
                attemptCount = entity.retryCount.coerceAtMost(1000),
                origin = entity.origin,
            )
        }
        if (pending.isEmpty()) return SyncOutcome.Synced(0)
        val response = try {
            api.syncEvents(pending)
        } catch (error: java.io.IOException) {
            offlineDatabase.pendingSyncDao().markRetry(entities.map { it.eventId }, error.message ?: "Network unavailable")
            return SyncOutcome.Retry(error.message ?: "Network unavailable")
        } catch (error: retrofit2.HttpException) {
            val message = error.message()
            return if (error.code() == 401 || error.code() == 408 || error.code() == 429 || error.code() >= 500) {
                offlineDatabase.pendingSyncDao().markRetry(entities.map { it.eventId }, message)
                SyncOutcome.Retry("Server unavailable. Saved records will retry.")
            } else {
                offlineDatabase.pendingSyncDao().markFailed(entities.map { it.eventId }, message)
                SyncOutcome.Partial(0, entities.size)
            }
        }
        val requestedIds = entities.map { it.eventId }.toSet()
        val results = response.results.filter { it.eventId in requestedIds }
        val completed = results.filter { it.status == "accepted" || it.status == "duplicate" }.map { it.eventId }.toSet()
        if (completed.isNotEmpty()) offlineDatabase.pendingSyncDao().delete(completed.toList())
        val rejectedResults = results.filter { it.status == "rejected" || it.status == "conflict" }
        if (rejectedResults.isNotEmpty()) {
            val message = rejectedResults.joinToString("; ") { it.detail ?: "Event was rejected by the server." }
            offlineDatabase.pendingSyncDao().markFailed(rejectedResults.map { it.eventId }, message)
        }
        val rejected = rejectedResults.size
        val unresolved = requestedIds - completed - rejectedResults.map { it.eventId }.toSet()
        if (unresolved.isNotEmpty()) {
            offlineDatabase.pendingSyncDao().markRetry(unresolved.toList(), "Incomplete server acknowledgement")
            return SyncOutcome.Retry("Some records still need server acknowledgement.")
        }
        return if (rejected > 0) SyncOutcome.Partial(completed.size, rejected) else SyncOutcome.Synced(completed.size)
    }
    /** Returns the number of events currently waiting in the offline queue. */
    override suspend fun pendingEventCount(): Int = withContext(Dispatchers.IO) {
        offlineDatabase.pendingSyncDao().getAll().size
    }
    override suspend fun failedEventCount(): Int = offlineDatabase.pendingSyncDao().countFailed()
    override fun patientId(): String = sessions.read()?.patientId ?: error("Please sign in first.")
}
