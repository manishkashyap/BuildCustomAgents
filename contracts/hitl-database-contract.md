# Human-in-the-loop database contract

Status: implementation contract, not an executable Flyway migration. MySQL DDL shown here defines
the intended ownership, columns, invariants, and indexes. Flyway migrations must handle existing
rows in additive/backfill/constraint phases.

## Ownership

| Data | Owner |
| --- | --- |
| Tool approval policy | Agent Management, inside the versioned tool definition JSON |
| Agent clarification policy | Agent Management, inside the versioned agent definition JSON |
| Runs and parent/child relationships | Agent Runtime |
| Conversation checkpoint | Agent Runtime |
| Tool invocation lifecycle | Agent Runtime |
| Human requests and immutable responses | Agent Runtime |
| Timeline/audit events and resume outbox | Agent Runtime |

Runtime reads published management definitions through its existing read-only management data
source. The services do not share JPA entities or a Maven dependency.

## Management definition extensions

No searchable policy column is required initially. Both policies remain part of `definition_json`,
which is already captured by tool/agent version. Management must validate these objects before a
definition can be published.

### Tool `executionPolicy.approval`

```json
{
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
```

Contract:

- `operation`: `READ`, `WRITE`, `EXTERNAL_COMMUNICATION`, `SENSITIVE_DATA`, or `DESTRUCTIVE`.
- `riskLevel`: `LOW`, `MEDIUM`, `HIGH`, or `DESTRUCTIVE`.
- `approval.required`: defaults to `false` for existing definitions.
- `audience.type`: `RUN_REQUESTER`, `ROLE`, or `GROUP`. `values` is required for role/group.
- `allowSelfApproval`: defaults to `true` for low/medium risk and is forcibly `false` when a stricter
  platform/tenant policy requires separation of duties.
- `expiresAfterSeconds`: 60 through 604800; default 86400.
- `onReject`: `RETURN_TO_AGENT` or `FAIL_RUN`; default `RETURN_TO_AGENT`.
- `onExpire`: `REJECT`, `FAIL_RUN`, or `ESCALATE`; default `REJECT`.

### Agent `humanInteractionPolicy.clarification`

```json
{
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
```

Contract:

- `defaultAudience.type`: `RUN_REQUESTER`, `ROLE`, or `GROUP`.
- Runtime may temporarily use `CALLER_AGENT` when the control-tool request is categorized as
  `CALLER_CONTEXT`; it falls back to the configured/default human audience.
- `onExpire`: `FAIL_CHILD`, `FAIL_ROOT`, `ESCALATE`, or `USE_DEFAULT`.
- `USE_DEFAULT` is valid only when the individual clarification supplies an explicit safe default.
- Missing policy uses requester, 86400 seconds, `FAIL_CHILD`, and 20 requests.

## Runtime schema changes

### `agent_runs`

Extend the existing table rather than replacing it:

```sql
ALTER TABLE agent_runs
    ADD COLUMN root_run_id VARCHAR(36) NULL AFTER id,
    ADD COLUMN parent_run_id VARCHAR(36) NULL AFTER root_run_id,
    ADD COLUMN parent_tool_invocation_id BIGINT NULL AFTER parent_run_id,
    ADD COLUMN requested_by VARCHAR(128) NULL AFTER license_code,
    ADD COLUMN wait_reason VARCHAR(30) NULL AFTER status,
    ADD COLUMN checkpoint_version BIGINT NOT NULL DEFAULT 0 AFTER wait_reason,
    ADD COLUMN last_activity_at TIMESTAMP(6) NULL AFTER started_at,
    ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;
```

New rows require `root_run_id`; a root points to itself. Existing rows are backfilled with
`root_run_id=id` before making the column non-null.

Status constraint:

```text
RUNNING
WAITING_FOR_HUMAN
WAITING_FOR_CHILD
PAUSED
SUCCEEDED
FAILED
CANCELLED
EXPIRED
```

Wait-reason constraint when non-null:

```text
CLARIFICATION
TOOL_APPROVAL
CHILD_RUN
OPERATOR_PAUSE
```

Required indexes and relationships:

