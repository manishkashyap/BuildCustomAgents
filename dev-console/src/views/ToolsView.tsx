import { useCallback, useEffect, useMemo, useState } from 'react'
import { toolsApi } from '../lib/api'
import { useIdentity } from '../lib/identity'
import { Banner, ErrorBanner, Field, JsonField, Spinner, StatusPill } from '../components/Primitives'
import type { ToolDefinition, ToolResponse, ToolStatus, ToolType } from '../lib/types'

const TOOL_TYPES: ToolType[] = ['HTTP', 'MCP', 'SQL_QUERY', 'FUNCTION', 'BUILT_IN', 'CUSTOM_AGENT']

const BLANK: ToolDefinition = {
  name: '',
  description: '',
  type: 'HTTP',
  inputSchema: { type: 'object', properties: {}, required: [] },
  outputSchema: null,
  configuration: { method: 'GET', url: 'https://api.example.com/v1/resource' },
  executionPolicy: null,
}

/**
 * An approval-requiring policy, offered as one click because it is the precondition for exercising
 * TOOL_APPROVAL human interactions and is tedious to retype.
 */
const APPROVAL_POLICY = {
  operation: 'EXTERNAL_COMMUNICATION',
  riskLevel: 'HIGH',
  approval: {
    required: true,
    audience: { type: 'ROLE', values: ['APPROVER'] },
    allowSelfApproval: false,
    expiresAfterSeconds: 86400,
    onReject: 'RETURN_TO_AGENT',
    onExpire: 'REJECT',
  },
}

export function ToolsView() {
  const identity = useIdentity()
  const [tools, setTools] = useState<ToolResponse[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [draft, setDraft] = useState<ToolDefinition>(BLANK)
  const [invalidJson, setInvalidJson] = useState<Set<string>>(new Set())
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setTools(await toolsApi.list(identity))
      setError(null)
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [identity])

  useEffect(() => { void refresh() }, [refresh])

  const selected = useMemo(
    () => tools.find((t) => t.id === selectedId) ?? null,
    [tools, selectedId],
  )

  function selectTool(tool: ToolResponse | null) {
    setSelectedId(tool?.id ?? null)
    setDraft(tool ? { ...tool.definition } : BLANK)
    setInvalidJson(new Set())
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

  const canSubmit =
    invalidJson.size === 0 && draft.name.trim() !== '' && draft.description.trim() !== ''

  async function save() {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const saved = selected
        ? await toolsApi.update(identity, selected.id, draft)
        : await toolsApi.create(identity, draft)
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

  async function setStatus(status: ToolStatus) {
    if (!selected) return
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      await toolsApi.setStatus(identity, selected.id, status)
      setNotice(`${selected.definition.name} → ${status}`)
      await refresh()
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="split">
      <aside className="list-pane">
        <div className="pane-head">
          <h2>Tools</h2>
          <div className="pane-actions">
            <button type="button" className="link" onClick={() => void refresh()}>refresh</button>
            <button type="button" onClick={() => selectTool(null)}>+ new</button>
          </div>
        </div>
        {loading && <Spinner label="loading tools…" />}
        {!loading && tools.length === 0 && <p className="dim small">No tools in this tenant yet.</p>}
        <ul className="list">
          {tools.map((tool) => (
            <li key={tool.id}>
              <button
                type="button"
                className={tool.id === selectedId ? 'list-item active' : 'list-item'}
                onClick={() => selectTool(tool)}
              >
                <span className="list-title mono">{tool.definition.name}</span>
                <span className="list-meta">
                  <StatusPill status={tool.status} />
                  <span className="chip">{tool.definition.type}</span>
                  <span className="dim">v{tool.version}</span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      </aside>

      <section className="detail-pane">
        <div className="pane-head">
          <h2>{selected ? `Edit ${selected.definition.name}` : 'New tool'}</h2>
          {selected && (
            <div className="pane-actions">
              <StatusPill status={selected.status} />
              <span className="dim small mono">{selected.id}</span>
            </div>
          )}
        </div>

        <ErrorBanner error={error} onDismiss={() => setError(null)} />
        {notice && <Banner kind="ok">{notice}</Banner>}
        {selected && selected.status !== 'DRAFT' && (
          <Banner kind="warn">
            This tool is {selected.status}. Editing a non-draft tool is rejected by the management
            service — publish a change by editing while it is DRAFT. There is no disable control
            because the status endpoint only accepts DRAFT → PUBLISHED; to stop a published HTTP tool
            from reaching its API, revoke its host under the Egress tab.
          </Banner>
        )}

        <Field label="Name" hint="the identifier the model sees; referenced by an agent's allowedTools">
          <input
            className="mono"
            value={draft.name}
            onChange={(e) => setDraft({ ...draft, name: e.target.value })}
            placeholder="campaign.send"
          />
        </Field>

        <Field label="Description" hint="shown to the model — counts against the publish-time prompt budget">
          <textarea
            rows={2}
            value={draft.description}
            onChange={(e) => setDraft({ ...draft, description: e.target.value })}
          />
        </Field>

        <Field label="Type">
          <select
            value={draft.type}
            onChange={(e) => setDraft({ ...draft, type: e.target.value as ToolType })}
          >
            {TOOL_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        </Field>

        <JsonField
          label="Input schema"
          hint="required"
          value={draft.inputSchema}
          allowEmpty={false}
          onChange={(v, ok) => { markJson('inputSchema', ok); if (ok) setDraft({ ...draft, inputSchema: v }) }}
        />

        <JsonField
          label="Output schema"
          hint="optional"
          value={draft.outputSchema}
          onChange={(v, ok) => { markJson('outputSchema', ok); if (ok) setDraft({ ...draft, outputSchema: v }) }}
        />

        <JsonField
          label="Configuration"
          hint="required — HTTP tools need method and url; the URL host must be allowed under the Egress tab before this tool can be published"
          value={draft.configuration}
          allowEmpty={false}
          onChange={(v, ok) => { markJson('configuration', ok); if (ok) setDraft({ ...draft, configuration: v }) }}
        />

        <div className="inline-actions">
          <button
            type="button"
            className="link"
            onClick={() => setDraft({ ...draft, executionPolicy: APPROVAL_POLICY })}
          >
            fill approval-required policy
          </button>
          <button
            type="button"
            className="link"
            onClick={() => setDraft({ ...draft, executionPolicy: null })}
          >
            clear policy
          </button>
        </div>
        <JsonField
          label="Execution policy"
          hint="optional — set approval.required to exercise TOOL_APPROVAL interactions"
          value={draft.executionPolicy}
          rows={12}
          onChange={(v, ok) => { markJson('executionPolicy', ok); if (ok) setDraft({ ...draft, executionPolicy: v }) }}
        />

        <div className="actions">
          <button type="button" disabled={!canSubmit || busy} onClick={() => void save()}>
            {selected ? 'Save changes' : 'Create tool'}
          </button>
          {selected && selected.status === 'DRAFT' && (
            <button type="button" disabled={busy} onClick={() => void setStatus('PUBLISHED')}>
              Publish
            </button>
          )}
          {selected && selected.status === 'DISABLED' && (
            <button type="button" disabled={busy} onClick={() => void setStatus('PUBLISHED')}>
              Re-publish
            </button>
          )}
          {busy && <Spinner />}
          {invalidJson.size > 0 && (
            <span className="field-error">fix JSON: {[...invalidJson].join(', ')}</span>
          )}
        </div>
      </section>
    </div>
  )
}
