import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { agentsApi, draftTestApi, runsApi } from '../lib/api'
import { useIdentity } from '../lib/identity'
import {
  Banner, ErrorBanner, Field, JsonBlock, JsonField, Spinner, StatusPill, UsageChips,
} from '../components/Primitives'
import { DraftConversationView, TraceView } from '../components/Turns'
import type {
  AgentResponse, AgentRunResponse, AgentRunTraceResponse, DraftAgentTestRequest,
  DraftAgentTestResponse, Json, PendingInteractionSummary, Provider, ResponseAction,
  TestHumanResponse,
} from '../lib/types'

const PROVIDERS: Provider[] = ['OPENAI', 'GOOGLE_GEMINI', 'ANTHROPIC_CLAUDE', 'OPEN_SOURCE']
const TERMINAL = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED']

export interface RunTarget {
  agentId: string
  published: boolean
}

/** A pending human boundary plus the answer being composed for it, for either run mode. */
interface AnswerDraft {
  action: ResponseAction
  answerText: string
  comment: string
}

export function RunView({ target, openRunId }: { target: RunTarget | null; openRunId: string | null }) {
  const identity = useIdentity()
  const [agents, setAgents] = useState<AgentResponse[]>([])
  const [mode, setMode] = useState<'draft' | 'published'>('draft')
  const [agentId, setAgentId] = useState('')
  const [task, setTask] = useState('Summarize the supplied input and state any missing information.')
  const [input, setInput] = useState<Json>({})
  const [provider, setProvider] = useState<Provider | ''>('')
  const [model, setModel] = useState('')
  const [mockToolResults, setMockToolResults] = useState<Json>(null)
  const [jsonValid, setJsonValid] = useState({ input: true, mocks: true })

  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)

  // Draft-test state: the request is replayed verbatim with accumulated answers each round.
  const [draftResult, setDraftResult] = useState<DraftAgentTestResponse | null>(null)
  const baseRequest = useRef<DraftAgentTestRequest | null>(null)
  const accumulated = useRef<TestHumanResponse[]>([])

  // Published-run state.
  const [run, setRun] = useState<AgentRunResponse | null>(null)
  const [trace, setTrace] = useState<AgentRunTraceResponse | null>(null)
  const [autoPoll, setAutoPoll] = useState(true)
  const [instruction, setInstruction] = useState('')
  const [loadId, setLoadId] = useState('')

  const [answers, setAnswers] = useState<Record<string, AnswerDraft>>({})

  useEffect(() => {
    agentsApi.list(identity).then(setAgents).catch(setError)
  }, [identity])

  // Arriving from the Agents tab preselects the agent and the matching mode.
  useEffect(() => {
    if (!target) return
    setAgentId(target.agentId)
    setMode(target.published ? 'published' : 'draft')
    resetResults()
  }, [target])

  // Opening a run from the inbox loads it directly, without needing the agent selector.
  useEffect(() => {
    if (!openRunId) return
    setMode('published')
    resetResults()
    setBusy(true)
    void loadRun(openRunId)
      .catch(setError)
      .finally(() => setBusy(false))
    // loadRun is stable for a given identity; re-running on every render would poll in a loop.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openRunId])

  const selectedAgent = useMemo(
    () => agents.find((a) => a.id === agentId) ?? null,
    [agents, agentId],
  )

  function resetResults() {
    setDraftResult(null)
    setRun(null)
    setTrace(null)
    setAnswers({})
    setNotice(null)
    setError(null)
    baseRequest.current = null
    accumulated.current = []
  }

  function answerFor(id: string, fallback: ResponseAction): AnswerDraft {
    return answers[id] ?? { action: fallback, answerText: '', comment: '' }
  }

  function setAnswer(id: string, patch: Partial<AnswerDraft>, fallback: ResponseAction) {
    setAnswers((prev) => ({ ...prev, [id]: { ...answerFor(id, fallback), ...patch } }))
  }

  /** Answers are typed as free text; parse to JSON when it parses, else send the raw string. */
  function parseAnswer(text: string): Json {
    const trimmed = text.trim()
    if (!trimmed) return null
    try {
      return JSON.parse(trimmed)
    } catch {
      return trimmed
    }
  }

  /* ---------------- draft test ---------------- */

  async function startDraftTest() {
    if (!agentId) return
    setBusy(true); resetResults()
    const request: DraftAgentTestRequest = {
      agentId,
      task: task || null,
      input: input ?? {},
      provider: provider || null,
      model: model || null,
      mockToolResults: (mockToolResults as Record<string, Json> | null) ?? null,
    }
    baseRequest.current = request
    try {
      const result = await draftTestApi.run(identity, request)
      setDraftResult(result)
      if (result.status === 'NEEDS_INPUT') {
        setNotice(`Paused at ${result.pendingInteractions.length} human boundary(s).`)
      }
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  /**
   * A draft test is stateless: nothing is stored, so continuing means re-running the original
   * request with every answer collected so far plus the draft revision it was produced against.
   */
  async function continueDraftTest() {
    const base = baseRequest.current
    const current = draftResult
    if (!base || !current) return

    const newAnswers: TestHumanResponse[] = current.pendingInteractions.map((pending) => {
      const fallback: ResponseAction = pending.type === 'TOOL_APPROVAL' ? 'APPROVE' : 'ANSWER'
      const draft = answerFor(pending.interactionId, fallback)
      return {
        interactionToken: pending.interactionToken,
        action: draft.action,
        answer: draft.action === 'ANSWER' ? parseAnswer(draft.answerText) : undefined,
      }
    })
    accumulated.current = [...accumulated.current, ...newAnswers]

    setBusy(true); setError(null); setNotice(null)
    try {
      const result = await draftTestApi.run(identity, {
        ...base,
        expectedDraftRevision: current.draftRevision,
        humanResponses: accumulated.current,
      })
      setDraftResult(result)
      setAnswers({})
      if (result.status === 'COMPLETED') setNotice('Draft test completed.')
    } catch (e) {
      // A 409 means the draft changed underneath the sequence and it must restart.
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  /* ---------------- published run ---------------- */

  const loadRun = useCallback(async (runId: string) => {
    const [state, traced] = await Promise.all([
      runsApi.get(identity, runId),
      runsApi.trace(identity, runId).catch(() => null),
    ])
    setRun(state)
    if (traced) setTrace(traced)
    return state
  }, [identity])

  async function startPublishedRun() {
    if (!agentId) return
    setBusy(true); resetResults()
    try {
      const started = await runsApi.start(identity, {
        agentId,
        task,
        input: input ?? {},
        provider: provider || null,
        model: model || null,
      })
      setRun(started)
      await loadRun(started.runId).catch(() => undefined)
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  // Poll while the run is not terminal, so WAITING_FOR_HUMAN → RUNNING → SUCCEEDED is visible.
  useEffect(() => {
    if (!run || !autoPoll || TERMINAL.includes(run.status)) return
    const timer = setInterval(() => { void loadRun(run.runId).catch(() => undefined) }, 2500)
    return () => clearInterval(timer)
  }, [run, autoPoll, loadRun])

  async function submitRootResponses() {
    if (!run) return
    const pending = run.pendingInteractions
    if (pending.length === 0) return
    setBusy(true); setError(null); setNotice(null)
    try {
      await runsApi.submitRootResponses(
        identity,
        run.rootRunId,
        pending.map((p) => {
          const fallback: ResponseAction = p.type === 'TOOL_APPROVAL' ? 'APPROVE' : 'ANSWER'
          const draft = answerFor(p.interactionId, fallback)
          return {
            interactionId: p.interactionId,
            action: draft.action,
            answer: draft.action === 'ANSWER' ? parseAnswer(draft.answerText) : undefined,
            comment: draft.comment || undefined,
          }
        }),
      )
      setAnswers({})
      setNotice('Responses accepted; the run resumes asynchronously.')
      await loadRun(run.runId)
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  async function cancelRun() {
    if (!run) return
    setBusy(true); setError(null)
    try {
      setRun(await runsApi.cancel(identity, run.runId, 'ROOT', 'cancelled from dev console'))
      await loadRun(run.runId)
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  async function sendInstruction() {
    if (!run || !instruction.trim()) return
    setBusy(true); setError(null); setNotice(null)
    try {
      await runsApi.addInstruction(identity, run.runId, instruction.trim())
      setInstruction('')
      setNotice('Instruction recorded.')
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  const canStart =
    agentId !== '' && jsonValid.input && jsonValid.mocks && !busy &&
    (mode === 'published' ? task.trim() !== '' : true)

  const statusMismatch =
    selectedAgent &&
    ((mode === 'draft' && selectedAgent.status !== 'DRAFT') ||
      (mode === 'published' && selectedAgent.status !== 'PUBLISHED'))

  return (
    <div className="run-view">
      <section className="panel">
        <div className="pane-head">
          <h2>Run an agent</h2>
          <div className="mode-switch">
            <button
              type="button"
              className={mode === 'draft' ? 'tab active' : 'tab'}
              onClick={() => { setMode('draft'); resetResults() }}
            >
              Draft test
            </button>
            <button
              type="button"
              className={mode === 'published' ? 'tab active' : 'tab'}
              onClick={() => { setMode('published'); resetResults() }}
            >
              Published run
            </button>
          </div>
        </div>

        <p className="dim small">
          {mode === 'draft'
            ? 'Transient execution of a DRAFT agent. Nothing is persisted — no run, turn, or HITL rows — so the conversation is returned inline and non-READ tools are mocked.'
            : 'Executes the tenant’s PUBLISHED definition. Runs, turns, tool invocations, and human interactions are persisted and the turn trace is read back from the runtime.'}
        </p>

        <div className="form-grid">
          <Field label="Agent">
            <select value={agentId} onChange={(e) => { setAgentId(e.target.value); resetResults() }}>
              <option value="">— select —</option>
              {agents.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.definition.name} · {a.status} · v{a.version}
                </option>
              ))}
            </select>
          </Field>

          <Field label="Provider" hint="optional; falls back to the runtime default">
            <select value={provider} onChange={(e) => setProvider(e.target.value as Provider | '')}>
              <option value="">— default —</option>
              {PROVIDERS.map((p) => <option key={p} value={p}>{p}</option>)}
            </select>
          </Field>

          <Field label="Model" hint="optional; pairs with provider">
            <input className="mono" value={model} onChange={(e) => setModel(e.target.value)} placeholder="gemini-3.6-flash" />
          </Field>
        </div>

        {statusMismatch && selectedAgent && (
          <Banner kind="warn">
            {mode === 'draft'
              ? `Draft tests require a DRAFT agent; ${selectedAgent.definition.name} is ${selectedAgent.status}.`
              : `Published runs require a PUBLISHED agent; ${selectedAgent.definition.name} is ${selectedAgent.status}.`}
          </Banner>
        )}

        <Field label="Task" hint={mode === 'published' ? 'required' : 'optional for a draft test'}>
          <textarea rows={3} value={task} onChange={(e) => setTask(e.target.value)} />
        </Field>

        <JsonField
          label="Input"
          hint="required object"
          value={input}
          allowEmpty={false}
          onChange={(v, ok) => { setJsonValid((s) => ({ ...s, input: ok })); if (ok) setInput(v) }}
        />

        {mode === 'draft' && (
          <JsonField
            label="Mock tool results"
            hint='keyed by published tool name, e.g. {"campaign.send": {"status": "SIMULATED"}}'
            value={mockToolResults}
            rows={5}
            onChange={(v, ok) => { setJsonValid((s) => ({ ...s, mocks: ok })); if (ok) setMockToolResults(v) }}
          />
        )}

        <div className="actions">
          <button
            type="button"
            disabled={!canStart}
            onClick={() => void (mode === 'draft' ? startDraftTest() : startPublishedRun())}
          >
            {mode === 'draft' ? 'Run draft test' : 'Start published run'}
          </button>
          {(draftResult || run) && (
            <button type="button" className="secondary" onClick={resetResults}>Clear</button>
          )}
          {busy && <Spinner />}
        </div>

        {mode === 'published' && (
          <div className="row-inline">
            <input
              className="grow mono"
              placeholder="or load an existing run by id"
              value={loadId}
              onChange={(e) => setLoadId(e.target.value)}
            />
            <button
              type="button"
              className="secondary"
              disabled={busy || !loadId.trim()}
              onClick={() => {
                resetResults()
                setBusy(true)
                void loadRun(loadId.trim()).catch(setError).finally(() => setBusy(false))
              }}
            >
              Load run
            </button>
          </div>
        )}

        <ErrorBanner error={error} onDismiss={() => setError(null)} />
        {notice && <Banner kind="info">{notice}</Banner>}
      </section>

      {/* ---------------- draft results ---------------- */}
      {draftResult && (
        <section className="panel">
          <div className="pane-head">
            <h2>Draft test result</h2>
            <div className="pane-actions">
              <StatusPill status={draftResult.status} />
              <UsageChips usage={draftResult.usage} prefix="aggregate" />
            </div>
          </div>
          <div className="meta-row mono small dim">
            testRunId {draftResult.testRunId} · revision {draftResult.draftRevision.slice(0, 12)}
            {draftResult.model ? ` · ${draftResult.model}` : ''}
          </div>

          {draftResult.status === 'NEEDS_INPUT' && (
            <div className="hitl">
              <h3>Human input required</h3>
              <p className="dim small">
                Each answer carries a signed interaction token. Submitting replays the original
                request with every answer collected so far; if the draft changed since, the runtime
                returns 409 and the sequence must restart.
              </p>
              {draftResult.pendingInteractions.map((pending) => {
                const fallback: ResponseAction = pending.type === 'TOOL_APPROVAL' ? 'APPROVE' : 'ANSWER'
                const draft = answerFor(pending.interactionId, fallback)
                return (
                  <div className="interaction" key={pending.interactionId}>
                    <div className="interaction-head">
                      <span className="chip chip-strong">{pending.type}</span>
                      {pending.category && <span className="chip">{pending.category}</span>}
                      {pending.responseType && <span className="chip">{pending.responseType}</span>}
                    </div>
                    {pending.question && <p className="question">{pending.question}</p>}
                    {pending.reason && <p className="dim small">{pending.reason}</p>}
                    <JsonBlock value={pending.request} label="request" />
                    <div className="row-inline">
                      <select
                        value={draft.action}
                        onChange={(e) => setAnswer(pending.interactionId, { action: e.target.value as ResponseAction }, fallback)}
                      >
                        {pending.type === 'TOOL_APPROVAL'
                          ? <><option value="APPROVE">APPROVE</option><option value="REJECT">REJECT</option></>
                          : <option value="ANSWER">ANSWER</option>}
                      </select>
                      {draft.action === 'ANSWER' && (
                        <input
                          className="grow"
                          placeholder="answer — JSON is parsed, anything else is sent as text"
                          value={draft.answerText}
                          onChange={(e) => setAnswer(pending.interactionId, { answerText: e.target.value }, fallback)}
                        />
                      )}
                    </div>
                  </div>
                )
              })}
              <div className="actions">
                <button type="button" disabled={busy} onClick={() => void continueDraftTest()}>
                  Submit answers and continue
                </button>
              </div>
            </div>
          )}

          {draftResult.mockedToolCalls.length > 0 && (
            <div className="subsection">
              <h3>Mocked tool calls</h3>
              {draftResult.mockedToolCalls.map((call, i) => (
                <div className="invocation" key={i}>
                  <div className="invocation-head">
                    <span className="tool-name mono">{call.toolName}</span>
                    {call.operation && <span className="chip">{call.operation}</span>}
                    <span className="chip">mocked</span>
                  </div>
                  <JsonBlock value={call.arguments} label="arguments" />
                  <JsonBlock value={call.result} label="result" />
                </div>
              ))}
            </div>
          )}

          <div className="subsection">
            <h3>Conversation</h3>
            <p className="dim small">
              A draft test persists nothing, so per-turn token usage is not available — only the
              aggregate above.
            </p>
            <DraftConversationView conversation={draftResult.conversation} />
          </div>

          {draftResult.output != null && (
            <div className="subsection">
              <h3>Output</h3>
              <JsonBlock value={draftResult.output} />
            </div>
          )}
        </section>
      )}

      {/* ---------------- published results ---------------- */}
      {run && (
        <section className="panel">
          <div className="pane-head">
            <h2>Run {run.runId.slice(0, 8)}</h2>
            <div className="pane-actions">
              <StatusPill status={run.status} />
              <label className="toggle">
                <input type="checkbox" checked={autoPoll} onChange={(e) => setAutoPoll(e.target.checked)} />
                auto-refresh
              </label>
              <button type="button" className="link" onClick={() => void loadRun(run.runId)}>refresh now</button>
            </div>
          </div>

          <div className="meta-row mono small dim">
            root {run.rootRunId.slice(0, 8)} · agent {run.agentId.slice(0, 8)} v{run.agentVersion}
            {run.model ? ` · ${run.model}` : ''}
            {run.startedAt ? ` · started ${new Date(run.startedAt).toLocaleTimeString()}` : ''}
          </div>

          <Banner kind="info">
            Token usage comes from the turn trace below. <code>GET /agent-runs/{'{runId}'}</code>
            {' '}reports <code>usage</code> as zero for an already-started run, so the trace totals are
            the reliable figure.
          </Banner>

          {run.pendingInteractions.length > 0 && (
            <div className="hitl">
              <h3>Human input required</h3>
              <p className="dim small">
                Answered as <span className="mono">{identity.userId}</span> with roles{' '}
                <span className="mono">{identity.roles.join(', ') || 'none'}</span>. An interaction whose
                audience excludes this identity is rejected — switch identity in the top bar to test routing.
              </p>
              {run.pendingInteractions.map((pending: PendingInteractionSummary) => {
                const fallback: ResponseAction = pending.type === 'TOOL_APPROVAL' ? 'APPROVE' : 'ANSWER'
                const draft = answerFor(pending.interactionId, fallback)
                return (
                  <div className="interaction" key={pending.interactionId}>
                    <div className="interaction-head">
                      <span className="chip chip-strong">{pending.type}</span>
                      {pending.responseType && <span className="chip">{pending.responseType}</span>}
                      {pending.audienceType && (
                        <span className="chip">
                          {pending.audienceType}
                          {pending.audienceValues?.length ? `: ${pending.audienceValues.join(', ')}` : ''}
                        </span>
                      )}
                      {pending.assignedUserId && <span className="chip">assigned {pending.assignedUserId}</span>}
                      <span className="mono dim small">{pending.interactionId.slice(0, 8)}</span>
                    </div>
                    {pending.question && <p className="question">{pending.question}</p>}
                    <div className="row-inline">
                      <select
                        value={draft.action}
                        onChange={(e) => setAnswer(pending.interactionId, { action: e.target.value as ResponseAction }, fallback)}
                      >
                        {pending.type === 'TOOL_APPROVAL'
                          ? <><option value="APPROVE">APPROVE</option><option value="REJECT">REJECT</option></>
                          : <option value="ANSWER">ANSWER</option>}
                      </select>
                      {draft.action === 'ANSWER' && (
                        <input
                          className="grow"
                          placeholder="answer — JSON is parsed, anything else is sent as text"
                          value={draft.answerText}
                          onChange={(e) => setAnswer(pending.interactionId, { answerText: e.target.value }, fallback)}
                        />
                      )}
                      <input
                        placeholder="comment (optional)"
                        value={draft.comment}
                        onChange={(e) => setAnswer(pending.interactionId, { comment: e.target.value }, fallback)}
                      />
                    </div>
                  </div>
                )
              })}
              <div className="actions">
                <button type="button" disabled={busy} onClick={() => void submitRootResponses()}>
                  Submit all responses
                </button>
              </div>
            </div>
          )}

          <div className="subsection">
            <h3>Turns and token usage</h3>
            {trace
              ? <TraceView trace={trace} />
              : <p className="dim small">No trace loaded yet.</p>}
          </div>

          {run.output != null && (
            <div className="subsection">
              <h3>Output</h3>
              <JsonBlock value={run.output} />
            </div>
          )}

          <div className="subsection">
            <h3>Run controls</h3>
            <div className="row-inline">
              <input
                className="grow"
                placeholder="add a human instruction to this run"
                value={instruction}
                onChange={(e) => setInstruction(e.target.value)}
              />
              <button type="button" className="secondary" disabled={busy || !instruction.trim()} onClick={() => void sendInstruction()}>
                Send instruction
              </button>
              <button
                type="button"
                className="secondary danger"
                disabled={busy || TERMINAL.includes(run.status)}
                onClick={() => void cancelRun()}
              >
                Cancel run
              </button>
            </div>
          </div>
        </section>
      )}
    </div>
  )
}
