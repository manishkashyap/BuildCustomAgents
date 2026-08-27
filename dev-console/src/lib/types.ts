// Mirrors the two services' OpenAPI documents (/openapi on each service).
// Kept hand-written and small on purpose: this console is a testing harness, and an
// explicit type is easier to reason about than a generated 2000-line client.

export type AgentStatus = 'DRAFT' | 'PUBLISHED' | 'SUPERSEDED' | 'RETIRING' | 'RETIRED'
export type ToolStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED'
export type ToolType = 'HTTP' | 'MCP' | 'SQL_QUERY' | 'FUNCTION' | 'BUILT_IN' | 'CUSTOM_AGENT'

export type RunStatus =
  | 'PENDING' | 'RUNNING' | 'WAITING_FOR_HUMAN' | 'WAITING_FOR_CHILD'
  | 'PAUSED' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'EXPIRED'

export type Provider = 'OPENAI' | 'GOOGLE_GEMINI' | 'ANTHROPIC_CLAUDE' | 'OPEN_SOURCE'

export type InteractionType = 'CLARIFICATION' | 'TOOL_APPROVAL'
export type InteractionStatus = 'PENDING' | 'ANSWERED' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'CANCELLED'
export type ResponseAction = 'ANSWER' | 'APPROVE' | 'REJECT'
export type ResponseType =
  | 'FREE_TEXT' | 'BOOLEAN' | 'SINGLE_SELECT' | 'MULTI_SELECT'
  | 'NUMBER' | 'DATE' | 'JSON' | 'FILE' | 'APPROVAL'
export type AudienceType = 'CALLER_AGENT' | 'RUN_REQUESTER' | 'ROLE' | 'GROUP'

export type Json = unknown

export interface TokenUsage {
  inputTokens: number
  outputTokens: number
  totalTokens: number
}

/* ---------- management: tools ---------- */

export interface ToolDefinition {
  name: string
  description: string
  type: ToolType
  inputSchema: Json
  outputSchema?: Json | null
  configuration: Json
  executionPolicy?: Json | null
}

export interface ToolResponse {
  id: string
  licenseCode: string
  status: ToolStatus
  version: number
  definition: ToolDefinition
  createdAt: string
  updatedAt: string
}

/* ---------- management: tenant egress allowlist ---------- */

export type EgressHostStatus = 'ACTIVE' | 'DISABLED'

export interface EgressHostResponse {
  id: string
  licenseCode: string
  hostPattern: string
  description?: string | null
  status: EgressHostStatus
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
}

/* ---------- management: agents ---------- */

export interface AgentExample {
  description?: string | null
  input: Json
  expectedOutput?: Json | null
}

export interface AgentDefinition {
  name: string
  description?: string | null
  role: string
  instructions: string
  rules: string[]
  outputFormat?: string | null
  outputSchema?: Json | null
  context?: Json | null
  examples: AgentExample[]
  allowedTools: string[]
  humanInteractionPolicy?: Json | null
}

export interface AgentResponse {
  id: string
  licenseCode: string
  status: AgentStatus
  version: number
  definition: AgentDefinition
  createdAt: string
  updatedAt: string
}

/* ---------- runtime: messages and turns ---------- */

export interface ToolCall {
  id?: string | null
  name: string
  arguments?: Json
  providerMetadata?: Record<string, string> | null
}

export interface AgentMessage {
  role: 'SYSTEM' | 'USER' | 'ASSISTANT' | 'TOOL'
  content?: string | null
  name?: string | null
  toolCallId?: string | null
  toolCalls?: ToolCall[] | null
}

/* ---------- runtime: published runs ---------- */

export interface PendingInteractionSummary {
  interactionId: string
  type: InteractionType
  status: InteractionStatus
  runId: string
  question?: string | null
  responseType?: ResponseType | null
  audienceType?: AudienceType | null
  audienceValues?: string[] | null
  assignedUserId?: string | null
  expiresAt?: string | null
}

