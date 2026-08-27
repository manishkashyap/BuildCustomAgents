import type { Identity } from './identity'
import type {
  AgentDefinition, AgentResponse, AgentRunResponse, AgentRunTraceResponse, AgentStatus,
  DraftAgentTestRequest, DraftAgentTestResponse, EgressHostResponse, EgressHostStatus,
  HumanInteractionResolutionResponse, HumanInteractionView, Json, Provider, ResponseAction,
  ToolDefinition, ToolResponse, ToolStatus,
} from './types'

const MANAGEMENT = '/proxy/management'
const RUNTIME = '/proxy/runtime'

/** An RFC 9457 Problem Detail, surfaced verbatim so backend validation is debuggable here. */
export class ApiError extends Error {
  readonly status: number
  readonly problem: Record<string, unknown> | null
  readonly method: string
  readonly path: string

  constructor(
    method: string, path: string, status: number,
    problem: Record<string, unknown> | null, fallback: string,
  ) {
    const detail = typeof problem?.detail === 'string' ? problem.detail : null
    const title = typeof problem?.title === 'string' ? problem.title : null
    super(detail ?? title ?? fallback)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
    this.method = method
    this.path = path
  }
}

function headers(identity: Identity, extra?: Record<string, string>): HeadersInit {
  return {
    'Content-Type': 'application/json',
    'X-Agent-License-Code': identity.licenseCode,
    'X-Agent-User-Id': identity.userId,
    'X-Agent-Roles': identity.roles.join(','),
    ...extra,
  }
}

/** Mutating human-interaction endpoints require a fresh Idempotency-Key on every attempt. */
export function idempotencyKey(): string {
  return crypto.randomUUID()
}

