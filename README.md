# Custom Agent Platform

One product and repository containing two independently deployable Java 21 / Spring Boot applications:

- `agent-management`: custom-agent authoring, validation, persistence, and lifecycle APIs
- `agent-runtime`: custom-agent execution, tools, conversation logging, and LLM-provider integration

The applications run in separate JVMs and do not have a Maven dependency on each other.

## Build and test

Build both applications from the repository root:

```bash
mvn test
mvn package
```

Build or run a single application:

```bash
mvn -pl agent-management test
mvn -pl agent-runtime test

mvn -pl agent-management spring-boot:run
mvn -pl agent-runtime spring-boot:run
```

## One-command local deployment

Start MySQL and both application JVMs:

```bash
docker compose up --build
```

Local endpoints:

| Application | Base URL | Health | Swagger UI |
| --- | --- | --- | --- |
| Agent Management | `http://localhost:8080` | `http://localhost:8080/actuator/health` | `http://localhost:8080/swagger-ui.html` |
| Agent Runtime | `http://localhost:8081` | `http://localhost:8081/actuator/health` | `http://localhost:8081/swagger-ui.html` |

The local MySQL container creates separately owned `custom_agent_management` and
`custom_agent_runtime` schemas. Agent Management owns custom-agent and dynamic-tool definitions.
Agent Runtime owns run, conversation-turn, and tool-invocation logs.

Provider integrations remain disabled unless credentials are provided before starting Compose.
Enable every provider/model that customers may select. Gemini is the fallback when a run omits
`provider` and `model`:

```bash
export OPENAI_ENABLED=true
export OPENAI_API_KEY='...'
export OPENAI_MODELS=gpt-5.6-sol

export GEMINI_ENABLED=true
export GEMINI_API_KEY='...'
export GEMINI_MODELS=gemini-3.6-flash

# Optional fallback override; defaults to GOOGLE_GEMINI / gemini-3.6-flash.
# export AGENT_RUNTIME_PROVIDER=GOOGLE_GEMINI
# export AGENT_RUNTIME_MODEL=gemini-3.6-flash

docker compose up --build
```

## Create and publish a dynamic HTTP tool

Dynamic tools are data in the management service. Creating another HTTP tool does not require an
Agent Runtime build or deployment.

```bash
curl --request POST 'http://localhost:8080/api/v1/tools' \
  --header 'Content-Type: application/json' \
  --header 'X-Agent-License-Code: account-123' \
  --data '{
    "name": "campaign.get",
    "description": "Gets campaign configuration and status",
    "type": "HTTP",
    "inputSchema": {
      "type": "object",
      "properties": {"campaignId": {"type": "string"}},
      "required": ["campaignId"]
    },
    "outputSchema": {
      "type": "object",
      "properties": {"status": {"type": "string"}, "sentAt": {"type": "string"}}
    },
    "configuration": {
      "method": "GET",
      "url": "https://api.example.com/v1/campaigns/{campaignId}"
    },
    "executionPolicy": {
      "operation": "EXTERNAL_COMMUNICATION",
      "riskLevel": "HIGH",
      "approval": {
        "required": true,
        "audience": {"type": "ROLE", "values": ["CAMPAIGN_APPROVER"]},
        "allowSelfApproval": false,
        "expiresAfterSeconds": 86400,
        "onReject": "RETURN_TO_AGENT",
        "onExpire": "REJECT"
      }
    }
  }'
```

Publish the returned tool ID:

```bash
curl --request PATCH 'http://localhost:8080/api/v1/tools/{tool-id}/status' \
  --header 'Content-Type: application/json' \
  --header 'X-Agent-License-Code: account-123' \
  --data '{"status":"PUBLISHED"}'
```

`outputSchema` is optional and documents what the tool returns. Function-calling APIs have no field
for a response shape, so the runtime appends it to the description it sends the provider — which
means a tool can describe its response as a schema instead of as prose, and the model gets a
contract rather than a paragraph.

HTTP tool destinations are denied unless the host is allowed for the tenant. The allowlist is
per-tenant data, not process configuration, because every tenant registers its own tool hosts:

```bash
curl -X POST http://localhost:8080/api/v1/egress-hosts \
  --header 'X-Agent-License-Code: DEV_LICENSE' \
  --header 'X-Agent-User-Id: admin-user' \
  --header 'X-Agent-Roles: AGENT_ADMIN' \
  --header 'Content-Type: application/json' \
  --data '{"hostPattern":"api.example.com","description":"Campaign API"}'
```

Writes require `AGENT_ADMIN` or `PLATFORM_ADMIN`: if whoever authors a tool can also approve its
destination, the allowlist is not a control. `*.example.com` covers subdomains but never the apex,
and a wildcard must span at least two labels, so `*.com` is rejected.