export interface AgentRunResponse {
  runId: string
  rootRunId: string
  agentId: string
  agentVersion: number
  status: RunStatus
  provider?: string | null
  model?: string | null
  output?: Json
  usage?: TokenUsage | null
  pendingInteractions: PendingInteractionSummary[]
  startedAt?: string | null
  lastActivityAt?: string | null
  completedAt?: string | null
}

/* The read-only trace added to agent-runtime for this console. */

export interface TracedToolInvocation {
  invocationId?: number | null
  toolCallId?: string | null
  toolName?: string | null
  toolType?: string | null
  toolVersion?: number | null
  status?: string | null
  arguments?: Json
  result?: Json
  childRunId?: string | null
  errorMessage?: string | null
  durationMs?: number | null
  createdAt?: string | null
}

export interface TracedTurn {
  turnNumber: number
  messages: AgentMessage[]
  text?: string | null
  toolCalls: ToolCall[]
  finishReason?: string | null
  usage: TokenUsage
  createdAt?: string | null
  toolInvocations: TracedToolInvocation[]
}

export interface TracedRun {
  runId: string
  parentRunId?: string | null
  agentId: string
  agentVersion: number
  depth: number
  status: RunStatus
  provider?: string | null
  model?: string | null
  input?: Json
  output?: Json
  errorMessage?: string | null
  usage: TokenUsage
  startedAt?: string | null
  lastActivityAt?: string | null
  completedAt?: string | null
  turns: TracedTurn[]
}

export interface AgentRunTraceResponse {
  rootRunId: string
  usage: TokenUsage
  turnCount: number
  runs: TracedRun[]
}

/* ---------- runtime: draft test runs ---------- */

export interface DraftTestPendingInteraction {
  interactionId: string
  type: InteractionType
  category?: string | null
  question?: string | null
  reason?: string | null
  responseType?: ResponseType | null
  request?: Json
  interactionToken: string
}

export interface MockedToolCall {
  toolName?: string | null
  operation?: string | null
  arguments?: Json
  result?: Json
}

export interface DraftTestConversationEntry {
  agentId?: string | null
  depth: number
  turn: number
  message: AgentMessage
}

export interface DraftAgentTestResponse {
  testRunId: string
  agentId: string
  draftRevision: string
  status: 'COMPLETED' | 'NEEDS_INPUT'
  provider?: string | null
  model?: string | null
  output?: Json
  usage?: TokenUsage | null
  pendingInteractions: DraftTestPendingInteraction[]
  mockedToolCalls: MockedToolCall[]
  conversation: DraftTestConversationEntry[]
  startedAt?: string | null
  completedAt?: string | null
}

export interface TestHumanResponse {
  interactionToken: string
  action: ResponseAction
  answer?: Json
}

export interface DraftAgentTestRequest {
  agentId: string
  task?: string | null
  input: Json
  provider?: Provider | null
  model?: string | null
  expectedDraftRevision?: string | null
  mockToolResults?: Record<string, Json> | null
  humanResponses?: TestHumanResponse[] | null
}

/* ---------- runtime: human interactions ---------- */

export interface HumanInteractionView {
  interactionId: string
  rootRunId: string
  runId: string
  type: InteractionType
  status: InteractionStatus
  category?: string | null
  question?: string | null
  reason?: string | null
  responseType?: ResponseType | null
  request?: Json
  audienceType?: AudienceType | null
  audienceValues?: string[] | null
  assignedUserId?: string | null
  createdAt?: string | null
  expiresAt?: string | null
  resolvedAt?: string | null
}

export interface HumanInteractionResolutionResponse {
  interactionId: string
  status: InteractionStatus
  action: ResponseAction
  actorId?: string | null
  resolvedAt?: string | null
  rootRunId?: string | null
  runStatus?: RunStatus | null
}
