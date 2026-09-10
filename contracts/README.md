# Service contracts

This directory owns language-neutral contracts exchanged between Agent Management and Agent Runtime.

- [`hitl-api-contract.md`](hitl-api-contract.md): management policy, runtime HTTP, control-tool,
  Problem Detail, event, and resume contracts for human-in-the-loop execution.
- [`hitl-database-contract.md`](hitl-database-contract.md): database ownership, definition JSON,
  runtime schema, transaction, idempotency, and retention contracts.
- [`tool-authentication-contract.md`](tool-authentication-contract.md): credentials an HTTP tool can
  present, how they are stored and applied, and what the model is deliberately never shown.
- [`tool-egress-contract.md`](tool-egress-contract.md): per-tenant HTTP egress allowlist, the
  publish-time and execution-time gates, and the platform network policy no tenant can widen.
- [`draft-agent-test-api-contract.md`](draft-agent-test-api-contract.md): transient draft execution,
  published dependency, tool mocking, stateless HITL, and non-persistence contracts.
- [`../docs/hitl-implementation-plan.md`](../docs/hitl-implementation-plan.md): architecture,
  delivery phases, compatibility rules, and acceptance scenarios.

Neither Spring Boot service may depend on the other service as a Java or Maven dependency. Each
service owns its Java models; the contracts in this directory remain language-neutral.
