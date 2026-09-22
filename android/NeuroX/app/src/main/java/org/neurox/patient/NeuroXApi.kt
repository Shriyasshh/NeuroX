package org.neurox.patient

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

data class LoginRequest(val email: String, val password: String)
data class RefreshTokenRequest(@SerializedName("refresh_token") val refreshToken: String)
data class AuthResponse(@SerializedName("access_token") val accessToken: String, @SerializedName("refresh_token") val refreshToken: String, val user: User)
data class User(val id: String, val name: String, val email: String, val role: String)
data class Patient(val id: String, val name: String, val age: Int, val preferredLanguage: String)
data class ActivityItem(val id: String, val title: String, val description: String, val difficulty: Int, val contentVersion: String = "legacy")
data class ReminderItem(val id: String, val title: String, val scheduledTime: String, val completed: Boolean, val description: String? = null, val type: String = "activity", val repeatRule: String? = null, val enabled: Boolean = true, val status: String = if (completed) "done" else "upcoming", val snoozedUntil: String? = null, val timezoneName: String = "Asia/Kolkata")
data class ActivityStartRequest(@SerializedName("user_id") val userId: String, @SerializedName("difficulty_level") val difficultyLevel: Int, @SerializedName("started_at") val startedAt: String, @SerializedName("event_id") val eventId: String, @SerializedName("offline_created") val offlineCreated: Boolean = false, @SerializedName("content_version") val contentVersion: String = "legacy", @SerializedName("accessibility_mode") val accessibilityMode: String = "large-touch", @SerializedName("app_version") val appVersion: String = BuildConfig.VERSION_NAME, @SerializedName("model_version") val modelVersion: String = "adaptive-v1")
data class ActivityCompletionRequest(@SerializedName("user_id") val userId: String, @SerializedName("activity_id") val activityId: String, @SerializedName("started_at") val startedAt: String, @SerializedName("completed_at") val completedAt: String, val accuracy: Float, @SerializedName("response_time") val responseTime: Float, val attempts: Int, @SerializedName("completion_status") val completionStatus: String = "completed", @SerializedName("difficulty_level") val difficultyLevel: Int, @SerializedName("offline_created") val offlineCreated: Boolean = false, @SerializedName("event_id") val eventId: String, @SerializedName("content_version") val contentVersion: String = "legacy", val interruptions: Int = 0, @SerializedName("accessibility_mode") val accessibilityMode: String = "large-touch", @SerializedName("app_version") val appVersion: String = BuildConfig.VERSION_NAME, @SerializedName("model_version") val modelVersion: String = "adaptive-v1")
data class SyncEventRequest(
    @SerializedName("event_id") val eventId: String,
    @SerializedName("event_type") val eventType: String,
    @SerializedName("patient_id") val patientId: String,
    val payload: Map<String, Any>,
    @SerializedName("schema_version") val schemaVersion: Int = 1,
    @SerializedName("device_time") val deviceTime: String? = null,
    @SerializedName("attempt_count") val attemptCount: Int = 0,
    val origin: String = "android",
)
data class SyncEventResult(@SerializedName("event_id") val eventId: String, val status: String, val result: Map<String, Any>? = null, val detail: String? = null)
data class SyncEventsResponse(val results: List<SyncEventResult>)
data class LocationUpdateRequest(val latitude: Double, val longitude: Double, @SerializedName("accuracy_m") val accuracyM: Double, @SerializedName("connection_state") val connectionState: String = "online", @SerializedName("captured_at") val capturedAt: String)
data class SafetyContact(val id: String, val name: String, val phone: String, val relationship: String, val priority: Int)
data class SafetySettingsSummary(val expectedReturnAt: String? = null)
data class SafetyState(val patientId: String, val status: String, val contacts: List<SafetyContact> = emptyList(), val settings: SafetySettingsSummary? = null)
data class ActivityHistoryItem(val id: String, val activityId: String, val startedAt: String, val completedAt: String?, val accuracy: Float?, val responseTime: Float?, val attempts: Int, val status: String, val difficulty: Int)
data class EmergencyContactItem(val id: String, val patientId: String, val name: String, val phone: String, val relationship: String, val priority: Int, val active: Boolean)
data class SosRequest(val message: String = "I need help. Please check on me.", @SerializedName("location_update_id") val locationUpdateId: String? = null)
data class SyncResult(val saved: Boolean = true, val message: String? = null, @SerializedName("next_difficulty") val nextDifficulty: Int? = null, @SerializedName("performance_score") val performanceScore: Float? = null)
data class PrivacyState(val locationSharingEnabled: Boolean = false, val consents: Map<String, ConsentState> = emptyMap())
data class ConsentState(val granted: Boolean, val noticeVersion: String, val recordedAt: String)
data class CaregiverAccess(val id: String, val name: String, val email: String)
data class LocationSharingRequest(val enabled: Boolean)
data class LocationSharingResponse(val enabled: Boolean, val message: String)
data class RevocationResponse(val revoked: Boolean, val caregiverId: String)
data class ConsentUpdateRequest(val purpose: String, val granted: Boolean, @SerializedName("notice_version") val noticeVersion: String = "phase1-v1")
data class ConsentUpdateResponse(val purpose: String, val granted: Boolean, val noticeVersion: String)
data class SpeechTranscriptionRequest(
    @SerializedName("audio_base64") val audioBase64: String,
    @SerializedName("language_code") val languageCode: String,
    @SerializedName("audio_format") val audioFormat: String = "wav",
    @SerializedName("sampling_rate") val samplingRate: Int = 16000,
)
data class SpeechTranscriptionResponse(val transcript: String, val provider: String, val retained: Boolean)

