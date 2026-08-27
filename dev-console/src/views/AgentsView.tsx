import { useCallback, useEffect, useMemo, useState } from 'react'
import { agentsApi, toolsApi } from '../lib/api'
import { useIdentity } from '../lib/identity'
import {
  Banner, ErrorBanner, Field, JsonBlock, JsonField, Spinner, StatusPill, StringList,
} from '../components/Primitives'
import type { AgentDefinition, AgentResponse, AgentStatus, ToolResponse } from '../lib/types'

const BLANK: AgentDefinition = {
  name: '',
  description: '',
  role: '',
  instructions: '',
  rules: [],
  outputFormat: null,
  outputSchema: null,
  context: null,
  examples: [],
  allowedTools: [],
  humanInteractionPolicy: null,
}

/** Makes the agent ask a human instead of guessing — the precondition for CLARIFICATION interactions. */
const CLARIFICATION_POLICY = {
  clarification: {
    defaultAudience: { type: 'RUN_REQUESTER', values: [] },
    expiresAfterSeconds: 86400,
    onExpire: 'FAIL_CHILD',
    maxRequestsPerRootRun: 20,
  },
}

export function AgentsView({ onTestAgent }: { onTestAgent: (agentId: string, published: boolean) => void }) {
  const identity = useIdentity()
  const [agents, setAgents] = useState<AgentResponse[]>([])
  const [tools, setTools] = useState<ToolResponse[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [draft, setDraft] = useState<AgentDefinition>(BLANK)
  const [invalidJson, setInvalidJson] = useState<Set<string>>(new Set())
  const [changeReason, setChangeReason] = useState('')
  const [copyName, setCopyName] = useState('')
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const [agentList, toolList] = await Promise.all([
        agentsApi.list(identity),
        toolsApi.list(identity).catch(() => [] as ToolResponse[]),
      ])
      setAgents(agentList)
      setTools(toolList)
      setError(null)
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [identity])

  useEffect(() => { void refresh() }, [refresh])

  const selected = useMemo(
    () => agents.find((a) => a.id === selectedId) ?? null,
    [agents, selectedId],
  )
  // Only published tools can be attached to an agent that will ever be published.
  const publishedTools = useMemo(() => tools.filter((t) => t.status === 'PUBLISHED'), [tools])

  function selectAgent(agent: AgentResponse | null) {
    setSelectedId(agent?.id ?? null)
    setDraft(agent ? { ...agent.definition } : BLANK)
    setInvalidJson(new Set())
    setChangeReason('')
    setCopyName(agent ? `${agent.definition.name} copy` : '')
    setNotice(null)
    setError(null)
  }

  function markJson(key: string, valid: boolean) {
    setInvalidJson((prev) => {
      const next = new Set(prev)
      if (valid) next.delete(key)
      else next.add(key)
      return next
    })
  }

  const isDraft = !selected || selected.status === 'DRAFT'
  const canSubmit =
    invalidJson.size === 0 &&
    draft.name.trim() !== '' &&
    draft.role.trim() !== '' &&
    draft.instructions.trim() !== ''

  async function save() {
    setBusy(true); setError(null); setNotice(null)
    try {
      const saved = selected
        ? await agentsApi.patch(identity, selected.id, draft as unknown as Record<string, unknown>, changeReason || undefined)
        : await agentsApi.create(identity, draft, changeReason || undefined)
      setNotice(selected ? `Updated ${saved.definition.name}` : `Created ${saved.definition.name}`)
      await refresh()
      setSelectedId(saved.id)
      setDraft({ ...saved.definition })
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  async function setStatus(status: AgentStatus) {
    if (!selected) return
    setBusy(true); setError(null); setNotice(null)
    try {
      await agentsApi.setStatus(identity, selected.id, status, changeReason || undefined)
      setNotice(`${selected.definition.name} → ${status}`)
      await refresh()
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  async function copy() {
    if (!selected || !copyName.trim()) return
    setBusy(true); setError(null); setNotice(null)
    try {
      const created = await agentsApi.copy(identity, selected.id, copyName.trim())
      setNotice(`Copied to new DRAFT ${created.definition.name}`)
      await refresh()
      setSelectedId(created.id)
      setDraft({ ...created.definition })
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  function toggleTool(name: string) {
    const has = draft.allowedTools.includes(name)
    setDraft({
      ...draft,
      allowedTools: has
        ? draft.allowedTools.filter((t) => t !== name)
        : [...draft.allowedTools, name],
    })
  }

  return (
    <div className="split">
      <aside className="list-pane">
        <div className="pane-head">
          <h2>Agents</h2>
          <div className="pane-actions">
            <button type="button" className="link" onClick={() => void refresh()}>refresh</button>
            <button type="button" onClick={() => selectAgent(null)}>+ new</button>
          </div>
        </div>
        {loading && <Spinner label="loading agents…" />}
        {!loading && agents.length === 0 && <p className="dim small">No agents in this tenant yet.</p>}
        <ul className="list">
          {agents.map((agent) => (
            <li key={agent.id}>
              <button
                type="button"
                className={agent.id === selectedId ? 'list-item active' : 'list-item'}
                onClick={() => selectAgent(agent)}
              >
                <span className="list-title">{agent.definition.name}</span>
                <span className="list-meta">
                  <StatusPill status={agent.status} />
                  <span className="dim">v{agent.version}</span>
                  <span className="dim">{agent.definition.allowedTools.length} tools</span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      </aside>

      <section className="detail-pane">
        <div className="pane-head">
          <h2>{selected ? `Edit ${selected.definition.name}` : 'New agent'}</h2>
          {selected && (
            <div className="pane-actions">
              <StatusPill status={selected.status} />
              <span className="dim small mono">{selected.id}</span>
            </div>
          )}
        </div>

        <ErrorBanner error={error} onDismiss={() => setError(null)} />
        {notice && <Banner kind="ok">{notice}</Banner>}
        {selected && !isDraft && (
          <Banner kind="warn">
            This agent is {selected.status}. Only DRAFT agents accept edits — use Copy to start a new
            draft from it.
          </Banner>
        )}

        {selected && (
          <div className="run-shortcuts">
            <button type="button" className="secondary" onClick={() => onTestAgent(selected.id, false)}>
              Test as draft →
            </button>
            <button
              type="button"
              className="secondary"
              disabled={selected.status !== 'PUBLISHED'}
              title={selected.status !== 'PUBLISHED' ? 'publish the agent first' : undefined}
              onClick={() => onTestAgent(selected.id, true)}
            >
              Run published →
            </button>
          </div>
        )}

        <Field label="Name"><input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} /></Field>
        <Field label="Description">
          <textarea rows={2} value={draft.description ?? ''} onChange={(e) => setDraft({ ...draft, description: e.target.value })} />
        </Field>
        <Field label="Role" hint="required — how the agent is framed to the model">
          <input value={draft.role} onChange={(e) => setDraft({ ...draft, role: e.target.value })} placeholder="platform campaign quality analyst" />
        </Field>
        <Field label="Instructions" hint="required">
          <textarea rows={6} value={draft.instructions} onChange={(e) => setDraft({ ...draft, instructions: e.target.value })} />
        </Field>

        <StringList
          label="Rules"
          hint="one constraint per line"
          values={draft.rules}
          onChange={(rules) => setDraft({ ...draft, rules })}
          placeholder="Never send a campaign without approval"
        />

        <Field label="Output format">
          <input value={draft.outputFormat ?? ''} onChange={(e) => setDraft({ ...draft, outputFormat: e.target.value || null })} placeholder="Return JSON" />
        </Field>

        <div className="field">
          <span className="field-label">
            Allowed tools
            <span className="field-hint">
              published tools only — publishing the agent fails if a listed tool is not published
            </span>
          </span>
          {publishedTools.length === 0 && (
            <p className="dim small">No published tools. Create and publish one in the Tools tab first.</p>
          )}
          <div className="checkbox-grid">
            {publishedTools.map((tool) => (
              <label key={tool.id} className="checkbox">
                <input
                  type="checkbox"
                  checked={draft.allowedTools.includes(tool.definition.name)}
                  onChange={() => toggleTool(tool.definition.name)}
                />
                <span className="mono">{tool.definition.name}</span>
                <span className="chip">{tool.definition.type}</span>
              </label>
            ))}
          </div>
          {draft.allowedTools.some((name) => !publishedTools.some((t) => t.definition.name === name)) && (
            <Banner kind="warn">
              Referenced tools not currently published:{' '}
              <span className="mono">
                {draft.allowedTools.filter((n) => !publishedTools.some((t) => t.definition.name === n)).join(', ')}
              </span>
            </Banner>
          )}
        </div>

        <JsonField label="Output schema" value={draft.outputSchema}
          onChange={(v, ok) => { markJson('outputSchema', ok); if (ok) setDraft({ ...draft, outputSchema: v }) }} />
        <JsonField label="Context" value={draft.context}
          onChange={(v, ok) => { markJson('context', ok); if (ok) setDraft({ ...draft, context: v }) }} />
        <JsonField label="Examples" hint="array of {description, input, expectedOutput}" value={draft.examples} rows={8}
          onChange={(v, ok) => {
            markJson('examples', ok)
            if (ok) setDraft({ ...draft, examples: Array.isArray(v) ? v as AgentDefinition['examples'] : [] })
          }} />

        <div className="inline-actions">
          <button type="button" className="link" onClick={() => setDraft({ ...draft, humanInteractionPolicy: CLARIFICATION_POLICY })}>
            fill clarification policy
          </button>
          <button type="button" className="link" onClick={() => setDraft({ ...draft, humanInteractionPolicy: null })}>
            clear policy
          </button>
        </div>
        <JsonField
          label="Human interaction policy"
          hint="enables the built-in request_clarification tool routing"
          value={draft.humanInteractionPolicy}
          rows={10}
          onChange={(v, ok) => { markJson('humanInteractionPolicy', ok); if (ok) setDraft({ ...draft, humanInteractionPolicy: v }) }}
        />

        <Field label="Change reason" hint="optional, max 1000 chars — sent as X-Agent-Change-Reason">
          <input value={changeReason} onChange={(e) => setChangeReason(e.target.value)} />
        </Field>

        <div className="actions">
          <button type="button" disabled={!canSubmit || busy} onClick={() => void save()}>
            {selected ? 'Save draft' : 'Create agent'}
          </button>
          {selected?.status === 'DRAFT' && (
            <button type="button" disabled={busy} onClick={() => void setStatus('PUBLISHED')}>
              Publish
            </button>
          )}
          {selected?.status === 'PUBLISHED' && (
            <button type="button" className="secondary" disabled={busy} onClick={() => void setStatus('RETIRED')}>
              Retire
            </button>
          )}
          {busy && <Spinner />}
          {invalidJson.size > 0 && <span className="field-error">fix JSON: {[...invalidJson].join(', ')}</span>}
        </div>

        {selected && (
          <div className="subsection">
            <h3>Copy to a new draft</h3>
            <p className="dim small">
              The source must be PUBLISHED or RETIRED. The copy is an independent DRAFT at version 1
              with no stored lineage.
            </p>
            <div className="row-inline">
              <input value={copyName} onChange={(e) => setCopyName(e.target.value)} placeholder="new agent name" />
              <button
                type="button"
                className="secondary"
                disabled={busy || !copyName.trim() || !['PUBLISHED', 'RETIRED'].includes(selected.status)}
                onClick={() => void copy()}
              >
                Copy
              </button>
            </div>
          </div>
        )}

        {selected && (
          <div className="subsection">
            <h3>Stored definition</h3>
            <JsonBlock value={selected.definition} />
          </div>
        )}
      </section>
    </div>
  )
}
