package org.neurox.patient

import java.util.UUID

interface PatientRepository {
    suspend fun load(): RemoteData
    suspend fun cachedData(): RemoteData?
    suspend fun pendingEventCount(): Int
    suspend fun failedEventCount(): Int = 0
    fun schedulePendingSync()
    suspend fun retryFailedSync() {}
    fun patientId(): String
    suspend fun startActivity(activity: ActivityItem, eventId: String, startedAt: String, offline: Boolean): Map<String, Any>
    suspend fun completeActivity(activity: ActivityItem, request: ActivityCompletionRequest): SyncResult
    suspend fun queueActivityCompletion(request: ActivityCompletionRequest)
    suspend fun markReminderComplete(reminderId: String): ReminderItem
    suspend fun markReminderCompletedLocally(reminderId: String)
    suspend fun queueReminderUpdate(reminderId: String)
    suspend fun updateReminderState(reminderId: String, status: String, snoozedUntil: String? = null): ReminderItem
    suspend fun updateReminderStateLocally(reminderId: String, status: String, snoozedUntil: String? = null)
    suspend fun queueReminderState(reminderId: String, status: String, snoozedUntil: String? = null)
    suspend fun sendSos(request: SosRequest): Map<String, Any>
    suspend fun publishLocation(request: LocationUpdateRequest): Map<String, Any> =
        error("Location sharing is unavailable.")
    suspend fun queueLocation(request: LocationUpdateRequest) {
        error("Offline location sharing is unavailable.")
    }
    suspend fun queueSos(request: SosRequest, eventId: String = UUID.randomUUID().toString())
    suspend fun privacy(): PrivacyState = PrivacyState()
    suspend fun caregivers(): List<CaregiverAccess> = emptyList()
    suspend fun setLocationSharing(enabled: Boolean): LocationSharingResponse =
        error("Privacy controls are unavailable.")
    suspend fun revokeCaregiver(caregiverId: String): RevocationResponse =
        error("Privacy controls are unavailable.")
    suspend fun setConsent(purpose: String, granted: Boolean): ConsentUpdateResponse =
        error("Consent controls are unavailable.")
    suspend fun transcribeSpeech(audioBase64: String, languageCode: String): SpeechTranscriptionResponse =
        error("Online speech recognition is unavailable.")
}

data class RemoteData(
    val patient: Patient,
    val activities: List<ActivityItem>,
    val reminders: List<ReminderItem>,
    val history: List<ActivityHistoryItem> = emptyList(),
    val contacts: List<EmergencyContactItem> = emptyList(),
    val safety: SafetyState? = null
)

sealed interface SyncOutcome {
    data class Synced(val count: Int) : SyncOutcome
    data class Partial(val synced: Int, val failed: Int) : SyncOutcome
    data class Retry(val reason: String) : SyncOutcome
}
