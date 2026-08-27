import { useCallback, useEffect, useState } from 'react'
import { interactionsApi } from '../lib/api'
import { useIdentity } from '../lib/identity'
import { Banner, ErrorBanner, JsonBlock, Spinner, StatusPill } from '../components/Primitives'
import type { HumanInteractionView, Json, ResponseAction } from '../lib/types'

/**
 * The tenant's pending human interactions for the current identity. The inbox is audience-filtered
 * server-side, so switching identity in the top bar is how audience routing gets validated.
 */
export function InboxView({ onOpenRun }: { onOpenRun: (rootRunId: string) => void }) {
  const identity = useIdentity()
  const [items, setItems] = useState<HumanInteractionView[]>([])
  const [loading, setLoading] = useState(false)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [drafts, setDrafts] = useState<Record<string, { action: ResponseAction; answer: string; comment: string }>>({})
  const [autoRefresh, setAutoRefresh] = useState(true)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const inbox = await interactionsApi.inbox(identity)
      setItems(Array.isArray(inbox) ? inbox : [])
      setError(null)
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [identity])

  useEffect(() => { void refresh() }, [refresh])

  useEffect(() => {
    if (!autoRefresh) return
    const timer = setInterval(() => { void refresh() }, 5000)
    return () => clearInterval(timer)
  }, [autoRefresh, refresh])

  function draftFor(item: HumanInteractionView) {
    return drafts[item.interactionId] ?? {
      action: (item.type === 'TOOL_APPROVAL' ? 'APPROVE' : 'ANSWER') as ResponseAction,
      answer: '',
      comment: '',
    }
  }

  function parseAnswer(text: string): Json {
    const trimmed = text.trim()
    if (!trimmed) return null
    try { return JSON.parse(trimmed) } catch { return trimmed }
  }

  async function respond(item: HumanInteractionView) {
    const draft = draftFor(item)
    setBusyId(item.interactionId)
    setError(null); setNotice(null)
    try {
      const result = await interactionsApi.respond(identity, item.interactionId, {
        action: draft.action,
        answer: draft.action === 'ANSWER' ? parseAnswer(draft.answer) : undefined,
        comment: draft.comment || undefined,
      })
      setNotice(
        `${item.interactionId.slice(0, 8)} → ${result.status}` +
        (result.runStatus ? `; run is ${result.runStatus}` : ''),
      )
      await refresh()
    } catch (e) {
      setError(e)
    } finally {
      setBusyId(null)
    }
  }

  return (
    <div className="panel">
      <div className="pane-head">
        <h2>Human interaction inbox</h2>
        <div className="pane-actions">
          <label className="toggle">
            <input type="checkbox" checked={autoRefresh} onChange={(e) => setAutoRefresh(e.target.checked)} />
            auto-refresh
          </label>
          <button type="button" className="link" onClick={() => void refresh()}>refresh</button>
        </div>
      </div>

      <p className="dim small">
        Visible to <span className="mono">{identity.userId}</span> with roles{' '}
        <span className="mono">{identity.roles.join(', ') || 'none'}</span> in tenant{' '}
        <span className="mono">{identity.licenseCode}</span>. Draft-test interactions never appear here —
        they are stateless and answered inside the run view.
      </p>

      <ErrorBanner error={error} onDismiss={() => setError(null)} />
      {notice && <Banner kind="ok">{notice}</Banner>}
      {loading && items.length === 0 && <Spinner label="loading inbox…" />}
      {!loading && items.length === 0 && (
        <p className="dim small">Nothing pending. Start a published run whose agent or tool requires a human.</p>
      )}

      {items.map((item) => {
        const draft = draftFor(item)
        const resolved = item.status !== 'PENDING'
        return (
          <div className="interaction" key={item.interactionId}>
            <div className="interaction-head">
              <span className="chip chip-strong">{item.type}</span>
              <StatusPill status={item.status} />
              {item.responseType && <span className="chip">{item.responseType}</span>}
              {item.category && <span className="chip">{item.category}</span>}
              {item.audienceType && (
                <span className="chip">
                  {item.audienceType}
                  {item.audienceValues?.length ? `: ${item.audienceValues.join(', ')}` : ''}
                </span>
              )}
              <span className="grow" />
              <button type="button" className="link" onClick={() => onOpenRun(item.rootRunId)}>
                open run {item.rootRunId.slice(0, 8)} →
              </button>
            </div>

            {item.question && <p className="question">{item.question}</p>}
            {item.reason && <p className="dim small">{item.reason}</p>}
            <div className="meta-row mono small dim">
              interaction {item.interactionId.slice(0, 8)} · run {item.runId.slice(0, 8)}
              {item.expiresAt ? ` · expires ${new Date(item.expiresAt).toLocaleString()}` : ''}
            </div>
            <JsonBlock value={item.request} label="request" />

            {!resolved && (
              <div className="row-inline">
                <select
                  value={draft.action}
                  onChange={(e) =>
                    setDrafts((p) => ({ ...p, [item.interactionId]: { ...draft, action: e.target.value as ResponseAction } }))
                  }
                >
                  {item.type === 'TOOL_APPROVAL'
                    ? <><option value="APPROVE">APPROVE</option><option value="REJECT">REJECT</option></>
                    : <option value="ANSWER">ANSWER</option>}
                </select>
                {draft.action === 'ANSWER' && (
                  <input
                    className="grow"
                    placeholder="answer — JSON is parsed, anything else is sent as text"
                    value={draft.answer}
                    onChange={(e) =>
                      setDrafts((p) => ({ ...p, [item.interactionId]: { ...draft, answer: e.target.value } }))
                    }
                  />
                )}
                <input
                  placeholder="comment (optional)"
                  value={draft.comment}
                  onChange={(e) =>
                    setDrafts((p) => ({ ...p, [item.interactionId]: { ...draft, comment: e.target.value } }))
                  }
                />
                <button type="button" disabled={busyId === item.interactionId} onClick={() => void respond(item)}>
                  Submit
                </button>
              </div>
            )}
            {resolved && (
              <p className="dim small">
                Resolved{item.resolvedAt ? ` at ${new Date(item.resolvedAt).toLocaleString()}` : ''}.
              </p>
            )}
          </div>
        )
      })}
    </div>
  )
}