async function request<T>(
  method: string, url: string, identity: Identity,
  body?: unknown, extraHeaders?: Record<string, string>,
): Promise<T> {
  const response = await fetch(url, {
    method,
    headers: headers(identity, extraHeaders),
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  const text = await response.text()
  let parsed: unknown = null
  if (text) {
    try {
      parsed = JSON.parse(text)
    } catch {
      parsed = text
    }
  }

  if (!response.ok) {
    const problem =
      parsed !== null && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : null
    throw new ApiError(method, url, response.status, problem, text || response.statusText)
  }
  return parsed as T
}

/**
 * Both services validate optional JSON-object fields with "must be a JSON object when provided",
 * so an explicit null is rejected on create - the field has to be absent instead. On an agent PATCH
 * the opposite holds: null is exactly how a stored field gets cleared, so patches keep their nulls.
 */
function withoutNullJson<T extends object>(body: T, keys: (keyof T)[]): Partial<T> {
  const copy: Partial<T> = { ...body }
  for (const key of keys) {
    if (copy[key] === null || copy[key] === undefined) delete copy[key]
  }
  return copy
}

const TOOL_NULLABLE_JSON = ['outputSchema', 'executionPolicy'] as const
const AGENT_NULLABLE_JSON = ['outputSchema', 'context', 'humanInteractionPolicy'] as const

/* ---------- tools ---------- */

export const toolsApi = {
  list: (id: Identity) => request<ToolResponse[]>('GET', `${MANAGEMENT}/api/v1/tools`, id),
  get: (id: Identity, toolId: string) =>
    request<ToolResponse>('GET', `${MANAGEMENT}/api/v1/tools/${toolId}`, id),
  // A tool PATCH replaces the whole definition and runs the same validator, so both strip nulls.
  create: (id: Identity, definition: ToolDefinition) =>
    request<ToolResponse>('POST', `${MANAGEMENT}/api/v1/tools`, id,
      withoutNullJson(definition, [...TOOL_NULLABLE_JSON])),
  update: (id: Identity, toolId: string, definition: ToolDefinition) =>
    request<ToolResponse>('PATCH', `${MANAGEMENT}/api/v1/tools/${toolId}`, id,
      withoutNullJson(definition, [...TOOL_NULLABLE_JSON])),
  setStatus: (id: Identity, toolId: string, status: ToolStatus) =>
    request<unknown>('PATCH', `${MANAGEMENT}/api/v1/tools/${toolId}/status`, id, { status }),
}

/* ---------- agents ---------- */

export const agentsApi = {
  list: (id: Identity) => request<AgentResponse[]>('GET', `${MANAGEMENT}/api/v1/agents`, id),
  get: (id: Identity, agentId: string) =>
    request<AgentResponse>('GET', `${MANAGEMENT}/api/v1/agents/${agentId}`, id),
  create: (id: Identity, definition: AgentDefinition, changeReason?: string) =>
    request<AgentResponse>('POST', `${MANAGEMENT}/api/v1/agents`, id,
      withoutNullJson(definition, [...AGENT_NULLABLE_JSON]),
      changeReason ? { 'X-Agent-Change-Reason': changeReason } : undefined),
  // PATCH takes a sparse object: omitted fields keep their stored value, and an explicit null
  // clears an optional one - so nulls are deliberately preserved here.
  patch: (id: Identity, agentId: string, patch: Record<string, unknown>, changeReason?: string) =>
    request<AgentResponse>('PATCH', `${MANAGEMENT}/api/v1/agents/${agentId}`, id, patch,
      changeReason ? { 'X-Agent-Change-Reason': changeReason } : undefined),
  setStatus: (id: Identity, agentId: string, status: AgentStatus, changeReason?: string) =>
    request<unknown>('PATCH', `${MANAGEMENT}/api/v1/agents/${agentId}/status`, id, { status },
      changeReason ? { 'X-Agent-Change-Reason': changeReason } : undefined),
  copy: (id: Identity, agentId: string, name: string) =>
    request<AgentResponse>('POST', `${MANAGEMENT}/api/v1/agents/${agentId}/copies`, id, { name },
      { 'Idempotency-Key': idempotencyKey() }),
}

/* ---------- tenant egress allowlist ---------- */

export const egressApi = {
  list: (id: Identity) =>
    request<EgressHostResponse[]>('GET', `${MANAGEMENT}/api/v1/egress-hosts`, id),
  create: (id: Identity, hostPattern: string, description?: string) =>
    request<EgressHostResponse>('POST', `${MANAGEMENT}/api/v1/egress-hosts`, id,
      { hostPattern, description: description || null }),
  update: (id: Identity, hostId: string, status: EgressHostStatus, description?: string | null) =>
    request<EgressHostResponse>('PATCH', `${MANAGEMENT}/api/v1/egress-hosts/${hostId}`, id,
      { status, description: description || null }),
  remove: (id: Identity, hostId: string) =>
    request<void>('DELETE', `${MANAGEMENT}/api/v1/egress-hosts/${hostId}`, id),
}

/* ---------- runs ---------- */

export interface StartRunInput {
  agentId: string
  task: string
  input?: Json
  provider?: Provider | null
  model?: string | null
}

export const runsApi = {
  start: (id: Identity, body: StartRunInput) =>
    request<AgentRunResponse>('POST', `${RUNTIME}/api/v1/agent-runs`, id, body),
  get: (id: Identity, runId: string) =>
    request<AgentRunResponse>('GET', `${RUNTIME}/api/v1/agent-runs/${runId}`, id),
  // Added to agent-runtime for this console: read-only turn-by-turn trace of the whole run tree.
  trace: (id: Identity, runId: string) =>
    request<AgentRunTraceResponse>('GET', `${RUNTIME}/api/v1/agent-runs/${runId}/turns`, id),
  cancel: (id: Identity, runId: string, scope: string, reason: string) =>
    request<AgentRunResponse>('POST', `${RUNTIME}/api/v1/agent-runs/${runId}/cancel`, id, { scope, reason }),
  addInstruction: (id: Identity, runId: string, message: string, targetRunId?: string) =>
    request<unknown>('POST', `${RUNTIME}/api/v1/agent-runs/${runId}/instructions`, id,
      { message, targetRunId: targetRunId || null }),
  submitRootResponses: (
    id: Identity, rootRunId: string,
    responses: { interactionId: string; action: ResponseAction; answer?: Json; comment?: string }[],
  ) =>
    request<unknown>('POST', `${RUNTIME}/api/v1/agent-runs/${rootRunId}/human-responses`, id,
      { responses }, { 'Idempotency-Key': idempotencyKey() }),
}

/* ---------- draft test runs ---------- */

export const draftTestApi = {
  run: (id: Identity, body: DraftAgentTestRequest) =>
    request<DraftAgentTestResponse>('POST', `${RUNTIME}/api/v1/agent-test-runs`, id, body),
}

/* ---------- human interactions ---------- */

export const interactionsApi = {
  inbox: (id: Identity) =>
    request<HumanInteractionView[]>('GET', `${RUNTIME}/api/v1/human-interactions`, id),
  get: (id: Identity, interactionId: string) =>
    request<HumanInteractionView>('GET', `${RUNTIME}/api/v1/human-interactions/${interactionId}`, id),
  respond: (
    id: Identity, interactionId: string,
    body: { action: ResponseAction; answer?: Json; comment?: string },
  ) =>
    request<HumanInteractionResolutionResponse>(
      'POST', `${RUNTIME}/api/v1/human-interactions/${interactionId}/responses`, id, body,
      { 'Idempotency-Key': idempotencyKey() }),
}

export const health = {
  management: () => fetch(`${MANAGEMENT}/actuator/health`).then((r) => r.ok).catch(() => false),
  runtime: () => fetch(`${RUNTIME}/actuator/health`).then((r) => r.ok).catch(() => false),
}
