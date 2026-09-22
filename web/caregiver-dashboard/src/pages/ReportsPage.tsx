import { FormEvent, useEffect, useState } from 'react'
import { BrainCircuit, Info } from 'lucide-react'
import {
  Area, AreaChart, Line, LineChart,
  ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import { dashboardApi, type ActivityReport, type CognitiveModelStatus, type CognitiveScreeningResult } from '../api/dashboardApi'
import { EmptyState, ErrorState, LoadingState } from '../components/ui/AsyncState'

type Preset = '7d' | '30d' | '90d' | 'custom'

function applyPreset(preset: Preset): { from: string; to: string } {
  const now = new Date()
  const to  = new Date(now)
  const toStr = () => to.toISOString().slice(0, 16)
  if (preset === '7d') {
    const from = new Date(now); from.setDate(from.getDate() - 7)
    return { from: from.toISOString().slice(0, 16), to: toStr() }
  }
  if (preset === '30d') {
    const from = new Date(now); from.setDate(from.getDate() - 30)
    return { from: from.toISOString().slice(0, 16), to: toStr() }
  }
  if (preset === '90d') {
    const from = new Date(now); from.setDate(from.getDate() - 90)
    return { from: from.toISOString().slice(0, 16), to: toStr() }
  }
  return { from: '', to: '' }
}

export function ReportsPage({ patientId }: { patientId?: string }) {
  const [report, setReport]   = useState<ActivityReport | null>(null)
  const [error, setError]     = useState('')
  const [loading, setLoading] = useState(Boolean(patientId))
  const [from, setFrom]       = useState('')
  const [to, setTo]           = useState('')
  const [activityId, setActivityId] = useState('')
  const [preset, setPreset]   = useState<Preset>('30d')
  const [modelStatus, setModelStatus] = useState<CognitiveModelStatus | null>(null)
  const [screening, setScreening] = useState({ age: '', yearsSchooling: '', literacy: '', residence: '', state: '' })
  const [screeningResult, setScreeningResult] = useState<CognitiveScreeningResult | null>(null)
  const [screeningError, setScreeningError] = useState('')
  const [screeningBusy, setScreeningBusy] = useState(false)

  const applyAndLoad = async (fromVal: string, toVal: string, actId: string) => {
    if (!patientId) return
    if (fromVal && toVal && new Date(fromVal) > new Date(toVal)) {
      setError('Report start must precede end.')
      return
    }
    setLoading(true)
    setError('')
    try {
      setReport(await dashboardApi.report(patientId, { from: fromVal, to: toVal, activityId: actId }))
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to load the activity report.')
    } finally {
      setLoading(false)
    }
  }

  // Load with default preset on mount
  useEffect(() => {
    const { from: f, to: t } = applyPreset('30d')
    setFrom(f); setTo(t)
    void applyAndLoad(f, t, '')
    if (patientId) {
      void Promise.all([dashboardApi.patient(patientId), dashboardApi.cognitiveModelStatus(patientId)])
        .then(([patient, status]) => {
          setScreening(current => ({ ...current, age: String(patient.age) }))
          setModelStatus(status)
        })
        .catch(() => setModelStatus(null))
    }
  }, [patientId])

  const runScreening = async (event: FormEvent) => {
    event.preventDefault()
    if (!patientId) return
    setScreeningBusy(true); setScreeningError(''); setScreeningResult(null)
    try {
      setScreeningResult(await dashboardApi.cognitiveScreening(patientId, {
        age: screening.age ? Number(screening.age) : null,
        years_schooling: screening.yearsSchooling ? Number(screening.yearsSchooling) : null,
        literacy: screening.literacy ? Number(screening.literacy) : null,
        residence: screening.residence ? Number(screening.residence) : null,
        state: screening.state || null,
      }))
    } catch (cause) {
      setScreeningError(cause instanceof Error ? cause.message : 'Unable to generate the research estimate.')
    } finally { setScreeningBusy(false) }
  }

  const handlePreset = (p: Preset) => {
    setPreset(p)
    if (p !== 'custom') {
      const { from: f, to: t } = applyPreset(p)
      setFrom(f); setTo(t)
      void applyAndLoad(f, t, activityId)
    }
  }

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    setPreset('custom')
    void applyAndLoad(from, to, activityId)
  }

  const downloadCsv = () => {
    if (!report) return
    const rows = [
      ['NeuroX supportive activity report — not a diagnosis'],
      [report.truncated ? 'Partial report: narrow the filters to include all matching records.' : 'Filtered synced records'],
      ['date', 'completion_rate', 'accuracy', 'response_time_seconds', 'difficulty', 'offline_created', 'policy_version'],
      ...report.series.map(p => [
        p.date, String(p.completionRate), String(p.accuracy),
        String(p.responseTime), String(p.difficulty),
        String(p.offlineCreated ?? 'unknown'), p.modelVersion ?? 'unknown',
      ]),
    ]
    const csv = rows.map(row =>
      row.map(v => `"${(/^[\s]*[=+@-]/.test(v) ? "'" + v : v).split('"').join('""')}"`).join(',')
    ).join('\n')
    const url  = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `neurox-supportive-activity-report-${patientId ?? 'patient'}.csv`
    link.click()
    URL.revokeObjectURL(url)
  }

  const chartData = report?.series.map((p, i) => ({
    name: `Sess. ${i + 1}`,
    completion: Math.round(p.completionRate * 100),
    accuracy:   Math.round(p.accuracy * 100),
    difficulty: p.difficulty,
    response:   p.responseTime,
  })) ?? []

  return (
    <section className="panel" aria-labelledby="reports-heading">
      <div className="panel-head">
        <div>
          <p className="eyebrow">REPORTS</p>
          <h2 id="reports-heading">Activity report</h2>
          <p>Supportive engagement information, not a medical assessment.</p>
        </div>
        {report && (
          <div style={{ display: 'flex', gap: 8 }}>
            <button className="quiet" onClick={downloadCsv} aria-label="Download CSV report">
              Download CSV
            </button>
            <button className="quiet" onClick={() => window.print()} aria-label="Print report">
              Print
            </button>
          </div>
        )}
      </div>

      <div className="research-model-card" aria-labelledby="research-model-heading">
        <div className="research-model-heading">
          <BrainCircuit size={24} aria-hidden="true" />
          <div>
            <h3 id="research-model-heading">LASI cognitive research estimate</h3>
            <p>Optional research support only. This does not diagnose dementia or recommend treatment.</p>
          </div>
        </div>
        {modelStatus && !modelStatus.available ? (
          <p className="auth-error" role="status">The research model is not installed on this server.</p>
        ) : (
          <form className="config-form research-model-form" onSubmit={runScreening}>
            <label>Age<input type="number" min="0" max="130" required value={screening.age} onChange={e => setScreening({ ...screening, age: e.target.value })} /></label>
            <label>Years of schooling<input type="number" min="0" max="100" value={screening.yearsSchooling} onChange={e => setScreening({ ...screening, yearsSchooling: e.target.value })} /></label>
            <label>Literacy<select value={screening.literacy} onChange={e => setScreening({ ...screening, literacy: e.target.value })}><option value="">Unknown</option><option value="1">Yes</option><option value="2">No</option></select></label>
            <label>Residence<select value={screening.residence} onChange={e => setScreening({ ...screening, residence: e.target.value })}><option value="">Unknown</option><option value="1">Rural</option><option value="2">Urban</option></select></label>
            <label>State<input maxLength={100} value={screening.state} onChange={e => setScreening({ ...screening, state: e.target.value })} placeholder="Maharashtra" /></label>
            <button className="auth-submit" disabled={screeningBusy || modelStatus?.available === false}>{screeningBusy ? 'Calculating…' : 'Generate estimate'}</button>
          </form>
        )}
        <p className="research-consent-note">Patient consent for cognitive screening is required. Inputs are not stored; generation is audit logged.</p>
        {screeningError && <p className="auth-error" role="alert">{screeningError}</p>}
        {screeningResult && (
          <div className="research-result" role="status">
            <strong>{Math.round(screeningResult.low_delayed_recall_probability * 100)}%</strong>
            <span>model-estimated likelihood of matching the LASI low delayed-recall pattern</span>
            <small>{screeningResult.disclaimer}</small>
          </div>
        )}
      </div>

      {/* Date presets */}
      <div className="date-presets" role="group" aria-label="Date range presets">
        {(['7d', '30d', '90d', 'custom'] as Preset[]).map(p => (
          <button
            key={p}
            className={`preset-btn${preset === p ? ' active' : ''}`}
            onClick={() => handlePreset(p)}
            aria-pressed={preset === p}
          >
            {p === '7d' ? 'Last 7 days' : p === '30d' ? 'Last 30 days' : p === '90d' ? 'Last 90 days' : 'Custom range'}
          </button>
        ))}
      </div>

      {/* Filters */}
      <form className="config-form" onSubmit={handleSubmit} style={{ gridTemplateColumns: '1fr 1fr 1fr auto', alignItems: 'end', marginTop: 0 }}>
        <label>
          From
          <input
            type="datetime-local"
            value={from}
            onChange={e => { setFrom(e.target.value); setPreset('custom') }}
          />
        </label>
        <label>
          To
          <input
            type="datetime-local"
            value={to}
            onChange={e => { setTo(e.target.value); setPreset('custom') }}
          />
        </label>
        <label>
          Activity ID (optional)
          <input
            placeholder="memory-match"
            value={activityId}
            onChange={e => setActivityId(e.target.value)}
          />
        </label>
        <button className="auth-submit" disabled={loading} style={{ height: 44, marginBottom: 0 }} aria-label="Apply filters">
          {loading ? 'Loading…' : 'Apply filters'}
        </button>
      </form>

      {loading ? (
        <LoadingState label="Generating report…" />
      ) : error ? (
        <ErrorState message={error} onRetry={() => void applyAndLoad(from, to, activityId)} />
      ) : !report || report.summary.sessions === 0 ? (
        <EmptyState message="No completed activities are available for this report. Missing dates mean NeuroX has no synced completed activity for that period." />
      ) : (
        <>
          {/* Data freshness disclaimer */}
          <div className="data-freshness-banner">
            <Info size={15} aria-hidden="true" />
            <span>
              This report uses synced activity records only. Offline or missing periods are not
              interpreted as a change in health or ability.
              {report.generatedAt && ` Report generated: ${new Date(report.generatedAt).toLocaleString()}`}
            </span>
          </div>

          {report.truncated && (
            <p role="status" style={{ fontSize: '0.8rem', color: 'var(--amber)', marginBottom: 8 }}>
              Partial report: narrow the date or activity filters to view all matching records.
            </p>
          )}

          {/* Summary stat cards */}
          <div className="report-stats">
            <div className="report-stat">
              <small>Sessions</small>
              <strong>{report.summary.sessions}</strong>
            </div>
            <div className="report-stat">
              <small>Completion</small>
              <strong>{Math.round(report.summary.completionRate * 100)}%</strong>
            </div>
            <div className="report-stat">
              <small>Avg accuracy</small>
              <strong>{Math.round(report.summary.averageAccuracy * 100)}%</strong>
            </div>
            <div className="report-stat">
              <small>Avg response</small>
              <strong>{report.summary.averageResponseTime}s</strong>
            </div>
          </div>

          {/* Completion & accuracy trend chart */}
          {chartData.length > 1 && (
            <>
              <p style={{ fontSize: '0.78rem', color: 'var(--text-muted)', margin: '16px 0 4px' }}>
                Completion &amp; accuracy over sessions
              </p>
              <div className="chart">
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart data={chartData}>
                    <defs>
                      <linearGradient id="fillCompletion" x1="0" x2="0" y1="0" y2="1">
                        <stop stopColor="#5b8df0" stopOpacity="0.3" />
                        <stop offset="1" stopColor="#5b8df0" stopOpacity="0" />
                      </linearGradient>
                      <linearGradient id="fillAccuracy" x1="0" x2="0" y1="0" y2="1">
                        <stop stopColor="#34c9a0" stopOpacity="0.2" />
                        <stop offset="1" stopColor="#34c9a0" stopOpacity="0" />
                      </linearGradient>
                    </defs>
                    <XAxis dataKey="name" axisLine={false} tickLine={false} tick={{ fill: 'var(--text-muted)', fontSize: 11 }} />
                    <YAxis domain={[0, 100]} axisLine={false} tickLine={false} tick={{ fill: 'var(--text-muted)', fontSize: 11 }} />
                    <Tooltip
                      contentStyle={{ background: 'var(--bg-raised)', border: '1px solid var(--border-strong)', borderRadius: 8, fontSize: 12 }}
                      labelStyle={{ color: 'var(--text-primary)', marginBottom: 4 }}
                    />
                    <Area type="monotone" dataKey="completion" name="Completion %" stroke="#5b8df0" strokeWidth={2.5} fill="url(#fillCompletion)" />
                    <Area type="monotone" dataKey="accuracy"   name="Accuracy %"   stroke="#34c9a0" strokeWidth={2}   fill="url(#fillAccuracy)" />
                  </AreaChart>
                </ResponsiveContainer>
              </div>

              <p style={{ fontSize: '0.78rem', color: 'var(--text-muted)', margin: '14px 0 4px' }}>
                Response time &amp; difficulty progression
              </p>
              <div className="mini-chart">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={chartData}>
                    <XAxis dataKey="name" hide />
                    <YAxis yAxisId="resp" hide domain={[0, 'dataMax + 2']} />
                    <YAxis yAxisId="diff" hide domain={[1, 5]} />
                    <Tooltip
                      contentStyle={{ background: 'var(--bg-raised)', border: '1px solid var(--border-strong)', borderRadius: 8, fontSize: 12 }}
                    />
                    <Line yAxisId="resp" type="monotone" dataKey="response"   name="Response (s)"  stroke="#f5a623" strokeWidth={2} dot={false} />
                    <Line yAxisId="diff" type="stepAfter" dataKey="difficulty" name="Difficulty"    stroke="#34c9a0" strokeWidth={2} dot={false} />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            </>
          )}

          {/* Series table */}
          <div className="location-history" style={{ marginTop: 16 }}>
            {report.series.map((point, index) => (
              <div className="contact-row" key={`${point.date}-${index}`}>
                <div>
                  <b>{new Date(point.date).toLocaleString([], { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })}</b>
                  <p>
                    Completion {Math.round(point.completionRate * 100)}%
                    · Accuracy {Math.round(point.accuracy * 100)}%
                    · Difficulty {point.difficulty}
                    {point.offlineCreated && ' · offline session'}
                  </p>
                </div>
                <span style={{ fontSize: '0.82rem', fontWeight: 600, color: 'var(--text-primary)' }}>
                  {point.responseTime}s
                </span>
              </div>
            ))}
          </div>
        </>
      )}
    </section>
  )
}
