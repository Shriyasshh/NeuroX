import { api, endSession, request } from './client'
import type { AuthResponse, AuthUser, CaregiverPreferences, Contact, LanguageConfig, Patient, PerformanceData, Reminder, SafetyState, SecuritySession } from '../types/dashboard'

export type ActivityReport = { patientId: string; truncated?: boolean; generatedAt?: string; summary: { sessions: number; completionRate: number; averageAccuracy: number; averageResponseTime: number; averageDifficulty: number }; series: Array<{date: string; completionRate: number; accuracy: number; responseTime: number; difficulty: number; offlineCreated?: boolean; modelVersion?: string}>; note: string }
export type ActivityHistory = { id: string; activityId: string; startedAt: string; completedAt: string | null; accuracy: number | null; responseTime: number | null; attempts: number; status: string; difficulty: number }
export type DashboardAlert = { id: string; kind: string; type?: string; severity?: string; status: string; message: string; createdAt: string; escalatedToPriority: number; workflowNote?: string }
export type LocationHistoryItem = { id: string; latitude: number; longitude: number; accuracyM: number; connectionState: string; capturedAt: string; freshness: string; label: string }
export type CognitiveModelStatus = { available: boolean; model: string; disclaimer: string }
export type CognitiveScreeningResult = { patientId: string; low_delayed_recall_probability: number; model: string; target: string; disclaimer: string }

export const authApi = {
  login: (email: string, password: string) => api.post<AuthResponse>('/auth/browser/login', {email, password}),
  register: (name: string, email: string, password: string) => api.post<AuthResponse>('/auth/browser/register', {name, email, password, role: 'CAREGIVER'}),
  google: (credential: string) => api.post<AuthResponse>('/auth/browser/google', {credential}),
  me: () => api.get<AuthUser>('/api/v1/auth/me'),
  logout: endSession,
  updateProfile: (name: string) => api.put<AuthUser>('/api/v1/auth/me', {name}),
  changePassword: (currentPassword: string, newPassword: string) => api.put<{updated: boolean}>('/api/v1/auth/me/password', {current_password: currentPassword, new_password: newPassword}),
  sessions: () => api.get<SecuritySession[]>('/api/v1/auth/sessions'),
  revokeSession: (id: string) => api.delete<{revoked: boolean; session_id: string}>(`/api/v1/auth/sessions/${id}`),
  requestEmailVerification: (accessToken?: string) => accessToken
    ? request<{message: string}>('/api/v1/auth/email-verification/request', {method: 'POST', headers: {Authorization: `Bearer ${accessToken}`}, body: '{}'})
    : api.post<{message: string}>('/api/v1/auth/email-verification/request', {}),
  confirmEmailVerification: (token: string) => api.post<{verified: boolean}>('/api/v1/auth/email-verification/confirm', {token}),
  requestPasswordReset: (email: string) => api.post<{message: string}>('/api/v1/auth/password-reset/request', {email}),
  confirmPasswordReset: (token: string, newPassword: string) => api.post<{reset: boolean}>('/api/v1/auth/password-reset/confirm', {token, new_password: newPassword}),
  preferences: () => api.get<CaregiverPreferences>('/api/v1/caregivers/me/preferences'),
  updatePreferences: (payload: Partial<{available: boolean; notify_sos: boolean; notify_safety_alerts: boolean; notify_reminders: boolean}>) => api.put<CaregiverPreferences>('/api/v1/caregivers/me/preferences', payload),
  privacy: () => api.get<{locationSharingEnabled: boolean}>('/api/v1/patients/me/privacy'),
  setLocationSharing: (enabled: boolean) => api.put<{enabled: boolean; message: string}>('/api/v1/patients/me/privacy/location-sharing', {enabled}),
  caregivers: () => api.get<Array<{id: string; name: string; email: string}>>('/api/v1/patients/me/caregivers'),
  revokeCaregiver: (id: string) => api.delete(`/api/v1/patients/me/caregivers/${id}`),
  exportData: () => api.get('/api/v1/patients/me/privacy/export'),
  requestDeletion: () => api.post<{message: string}>('/api/v1/patients/me/privacy/deletion-request', {}),
}

export const dashboardApi = {
  assignedPatients: () => api.get<Patient[]>('/api/v1/caregivers/me/patients'),
  patientMe: () => api.get<Patient>('/api/v1/patients/me'),
  patient: (patientId: string) => api.get<Patient>(`/api/v1/patients/${patientId}`),
  safety: (patientId: string) => api.get<SafetyState>(`/api/v1/patients/${patientId}/safety`),
  locationHistory: (patientId: string) => api.get<LocationHistoryItem[]>(`/api/v1/patients/${patientId}/location-updates`),
  performance: (patientId: string) => api.get<PerformanceData>(`/api/v1/patients/${patientId}/performance`),
  report: (patientId: string, filters: {from?: string; to?: string; activityId?: string} = {}) => {
    const params = new URLSearchParams()
    if (filters.from) params.set('from', new Date(filters.from).toISOString())
    if (filters.to) params.set('to', new Date(filters.to).toISOString())
    if (filters.activityId) params.set('activity_id', filters.activityId)
    const query = params.toString()
    return api.get<ActivityReport>(`/api/v1/patients/${patientId}/reports/activity${query ? `?${query}` : ''}`)
  },
  history: (patientId: string) => api.get<ActivityHistory[]>(`/api/v1/patients/${patientId}/activity-sessions`),
  alerts: (patientId: string) => api.get<DashboardAlert[]>(`/api/v1/patients/${patientId}/alerts?status=all`),
  acknowledge: (patientId: string, kind: 'alert' | 'sos', id: string) => api.post(`/api/v1/patients/${patientId}/${kind === 'sos' ? 'sos-events' : 'safety-alerts'}/${id}/acknowledge`, {note: 'Acknowledged from caregiver dashboard'}),
  languages: () => api.get<LanguageConfig[]>('/api/v1/language-config'),
  updateSafetySettings: (patientId: string, payload: unknown) => api.put(`/api/v1/patients/${patientId}/safety/settings`, payload),
  contacts: (patientId: string) => api.get<Contact[]>(`/api/v1/patients/${patientId}/emergency-contacts`),
  reminders: (patientId: string) => api.get<Reminder[]>(`/api/v1/patients/${patientId}/reminders`),
  createReminder: (payload: unknown) => api.post<Reminder>('/api/v1/reminders', payload),
  updateReminder: (reminderId: string, payload: unknown) => api.put<Reminder>(`/api/v1/reminders/${reminderId}`, payload),
  createContact: (patientId: string, payload: unknown) => api.post<Contact>(`/api/v1/patients/${patientId}/emergency-contacts`, payload),
  updateContact: (patientId: string, contactId: string, payload: unknown) => api.put<Contact>(`/api/v1/patients/${patientId}/emergency-contacts/${contactId}`, payload),
  cognitiveModelStatus: (patientId: string) => api.get<CognitiveModelStatus>(`/api/v1/patients/${patientId}/cognitive-model/status`),
  cognitiveScreening: (patientId: string, payload: unknown) => api.post<CognitiveScreeningResult>(`/api/v1/patients/${patientId}/cognitive-screening`, payload),
}
