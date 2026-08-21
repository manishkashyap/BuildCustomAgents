# Project Objective

Converyn is a platform for creating, publishing, executing, testing, and governing purpose-built custom agents.

The primary user value is to let customers define agents declaratively—without writing or deploying Java code—and have those agents:

- Perform a task using an underlying LLM.
- Dynamically choose from explicitly allowed tools.
- Retrieve required data through those tools.
- Delegate work to other published custom agents.
- Pause for human clarification or approval.
- Preserve a request-level execution history.
- Run interactively through APIs.
- Eventually run on schedules.
- Integrate with generic MCP servers and import selected MCP tools or prompts.

An agent definition follows this conceptual authoring flow:

1. Role and responsibilities.
2. Task at hand, supplied when the agent runs.
3. Instructions and rules.
4. Expected output and optional output schema.
5. Examples for few-shot prompting.
6. Allowed tools.

Agent definitions deliberately do not contain an `inputSchema`. The LLM receives the runtime task and input, decides what data is required, and selects from the allowed tools.

The following prompt sections map naturally to the interface:

| Prompt section | Agent field |
|---|---|
| `ROLE` | `role` |
| Runtime input data | Run request `task` and `input` |
| Guidelines | `instructions` and `rules` |
| Expected JSON/output | `outputFormat` and `outputSchema` |
| Examples | `examples` |
| Available capabilities | `allowedTools` |

The project began as a WebEngage-specific platform but was intentionally generalized. Source packages now use `com.manish.customagents`, API headers use `X-Agent-*`, and the product is referred to as Converyn. The repository directory still has the historical name `webengage-custom-agents-playbook`, and generated clarification artifacts still contain old branding.

# Tech Stack & Architecture

## Repository

Actual repository:

```text
/Users/manish/gitspace/webengage-custom-agents-playbook
```

Git state at hand-off:

```text
Branch: main
HEAD: cb0fd3a
Commit: feat: Framework to build purpose-built custom agents with generic MCP connections and tool discovery
Remote: git@github.com:manishkashyap/BuildCustomAgents.git
Remote main is up to date with HEAD.
```

Untracked files currently exist:

```text
.dockerignore
.env.example
.gitignore
```

These should be reviewed and committed separately if appropriate.

## Technology

- Java 21
- Spring Boot 3.5.16
- Maven multi-module build
- Spring MVC
- Spring Validation
- Spring Data JPA
- Spring Security
- OAuth2 Resource Server/JWT support
- MySQL 8.4
- Flyway migrations
- Spring `RestClient`
- springdoc OpenAPI/Swagger
- Dockerfiles and Docker Compose
- JUnit/Spring Boot tests

## Monorepo and deployment model

One repository contains two independently deployable Spring Boot applications:

```text
custom-agents-platform/
├── agent-management/
├── agent-runtime/
├── deploy/mysql/init/
├── compose.yaml
└── pom.xml
```

They form one product and support one-command local deployment, but run as separate JVM processes and production deployments.

### Agent Management

- Port: `8080`
- Database: `custom_agent_management`
- Responsibilities:
  - Agent CRUD
  - Agent draft editing
  - Agent copy/publish/retire lifecycle
  - Tool definition CRUD
  - Published dependency validation
  - Agent audit events
  - Retirement coordination with runtime
  - Future MCP connection and capability management

### Agent Runtime

- Port: `8081`
- Database: `custom_agent_runtime`
- Has read access to the management database for published definitions.
- Responsibilities:
  - Agent execution loop
  - LLM provider selection
  - Tool execution
  - Child-agent delegation
  - Conversation/turn logging
  - HITL checkpoints and continuation
  - Draft-agent tests
  - Runtime retirement eligibility
  - Future MCP execution

### Local deployment

```bash
docker compose up --build
```

MySQL currently maps host port `3306`, so local startup fails if another MySQL instance already owns that port. The host port can be changed or removed from Compose when external host access is unnecessary.

## Database separation

Management and runtime use separate databases because they have different ownership, scaling, retention, and failure characteristics.

Runtime currently reads published agent/tool definitions directly from the management database. There is no immutable publish-snapshot event pipeline.

## LLM abstraction

The provider-neutral generation facade is `BaseAgent`.

```text
BaseAgent
  → LlmClientRegistry
      → GeminiLlmClient
      → OpenAiLlmClient
```

Core contracts:

```java
public interface LlmClient {
    ModelProvider provider();
    boolean supports(String model);
    BaseAgentResponse generate(BaseAgentRequest request);
}
```

The registry is constructed by Spring from all enabled `LlmClient` beans.