Enforcement happens twice. Publishing a tool whose host is not allowed returns `422`
`tool-host-not-allowed`, so the failure surfaces during authoring. The runtime re-checks on every
call, so revoking a host stops already-published agents within the allowlist cache TTL
(`AGENT_HTTP_TOOL_ALLOWLIST_CACHE_TTL`, 30s by default). A URL whose *host* is a template variable
is refused at publish time — the destination would otherwise be chosen by model-supplied arguments.

Two things stay platform policy and are deliberately not tenant-configurable:

- Hosts resolving to loopback, link-local (`169.254.169.254`), private, or CGNAT ranges are refused
  no matter what a tenant registers. Allowlisting by name alone would let a tenant point a host they
  own at cloud instance metadata. Set `AGENT_HTTP_TOOL_ALLOW_PRIVATE_NETWORKS=true` for local
  development only.
- Redirects are never followed, since a `302` to an internal address would bypass the check.

`AGENT_HTTP_TOOL_ALLOWED_HOSTS` still exists but now means "allowed for *every* tenant". Keep it
empty in a deployed environment; it is there so local examples can reach their APIs without first
registering a tenant.

Publishing is refused with `422` if a definition would not fit in a model request. The budgets are
per tool, per agent, and per agent-plus-its-tools; see `PromptBudget`. Drafts are not checked, so
work in progress can always be saved.

## Create and publish an agent

```bash
curl --request POST 'http://localhost:8080/api/v1/agents' \
  --header 'Content-Type: application/json' \
  --header 'X-Agent-License-Code: account-123' \
  --header 'X-Agent-User-Id: user-123' \
  --header 'X-Agent-Roles: AGENT_EDITOR' \
  --data '{
    "name": "Campaign QA Agent",
    "description": "Checks campaign readiness",
    "role": "platform campaign quality analyst",
    "instructions": "Review the supplied campaign and return actionable findings.",
    "rules": ["Never mutate a campaign without approval"],
    "outputFormat": "Return JSON matching the output schema",
    "outputSchema": {"type": "object", "required": ["findings"]},
    "context": {},
    "examples": [],
    "allowedTools": ["campaign.get"],
    "humanInteractionPolicy": {
      "clarification": {
        "defaultAudience": {"type": "RUN_REQUESTER", "values": []},
        "expiresAfterSeconds": 86400,
        "onExpire": "FAIL_CHILD",
        "maxRequestsPerRootRun": 20
      }
    }
  }'
```

Publish the returned ID:

```bash
curl --request PATCH 'http://localhost:8080/api/v1/agents/{agent-id}/status' \
  --header 'Content-Type: application/json' \
  --header 'X-Agent-License-Code: account-123' \
  --header 'X-Agent-User-Id: user-123' \
  --header 'X-Agent-Roles: AGENT_PUBLISHER' \
  --data '{"status":"PUBLISHED"}'
```

## Read and edit a draft agent

```bash
curl --request GET 'http://localhost:8080/api/v1/agents/{agent-id}' \
  --header 'X-Agent-License-Code: account-123' \
  --header 'X-Agent-User-Id: user-123' \
  --header 'X-Agent-Roles: AGENT_EDITOR'

curl --request PATCH 'http://localhost:8080/api/v1/agents/{agent-id}' \
  --header 'Content-Type: application/json' \
  --header 'X-Agent-License-Code: account-123' \
  --header 'X-Agent-User-Id: user-123' \
  --header 'X-Agent-Roles: AGENT_EDITOR' \
  --header 'X-Agent-Change-Reason: Tune the strategy prompt' \
  --data '{
    "instructions": "Review the campaign and prioritize high-impact findings.",
    "allowedTools": ["campaign.get", "audience.search"]
  }'
```

PATCH is available only while the agent is `DRAFT`. Omitted fields remain unchanged, explicit
`null` clears optional fields, and empty collections clear collection fields. Draft edits run in a
transaction and use last-commit-wins behavior; the business `version` remains `1`.

## Copy a published or retired agent

```bash
curl --request POST 'http://localhost:8080/api/v1/agents/{source-agent-id}/copies' \
  --header 'Content-Type: application/json' \
  --header 'X-Agent-License-Code: account-123' \
  --header 'X-Agent-User-Id: user-123' \
  --header 'X-Agent-Roles: AGENT_EDITOR' \
  --header 'Idempotency-Key: campaign-qa-v2-copy-1' \
  --data '{"name":"Campaign QA Agent v2"}'
```

The copy receives a new ID, starts as an independent version-1 draft, and does not retain source
lineage. The source remains unchanged. Publishing the copy does not create or retarget a custom
agent tool.

