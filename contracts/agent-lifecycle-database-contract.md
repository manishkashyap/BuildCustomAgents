# Agent lifecycle database additions

Management migration `V5__add_agent_edit_copy_retirement_audit.sql`:

- extends `custom_agents.status` with internal `RETIRING`;
- adds `created_by`, `updated_by`, and the latest `change_reason`;
- adds immutable `agent_audit_events` for create, update, copy, publish, retire, and recovery events;
- adds `agent_copy_requests` for tenant-scoped copy idempotency.

Copies do not store `sourceAgentId`. The idempotency row stores only the request hash and resulting
agent ID. Draft definitions continue to live in `custom_agents.definition_json`; draft edits do not
create versions or increment `custom_agents.version`.

Runtime ownership remains unchanged. Management does not query `agent_runs`; it calls Runtime's
internal retirement-eligibility API. Runtime groups non-terminal runs by status using tenant and
agent ID.
