import { useEffect, useId, useState, type ReactNode } from 'react'
import { ApiError } from '../lib/api'

export function StatusPill({ status }: { status: string | null | undefined }) {
  if (!status) return null
  return <span className={`pill pill-${status.toLowerCase()}`}>{status}</span>
}

export function Field({
  label, hint, children,
}: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="field">
      <span className="field-label">
        {label}
        {hint && <span className="field-hint">{hint}</span>}
      </span>
      {children}
    </label>
  )
}

/**
 * A JSON text area that reports parse errors inline instead of on submit. `value` is the parsed
 * value; text is kept locally so a half-typed object is not destroyed by a re-render.
 */
export function JsonField({
  label, hint, value, onChange, rows = 6, allowEmpty = true,
}: {
  label: string
  hint?: string
  value: unknown
  onChange: (parsed: unknown, valid: boolean) => void
  rows?: number
  allowEmpty?: boolean
}) {
  const incoming = serialize(value)
  const [text, setText] = useState(incoming)
  const [error, setError] = useState<string | null>(null)

  // Re-seed when the caller swaps in a different record (e.g. selecting another tool).
  useEffect(() => {
    setText(incoming)
    setError(null)
  }, [incoming])

  function handle(next: string) {
    setText(next)
    if (!next.trim()) {
      if (allowEmpty) {
        setError(null)
        onChange(null, true)
      } else {
        setError('required')
        onChange(null, false)
      }
      return
    }
    try {
      const parsed = JSON.parse(next)
      setError(null)
      onChange(parsed, true)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'invalid JSON')
      onChange(null, false)
    }
  }

  return (
    <label className="field">
      <span className="field-label">
        {label}
        {hint && <span className="field-hint">{hint}</span>}
        {error && <span className="field-error">{error}</span>}
      </span>
      <textarea
        className={error ? 'mono invalid' : 'mono'}
        rows={rows}
        spellCheck={false}
        value={text}
        onChange={(e) => handle(e.target.value)}
      />
    </label>
  )
}

function serialize(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') return value
  return JSON.stringify(value, null, 2)
}

export function ErrorBanner({ error, onDismiss }: { error: unknown; onDismiss?: () => void }) {
  if (!error) return null
  const api = error instanceof ApiError ? error : null
  const extras =
    api?.problem
      ? Object.entries(api.problem).filter(
          ([k]) => !['type', 'title', 'status', 'detail', 'instance'].includes(k),
        )
      : []
  return (
    <div className="banner banner-error">
      <div className="banner-head">
        <strong>
          {api ? `${api.status} ${api.problem?.title ?? 'Request failed'}` : 'Request failed'}
        </strong>
        {onDismiss && (
          <button type="button" className="link" onClick={onDismiss}>
            dismiss
          </button>
        )}
      </div>
      <p>{error instanceof Error ? error.message : String(error)}</p>
      {api && (
        <p className="banner-meta mono">
          {api.method} {api.path}
          {typeof api.problem?.type === 'string' ? ` · ${api.problem.type}` : ''}
        </p>
      )}
      {extras.length > 0 && (
        <pre className="mono small">{JSON.stringify(Object.fromEntries(extras), null, 2)}</pre>
      )}
    </div>
  )
}

export function Banner({ kind, children }: { kind: 'info' | 'ok' | 'warn'; children: ReactNode }) {
  return <div className={`banner banner-${kind}`}>{children}</div>
}

export function JsonBlock({ value, label }: { value: unknown; label?: string }) {
  if (value === null || value === undefined) return null
  return (
    <div className="json-block">
      {label && <div className="json-label">{label}</div>}
      <pre className="mono small">{JSON.stringify(value, null, 2)}</pre>
    </div>
  )
}

/** A list of free-text strings (agent `rules`, audience values, and similar). */
export function StringList({
  label, hint, values, onChange, placeholder,
}: {
  label: string
  hint?: string
  values: string[]
  onChange: (next: string[]) => void
  placeholder?: string
}) {
  const id = useId()
  return (
    <div className="field">
      <span className="field-label">
        {label}
        {hint && <span className="field-hint">{hint}</span>}
      </span>
      {values.map((value, index) => (
        <div className="row-inline" key={`${id}-${index}`}>
          <input
            value={value}
            placeholder={placeholder}
            onChange={(e) => {
              const next = [...values]
              next[index] = e.target.value
              onChange(next)
            }}
          />
          <button
            type="button"
            className="link danger"
            onClick={() => onChange(values.filter((_, i) => i !== index))}
          >
            remove
          </button>
        </div>
      ))}
      <button type="button" className="link" onClick={() => onChange([...values, ''])}>
        + add
      </button>
    </div>
  )
}

export function Spinner({ label }: { label?: string }) {
  return <span className="spinner">{label ?? 'working…'}</span>
}

export function UsageChips({ usage, prefix }: { usage: { inputTokens: number; outputTokens: number; totalTokens: number } | null | undefined; prefix?: string }) {
  if (!usage) return null
  return (
    <span className="usage">
      {prefix && <span className="usage-prefix">{prefix}</span>}
      <span className="chip" title="input tokens">in {usage.inputTokens.toLocaleString()}</span>
      <span className="chip" title="output tokens">out {usage.outputTokens.toLocaleString()}</span>
      <span className="chip chip-strong" title="total tokens">total {usage.totalTokens.toLocaleString()}</span>
    </span>
  )
}