Provider and model selection are dynamic per run:

- Both `provider` and `model` may be supplied.
- Both must be omitted together to use defaults.
- Default configuration is currently Gemini.
- Provider implementations are conditionally enabled through environment variables.

Current defaults:

```text
Provider: GOOGLE_GEMINI
Model: gemini-3.6-flash
Maximum turns: 8
Maximum child-agent depth: 3
Maximum child-agent invocations: 10
```

## Tool execution

All tool types route through a single registry:

```text
ToolExecutorRegistry
    ├── BuiltInToolExecutor
    ├── HttpToolExecutor
    ├── CustomAgentToolExecutor
    └── future McpToolExecutor
```

Contract:

```java
public interface ToolExecutor {
    ToolType type();
    ToolExecutionResult execute(ToolExecutionRequest request);
}
```

No custom-agent-specific switch exists outside the registry.

Supported definition types:

```text
HTTP
MCP
SQL_QUERY
FUNCTION
BUILT_IN
CUSTOM_AGENT
```

Only these currently have runtime executors:

```text
HTTP
BUILT_IN
CUSTOM_AGENT
```

`MCP`, `SQL_QUERY`, and `FUNCTION` are definition types but have no runtime executor yet.

HTTP tools enforce a configured host allowlist:

```text
AGENT_HTTP_TOOL_ALLOWED_HOSTS
```

A host not in the allowlist produces:

```text
HTTP tool host is not allowlisted: <host>
```

## Security

Security is disabled by default for local development:

```text
AGENT_SECURITY_ENABLED=false
```

When enabled:

- APIs use JWT authentication.
- JWT `roles` become Spring authorities.
- Runtime also maps JWT `groups` to `GROUP_<name>`.
- Tenant context is carried through `X-Agent-License-Code`.
- Actor identity is carried through `X-Agent-User-Id`.

Management roles include:

```text
AGENT_EDITOR
AGENT_PUBLISHER
AGENT_ADMIN
PLATFORM_ADMIN
```

Runtime roles include concepts such as:

```text
RUN_REQUESTER
RUN_OPERATOR
CAMPAIGN_APPROVER
```

Agent controllers perform explicit role and tenant checks. Tool controllers are covered by the global management security chain when security is enabled, but do not yet have the same detailed controller-level actor/audit handling as agent APIs.

Internal management-to-runtime retirement checks use an internal token.

## Reliability patterns

- Published definitions are read dynamically from the database.
- Runtime conversations and tool calls are persisted.
- HITL uses checkpoints and an outbox.
- One root-level continuation command is emitted per human-response batch.
- A short-lived `RETIRING` agent state reduces retirement race conditions.
- No `SELECT ... FOR UPDATE` or management optimistic locking was requested.
- Parallel branch execution remains disabled until scheduling and failure semantics are explicitly designed.

# Data Model & Schema

## Custom agent definition

Current management request:

```json
{
  "name": "Opportunity Research Agent",
  "description": "Researches an opportunity and produces a briefing.",
  "role": "You are a sales research specialist.",
  "instructions": "Review the available account and opportunity information.",
  "rules": [
    "Use only supplied tools.",
    "Do not invent customer facts."
  ],
  "outputFormat": "Return a concise JSON briefing.",
  "outputSchema": {
    "type": "object",
    "properties": {
      "summary": {
        "type": "string"
      }
    }
  },
  "context": {
    "businessUnit": "Enterprise Sales"
  },
  "examples": [
    {
      "description": "Example account briefing",
      "input": {
        "account": "Example Corp"
      },
      "expectedOutput": {
        "summary": "Example summary"
      }
    }
  ],
  "allowedTools": [
    "crm_get_opportunity",
    "crm_search_contacts"
  ],
  "humanInteractionPolicy": {
    "clarification": {
      "expiresAfterSeconds": 86400,
      "maxRequestsPerRootRun": 20,
      "onExpire": "FAIL_CHILD",
      "defaultAudience": {
        "type": "RUN_REQUESTER",
        "values": []
      }
    }
  }
}
```

There is no agent `inputSchema`.

## Agent lifecycle

```text
DRAFT → PUBLISHED → RETIRING → RETIRED
```

Rules:

- Customers may edit all supported fields while the agent is `DRAFT`.
- Published agents are immutable.
- Published agents can only be retired.
- Published or retired agents can be copied into a new draft.
- The copy does not retain `sourceAgentId`.
- The source remains unchanged and active.
- Copy requests require an `Idempotency-Key`.
- Draft edits do not increment version.
- Current code creates every new/copy draft with version `1`; no lineage-based version sequence exists.