interface NeuroXApi {
    @POST("api/v1/auth/login") suspend fun login(@Body request: LoginRequest): AuthResponse
    @POST("api/v1/auth/refresh") fun refresh(@Body request: RefreshTokenRequest): retrofit2.Call<AuthResponse>
    @GET("api/v1/patients/me") suspend fun patientWithToken(@retrofit2.http.Header("Authorization") token: String): Patient
    @GET("api/v1/patients/me") suspend fun patient(): Patient
    @GET("api/v1/activities") suspend fun activities(): List<ActivityItem>
    @GET("api/v1/patients/{patientId}/reminders") suspend fun reminders(@Path("patientId") patientId: String): List<ReminderItem>
    @GET("api/v1/patients/{patientId}/activity-sessions") suspend fun activityHistory(@Path("patientId") patientId: String): List<ActivityHistoryItem>
    @GET("api/v1/patients/{patientId}/emergency-contacts") suspend fun emergencyContacts(@Path("patientId") patientId: String): List<EmergencyContactItem>
    @PUT("api/v1/reminders/{reminderId}") suspend fun updateReminder(@Path("reminderId") reminderId: String, @Body request: Map<String, @JvmSuppressWildcards Any?>): ReminderItem
    @POST("api/v1/activities/{activityId}/start") suspend fun startActivity(@Path("activityId") activityId: String, @Body request: ActivityStartRequest): Map<String, Any>
    @POST("api/v1/activities/{activityId}/complete") suspend fun completeActivity(@Path("activityId") activityId: String, @Body request: ActivityCompletionRequest): SyncResult
    @POST("api/v1/sync/events") suspend fun syncEvents(@Body events: List<SyncEventRequest>): SyncEventsResponse
    @GET("api/v1/patients/{patientId}/safety") suspend fun safety(@Path("patientId") patientId: String): SafetyState
    @POST("api/v1/patients/{patientId}/location-updates") suspend fun location(@Path("patientId") patientId: String, @Body request: LocationUpdateRequest): Map<String, Any>
    @POST("api/v1/patients/{patientId}/sos-events") suspend fun sos(@Path("patientId") patientId: String, @Body request: SosRequest): Map<String, Any>
    @GET("api/v1/patients/me/privacy") suspend fun privacy(): PrivacyState
    @PUT("api/v1/patients/me/privacy/location-sharing") suspend fun setLocationSharing(@Body request: LocationSharingRequest): LocationSharingResponse
    @GET("api/v1/patients/me/caregivers") suspend fun caregivers(): List<CaregiverAccess>
    @DELETE("api/v1/patients/me/caregivers/{caregiverId}") suspend fun revokeCaregiver(@Path("caregiverId") caregiverId: String): RevocationResponse
    @PUT("api/v1/patients/me/privacy/consents") suspend fun setConsent(@Body request: ConsentUpdateRequest): ConsentUpdateResponse
    @POST("api/v1/speech/transcriptions") suspend fun transcribeSpeech(@Body request: SpeechTranscriptionRequest): SpeechTranscriptionResponse
}