## Retire a published agent

```bash
curl --request PATCH 'http://localhost:8080/api/v1/agents/{agent-id}/status' \
  --header 'Content-Type: application/json' \
  --header 'X-Agent-License-Code: account-123' \
  --header 'X-Agent-User-Id: user-123' \
  --header 'X-Agent-Roles: AGENT_PUBLISHER' \
  --data '{"status":"RETIRED"}'
```

Retirement is blocked when the agent has a non-terminal root or child run (`PENDING`, `RUNNING`,
`WAITING_FOR_HUMAN`, `WAITING_FOR_CHILD`, or `PAUSED`), or when a published
custom-agent tool targeting it is allowed by another published agent. Management uses an internal
`RETIRING` guard and asks Runtime for active-run eligibility. `RETIRING` is never accepted from the
public API. An interrupted retirement is restored to `PUBLISHED` automatically.

## Run a published agent

```bash
curl --request POST 'http://localhost:8081/api/v1/agent-runs' \
  --header 'Content-Type: application/json' \
  --header 'X-Agent-License-Code: account-123' \
  --data '{
    "agentId": "{agent-id}",
    "task": "Review this campaign and identify launch risks",
    "input": {"campaignId": "cmp-123"},
    "provider": "OPENAI",
    "model": "gpt-5.6-sol"
  }'
```

`provider` and `model` are optional but must be supplied together. If both are omitted, the runtime
uses `AGENT_RUNTIME_PROVIDER` and `AGENT_RUNTIME_MODEL`, whose defaults are
`GOOGLE_GEMINI` and `gemini-3.6-flash`.

The runtime loads the tenant-scoped row directly from
`custom_agent_management.custom_agents` for every request and requires its status to be
`PUBLISHED`. It also loads each allowed tool from `custom_tools`, requiring the tool to be
`PUBLISHED`. It builds the prompt, invokes the run-selected (or fallback) base agent, routes tool calls through the
executor registered for the definition's type, and feeds each result back into the next LLM turn.
It stops on a final response or after `AGENT_RUNTIME_MAX_TURNS`.

## Testing draft agents

Draft agents are tested synchronously through Agent Runtime without creating runtime database
records. The root agent must be `DRAFT`; every referenced tool must be `PUBLISHED`, and every
`CUSTOM_AGENT` tool must target the captured version of a `PUBLISHED` child. Published children
inherit test mode, so nested side effects cannot escape into live execution.

```bash
curl --request POST 'http://localhost:8081/api/v1/agent-test-runs' \
  --header 'Content-Type: application/json' \
  --header 'X-Agent-License-Code: account-123' \
  --header 'X-Agent-User-Id: editor-123' \
  --data '{
    "agentId": "{draft-agent-id}",
    "task": "Review this campaign and identify launch risks",
    "input": {"campaignId": "cmp-123"},
    "mockToolResults": {
      "campaign.send": {"campaignId": "cmp-123", "status": "SIMULATED"}
    },
    "humanResponses": []
  }'
```

Only tools explicitly classified with `executionPolicy.operation: "READ"` execute their real
executor. `WRITE`, `EXTERNAL_COMMUNICATION`, `SENSITIVE_DATA`, `DESTRUCTIVE`, and unclassified
tools return a test result from `mockToolResults` or a generated `SIMULATED_SUCCESS` result. Tool
approval policies are still honored before a mocked result is returned.

When `status` is `NEEDS_INPUT`, submit a new test run with the same agent, task, input, model, and
mock results. Supply the returned `draftRevision` as `expectedDraftRevision` and append the human
response using the signed `interactionToken`:

```bash
curl --request POST 'http://localhost:8081/api/v1/agent-test-runs' \
  --header 'Content-Type: application/json' \
  --header 'X-Agent-License-Code: account-123' \
  --header 'X-Agent-User-Id: editor-123' \
  --data '{
    "agentId": "{draft-agent-id}",
    "task": "Review this campaign and identify launch risks",
    "input": {"campaignId": "cmp-123"},
    "expectedDraftRevision": "{draft-revision-from-the-previous-response}",
    "humanResponses": [{
      "interactionToken": "{interaction-token}",
      "action": "ANSWER",
      "answer": "Use Bengaluru"
    }]
  }'
```

Every submission receives a new `testRunId` and starts from turn one. Previous clarification
answers are added to the prompt, while tool approvals are matched to the exact tool ID, version,
and arguments. There is no test-run GET, polling, cancellation, scheduling, or human-response
endpoint. Configure the shared signing key with `AGENT_TEST_INTERACTION_TOKEN_SECRET` on every
runtime replica; never use the local-development default in production.

## Human-in-the-loop execution

