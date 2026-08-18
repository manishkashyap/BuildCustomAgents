# Custom agent editing, copy, and retirement contract

All public requests require `X-Agent-License-Code`, `X-Agent-User-Id`, and `X-Agent-Roles`.
Create, read, edit, and copy require `AGENT_EDITOR` (read also permits `AGENT_PUBLISHER`). Publish
and retire require `AGENT_PUBLISHER`. `AGENT_ADMIN` and `PLATFORM_ADMIN` satisfy either role.

## Read

`GET /api/v1/agents/{agentId}` returns the complete stored definition. Tenant mismatches are
reported as `404`.

## Update a draft

`PATCH /api/v1/agents/{agentId}` accepts a non-empty object containing any of `name`, `description`,
`role`, `instructions`, `rules`, `outputFormat`, `outputSchema`, `context`, `examples`,
`allowedTools`, or `humanInteractionPolicy`.

- Omitted fields remain unchanged.
- Explicit null clears optional fields only.
- Empty collections clear collection fields.
- Updates to non-draft agents return `409`.
- The operation is transactional without row locking or an HTTP concurrency precondition.
- Concurrent changes to the same field use last-commit-wins behavior.
- Draft edits do not increment the business version.
- `X-Agent-Change-Reason` is optional and limited to 1000 characters.

## Copy

`POST /api/v1/agents/{sourceAgentId}/copies` accepts `{"name":"unique name"}` and requires an
`Idempotency-Key`. The source must be `PUBLISHED` or `RETIRED`. The response is `201` with a
`Location` header and a new independent `DRAFT`, version `1`. No source ID or lineage is persisted.

## Status

The public status endpoint permits `DRAFT -> PUBLISHED` and `PUBLISHED -> RETIRED`. Publishing
validates that every allowed tool is published and that every custom-agent tool targets a currently
published agent at its configured version. `RETIRING` is internal-only.

Retirement uses `PUBLISHED -> RETIRING -> RETIRED`. After the guard is committed, Management checks
published parent-agent dependencies and calls Runtime's service-authenticated endpoint:

`GET /internal/v1/agents/{agentId}/retirement-eligibility`

Active `PENDING`, `RUNNING`, `WAITING_FOR_HUMAN`, `WAITING_FOR_CHILD`, or `PAUSED` runs block
retirement. A conflict restores `PUBLISHED` and returns `409`; inability to consult Runtime
restores `PUBLISHED` and returns `503`. New runs recheck publication after persisting their run row,
closing the start-versus-retire race. Interrupted `RETIRING` states older than five minutes are
automatically restored.

## Internal outbox states

Runtime outbox states such as `PENDING`, `CLAIMED`, and `PUBLISHED` remain internal and are not part
of these response bodies.
