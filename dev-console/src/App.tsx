import { useEffect, useMemo, useState } from 'react'
import { health } from './lib/api'
import {
  ALL_ROLES, IdentityContext, loadIdentity, saveIdentity, type Identity,
} from './lib/identity'
import { AgentsView } from './views/AgentsView'
import { EgressView } from './views/EgressView'
import { InboxView } from './views/InboxView'
import { RunView, type RunTarget } from './views/RunView'
import { ToolsView } from './views/ToolsView'

type Tab = 'tools' | 'agents' | 'run' | 'inbox' | 'egress'

const TABS: { id: Tab; label: string }[] = [
  { id: 'tools', label: 'Tools' },
  { id: 'agents', label: 'Agents' },
  { id: 'run', label: 'Run' },
  { id: 'inbox', label: 'HITL inbox' },
  { id: 'egress', label: 'Egress' },
]

function IdentityBar({
  identity, onChange,
}: { identity: Identity; onChange: (next: Identity) => void }) {
  const [open, setOpen] = useState(false)

  function toggleRole(role: string) {
    const roles = identity.roles.includes(role)
      ? identity.roles.filter((r) => r !== role)
      : [...identity.roles, role]
    onChange({ ...identity, roles })
  }

  return (
    <div className="identity">
      <button type="button" className="identity-summary" onClick={() => setOpen(!open)}>
        <span className="mono">{identity.licenseCode}</span>
        <span className="sep">/</span>
        <span className="mono">{identity.userId}</span>
        <span className="chip">{identity.roles.length} roles</span>
        <span className="caret">{open ? '▴' : '▾'}</span>
      </button>

      {open && (
        <div className="identity-panel">
          <p className="dim small">
            Sent as <span className="mono">X-Agent-License-Code</span>,{' '}
            <span className="mono">X-Agent-User-Id</span>, and{' '}
            <span className="mono">X-Agent-Roles</span>. With{' '}
            <span className="mono">agent-platform.security.enabled=false</span> (the compose default)
            both services trust these headers directly, so this is the whole authorization surface.
          </p>
          <label className="field">
            <span className="field-label">License code <span className="field-hint">tenant boundary</span></span>
            <input
              className="mono"
              value={identity.licenseCode}
              onChange={(e) => onChange({ ...identity, licenseCode: e.target.value })}
            />
          </label>
          <label className="field">
            <span className="field-label">User id <span className="field-hint">the acting actor</span></span>
            <input
              className="mono"
              value={identity.userId}
              onChange={(e) => onChange({ ...identity, userId: e.target.value })}
            />
          </label>
          <div className="field">
            <span className="field-label">Roles</span>
            <div className="checkbox-grid">
              {ALL_ROLES.map((role) => (
                <label className="checkbox" key={role}>
                  <input
                    type="checkbox"
                    checked={identity.roles.includes(role)}
                    onChange={() => toggleRole(role)}
                  />
                  <span className="mono">{role}</span>
                </label>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

function HealthDot({ label, up }: { label: string; up: boolean | null }) {
  const cls = up === null ? 'dot dot-unknown' : up ? 'dot dot-up' : 'dot dot-down'
  const title =
    up === null ? `${label}: checking` : up ? `${label}: reachable` : `${label}: unreachable`
  return (
    <span className="health" title={title}>
      <span className={cls} />
      {label}
    </span>
  )
}

export default function App() {
  const [identity, setIdentity] = useState<Identity>(loadIdentity)
  const [tab, setTab] = useState<Tab>('tools')
  const [runTarget, setRunTarget] = useState<RunTarget | null>(null)
  const [openRunId, setOpenRunId] = useState<string | null>(null)
  const [mgmtUp, setMgmtUp] = useState<boolean | null>(null)
  const [runtimeUp, setRuntimeUp] = useState<boolean | null>(null)

  useEffect(() => {
    saveIdentity(identity)
  }, [identity])

  useEffect(() => {
    let cancelled = false
    async function check() {
      const [m, r] = await Promise.all([health.management(), health.runtime()])
      if (!cancelled) { setMgmtUp(m); setRuntimeUp(r) }
    }
    void check()
    const timer = setInterval(() => void check(), 15000)
    return () => { cancelled = true; clearInterval(timer) }
  }, [])

  // Re-mounting the views on identity change drops any list loaded for the previous tenant.
  const scopeKey = useMemo(
    () => `${identity.licenseCode}::${identity.userId}::${identity.roles.join(',')}`,
    [identity],
  )

  function testAgent(agentId: string, published: boolean) {
    setRunTarget({ agentId, published })
    setOpenRunId(null)
    setTab('run')
  }

  function openRun(rootRunId: string) {
    setOpenRunId(rootRunId)
    setRunTarget(null)
    setTab('run')
  }

  return (
    <IdentityContext.Provider value={identity}>
      <header className="topbar">
        <div className="brand">
          Agent Platform <span className="dim">dev console</span>
        </div>
        <nav className="tabs">
          {TABS.map((t) => (
            <button
              key={t.id}
              type="button"
              className={tab === t.id ? 'tab active' : 'tab'}
              onClick={() => setTab(t.id)}
            >
              {t.label}
            </button>
          ))}
        </nav>
        <div className="topbar-right">
          <HealthDot label="management" up={mgmtUp} />
          <HealthDot label="runtime" up={runtimeUp} />
          <IdentityBar identity={identity} onChange={setIdentity} />
        </div>
      </header>

      {(mgmtUp === false || runtimeUp === false) && (
        <div className="banner banner-error global">
          {mgmtUp === false && runtimeUp === false
            ? 'Neither service is reachable through the dev proxy.'
            : `${mgmtUp === false ? 'agent-management' : 'agent-runtime'} is not reachable through the dev proxy.`}
          {' '}Check that the stack is up and that MANAGEMENT_BASE_URL / RUNTIME_BASE_URL in .env match
          its published ports.
        </div>
      )}

      <main key={scopeKey}>
        {tab === 'tools' && <ToolsView />}
        {tab === 'agents' && <AgentsView onTestAgent={testAgent} />}
        {tab === 'run' && <RunView target={runTarget} openRunId={openRunId} />}
        {tab === 'inbox' && <InboxView onOpenRun={openRun} />}
        {tab === 'egress' && <EgressView />}
      </main>
    </IdentityContext.Provider>
  )
}