```sql
CREATE INDEX idx_agent_runs_root_status
    ON agent_runs (license_code, root_run_id, status);

CREATE INDEX idx_agent_runs_parent
    ON agent_runs (parent_run_id, status);

ALTER TABLE agent_runs
    ADD CONSTRAINT fk_agent_runs_root
        FOREIGN KEY (root_run_id) REFERENCES agent_runs (id),
    ADD CONSTRAINT fk_agent_runs_parent
        FOREIGN KEY (parent_run_id) REFERENCES agent_runs (id);
```

`requested_by` is non-null for new root runs after authentication rollout. Child rows inherit the
root requester. `lock_version` protects transitions; `checkpoint_version` identifies the checkpoint
the worker must load.

The `parent_tool_invocation_id` foreign key is added only after invocation migration/backfill to
avoid migration ordering problems.

### `agent_run_checkpoints`

One mutable provider-neutral checkpoint per non-terminal run:

```sql
CREATE TABLE agent_run_checkpoints (
    run_id               VARCHAR(36)  NOT NULL,
    version              BIGINT       NOT NULL,
    next_turn_number     INT          NOT NULL,
    messages_json        LONGTEXT     NOT NULL,
    pending_work_json    LONGTEXT     NOT NULL,
    token_usage_json     LONGTEXT     NOT NULL,
    execution_scope_json LONGTEXT     NOT NULL,
    created_at           TIMESTAMP(6) NOT NULL,
    updated_at           TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_agent_run_checkpoints PRIMARY KEY (run_id),
    CONSTRAINT fk_agent_checkpoint_run
        FOREIGN KEY (run_id) REFERENCES agent_runs (id),
    CONSTRAINT chk_agent_checkpoint_version CHECK (version > 0),
    CONSTRAINT chk_agent_checkpoint_turn CHECK (next_turn_number > 0)
);
```

`messages_json` stores provider-neutral `AgentMessage` values, including provider metadata needed to
replay tool calls. `pending_work_json` identifies the exact pending tool call/child/interaction.
`execution_scope_json` captures depth, ancestry, remaining root invocation budget, and human
interaction budget. Secrets must not be stored in any checkpoint.

Checkpoint update and run-state update occur in one transaction. The worker performs
compare-and-set on `version`; a stale worker must stop without external execution.

### `agent_run_steps`

Durable orchestration boundaries, separate from detailed LLM turn logs:

```sql
CREATE TABLE agent_run_steps (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    run_id          VARCHAR(36)  NOT NULL,
    sequence_number INT          NOT NULL,
    type            VARCHAR(30)  NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    subject_type    VARCHAR(30)  NULL,
    subject_id      VARCHAR(150) NULL,
    payload_json    LONGTEXT     NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    completed_at    TIMESTAMP(6) NULL,
    CONSTRAINT pk_agent_run_steps PRIMARY KEY (id),
    CONSTRAINT uk_agent_run_step_sequence UNIQUE (run_id, sequence_number),
    CONSTRAINT fk_agent_run_step_run
        FOREIGN KEY (run_id) REFERENCES agent_runs (id),
    CONSTRAINT chk_agent_run_step_type
        CHECK (type IN ('LLM_TURN', 'TOOL_CALL', 'CHILD_RUN', 'HUMAN_INPUT', 'SYSTEM')),
    CONSTRAINT chk_agent_run_step_status
        CHECK (status IN ('STARTED', 'WAITING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
);
```

Steps support ordered checkpoint/restart semantics. `agent_run_turns` remains the detailed immutable
LLM request/response log.

### `agent_tool_invocations`

Change from completion-only logging to a mutable lifecycle record created before execution.

Add columns:

```sql
ALTER TABLE agent_tool_invocations
    ADD COLUMN step_id BIGINT NULL AFTER run_id,
    ADD COLUMN arguments_sha256 CHAR(64) NULL AFTER arguments_json,
    ADD COLUMN approval_policy_json LONGTEXT NULL AFTER arguments_sha256,
    ADD COLUMN child_run_id VARCHAR(36) NULL AFTER approval_policy_json,
    ADD COLUMN result_metadata_json LONGTEXT NULL AFTER result_json,
    ADD COLUMN started_at TIMESTAMP(6) NULL AFTER created_at,
    ADD COLUMN completed_at TIMESTAMP(6) NULL AFTER started_at,
    ADD COLUMN updated_at TIMESTAMP(6) NULL AFTER completed_at,
    ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;
```

