# Human-in-the-loop implementation plan

Status: proposed implementation contract after stakeholder clarification. This document does not
change runtime behaviour by itself.

## Agreed scope

The first implementation supports exactly two blocking human interaction types:

- `CLARIFICATION`: an agent needs information before it can continue.
- `TOOL_APPROVAL`: a tool call, including a `CUSTOM_AGENT` tool call, must be approved before it
  executes.

Plan approval and final-output approval are outside this slice. The tool definition owns the normal
approval requirement. Platform and tenant guardrails may make the effective policy stricter. An
agent, parent agent, or run request cannot make it weaker.

The initial `POST /api/v1/agent-runs` remains synchronous until it reaches one of two stable
application outcomes:

- `SUCCEEDED`: return HTTP 200 with the final output.
- `WAITING_FOR_HUMAN`: return HTTP 200 with the pending interactions.

Validation, authorization, definition, provider, and unexpected execution failures continue to use
non-200 Problem Detail responses. Human responses are persisted synchronously, while run resumption
is queued and performed by a worker.

## Architectural principles

1. Waiting is durable. No HTTP connection, JVM thread, recursive Java stack, or database transaction
   remains open while waiting for a person.
2. The same run resumes. A response never creates a replacement run or repeats completed tool calls.
3. Submitted responses are immutable. Corrections are new audited human-input events.
4. Approval binds to the exact run, step, agent/tool version, and canonical arguments hash.
5. Every tool that executes is still dispatched through `ToolExecutorRegistry`, including
   `CUSTOM_AGENT` and the runtime-provided clarification control tool.
6. Approval is common orchestration around the registry, not logic duplicated in each executor.
7. Management owns policy definitions; runtime owns executions, checkpoints, interactions, and
   audit events.
8. Parent and child runs are separate durable executions connected by IDs, not a synchronous-only
   call stack.

## Target dependency direction

```text
AgentRunController
  -> AgentRunApplicationService
      -> AgentRunEngine
          -> HumanInteractionCoordinator
          -> ToolExecutionCoordinator
              -> EffectiveApprovalPolicyEvaluator
              -> ToolExecutorRegistry
                  -> HTTP / CUSTOM_AGENT / BUILT_IN / future executors

HumanInteractionController
  -> HumanInteractionService
      -> runtime database + ResumeCommandOutbox

ResumeWorker
  -> AgentRunEngine
```

`ToolExecutorRegistry` remains the only type-to-executor router. `ToolExecutionCoordinator` owns
cross-cutting steps that must happen before and after dispatch: invocation persistence, policy
evaluation, approval suspension, argument binding, status transitions, and audit events.

Executors must not depend on `AgentExecutionService` or controllers. `CUSTOM_AGENT` continues to use
an `AgentToolInvocationPort`. The clarification control executor returns a suspended execution
outcome rather than blocking.

## Execution outcomes

The current completed-only `ToolExecutionResult` needs an orchestration-level outcome that can
represent:

- `COMPLETED`: output is ready to append as the LLM tool result.
- `WAITING_FOR_HUMAN`: a persisted interaction must be answered first.
- `WAITING_FOR_CHILD`: a child run must reach a stable outcome first.

This should be a closed Java type (for example, a sealed interface), not a magic metadata string.
The registry still returns one uniform outcome for every executor type.

## State machines

### Agent run

```text
RUNNING
  -> WAITING_FOR_HUMAN -> RUNNING
  -> WAITING_FOR_CHILD -> RUNNING
  -> PAUSED             -> RUNNING
  -> SUCCEEDED
  -> FAILED
  -> CANCELLED
  -> EXPIRED
```

Only `RUNNING` may advance LLM or tool execution. `SUCCEEDED`, `FAILED`, `CANCELLED`, and `EXPIRED`
are terminal. A root-run API view reports `WAITING_FOR_HUMAN` when a blocking descendant has a
pending interaction, even if an intermediate parent is locally `WAITING_FOR_CHILD`.

### Tool invocation

```text
PENDING
  -> WAITING_FOR_APPROVAL -> APPROVED -> RUNNING -> SUCCEEDED | FAILED
  -> RUNNING -> WAITING_FOR_CHILD -> RUNNING -> SUCCEEDED | FAILED
  -> RUNNING -> WAITING_FOR_HUMAN -> RUNNING -> SUCCEEDED | FAILED
  -> REJECTED
  -> CANCELLED
```

The invocation row is created before policy evaluation. This is required to bind approval to a
stable invocation ID and exact arguments.

