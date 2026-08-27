import { useState } from 'react'
import { JsonBlock, StatusPill, UsageChips } from './Primitives'
import type {
  AgentMessage, AgentRunTraceResponse, DraftTestConversationEntry, TracedRun, TracedTurn,
} from '../lib/types'

function MessageRow({ message }: { message: AgentMessage }) {
  const calls = message.toolCalls ?? []
  return (
    <div className={`msg msg-${message.role.toLowerCase()}`}>
      <div className="msg-head">
        <span className="msg-role">{message.role}</span>
        {message.name && <span className="msg-name">{message.name}</span>}
        {message.toolCallId && <span className="msg-id mono">{message.toolCallId}</span>}
      </div>
      {message.content && <div className="msg-body">{message.content}</div>}
      {calls.length > 0 && (
        <div className="msg-calls">
          {calls.map((call, i) => (
            <div className="tool-call" key={call.id ?? i}>
              <span className="tool-name mono">{call.name}</span>
              <JsonBlock value={call.arguments} label="arguments" />
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/**
 * One published-run turn. The turn's own token usage is shown on the header so it is obvious which
 * turn spent what, rather than only the run-level aggregate.
 */
function TurnCard({ turn, showFullPrompt }: { turn: TracedTurn; showFullPrompt: boolean }) {
  const [open, setOpen] = useState(false)
  // Every turn resends the whole conversation, so by default show only what this turn added.
  const messages = showFullPrompt || open ? turn.messages : turn.messages.slice(-1)
  const hidden = turn.messages.length - messages.length

  return (
    <div className="turn">
      <div className="turn-head">
        <span className="turn-no">turn {turn.turnNumber}</span>
        {turn.finishReason && <span className="chip">{turn.finishReason}</span>}
        <UsageChips usage={turn.usage} />
        <span className="grow" />
        {turn.createdAt && <span className="ts">{new Date(turn.createdAt).toLocaleTimeString()}</span>}
      </div>

      {hidden > 0 && (
        <button type="button" className="link" onClick={() => setOpen(true)}>
          show {hidden} earlier prompt message{hidden === 1 ? '' : 's'}
        </button>
      )}
      {open && (
        <button type="button" className="link" onClick={() => setOpen(false)}>
          collapse prompt
        </button>
      )}

      <div className="msg-list">
        {messages.map((m, i) => <MessageRow key={i} message={m} />)}
      </div>

      {turn.text && (
        <div className="msg msg-assistant">
          <div className="msg-head"><span className="msg-role">ASSISTANT</span></div>
          <div className="msg-body">{turn.text}</div>
        </div>
      )}

      {turn.toolCalls.length > 0 && (
        <div className="msg-calls">
          {turn.toolCalls.map((call, i) => (
            <div className="tool-call" key={call.id ?? i}>
              <span className="tool-name mono">{call.name}</span>
              <JsonBlock value={call.arguments} label="arguments" />
            </div>
          ))}
        </div>
      )}

      {turn.toolInvocations.length > 0 && (
        <div className="invocations">
          <div className="section-label">tool invocations</div>
          {turn.toolInvocations.map((inv) => (
            <div className="invocation" key={inv.invocationId ?? inv.toolCallId}>
              <div className="invocation-head">
                <span className="tool-name mono">{inv.toolName}</span>
                {inv.toolType && <span className="chip">{inv.toolType}</span>}
                <StatusPill status={inv.status} />
                {inv.durationMs != null && <span className="chip">{inv.durationMs} ms</span>}
                {inv.childRunId && (
                  <span className="chip chip-link mono" title="child run started by this tool">
                    child {inv.childRunId.slice(0, 8)}
                  </span>
                )}
              </div>
              <JsonBlock value={inv.arguments} label="arguments" />
              <JsonBlock value={inv.result} label="result" />
              {inv.errorMessage && <div className="err">{inv.errorMessage}</div>}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function RunCard({ run, showFullPrompt }: { run: TracedRun; showFullPrompt: boolean }) {
  return (
    <div className="traced-run" style={{ marginLeft: run.depth * 16 }}>
      <div className="traced-run-head">
        {run.depth > 0 && <span className="chip">depth {run.depth}</span>}
        <span className="mono">{run.runId.slice(0, 8)}</span>
        <StatusPill status={run.status} />
        <span className="dim">agent {run.agentId.slice(0, 8)} v{run.agentVersion}</span>
        {run.model && <span className="chip">{run.model}</span>}
        <span className="grow" />
        <UsageChips usage={run.usage} prefix="run" />
      </div>
      {run.errorMessage && <div className="err">{run.errorMessage}</div>}
      {run.turns.length === 0 && <div className="dim small">no turns recorded yet</div>}
      {run.turns.map((turn) => (
        <TurnCard key={turn.turnNumber} turn={turn} showFullPrompt={showFullPrompt} />
      ))}
      <JsonBlock value={run.output} label="run output" />
    </div>
  )
}

/** Published-run trace: the whole run tree, turn by turn, with per-turn token usage. */
export function TraceView({ trace }: { trace: AgentRunTraceResponse }) {
  const [showFullPrompt, setShowFullPrompt] = useState(false)
  return (
    <div className="trace">
      <div className="trace-head">
        <strong>{trace.turnCount}</strong> turn{trace.turnCount === 1 ? '' : 's'} across{' '}
        <strong>{trace.runs.length}</strong> run{trace.runs.length === 1 ? '' : 's'}
        <UsageChips usage={trace.usage} prefix="total" />
        <span className="grow" />
        <label className="toggle">
          <input
            type="checkbox"
            checked={showFullPrompt}
            onChange={(e) => setShowFullPrompt(e.target.checked)}
          />
          expand full prompt per turn
        </label>
      </div>
      {trace.runs.map((run) => (
        <RunCard key={run.runId} run={run} showFullPrompt={showFullPrompt} />
      ))}
    </div>
  )
}

/**
 * Draft-test conversation. A draft test writes nothing to the database, so Runtime returns the
 * conversation inline and there is no trace endpoint to call: usage is only available in aggregate.
 */
export function DraftConversationView({
  conversation,
}: { conversation: DraftTestConversationEntry[] }) {
  if (conversation.length === 0) return <div className="dim small">no conversation returned</div>
  const groups = new Map<string, DraftTestConversationEntry[]>()
  for (const entry of conversation) {
    const key = `${entry.depth}::${entry.agentId ?? 'root'}`
    const list = groups.get(key)
    if (list) list.push(entry)
    else groups.set(key, [entry])
  }

  return (
    <div className="trace">
      {[...groups.entries()].map(([key, entries]) => {
        const { depth, agentId } = entries[0]
        return (
          <div className="traced-run" key={key} style={{ marginLeft: depth * 16 }}>
            <div className="traced-run-head">
              {depth > 0 && <span className="chip">depth {depth}</span>}
              <span className="dim">agent {agentId ? agentId.slice(0, 8) : 'root'}</span>
              <span className="chip">{entries.length} message{entries.length === 1 ? '' : 's'}</span>
            </div>
            {entries.map((entry, i) => (
              <div className="turn" key={i}>
                <div className="turn-head">
                  <span className="turn-no">turn {entry.turn}</span>
                </div>
                <MessageRow message={entry.message} />
              </div>
            ))}
          </div>
        )
      })}
    </div>
  )
}
