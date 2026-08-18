# Human-in-the-loop API and event contract

Status: proposed v1 additive contract. JSON property names and enum values in this document are the
implementation target. Existing error responses continue to use RFC 9457/Spring `ProblemDetail`.

## Common HTTP rules

- Runtime base path: `/api/v1`.
- Management base path: `/api/v1` on the management deployment.
- `X-Agent-License-Code` remains the tenant selector and must match an allowed tenant in the
  authenticated principal.
- All mutating human-interaction endpoints require `Idempotency-Key` (1-128 visible ASCII
  characters).
- Timestamps are UTC ISO-8601 instants.
- IDs are opaque UUID strings unless the field is an existing numeric step/invocation ID.
- Authorization uses the authenticated actor; actor IDs, roles, or assignees are never accepted as
  trusted request-body identity.

Target identity claims/authorities:

```text
sub                       actor ID
license_codes             allowed tenant/license codes
roles                     RUN_REQUESTER, RUN_OPERATOR, APPROVER, AGENT_OWNER, AGENT_ADMIN
groups                    tenant-scoped role/group memberships
```

## Management contracts

### Tool approval policy

`POST /api/v1/tools` retains the current shape and gives `executionPolicy` this documented schema:

```json
{
  "name": "campaign.send",
  "description": "Sends an approved campaign",
  "type": "HTTP",
  "inputSchema": {
    "type": "object",
    "properties": {
      "campaignId": {"type": "string"}
    },
    "required": ["campaignId"]
  },
  "configuration": {
    "method": "POST",
    "url": "https://api.example.com/v1/campaigns/{campaignId}/send"
  },
  "executionPolicy": {
    "operation": "EXTERNAL_COMMUNICATION",
    "riskLevel": "HIGH",
    "approval": {
      "required": true,
      "audience": {
        "type": "ROLE",
        "values": ["CAMPAIGN_APPROVER"]
      },
      "allowSelfApproval": false,
      "expiresAfterSeconds": 86400,
      "onReject": "RETURN_TO_AGENT",
      "onExpire": "REJECT"
    }
  }
}
```

Backward compatibility: omitted `approval` is equivalent to `{"required":false}`.

Validation errors use `400` with type
`https://agent-platform.example/problems/invalid-tool-definition`. Publishing must reject a policy with an
unknown audience, missing role/group values, invalid duration, or inconsistent operation/risk.

### Agent clarification policy

`POST /api/v1/agents` adds an optional `humanInteractionPolicy`:

```json
{
  "name": "Campaign QA Agent",
  "description": "Checks campaign readiness",
  "role": "platform campaign quality analyst",
  "instructions": "Review the supplied campaign and return actionable findings.",
  "rules": [],
  "outputFormat": "Return JSON",
  "outputSchema": {"type": "object"},
  "context": {},
  "examples": [],
  "allowedTools": ["campaign.send"],
  "humanInteractionPolicy": {
    "clarification": {
      "defaultAudience": {
        "type": "RUN_REQUESTER",
        "values": []
      },
      "expiresAfterSeconds": 86400,
      "onExpire": "FAIL_CHILD",
      "maxRequestsPerRootRun": 20
    }
  }
}
```

Omission uses the documented defaults. `CALLER_AGENT` is a runtime routing hint, not a configured
human audience.

## Runtime control-tool contract

Runtime automatically adds this provider-neutral built-in tool to an agent's LLM request. It is not
stored in `custom_tools` and does not need to appear in `allowedTools`.

Name: `request_clarification`