### Human interaction

```text
PENDING -> ANSWERED | APPROVED | REJECTED | EXPIRED | CANCELLED
```

An interaction has exactly one submitted response. Repeated delivery with the same idempotency key
returns the original result. A different second response returns `409 Conflict`.

## Clarification flow

1. Runtime automatically exposes `request_clarification` to every executing agent as a governed
   `BUILT_IN` control tool; no `custom_tools` row is required.
2. The LLM calls it with a category, question, reason, response type, options/schema, optional safe
   default, and audience hint.
3. The built-in executor validates the request and produces `WAITING_FOR_HUMAN`.
4. Runtime creates the interaction, stores the conversation checkpoint, and marks the local run
   `WAITING_FOR_HUMAN`.
5. Every sequential ancestor stops starting new work. Direct parents are locally
   `WAITING_FOR_CHILD`; the root API view is `WAITING_FOR_HUMAN`.
6. Audience resolution tries `CALLER_AGENT` only for caller-context questions. If the parent cannot
   answer from explicit existing context, it falls back to the run requester. A configured role or
   group overrides the requester when agent policy requires it.
7. An authorized human answers. Runtime atomically stores the immutable response and writes a resume
   command to the outbox.
8. The worker loads the checkpoint, appends a `TOOL` result for the original clarification tool-call
   ID, and continues the child.
9. When the child succeeds, its original `CUSTOM_AGENT` invocation completes and its output is
   appended to the parent conversation. Ancestors resume in order until the root reaches another
   checkpoint or succeeds.

Normal assistant prose is never parsed heuristically as a clarification request.

## Tool approval flow

1. The LLM produces a tool call.
2. Runtime resolves the published tool and persists a `PENDING` invocation containing the tool
   version, canonical arguments, SHA-256 binding, and approval-policy snapshot.
3. The effective policy evaluator combines platform, tenant, tool, agent/parent, and run constraints;
   the most restrictive result wins. The tool definition is the normal source of `required=true`.
4. If approval is unnecessary, runtime marks the invocation `RUNNING` and dispatches through the
   registry.
5. If approval is required, runtime creates a `TOOL_APPROVAL` interaction and marks the invocation
   `WAITING_FOR_APPROVAL` without calling the executor.
6. Approval verifies authorization, expiry, invocation status, tool version, and arguments hash in
   one transaction. It queues resumption.
7. The worker changes `APPROVED -> RUNNING`, executes the exact persisted arguments through the
   registry, persists the result, and resumes the LLM.
8. Rejection marks the invocation `REJECTED` and appends a structured `HUMAN_DECLINED` tool result so
   the LLM may replan, unless the captured policy requires the run to fail.

Editing tool arguments creates a new invocation attempt and invalidates the original approval. The
first implementation does not silently mutate an approved invocation.

## Nested custom-agent behaviour

- A `CUSTOM_AGENT` definition may itself require approval. That approval gates creation/execution of
  the child run.
- The child is a separate `agent_runs` row with `root_run_id`, `parent_run_id`, and
  `parent_tool_invocation_id`.
- Tools inside the child independently apply their own published approval policies.
- A child clarification is shown in the root timeline with the child agent/run context.
- Cancelling the root cancels all non-terminal descendants. Cancelling only a child returns a
  structured failure to the parent when its failure policy permits replanning.
- Cycle, depth, invocation, and human-interaction budgets remain enforced across the root tree.

## Audience resolution

Runtime, not the LLM, chooses the actual assignee:

1. `CALLER_CONTEXT`: offer the structured question to the parent agent using only explicit context.
2. Agent policy `ROLE` or `GROUP`: assign to eligible members in the same tenant.
3. Otherwise: assign to the authenticated root-run requester.
4. If no eligible assignee exists: escalate to the agent owner, then tenant administrator.

The LLM supplies only an audience hint and category. It cannot name a user or grant authority.

## Root workflow pause rule

Phase one is sequential. As soon as any branch records a blocking interaction:

- do not start new LLM turns, tool calls, or child runs anywhere in that root workflow;
- allow an operation that was already externally dispatched to finish and record its result;
- never repeat an already completed side effect during resume;
- resume from the deepest waiting child and then propagate completion toward the root.

Parallel DAG execution and independent branch continuation are explicitly deferred.

## Security boundary

The current tenant header is not sufficient for human decisions. Before exposing response APIs:

