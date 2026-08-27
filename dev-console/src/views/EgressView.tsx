import { useCallback, useEffect, useMemo, useState } from 'react'
import { egressApi, toolsApi } from '../lib/api'
import { useIdentity } from '../lib/identity'
import { Banner, ErrorBanner, Field, Spinner, StatusPill } from '../components/Primitives'
import type { EgressHostResponse, ToolResponse } from '../lib/types'

/** Mirrors EgressHostRules on the server, so the form can explain a rejection before sending it. */
function patternProblem(pattern: string): string | null {
  const value = pattern.trim().toLowerCase().replace(/\.+$/, '')
  if (!value) return 'required'
  const body = value.startsWith('*.') ? value.slice(2) : value
  if (value.startsWith('*.') && !body.includes('.')) {
    return 'a wildcard must cover at least two labels, e.g. *.example.com'
  }
  if (!/^[a-z0-9-]+(\.[a-z0-9-]+)*$/.test(body)) return 'not a valid host name'
  if (body.split('.').some((label) => label.startsWith('-') || label.endsWith('-'))) {
    return 'labels cannot start or end with "-"'
  }
  return null
}

function hostOf(url: string | undefined): string | null {
  if (!url) return null
  const withoutScheme = url.split('://')[1]
  if (!withoutScheme) return null
  const authority = withoutScheme.split(/[/?#]/)[0].split('@').pop() ?? ''
  const host = authority.split(':')[0].toLowerCase().replace(/\.+$/, '')
  return host || null
}

function covered(patterns: EgressHostResponse[], host: string): boolean {
  return patterns.some((entry) => {
    if (entry.status !== 'ACTIVE') return false
    const pattern = entry.hostPattern
    if (pattern.startsWith('*.')) {
      const suffix = pattern.slice(1)
      return host.endsWith(suffix) && host.length > suffix.length
    }
    return host === pattern
  })
}

/**
 * The tenant's HTTP egress allowlist. Writes need AGENT_ADMIN or PLATFORM_ADMIN — an editor who
 * could approve their own tool's destination would make the allowlist meaningless — so switching to
 * an editor-only identity in the top bar is how you check that the gate holds.
 */
export function EgressView() {
  const identity = useIdentity()
  const [hosts, setHosts] = useState<EgressHostResponse[]>([])
  const [tools, setTools] = useState<ToolResponse[]>([])
  const [pattern, setPattern] = useState('')
  const [description, setDescription] = useState('')
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const canWrite = identity.roles.some((r) => r === 'AGENT_ADMIN' || r === 'PLATFORM_ADMIN')

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const [hostList, toolList] = await Promise.all([
        egressApi.list(identity),
        toolsApi.list(identity).catch(() => [] as ToolResponse[]),
      ])
      setHosts(hostList)
      setTools(toolList)
      setError(null)
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [identity])

  useEffect(() => { void refresh() }, [refresh])

  // Which HTTP tools would fail to publish right now, so a revocation's blast radius is visible.
  const httpTools = useMemo(
    () => tools
      .filter((t) => t.definition.type === 'HTTP')
      .map((t) => ({
        tool: t,
        host: hostOf((t.definition.configuration as { url?: string } | null)?.url),
      })),
    [tools],
  )
  const uncovered = httpTools.filter(({ host }) => host && !covered(hosts, host))

  const problem = pattern.trim() ? patternProblem(pattern) : null

  async function add() {
    setBusy(true); setError(null); setNotice(null)
    try {
      const created = await egressApi.create(identity, pattern.trim(), description.trim())
      setNotice(`Allowed ${created.hostPattern}`)
      setPattern('')
      setDescription('')
      await refresh()
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  async function toggle(host: EgressHostResponse) {
    setBusy(true); setError(null); setNotice(null)
    try {
      const next = host.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
      await egressApi.update(identity, host.id, next, host.description)
      setNotice(`${host.hostPattern} → ${next}`)
      await refresh()
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  async function remove(host: EgressHostResponse) {
    setBusy(true); setError(null); setNotice(null)
    try {
      await egressApi.remove(identity, host.id)
      setNotice(`Removed ${host.hostPattern}`)
      await refresh()
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="panel">
      <div className="pane-head">
        <h2>HTTP egress allowlist</h2>
        <div className="pane-actions">
          <span className="chip">{identity.licenseCode}</span>
          <button type="button" className="link" onClick={() => void refresh()}>refresh</button>
        </div>
      </div>

      <p className="dim small">
        Hosts this tenant’s HTTP tools may reach. Enforced twice: publishing a tool whose host is not
        listed is rejected, and the runtime re-checks on every call, so revoking a host stops
        already-published agents within the allowlist cache TTL (30s by default).
      </p>
      <Banner kind="info">
        Reaching loopback, link-local (<code>169.254.169.254</code>), or private addresses is refused
        by the runtime regardless of what is listed here — that is platform policy, not tenant
        configuration, so an entry resolving to an internal address still fails.
      </Banner>

      <ErrorBanner error={error} onDismiss={() => setError(null)} />
      {notice && <Banner kind="ok">{notice}</Banner>}
      {!canWrite && (
        <Banner kind="warn">
          Read-only for this identity. Adding or revoking a host needs <span className="mono">AGENT_ADMIN</span>{' '}
          or <span className="mono">PLATFORM_ADMIN</span>; an editor who could approve their own tool’s
          destination would defeat the control.
        </Banner>
      )}

      {uncovered.length > 0 && (
        <Banner kind="warn">
          {uncovered.length} published HTTP tool{uncovered.length === 1 ? '' : 's'} point at a host no
          active entry covers, and will fail at run time:{' '}
          <span className="mono">
            {uncovered.map(({ tool, host }) => `${tool.definition.name} → ${host}`).join(', ')}
          </span>
        </Banner>
      )}

      {canWrite && (
        <div className="subsection">
          <h3>Allow a host</h3>
          <div className="form-grid">
            <Field label="Host pattern" hint="exact host, or *.example.com for subdomains">
              <input
                className={problem ? 'mono invalid' : 'mono'}
                value={pattern}
                onChange={(e) => setPattern(e.target.value)}
                placeholder="api.stripe.com"
              />
            </Field>
            <Field label="Description" hint="optional — why this host is allowed">
              <input value={description} onChange={(e) => setDescription(e.target.value)} />
            </Field>
          </div>
          {problem && <div className="field-error">{problem}</div>}
          <div className="actions">
            <button type="button" disabled={busy || !pattern.trim() || problem !== null} onClick={() => void add()}>
              Allow host
            </button>
            {busy && <Spinner />}
          </div>
        </div>
      )}

      <div className="subsection">
        <h3>Allowed hosts</h3>
        {loading && hosts.length === 0 && <Spinner label="loading allowlist…" />}
        {!loading && hosts.length === 0 && (
          <p className="dim small">
            Nothing allowed yet. Until a host is added, publishing any HTTP tool for this tenant is rejected.
          </p>
        )}
        {hosts.map((host) => {
          const usedBy = httpTools.filter(({ host: h }) => h && covered([host], h))
          return (
            <div className="interaction" key={host.id}>
              <div className="interaction-head">
                <span className="tool-name mono">{host.hostPattern}</span>
                <StatusPill status={host.status} />
                {usedBy.length > 0 && (
                  <span className="chip">
                    {usedBy.length} tool{usedBy.length === 1 ? '' : 's'}
                  </span>
                )}
                <span className="grow" />
                {canWrite && (
                  <>
                    <button type="button" className="link" disabled={busy} onClick={() => void toggle(host)}>
                      {host.status === 'ACTIVE' ? 'disable' : 'enable'}
                    </button>
                    <button type="button" className="link danger" disabled={busy} onClick={() => void remove(host)}>
                      remove
                    </button>
                  </>
                )}
              </div>
              {host.description && <p className="dim small">{host.description}</p>}
              <div className="meta-row mono small dim">
                added by {host.createdBy} · updated {new Date(host.updatedAt).toLocaleString()}
                {usedBy.length > 0 && ` · covers ${usedBy.map((u) => u.tool.definition.name).join(', ')}`}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