Adjust existing constraints:

- `duration_ms` becomes nullable until terminal.
- `status` becomes mutable and supports `PENDING`, `WAITING_FOR_APPROVAL`, `APPROVED`, `RUNNING`,
  `WAITING_FOR_HUMAN`, `WAITING_FOR_CHILD`, `SUCCEEDED`, `FAILED`, `REJECTED`, and `CANCELLED`.
- `UNKNOWN_OUTCOME` is also terminal-for-automation and requires operator reconciliation when a
  non-idempotent downstream side effect may have happened but runtime could not persist its result.
- `tool_id`, `tool_version`, and `tool_type` are mandatory for new allowed calls. They remain nullable
  only for historical invalid/unresolved calls.
- `arguments_json` is canonicalized before persistence; `arguments_sha256` hashes the canonical UTF-8
  JSON bytes.
- `approval_policy_json` is the immutable effective-policy snapshot, not a live reference.
- `child_run_id` is set only for `CUSTOM_AGENT` invocations.

Relationships and indexes:

```sql
CREATE INDEX idx_agent_tool_status
    ON agent_tool_invocations (run_id, status);

CREATE INDEX idx_agent_tool_child_run
    ON agent_tool_invocations (child_run_id);

ALTER TABLE agent_tool_invocations
    ADD CONSTRAINT fk_agent_tool_step
        FOREIGN KEY (step_id) REFERENCES agent_run_steps (id),
    ADD CONSTRAINT fk_agent_tool_child_run
        FOREIGN KEY (child_run_id) REFERENCES agent_runs (id);

ALTER TABLE agent_runs
    ADD CONSTRAINT fk_agent_run_parent_invocation
        FOREIGN KEY (parent_tool_invocation_id) REFERENCES agent_tool_invocations (id);
```

The existing unique key `(run_id, tool_call_id)` is the primary duplicate-side-effect guard and must
remain.

### `human_interaction_requests`

```sql
CREATE TABLE human_interaction_requests (
    id                       VARCHAR(36)   NOT NULL,
    license_code             VARCHAR(128)  NOT NULL,
    root_run_id              VARCHAR(36)   NOT NULL,
    run_id                   VARCHAR(36)   NOT NULL,
    step_id                  BIGINT        NOT NULL,
    tool_invocation_id       BIGINT        NULL,
    type                     VARCHAR(30)   NOT NULL,
    status                   VARCHAR(20)   NOT NULL,
    category                 VARCHAR(40)   NULL,
    question                 VARCHAR(4000) NULL,
    reason                   VARCHAR(4000) NULL,
    response_type            VARCHAR(30)   NOT NULL,
    request_json             LONGTEXT      NOT NULL,
    audience_type            VARCHAR(30)   NOT NULL,
    audience_values_json     LONGTEXT      NOT NULL,
    assigned_user_id         VARCHAR(128)  NULL,
    subject_sha256           CHAR(64)      NOT NULL,
    created_by_type          VARCHAR(20)   NOT NULL,
    created_by_id            VARCHAR(128)  NULL,
    expires_at               TIMESTAMP(6)  NOT NULL,
    resolved_at              TIMESTAMP(6)  NULL,
    created_at               TIMESTAMP(6)  NOT NULL,
    updated_at               TIMESTAMP(6)  NOT NULL,
    lock_version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_human_interaction_requests PRIMARY KEY (id),
    CONSTRAINT fk_human_interaction_root_run
        FOREIGN KEY (root_run_id) REFERENCES agent_runs (id),
    CONSTRAINT fk_human_interaction_run
        FOREIGN KEY (run_id) REFERENCES agent_runs (id),
    CONSTRAINT fk_human_interaction_step
        FOREIGN KEY (step_id) REFERENCES agent_run_steps (id),
    CONSTRAINT fk_human_interaction_invocation
        FOREIGN KEY (tool_invocation_id) REFERENCES agent_tool_invocations (id),
    CONSTRAINT chk_human_interaction_type
        CHECK (type IN ('CLARIFICATION', 'TOOL_APPROVAL')),
    CONSTRAINT chk_human_interaction_status
        CHECK (status IN ('PENDING', 'ANSWERED', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT chk_human_interaction_response_type
        CHECK (response_type IN ('FREE_TEXT', 'BOOLEAN', 'SINGLE_SELECT', 'MULTI_SELECT',
                                 'NUMBER', 'DATE', 'JSON', 'FILE', 'APPROVAL')),
    CONSTRAINT chk_human_interaction_audience
        CHECK (audience_type IN ('CALLER_AGENT', 'RUN_REQUESTER', 'ROLE', 'GROUP')),
    CONSTRAINT chk_human_interaction_creator
        CHECK (created_by_type IN ('AGENT', 'POLICY', 'OPERATOR'))
);

CREATE INDEX idx_human_interaction_inbox
    ON human_interaction_requests (license_code, status, assigned_user_id, created_at);

CREATE INDEX idx_human_interaction_root
    ON human_interaction_requests (root_run_id, status, created_at);

CREATE INDEX idx_human_interaction_expiry
    ON human_interaction_requests (status, expires_at);

CREATE UNIQUE INDEX uk_human_interaction_tool_type
    ON human_interaction_requests (tool_invocation_id, type);
```

