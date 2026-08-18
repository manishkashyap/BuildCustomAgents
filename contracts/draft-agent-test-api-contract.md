# Draft Agent Test API Contract

## Endpoint and authorization

`POST /api/v1/agent-test-runs` belongs to Agent Runtime and requires the tenant selected by
`X-Agent-License-Code` plus `AGENT_EDITOR` or `AGENT_ADMIN`. The response is synchronous. HTTP 200
means either `COMPLETED` or `NEEDS_INPUT`; other outcomes use the normal Problem Detail contract.

## Eligibility

- The root agent must exist in the tenant and have status `DRAFT`.
- Allowed tool names resolve only against `PUBLISHED`, non-deleted tool records.
- A `CUSTOM_AGENT` tool must itself be published and target the captured version of a
  `PUBLISHED` child agent.
- Child and descendant agents execute transiently and inherit draft-test tool safety.
- Draft, disabled, retired, or retiring dependencies are never made available to the LLM.

## Request

```json
{
  "agentId": "uuid",
  "task": "Task for this test",
  "input": {},
  "provider": "GOOGLE_GEMINI",
  "model": "gemini-3.6-flash",
  "expectedDraftRevision": null,
  "mockToolResults": {
    "campaign.send": {"status": "SIMULATED"}
  },
  "humanResponses": []
}
```

Provider and model are optional together and use the live-run fallback when absent. Mock results
are keyed by published tool name. `expectedDraftRevision` is optional for an initial request and
required whenever `humanResponses` is non-empty.

## Test tool policy

Every call still enters `ToolExecutorRegistry`. `BUILT_IN` and `CUSTOM_AGENT` calls route to their
registered executors. An external tool explicitly classified as `READ` executes normally. All
other operations, including missing classifications, bypass their concrete executor and return a
caller-provided or generated mock result. Approval policy evaluation occurs before either real or
mocked dispatch.

## Response

```json
{
  "testRunId": "uuid",
  "agentId": "uuid",
  "draftRevision": "sha256",
  "status": "COMPLETED",
  "provider": "GOOGLE_GEMINI",
  "model": "gemini-3.6-flash",
  "output": {},
  "usage": {"inputTokens": 0, "outputTokens": 0, "totalTokens": 0},
  "pendingInteractions": [],
  "mockedToolCalls": [],
  "conversation": [],
  "startedAt": "2026-08-14T08:00:00Z",
  "completedAt": "2026-08-14T08:00:01Z"
}
```

Usage is the aggregate provider usage for the transient root and all child executions. The
conversation is returned because no conversation or checkpoint is stored by Runtime.

## Stateless human interaction

A human boundary returns `NEEDS_INPUT` with an array of pending interactions. Each entry contains
a signed, expiring `interactionToken`. The token binds tenant, root agent, draft revision, original
request fingerprint, interaction type, and—for approval—the exact tool binding.

The client starts another test run and resubmits the original request plus all accumulated response
items. All tokens and action shapes are validated before an LLM call. Clarification requires
`ANSWER`; approval requires `APPROVE` or `REJECT`. If the draft changed, Runtime returns 409 and the
test sequence must restart. A different approval binding causes a new approval interaction.

## Persistence boundary

The feature performs no writes to agent runs, turns, tool invocations, checkpoints, HITL tables,
response batches, or outbox tables. The returned IDs exist only for response correlation. Normal
metadata-only service logs and external provider/tool retention are outside this runtime database
guarantee.