Input schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "category": {
      "type": "string",
      "enum": [
        "CALLER_CONTEXT",
        "MISSING_TASK_INPUT",
        "BUSINESS_DECISION",
        "AGENT_CONFIGURATION",
        "PRIVILEGED_DECISION"
      ]
    },
    "question": {"type": "string", "minLength": 1, "maxLength": 4000},
    "reason": {"type": "string", "minLength": 1, "maxLength": 4000},
    "responseType": {
      "type": "string",
      "enum": [
        "FREE_TEXT", "BOOLEAN", "SINGLE_SELECT", "MULTI_SELECT",
        "NUMBER", "DATE", "JSON", "FILE"
      ]
    },
    "options": {
      "type": "array",
      "maxItems": 100,
      "items": {"type": "string", "maxLength": 1000}
    },
    "responseSchema": {"type": "object"},
    "defaultAnswer": {},
    "sensitive": {"type": "boolean", "default": false},
    "audienceHint": {
      "type": "string",
      "enum": ["CALLER_AGENT", "RUN_REQUESTER", "CONFIGURED_ROLE_OR_GROUP"]
    }
  },
  "required": ["category", "question", "reason", "responseType"]
}
```

`options` is required for select responses. `responseSchema` is required for `JSON`. Runtime resolves
the actual audience. The LLM cannot identify an individual user.

After an answer, the original tool call receives this result:

```json
{
  "status": "ANSWERED",
  "interactionId": "b87b41b0-a648-40d8-b193-b58d758ca0cb",
  "answer": 90,
  "answeredAt": "2026-08-11T13:00:00Z"
}
```

## Start a run

### `POST /api/v1/agent-runs`

The request remains unchanged. Runtime executes synchronously until success or the first blocking
human checkpoint.

#### Success

HTTP 200:

```json
{
  "runId": "e1665ecf-c1f3-45a6-8425-0239b8146434",
  "rootRunId": "e1665ecf-c1f3-45a6-8425-0239b8146434",
  "agentId": "62c4ea7c-54d3-4412-ab52-0811b3e09e9d",
  "agentVersion": 1,
  "status": "SUCCEEDED",
  "provider": "GOOGLE_GEMINI",
  "model": "gemini-3.6-flash",
  "output": {"result": "..."},
  "usage": {
    "inputTokens": 1200,
    "outputTokens": 300,
    "totalTokens": 1500
  },
  "pendingInteractions": []
}
```

#### Human checkpoint

HTTP 200:

```json
{
  "runId": "e1665ecf-c1f3-45a6-8425-0239b8146434",
  "rootRunId": "e1665ecf-c1f3-45a6-8425-0239b8146434",
  "agentId": "62c4ea7c-54d3-4412-ab52-0811b3e09e9d",
  "agentVersion": 1,
  "status": "WAITING_FOR_HUMAN",
  "provider": "GOOGLE_GEMINI",
  "model": "gemini-3.6-flash",
  "output": null,
  "usage": {
    "inputTokens": 800,
    "outputTokens": 100,
    "totalTokens": 900
  },
  "pendingInteractions": [
    {
      "interactionId": "b87b41b0-a648-40d8-b193-b58d758ca0cb",
      "type": "CLARIFICATION",
      "status": "PENDING",
      "runId": "f42bd762-6c89-4ee7-9905-34f8fc81cb17",
      "agentId": "child-agent-id",
      "question": "Which inactivity period should be used?",
      "responseType": "NUMBER",
      "assignedAudience": {"type": "RUN_REQUESTER", "values": []},
      "assignedUserId": "user-123",
      "expiresAt": "2026-08-12T13:00:00Z"
    }
  ]
}
```

`status` is the aggregate root view. A nested parent may locally be `WAITING_FOR_CHILD`.

The initial request does not return `202` for HITL. Provider/tool/runtime failures remain existing
Problem Detail errors and include `runId` when a run row was already created.

## Get run state

### `GET /api/v1/agent-runs/{runId}`

Returns the tenant-scoped root or child run. Root responses include aggregate status and all pending
descendant interactions visible to the caller.

HTTP 200 uses the same `AgentRunResponse` as the start endpoint, plus:

```json
{
  "startedAt": "2026-08-11T12:00:00Z",
  "lastActivityAt": "2026-08-11T12:01:00Z",
  "completedAt": null
}
```

`404` is returned for an absent run or a run outside the tenant, avoiding existence leakage.

## Root timeline

### `GET /api/v1/agent-runs/{rootRunId}/events`

Query parameters:

- `afterId`: exclusive event cursor, default 0.
- `limit`: 1-200, default 50.

HTTP 200:

```json
{
  "items": [
    {
      "eventId": 1001,
      "rootRunId": "e1665ecf-c1f3-45a6-8425-0239b8146434",
      "runId": "f42bd762-6c89-4ee7-9905-34f8fc81cb17",
      "eventType": "CLARIFICATION_REQUESTED",
      "actor": {"type": "AGENT", "id": "child-agent-id"},
      "subject": {
        "type": "HUMAN_INTERACTION",
        "id": "b87b41b0-a648-40d8-b193-b58d758ca0cb"
      },
      "data": {},
      "createdAt": "2026-08-11T12:01:00Z"
    }
  ],
  "nextAfterId": 1001
}
```

Payload fields are redacted for the caller.

## Interaction representation

The full representation is:

```json
{
  "interactionId": "b87b41b0-a648-40d8-b193-b58d758ca0cb",
  "licenseCode": "account-123",
  "rootRunId": "e1665ecf-c1f3-45a6-8425-0239b8146434",
  "runId": "f42bd762-6c89-4ee7-9905-34f8fc81cb17",
  "agent": {
    "id": "child-agent-id",
    "version": 2,
    "name": "Audience analysis agent"
  },
  "type": "CLARIFICATION",
  "status": "PENDING",
  "category": "MISSING_TASK_INPUT",
  "question": "Which inactivity period should be used?",
  "reason": "Three configured periods are valid for this task.",
  "response": {
    "type": "NUMBER",
    "options": [],
    "schema": null,
    "sensitive": false
  },
  "toolCall": null,
  "assignedAudience": {
    "type": "RUN_REQUESTER",
    "values": []
  },
  "assignedUserId": "user-123",
  "createdAt": "2026-08-11T12:01:00Z",
  "expiresAt": "2026-08-12T12:01:00Z",
  "resolvedAt": null,
  "resolution": null
}
```

For tool approval, `response.type` is `APPROVAL` and `toolCall` is populated:

```json
{
  "toolCall": {
    "invocationId": 912,
    "toolId": "tool-id",
    "toolVersion": 3,
    "toolName": "campaign.send",
    "toolType": "HTTP",
    "operation": "EXTERNAL_COMMUNICATION",
    "riskLevel": "HIGH",
    "arguments": {"campaignId": "cmp-123"},
    "argumentsSha256": "9cc9...",
    "expectedEffect": "Sends campaign cmp-123"
  }
}
```

Secrets and unauthorized sensitive fields are replaced with redaction markers. Approval still binds
to the hash of the full canonical subject.

## Interaction inbox and detail

### `GET /api/v1/human-interactions`

Query parameters:

- `status`: default `PENDING`.
- `type`: optional `CLARIFICATION` or `TOOL_APPROVAL`.
- `assignedTo`: `ME` by default; `ROLE_OR_GROUP` requires operator authority.
- `rootRunId`: optional.
- `after`: opaque cursor.
- `limit`: 1-100, default 25.

Only interactions the caller is allowed to view/resolve are returned.

### `GET /api/v1/human-interactions/{interactionId}`

Returns the full representation. Cross-tenant or invisible interactions return `404`.

## Answer or decide

### `POST /api/v1/agent-runs/{rootRunId}/human-responses`

Required headers:

```text
X-Agent-License-Code: account-123
Idempotency-Key: 81ea5ccb-bf51-4f98-87eb-02b180e8bd81
```

The root-run contract is always an array, including for one response. A batch may resolve any
non-empty subset of the root's currently pending interactions:

```json
{
  "responses": [
    {
      "interactionId": "b87b41b0-a648-40d8-b193-b58d758ca0cb",
      "action": "ANSWER",
      "answer": 90,
      "comment": "Use the standard retention definition."
    },
    {
      "interactionId": "128de05e-414e-42b5-8953-cb988e10e181",
      "action": "APPROVE",
      "comment": "Campaign configuration has been reviewed."
    }
  ]
}
```

Validation rules:

- `ANSWER` is valid only for `CLARIFICATION` and must match its response contract.
- `APPROVE`/`REJECT` are valid only for `TOOL_APPROVAL`.
- Rejection comment is required, 1-2000 characters.
- Approval revalidates the subject hash, current invocation state, actor authority, tenant, and
  separation-of-duties policy.
- Interaction IDs must be unique in the batch and every interaction must belong to the URL root.
- All supplied items are validated and persisted atomically. A failure leaves every item pending.
- Responses not supplied remain pending; partial answering is valid.
- Each accepted item becomes a separate immutable `human_interaction_responses` row.
- One `ROOT_RESUME_REQUESTED` outbox command is emitted for the batch.

The HTTP request commits the response batch, claims its durable resume command, and runs until the
root reaches its next stable state: `SUCCEEDED`, `FAILED`, `CANCELLED`, or
`WAITING_FOR_HUMAN` with a new/remaining interaction. HTTP 200 returns that stable state:

```json
{
  "batchId": "81ea5ccb-bf51-4f98-87eb-02b180e8bd81",
  "rootRunId": "e1665ecf-c1f3-45a6-8425-0239b8146434",
  "rootRunStatus": "SUCCEEDED",
  "acceptedResponses": [
    {
      "interactionId": "b87b41b0-a648-40d8-b193-b58d758ca0cb",
      "runId": "child-run-id",
      "status": "ANSWERED",
      "action": "ANSWER",
      "actorId": "user-123",
      "resolvedAt": "2026-08-11T13:00:00Z"
    }
  ],
  "pendingInteractionCount": 0,
  "pendingInteractions": [],
  "acceptedAt": "2026-08-11T13:00:00Z",
  "output": {
    "strategy": "Generated strategy"
  }
}
```

If one supplied owning run still has another unanswered interaction, or continued execution creates
a new clarification/approval, the synchronous response instead returns
`rootRunStatus: "WAITING_FOR_HUMAN"` and the actionable pending interactions. The outbox command is
retained for recovery; the scheduled worker handles it only when the request/runtime dies before
the synchronous claimant completes.

Idempotency:

- Repeating the same key and semantically identical body returns the original HTTP 200 response.
- Reusing the key with a different body returns `409 idempotency-key-reused`.
- A different key after resolution returns `409 interaction-already-resolved`.

The compatibility endpoint `POST /api/v1/human-interactions/{interactionId}/responses` remains
available, delegates to the same synchronous atomic implementation with a one-item array, and
returns the resulting stable root status.

## Add a correction or instruction

### `POST /api/v1/agent-runs/{rootRunId}/instructions`

This does not edit a submitted response. It appends a new audited human message and pauses at the
next safe boundary when the run is currently executing.

```json
{
  "message": "Correction: use 60 days, not 90 days.",
  "targetRunId": "f42bd762-6c89-4ee7-9905-34f8fc81cb17"
}
```

HTTP 200:

```json
{
  "instructionId": "instruction-id",
  "rootRunId": "e1665ecf-c1f3-45a6-8425-0239b8146434",
  "targetRunId": "f42bd762-6c89-4ee7-9905-34f8fc81cb17",
  "status": "QUEUED",
  "createdAt": "2026-08-11T13:02:00Z"
}
```

If the target has already succeeded, return `409 run-already-terminal`; the caller starts a new run
to use the correction.

## Pause, resume, and cancel

### `POST /api/v1/agent-runs/{rootRunId}/pause`

Queues an operator pause at the next safe boundary. HTTP 200 returns the root view.

### `POST /api/v1/agent-runs/{rootRunId}/resume`

Valid only for an operator-paused run. It cannot bypass a pending clarification or approval. A
pending interaction produces `409 unresolved-human-interaction`.

### `POST /api/v1/agent-runs/{runId}/cancel`

```json
{
  "scope": "ROOT",
  "reason": "The campaign request was withdrawn."
}
```

`scope` is `ROOT` or `THIS_RUN`. Root cancellation cancels all non-terminal descendants and pending
interactions. Child-only cancellation uses the parent invocation failure policy. Already-dispatched
irreversible external effects are reported but cannot be undone automatically.

## Assignment and delegation

### `POST /api/v1/human-interactions/{interactionId}/assignment`

```json
{
  "assigneeType": "USER",
  "assignee": "user-456",
  "reason": "Primary approver is unavailable."
}
```

`assigneeType` is `USER`, `ROLE`, or `GROUP`. Runtime validates tenant membership, eligibility, and
delegation authority. Assignment changes are append-only audit events; they do not change the
interaction subject.

## Problem Detail catalogue

| HTTP | Type suffix | Meaning |
| --- | --- | --- |
| 400 | `invalid-human-interaction-response` | Body or answer does not match the interaction contract |
| 401 | `authentication-required` | No valid authenticated principal |
| 403 | `human-interaction-forbidden` | Actor lacks assignment/approval authority |
| 404 | `agent-run-not-found` | Run absent or hidden by tenant boundary |
| 404 | `human-interaction-not-found` | Interaction absent or hidden by tenant boundary |
| 409 | `interaction-already-resolved` | A different submitted response already won |
| 409 | `interaction-expired` | Interaction can no longer be resolved |
| 409 | `interaction-subject-changed` | Approval binding no longer matches exact invocation |
| 409 | `idempotency-key-reused` | Same key was used with a different request |
| 409 | `unresolved-human-interaction` | Resume attempted while a blocking interaction remains |
| 409 | `run-already-terminal` | Operation cannot apply to a terminal run |
| 422 | `invalid-tool-approval-policy` | Published/stored tool policy is invalid |
| 429 | `human-interaction-limit-exceeded` | Root interaction budget is exhausted |
| 502 | `agent-execution-failed` | Existing execution/provider failure contract |

Problem bodies should include `runId` and `interactionId` when known, but never reveal a hidden
cross-tenant identifier.

## Domain event envelope

Runtime publishes an outbox-backed, at-least-once event envelope:

```json
{
  "eventId": "event-uuid",
  "eventType": "HUMAN_INTERACTION_REQUESTED",
  "eventVersion": 1,
  "occurredAt": "2026-08-11T12:01:00Z",
  "licenseCode": "account-123",
  "rootRunId": "e1665ecf-c1f3-45a6-8425-0239b8146434",
  "runId": "f42bd762-6c89-4ee7-9905-34f8fc81cb17",
  "correlationId": "e1665ecf-c1f3-45a6-8425-0239b8146434",
  "causationId": "tool-call-id",
  "data": {}
}
```

Initial external event types:

- `HUMAN_INTERACTION_REQUESTED`
- `HUMAN_INTERACTION_RESOLVED`
- `HUMAN_INTERACTION_EXPIRED`
- `AGENT_RUN_WAITING_FOR_HUMAN`
- `AGENT_RUN_RESUMED`
- `AGENT_RUN_SUCCEEDED`
- `AGENT_RUN_FAILED`
- `AGENT_RUN_CANCELLED`

Consumers deduplicate on `eventId`. Adding fields is backward compatible; removing/renaming fields
or changing meaning requires a new `eventVersion`.

## Resume guarantees

- The response endpoint commits the decision and resume command before returning HTTP 200.
- Resume delivery is at-least-once.
- Optimistic checkpoint versioning ensures only one worker advances a run.
- `(run_id, tool_call_id)` ensures one logical invocation record.
- A stored argument hash prevents approval reuse after mutation.
- Downstream side effects are exactly-once only when the executor forwards the stable invocation ID
  as an idempotency key and the downstream system honors it.
- If a worker crashes after the external side effect but before result persistence and the downstream
  system lacks idempotency, runtime marks the invocation `UNKNOWN_OUTCOME` for operator attention
  rather than blindly retrying it.