`request_json` contains options/response schema for clarification or the redacted immutable tool-call
snapshot for approval. `subject_sha256` binds the full non-redacted canonical subject. Views apply
authorization-based redaction; the stored binding is never recomputed from a redacted view.

### `human_interaction_responses`

Exactly one submitted resolution per request:

```sql
CREATE TABLE human_interaction_responses (
    id                 VARCHAR(36)   NOT NULL,
    interaction_id     VARCHAR(36)   NOT NULL,
    license_code       VARCHAR(128)  NOT NULL,
    action             VARCHAR(20)   NOT NULL,
    response_json      LONGTEXT      NOT NULL,
    actor_type         VARCHAR(20)   NOT NULL,
    actor_id           VARCHAR(128)  NOT NULL,
    actor_roles_json   LONGTEXT      NOT NULL,
    delegated_by       VARCHAR(128)  NULL,
    audit_context_json LONGTEXT      NOT NULL,
    idempotency_key    VARCHAR(128)  NOT NULL,
    created_at         TIMESTAMP(6)  NOT NULL,
    CONSTRAINT pk_human_interaction_responses PRIMARY KEY (id),
    CONSTRAINT uk_human_interaction_response UNIQUE (interaction_id),
    CONSTRAINT uk_human_response_idempotency UNIQUE (license_code, idempotency_key),
    CONSTRAINT fk_human_response_request
        FOREIGN KEY (interaction_id) REFERENCES human_interaction_requests (id),
    CONSTRAINT chk_human_response_action
        CHECK (action IN ('ANSWER', 'APPROVE', 'REJECT')),
    CONSTRAINT chk_human_response_actor
        CHECK (actor_type IN ('HUMAN', 'CALLER_AGENT', 'SYSTEM_DEFAULT'))
);
```

Responses are insert-only. Corrections are new `HUMAN_INPUT` run steps/events and checkpoint
messages, never updates to this table.

### `agent_run_events`

Append-only audit and root timeline:

```sql
CREATE TABLE agent_run_events (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    license_code    VARCHAR(128)  NOT NULL,
    root_run_id     VARCHAR(36)   NOT NULL,
    run_id          VARCHAR(36)   NOT NULL,
    event_type      VARCHAR(60)   NOT NULL,
    actor_type      VARCHAR(20)   NOT NULL,
    actor_id        VARCHAR(128)  NULL,
    subject_type    VARCHAR(30)   NULL,
    subject_id      VARCHAR(150)  NULL,
    event_json      LONGTEXT      NOT NULL,
    created_at      TIMESTAMP(6)  NOT NULL,
    CONSTRAINT pk_agent_run_events PRIMARY KEY (id),
    CONSTRAINT fk_agent_run_event_root
        FOREIGN KEY (root_run_id) REFERENCES agent_runs (id),
    CONSTRAINT fk_agent_run_event_run
        FOREIGN KEY (run_id) REFERENCES agent_runs (id)
);

CREATE INDEX idx_agent_run_event_timeline
    ON agent_run_events (license_code, root_run_id, id);
```

