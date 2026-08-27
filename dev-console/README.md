# Agent Platform dev console

A minimal browser console for testing and validating the Custom Agent Platform by hand: author
tools and agents, publish them, run agents as a draft or as a published definition, answer
human-in-the-loop prompts, and read every turn with its token usage.

It is a **developer harness**, not a product UI. It shows raw JSON, surfaces Problem Details
verbatim, and lets you switch identity freely so authorization and HITL audience routing can be
exercised.

```bash
cp .env.example .env     # point at your services if the ports differ
npm install
npm run dev              # http://localhost:5173
```

## Why there is a proxy

Neither service sends CORS headers - a direct browser call is rejected with
`403 Invalid CORS request`. Every request therefore goes through the Vite dev server, which
forwards `/proxy/management/*` and `/proxy/runtime/*` to the two services. The app only ever talks
to its own origin, so no CORS configuration is needed upstream. Targets come from `.env`:

| Variable | Default | Purpose |
| --- | --- | --- |
| `MANAGEMENT_BASE_URL` | `http://localhost:8080` | agent-management |
| `RUNTIME_BASE_URL` | `http://localhost:8081` | agent-runtime |
| `PORT` | `5173` | this console |

## Identity

With `agent-platform.security.enabled` false (the compose default) both services skip JWT
authentication and read the caller's identity straight from request headers. The console sends:

```
X-Agent-License-Code: <tenant>
X-Agent-User-Id:      <actor>
X-Agent-Roles:        <comma-separated roles>
```

The top-right identity switcher edits all three and persists them to `localStorage`. Changing the
tenant remounts the views, so lists never leak across license codes. Switching user or roles is how
you test that an interaction addressed to `ROLE: APPROVER` is invisible to someone without it.

## What each tab does

**Tools** - list, create, edit, publish, disable. `fill approval-required policy` drops in an
`executionPolicy` whose `approval.required` is true, which is the precondition for exercising
`TOOL_APPROVAL` interactions. Editing is rejected on a non-draft tool; the UI says so before you try.

**Agents** - list, create, edit a draft, publish, retire, and copy. Allowed tools are checkboxes
over *published* tools only, since publishing an agent fails if a listed tool is not published.
`fill clarification policy` sets the `humanInteractionPolicy` that enables clarification routing.
Copy requires the source to be `PUBLISHED` or `RETIRED` and produces an independent draft at v1.

**Run** - two modes:

- *Draft test* (`POST /api/v1/agent-test-runs`) executes a `DRAFT` agent transiently. Nothing is
  persisted, so the conversation comes back inline and only **aggregate** usage is available. Non-`READ`
  tools are mocked; supply results under *Mock tool results*, keyed by published tool name. On
  `NEEDS_INPUT` the console keeps the original request plus every answer collected so far and replays
  it with the `draftRevision` it was produced against - a stateless resume. Edit the draft mid-sequence
  and the runtime returns 409, which the console surfaces.
- *Published run* (`POST /api/v1/agent-runs`) executes the tenant's published definition and persists
  everything. The console polls while the run is non-terminal, so `WAITING_FOR_HUMAN → RUNNING →
  SUCCEEDED` is visible. Turns come from the trace endpoint below. You can also load any past run by id.

**Egress** - the tenant's HTTP egress allowlist (`/api/v1/egress-hosts`). Adding or revoking a host
needs `AGENT_ADMIN`/`PLATFORM_ADMIN`; with an editor-only identity the tab goes read-only, which is
the quickest way to check that gate holds. The tab flags published HTTP tools whose host no active
entry covers, so a revocation's blast radius is visible before it bites at run time.

**HITL inbox** - the audience-filtered pending interactions for the current identity
(`GET /api/v1/human-interactions`), answered one at a time. Draft-test interactions never appear
here; they are stateless and answered inside the run view.

## Turns and token usage

For a published run the console calls
`GET /api/v1/agent-runs/{runId}/turns`, which returns the whole run tree - root plus every child
run a `CUSTOM_AGENT` tool spawned - with, per turn, the prompt messages, the assistant text and tool
calls, the tool invocations dispatched (arguments, result, status, duration, child run id), and
**that turn's own token usage**. Run-level and total usage are rolled up from the turns.

Two things to know:

- This endpoint was **added to agent-runtime for this console**. Nothing exposed per-turn data before;
  the rows existed in `agent_run_turns` with no reader. It is read-only and never mutates run state.
- `GET /api/v1/agent-runs/{runId}` reports `usage` as all zeros for an already-started run
  (`AgentExecutionService.get` returns `TokenUsage.ZERO`). The trace totals are the reliable figure,
  and the run view says so inline rather than showing a misleading zero.

The documented `GET /agent-runs/{rootRunId}/events` timeline is *not* implemented in the service, and
its `agent_run_events` / `agent_run_steps` tables have no entities and stay empty - which is why the
trace reads turns rather than events. `pause`, `resume`, and interaction `assignment` from the same
contract are also unimplemented, so the console does not offer them.

## Testing without an LLM key

`stub-llm/server.mjs` is a test double for the slice of the Gemini API that agent-runtime's adapter
uses. Because `GEMINI_BASE_URL` is configurable, pointing the runtime at it exercises multi-turn
generation, tool dispatch, and HITL deterministically with no key and no spend:

```bash
npm run stub-llm     # terminal 1, listens on :4010
docker compose -f compose.yaml -f dev-console/compose.stub-llm.yaml up -d agent-runtime
```

Its behaviour is driven by directives you type into a run's task text:

| Directive | Effect |
| --- | --- |
| *(none)* | call the first declared non-control tool, then summarize its result |
| `[[clarify]]` | ask a human first, via `request_clarification` |
| `[[notools]]` | answer immediately without calling any tool |

The override sets `GEMINI_ENABLED`, `GEMINI_API_KEY`, and `GEMINI_BASE_URL` for the runtime container
and leaves the platform's own `compose.yaml` untouched. Drop the extra `-f` to go back to the real
provider.

## HTTP tool destinations

A tool's host must be allowed for the tenant before it can be published; the runtime re-checks on
every call, so revoking a host stops already-published agents within ~30s. Separately, and not
configurable per tenant, the runtime refuses any host resolving to loopback, link-local
(`169.254.169.254`), private, or CGNAT ranges, and never follows redirects. See
[`../contracts/tool-egress-contract.md`](../contracts/tool-egress-contract.md).

The Tools tab has no *disable* control because the management status endpoint only accepts
`DRAFT -> PUBLISHED`; to stop a published HTTP tool reaching its API, revoke its host under Egress.

## Request shapes worth knowing

Both discovered by trying them; the console encodes both so you do not have to:

- **On create**, an optional JSON-object field must be *absent*, not `null` - the services validate
  `must be a JSON object when provided`, so `{"outputSchema": null}` is a 400.
- **On an agent PATCH**, `null` is meaningful: it is how a stored optional field gets cleared. The
  console omits nulls when creating and preserves them when patching.

## Layout

```
src/lib/types.ts     hand-written mirrors of both OpenAPI documents (/openapi on each service)
src/lib/api.ts       fetch wrapper: identity headers, Idempotency-Key, ApiError from ProblemDetail
src/lib/identity.ts  identity context and localStorage persistence
src/components/      shared primitives, and the turn/trace viewers
src/views/           one file per tab
stub-llm/            the no-key Gemini test double
```

Types are hand-written rather than generated: this is a harness, and a short explicit type is easier
to read against the contracts than a large generated client.