- authenticate callers through the platform identity/JWT integration;
- derive actor ID, roles, and groups from the security principal;
- require `X-Agent-License-Code` to belong to that principal;
- enforce tenant predicates on every lookup;
- support `RUN_REQUESTER`, `RUN_OPERATOR`, `APPROVER`, `AGENT_OWNER`, and `AGENT_ADMIN` authorities;
- allow requester self-approval only when the effective policy permits it;
- require separation of duties for destructive/high-sensitivity policies;
- redact secrets and unauthorized sensitive fields in interaction views and audit payloads.

## Phased delivery plan

### Phase 1: Contract and migration foundation

- Add management JSON contracts for tool approval and agent clarification policy.
- Add runtime migrations for run relationships/status, mutable invocation lifecycle, checkpoints,
  steps, interactions, responses, events, and outbox.
- Add domain enums and invariant tests before changing execution.
- Backfill existing runs with `root_run_id=id`; preserve `SUCCEEDED` and `FAILED` history.

### Phase 2: Durable run engine

- Separate the resumable engine from the HTTP request lifecycle.
- Replace recursive-stack-only state with persisted parent/child state.
- Add checkpoint save/load with optimistic locking.
- Make every transition idempotent and event-producing.
- Preserve the current synchronous-until-stable initial API behaviour.

### Phase 3: Identity and authorization

- Add Spring Security integration and tenant validation.
- Implement role/group audience resolution and self-approval restrictions.
- Add field redaction and immutable actor audit information.

### Phase 4: Clarification

- Add the runtime-provided `request_clarification` definition and executor.
- Add interaction creation, inbox/detail/response APIs, expiry, and queued resume.
- Append the answer as the original tool-call result and resume the same child.
- Add correction/instruction API and safe-boundary pause semantics.

### Phase 5: Tool approval

- Validate and expose approval policy in Agent Management.
- Add effective-policy evaluation and pre-registry execution coordination.
- Bind approval to canonical arguments and immutable definition versions.
- Implement approve/reject, authorization, expiry, and structured denial-to-LLM behaviour.

### Phase 6: Nested propagation

- Persist parent/child/invocation links.
- Propagate child waiting, completion, failure, and cancellation through outbox events.
- Resume deepest child first and parent chain afterward.
- Enforce root-level pause and budgets.

### Phase 7: Operations and rollout

- Add expiry/escalation workers, reminders, metrics, tracing, and stuck-run administration.
- Add feature flags per tenant/agent and initially disable HITL for existing definitions.
- Add retention cleanup for checkpoints and attachments while preserving required audit records.
- Load-test long waits, resume bursts, and duplicate delivery.

## Migration and compatibility rules

- Existing tools without an approval object behave as `required=false`.
- Existing agents without clarification policy use requester audience, 24-hour expiry, and a maximum
  of 20 interactions per root run.
- Keep the public successful status literal `SUCCEEDED` to avoid an unnecessary rename to
  `COMPLETED`.
- Existing run history remains readable; only new runs are resumable.
- A rollout flag prevents an older runtime binary from claiming resumable runs after migrations.
- Management and runtime continue to share no Java/Maven dependency. JSON contracts are duplicated
  into service-owned models and documented under `contracts/`.

## Required acceptance scenarios

1. Root succeeds without interaction and returns HTTP 200.
2. Root requests clarification, returns HTTP 200/`WAITING_FOR_HUMAN`, accepts an answer, resumes, and
   succeeds.
3. Parent invokes child; child asks; root pauses; answer resumes the same child; result returns to
   parent; root succeeds.
4. A protected HTTP tool is not dispatched before approval.
5. A protected `CUSTOM_AGENT` tool does not create/start the child before approval.
6. Child internal tools require their own approvals.
7. Rejection reaches the LLM as structured denial and can trigger replanning.
8. Changed arguments cannot use an old approval.
9. Duplicate response/resume delivery does not execute a tool twice.
10. A second conflicting responder receives `409`.
11. Unauthorized and cross-tenant actors receive `403`/`404` without data leakage.
12. Restarting every runtime instance while waiting does not lose the run.
13. Root cancellation cancels descendants and prevents later resume.
14. Expiry follows the captured policy and is audited.
15. A submitted clarification answer cannot be mutated; a correction is a new event.
16. Existing non-HITL agent and tool definitions remain compatible.

## Explicitly deferred

- Plan approval and final-output approval
- Batch approval
- Permanent “always approve” grants
- Independent parallel branch continuation
- Full notification-channel implementations beyond domain events/webhooks
- Full operator UI
- Automatic model switching during resume