Initial event names:

```text
RUN_STARTED, RUN_WAITING, RUN_RESUME_REQUESTED, RUN_RESUMED,
RUN_SUCCEEDED, RUN_FAILED, RUN_CANCELLED, RUN_EXPIRED,
LLM_TURN_COMPLETED,
TOOL_INVOCATION_CREATED, TOOL_APPROVAL_REQUESTED, TOOL_APPROVED,
TOOL_REJECTED, TOOL_INVOCATION_COMPLETED, TOOL_INVOCATION_FAILED,
CHILD_RUN_CREATED, CHILD_RUN_COMPLETED,
CLARIFICATION_REQUESTED, CLARIFICATION_ANSWERED,
HUMAN_INSTRUCTION_ADDED, INTERACTION_EXPIRED, INTERACTION_CANCELLED
```

### `runtime_outbox_events`

```sql
CREATE TABLE runtime_outbox_events (
    id               VARCHAR(36)  NOT NULL,
    aggregate_type   VARCHAR(30)  NOT NULL,
    aggregate_id     VARCHAR(36)  NOT NULL,
    event_type       VARCHAR(60)  NOT NULL,
    payload_json     LONGTEXT     NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    attempt_count    INT          NOT NULL DEFAULT 0,
    available_at     TIMESTAMP(6) NOT NULL,
    claimed_at       TIMESTAMP(6) NULL,
    claimed_by       VARCHAR(128) NULL,
    published_at     TIMESTAMP(6) NULL,
    last_error       VARCHAR(2000) NULL,
    created_at       TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_runtime_outbox_events PRIMARY KEY (id),
    CONSTRAINT chk_runtime_outbox_status
        CHECK (status IN ('PENDING', 'CLAIMED', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX idx_runtime_outbox_claim
    ON runtime_outbox_events (status, available_at, created_at);
```

`human_response_batches` stores the root ID, tenant-scoped idempotency key, canonical request hash,
actor snapshot, and original API response. Each immutable interaction response references its batch.
The unique `(license_code, idempotency_key)` constraint makes an identical batch replayable and
rejects reuse with different content.

A root response transaction locks all supplied interactions in deterministic ID order, validates
the entire batch, updates every accepted request, inserts the individual immutable responses and
batch record, and inserts one `ROOT_RESUME_REQUESTED` command atomically. Interactions omitted from
the batch remain pending. The request thread claims that exact command after commit and executes it
synchronously. Its `available_at` gives the request thread a recovery grace period; a background
worker claims the command only if the synchronous claimant never starts or retries it after failure.
A worker claim must use locking
such as `SELECT ... FOR UPDATE SKIP LOCKED`. Processing is at-least-once; the run engine is
idempotent and orders affected owners by persisted run depth before resuming them.

## Transaction boundaries

1. Persist LLM response and discovered tool call before any external side effect.
2. Persist invocation and approval request/checkpoint/run wait state atomically.
3. Persist a human response, interaction transition, audit event, and resume outbox command atomically.
4. Commit `RUNNING` invocation ownership before external execution; use unique tool-call identity and
   executor idempotency keys wherever the remote system supports them.
5. Persist external result before resuming the LLM.
6. Never hold a transaction across LLM, HTTP, MCP, database-tool, or human wait calls.

Database uniqueness prevents duplicate orchestration, but exactly-once external side effects also
require passing a stable idempotency key (preferably the invocation ID) to capable downstream tools.

## Retention

- Keep responses and audit events according to tenant policy; proposed default is 90 days.
- Delete completed mutable checkpoints after the recovery window, while retaining immutable turn,
  invocation, interaction, and audit records.
- Delete or revoke temporary attachments at expiry/retention boundaries.
- Never persist credentials, authorization headers, or resolved secret values in any JSON column.