Agent definition persistence:

```text
custom_agents
- id CHAR(36)
- license_code
- name
- normalized_name
- description
- definition_json LONGTEXT
- status
- version
- deleted
- created_at / updated_at
- created_by / updated_by
- change_reason
```

Supporting tables:

```text
agent_copy_requests
agent_audit_events
```

## Tool definition

Tool definitions still require an `inputSchema` because the LLM needs the invocation contract:

```json
{
  "name": "crm_search_contacts",
  "description": "Search CRM contacts.",
  "type": "HTTP",
  "inputSchema": {
    "type": "object",
    "properties": {
      "query": {
        "type": "string"
      }
    },
    "required": ["query"]
  },
  "configuration": {
    "method": "GET",
    "url": "https://crm.example.com/contacts?q={query}"
  },
  "executionPolicy": {
    "operation": "READ",
    "riskLevel": "LOW",
    "approval": {
      "required": false
    }
  }
}
```

Tool lifecycle:

```text
DRAFT → PUBLISHED
              ↓
           DISABLED
```

Tool policy operations:

```text
READ
WRITE
EXTERNAL_COMMUNICATION
SENSITIVE_DATA
DESTRUCTIVE
```

Risk levels:

```text
LOW
MEDIUM
HIGH
DESTRUCTIVE
```

Tool approval configuration supports:

```json
{
  "required": true,
  "allowSelfApproval": false,
  "expiresAfterSeconds": 86400,
  "onReject": "RETURN_TO_AGENT",
  "onExpire": "REJECT",
  "audience": {
    "type": "ROLE",
    "values": ["CAMPAIGN_APPROVER"]
  }
}
```

`TOOL_APPROVAL` is determined from the tool definition, not the agent.

## Custom agent as a tool

A published custom agent can be exposed using an explicit `CUSTOM_AGENT` tool definition:

```json
{
  "name": "research_opportunity",
  "description": "Delegates opportunity research to the research agent.",
  "type": "CUSTOM_AGENT",
  "inputSchema": {
    "type": "object",
    "properties": {
      "task": {
        "type": "string"
      },
      "input": {
        "type": "object"
      }
    },
    "required": ["task", "input"]
  },
  "configuration": {
    "agentId": "child-agent-id",
    "agentVersion": 1,
    "settings": {
      "failurePolicy": "RETURN_TO_PARENT"
    }
  },
  "executionPolicy": {
    "operation": "READ",
    "riskLevel": "MEDIUM"
  }
}
```

Agreed design decisions:

- Agent tools are explicit `custom_tools` records.
- Publishing an agent does not automatically create its tool.
- `allowedTools` contains both normal and agent tools.
- Runtime determines behavior from tool type.
- Normal and agent tools share a tenant-wide name namespace.
- The tool pins the target agent version.
- Parent-level invocation settings may override child defaults.
- Different parent agents may use different failure policies.
- Same-tenant and `PLATFORM_SHARED` visibility rules were accepted conceptually.
- Agent tool invocation uses the fixed `task` plus `input` contract.
- Creator-provided tool names are allowed; the UX should default to the agent name.

The current API still requires the tool creator to explicitly provide the tool name; automatic defaulting has not been implemented.

## Agent run request

```json
{
  "agentId": "agent-id",
  "task": "Prepare a briefing for this opportunity.",
  "input": {
    "opportunityId": "006..."
  },
  "provider": "GOOGLE_GEMINI",
  "model": "gemini-3.6-flash"
}
```

Provider and model can both be omitted to use defaults.

Run statuses:

```text
PENDING
RUNNING
WAITING_FOR_HUMAN
WAITING_FOR_CHILD
PAUSED
SUCCEEDED
FAILED
CANCELLED
EXPIRED
```

Runtime persistence includes:

```text
agent_runs
agent_run_turns
agent_tool_invocations
agent_run_checkpoints
agent_run_steps
agent_run_events
runtime_outbox_events
```

The run stores:

- Root and parent relationships
- Agent ID and version
- Provider/model
- Input and output
- Requester
- Status and wait reason
- Timestamps
- Errors
- Turn-level LLM requests/responses
- Tool-level arguments/results/status/duration
- Child-run references
- Approval-policy snapshots

## HITL model

Interaction types:

```text
CLARIFICATION
TOOL_APPROVAL
```

Interaction audiences:

```text
CALLER_AGENT
RUN_REQUESTER
ROLE
GROUP
```

Runtime-controlled audience resolution uses the run requester as default.

Responses are submitted at root-run level:

```http
POST /api/v1/agent-runs/{rootRunId}/human-responses
```

Request:

```json
{
  "responses": [
    {
      "interactionId": "interaction-1",
      "action": "ANSWER",
      "answer": "Use Bengaluru"
    },
    {
      "interactionId": "interaction-2",
      "action": "APPROVE",
      "comment": "Approved for this campaign"
    }
  ]
}
```

Contract decisions:

- Root response contract is an array from the beginning.
- A caller may answer only part of the root’s pending set.
- Each submitted batch is atomic.
- Each individual response is persisted immutably.
- Corrections require a new interaction/event rather than mutation.
- The batch is idempotent using `Idempotency-Key`.
- One continuation command is persisted per batch.
- Affected child runs resume deepest-first.
- The request remains synchronous until the root reaches its next stable state.
- `rootRunStatus` is the authoritative state.
- `resumeStatus` was removed from DTOs, services, tests and API documentation.
- Internal outbox states remain internal.

Response:

```json
{
  "batchId": "batch-id",
  "rootRunId": "root-run-id",
  "rootRunStatus": "WAITING_FOR_HUMAN",
  "acceptedResponses": [],
  "pendingInteractionCount": 1,
  "pendingInteractions": [],
  "acceptedAt": "2026-08-20T10:00:00Z",
  "output": null
}
```

The API returns `200` when:

- The run completes, or
- Execution reaches a stable non-complete state requiring human clarification/approval.

## Draft-agent testing

Endpoint:

```http
POST /api/v1/agent-test-runs
```

Behavior:

- Only `DRAFT` agents can be tested.
- Test runtime state is not persisted.
- Execution uses the same orchestration and tool path as published runs.
- Only published normal tools are available.
- Draft tools are not allowed.
- Draft child-agent tools are not allowed.
- Side-effecting tools are mocked.
- Read tools may execute for real.
- HITL returns pending interactions and signed interaction tokens.
- Providing human answers creates a new test run.
- `expectedDraftRevision` prevents answers being applied after the draft changed.
- Conversation, mocked calls, pending interactions and output are returned directly.

## Generic MCP model drafted but not implemented

The proposed generic layer separates an MCP connection from imported tools:

```text
MCP Connection
├── Credential bindings
├── Discovery runs
├── Logical capabilities
├── Immutable capability revisions
└── Imported Converyn tools
```

Proposed non-deployable Maven library:

```text
mcp-client-core/
├── client/
├── transport/
├── protocol/
└── error/
```

Proposed tables:

```text
mcp_connections
mcp_credential_bindings
mcp_discovery_runs
mcp_capabilities
mcp_capability_revisions
```

Proposed connection attributes include:

```text
provider_key
transport_type
endpoint_url
authentication_type
credential_scope
authentication_config
protocol_version_policy
status
health_status
```

Suggested initial transport:

```text
STREAMABLE_HTTP
```

Suggested credential scopes:

```text
TENANT
USER
```

Suggested generic authentication types:

```text
NONE
BEARER_TOKEN
API_KEY
STATIC_HEADERS
OAUTH2_AUTHORIZATION_CODE
OAUTH2_CLIENT_CREDENTIALS
```

Secrets should be stored only through vault references.

A discovered MCP tool would be imported into the existing `custom_tools` table:

```json
{
  "type": "MCP",
  "configuration": {
    "connectionId": "connection-id",
    "capabilityId": "capability-id",
    "capabilityRevisionId": "revision-id",
    "remoteToolName": "search_contacts",
    "definitionHash": "sha256:..."
  }
}
```

Remote schema changes must create new capability revisions and must never silently mutate published Converyn tools.

# Implementation Progress

## Implemented

### Management

- Create, list and get custom agents.
- JSON merge-style partial update for draft agents.
- Published/retired agent copying.
- Idempotent copy requests.
- Publish lifecycle.
- Retirement lifecycle with temporary `RETIRING`.
- Recovery of stale retirement attempts.
- Audit events for agent lifecycle changes.
- Published dependency validation.
- Tool create/list/get/update/publish/disable APIs.
- Agent `inputSchema` removed from API, storage and prompt construction.
- Flyway migrations through management `V5`.

### Runtime