The runtime automatically exposes a provider-neutral `request_clarification` tool to every agent.
The model supplies the question and response shape; the runtime applies the published agent's
audience, expiry, and request-limit policy. A tool whose published
`executionPolicy.approval.required` is `true` is persisted and paused before its executor is called.
This applies to every tool type, including `CUSTOM_AGENT` delegation.

The initial run remains synchronous until it either completes or needs human input. Both outcomes
return HTTP 200. A paused response has `status: "WAITING_FOR_HUMAN"` and one or more entries in
`pendingInteractions`. List the current user's actionable items:

```bash
curl 'http://localhost:8081/api/v1/human-interactions' \
  --header 'X-Agent-License-Code: account-123' \
  --header 'X-Agent-User-Id: user-123' \
  --header 'X-Agent-Roles: CAMPAIGN_APPROVER'
```

Answer one or several pending interactions through the root run. Use a one-element array for one
answer; omit other pending interactions to answer only part of the pending set:

```bash
curl --request POST 'http://localhost:8081/api/v1/agent-runs/{root-run-id}/human-responses' \
  --header 'Content-Type: application/json' \
  --header 'X-Agent-License-Code: account-123' \
  --header 'X-Agent-User-Id: user-123' \
  --header 'X-Agent-Roles: RUN_REQUESTER' \
  --header 'Idempotency-Key: root-response-batch-1' \
  --data '{
    "responses": [
      {
        "interactionId": "{interaction-id}",
        "action": "ANSWER",
        "answer": "Use the India region"
      }
    ]
  }'
```

Approve and answer together by adding another item such as
`{"interactionId":"...","action":"APPROVE","comment":"Reviewed"}`. Reject with action
`REJECT` and a required comment. A supplied batch is atomic, while interactions omitted from it
remain pending. Every accepted response is stored immutably and the batch emits one root resume
command; the request thread claims that command and resumes affected runs deepest-first. The HTTP
response is returned only after the root succeeds, fails, is cancelled, or reaches another human
interaction boundary. The response includes the authoritative `rootRunStatus`, `output`, and
pending interactions. Responses are immutable; a
correction is appended with `POST /api/v1/agent-runs/{root-run-id}/instructions`. The durable outbox
remains as crash recovery; a background worker can finish a command if the originating HTTP request
or runtime process dies after the batch commits. Client polling is not required for a normally
completed request.
Cancelling supports `{"scope":"ROOT","reason":"..."}` or `THIS_RUN` at
`POST /api/v1/agent-runs/{run-id}/cancel`.

Runtime history is stored in:

- `agent_runs`: request-level state, final output, and error
- `agent_run_turns`: the full generic-agent request and response for every LLM turn
- `agent_tool_invocations`: tool ID/version/type, arguments, result/error, status, and duration
- `agent_run_checkpoints`: provider-neutral messages and pending work used for durable resume
- `human_interaction_requests` / `human_interaction_responses`: immutable human decision history
- `human_response_batches`: root-scoped atomic batches and batch-level idempotency
- `runtime_outbox_events`: transactional resume requests with retry/backoff

`HTTP`, `MCP`, `SQL_QUERY`, `FUNCTION`, and `BUILT_IN` are valid catalog types. HTTP has a working
declarative executor. The remaining types fail explicitly until a governed `ToolExecutor` for that
type is added. Adding a tool definition of an already-supported type does not require deployment;
adding a new execution mechanism does.

Do not place credentials in tool definitions. Authentication/connection profiles and secret
references are the next required slice before using HTTP tools against authenticated production
services.

## Authentication and authorization

Local development defaults to `AGENT_SECURITY_ENABLED=false` and accepts the `X-Agent-User-Id` and
`X-Agent-Roles` test headers. Never use that mode in production. Enable JWT resource-server security
for both services and configure an issuer (or JWK set URI):

```bash
export AGENT_SECURITY_ENABLED=true
export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI='https://identity.example.com'
```

The JWT subject is the actor ID. Its `license_codes` array must include the requested
`X-Agent-License-Code`; `roles` supplies authorities, and runtime `groups` are mapped to `GROUP_*`
authorities. Management APIs require `AGENT_OWNER` or `AGENT_ADMIN`. Operator correction/cancel
requires `RUN_OPERATOR` or `AGENT_ADMIN`. Interaction visibility and resolution enforce the
requester, role, or group audience, and high-risk/destructive policies deny requester self-approval
unless an effective policy explicitly permits it.

## Service boundary

Agent Management owns the mutable agent definition table. Agent Runtime owns execution and log
tables, but has read access to the management definition table because this implementation does
not publish immutable snapshot events. The applications do not share JPA entities or Java module
dependencies; runtime maps the stored JSON into its own read model.