- Published agent execution by querying management tables.
- Generic, provider-neutral agent loop.
- Gemini LLM client.
- OpenAI LLM client.
- Dynamic per-run provider/model choice with Gemini default.
- Turn-level conversation logging.
- Tool invocation logging.
- HTTP tool execution with host allowlisting.
- Built-in tool execution.
- Custom-agent delegation through `ToolExecutorRegistry`.
- Parent/root/child run relationships.
- Recursion and child invocation limits.
- Human clarification.
- Tool approval.
- Runtime checkpoints.
- Immutable human-response records.
- Atomic multi-response root batches.
- Idempotent root response submission.
- Deepest-first continuation.
- Synchronous-until-stable-state behavior.
- Draft-agent test execution without persistence.
- Write-tool mocking in tests.
- Draft revision protection for test HITL.
- Runtime retirement eligibility checks.
- Flyway migrations through runtime `V5`.

### Agent retirement rules

Retirement is blocked when the agent has active runtime work in:

```text
PENDING
RUNNING
WAITING_FOR_HUMAN
WAITING_FOR_CHILD
PAUSED
```

Retirement is also blocked when:

1. A published `CUSTOM_AGENT` tool targets the agent.
2. Another published agent includes that tool in `allowedTools`.

If a retirement check fails, the agent returns from `RETIRING` to `PUBLISHED`.

### Test coverage present

Tests exist for:

- Agent management controller/service
- Tool management controller/service
- LLM registry and provider JSON mapping
- Generic agent contract
- Agent execution
- HTTP/built-in/custom-agent executors
- Tool registry draft mocking
- HITL services and controllers
- Root response batching
- Outbox storage
- Draft-agent tests
- Draft interaction tokens
- Retirement eligibility

Tests were not rerun during this hand-off inspection, so do not assume the current untracked-file state has been verified by a fresh build.

## Designed but not implemented

- Scheduled agent execution.
- Generic MCP connection CRUD.
- MCP credential bindings.
- MCP initialization and discovery.
- MCP capability revision storage.
- MCP tool import workflow.
- `McpToolExecutor`.
- MCP prompts.
- MCP resources.
- Secret-vault integration.
- OAuth authorization callbacks.
- Salesforce provider adapter.
- Salesforce per-user OAuth/PKCE.
- Automatic custom-agent tool-name defaulting.
- Automatic disabling/unavailability state propagation from retired child agents to standalone published tool records.
- Parallel branch execution.
- Production observability, rate limiting, cost limits and circuit breakers.
- Kubernetes manifests.
- Fully neutral renaming of the repository directory and generated clarification artifacts.

## Important Salesforce research conclusion

Salesforce should be added after the generic MCP layer.

The later Salesforce adapter will need:

- External Client App configuration.
- Authorization Code with PKCE.
- `mcp_api` and `refresh_token` scopes.
- Per-user credential bindings.
- Production/sandbox URL derivation.
- Salesforce identity validation.
- Read-only `platform/sobject-reads` as the recommended first server.
- Prompts kept user-initiated and separate from `allowedTools`.

Real Salesforce response samples are not required to start the generic MCP implementation. A fake MCP server can validate `initialize`, paginated `tools/list`, `tools/call`, errors and schema drift.

# Current Blocker / Next Task

There is no longer a Git push blocker: `origin/main` points to commit `cb0fd3a`.

The immediate next engineering task is to implement the generic MCP foundation before any Salesforce-specific code.

Recommended first vertical slice:

1. Add `mcp-client-core` to the parent Maven modules.
2. Define provider-neutral MCP JSON-RPC records and interfaces:
   - `McpClient`
   - `McpTransport`
   - `StreamableHttpMcpTransport`
   - `initialize`
   - `tools/list`
   - `tools/call`
3. Add management Flyway migration `V6` for:
   - `mcp_connections`
   - `mcp_credential_bindings`
   - `mcp_discovery_runs`
   - `mcp_capabilities`
   - `mcp_capability_revisions`
4. Implement management connection CRUD and activation.
5. Implement synchronous connection testing and tool discovery.
6. Implement selected-tool import into draft `custom_tools`.
7. Add a fake Streamable HTTP MCP server for contract tests.
8. Add runtime `McpToolExecutor` through the existing `ToolExecutorRegistry`.
9. Keep Salesforce authentication and provider rules out of this first slice.

The following proposed MCP defaults should be confirmed at the beginning of the next chat:

- Streamable HTTP only for Phase 1.
- Store capability kinds `TOOL`, `PROMPT`, and `RESOURCE`, but initially implement only tools.
- Initially support `NONE`, `BEARER_TOKEN`, `API_KEY`, and `STATIC_HEADERS`.
- MCP tools can only be created through discovery/import.
- Published tools never update silently after remote schema drift.
- Only draft MCP connections can change endpoint/transport/authentication type.
- Block connection deletion while published tools reference it.
- Keep STDIO out of scope until sandboxed process execution is separately designed.