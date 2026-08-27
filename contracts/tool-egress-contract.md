# HTTP tool egress contract

Which hosts an HTTP tool may reach is answered by two independent gates. Keeping them separate is
the whole design: one is tenant data and must be self-service, the other is platform policy and must
not be.

| Gate | Owner | Where | Configurable by a tenant |
| --- | --- | --- | --- |
| Is this host one the tenant registered? | Management (`tenant_egress_hosts`) | publish time and execution time | yes |
| Does the host resolve somewhere no tool may reach? | Runtime | execution time | no |

A single process-wide `AGENT_HTTP_TOOL_ALLOWED_HOSTS` could not express the first gate on a
multi-tenant platform, and making it tenant-writable would have deleted the second: a tenant could
register a host they control that resolves to `169.254.169.254` and read cloud instance credentials.

## Allowlist resource

`/api/v1/egress-hosts` on Agent Management, scoped by `X-Agent-License-Code`.

- `GET` requires `AGENT_EDITOR`, `AGENT_PUBLISHER`, `AGENT_ADMIN`, or `PLATFORM_ADMIN`.
- `POST`, `PATCH`, and `DELETE` require `AGENT_ADMIN` or `PLATFORM_ADMIN`. Authoring a tool and
  approving its destination are deliberately different privileges.

```json
{
  "hostPattern": "api.example.com",
  "description": "Campaign API"
}
```

A pattern is an exact host or a `*.suffix` wildcard. A wildcard must cover at least two labels, so
`*.com` is rejected, and it never grants its own apex: `*.example.com` matches `api.example.com` but
not `example.com`. Patterns are normalized to lower case with any trailing dot removed, and are
unique per tenant. A pattern is immutable once created — editing one in place would silently
re-point every tool relying on it; disable it and register a new one.

Status is `ACTIVE` or `DISABLED`. Only `ACTIVE` rows grant anything.

## Publish-time gate

`PATCH /api/v1/tools/{toolId}/status` to `PUBLISHED` rejects an `HTTP` tool whose host is not covered
by an active pattern, with `422` and type `tool-host-not-allowed`. The offending host is reported in
a `host` property. Drafts are never checked, so a tool can be written before its host is approved.

A URL whose **host portion contains a template variable** (`https://{host}/v1/x`) is rejected by the
same gate. The destination would otherwise be selected by model-supplied arguments at run time, which
would leave the allowlist as the only thing between a prompt-injected argument and an arbitrary
request. Variables in the path and query are unaffected.

## Execution-time gate

Runtime re-checks on every call rather than trusting publish-time validation, because the URL is
expanded with model-supplied arguments before it is called, a host can be revoked after publish, and
the two services are separately deployable.

Runtime reads the allowlist from the management database over its existing read-only connection and
caches it per tenant for `agents.tools.http.allowlist-cache-ttl` (default 30s). That TTL is also the
revocation delay.

After the host is matched, the host is resolved and every resulting address is checked. Loopback,
unspecified, link-local, site-local, multicast, carrier-grade NAT, and `0.0.0.0/8` are refused, and
the failure names the range rather than the address so internal topology is not confirmed to whoever
controls the tool definition. `agents.tools.http.allow-private-networks` lifts this for local
development only.

Redirects are never followed: a `302` to an internal address would be issued by the HTTP client and
would never re-enter the guard.

This is a check, not a pin. A name that resolves differently between the check and the connection
could still slip through (DNS rebinding); pinning the connection to the resolved address is the
natural next hardening step.

## Platform-wide allowance

`agents.tools.http.allowed-hosts` (`AGENT_HTTP_TOOL_ALLOWED_HOSTS`) still exists and now means
"allowed for every tenant". It exists so local examples can reach their APIs without registering a
tenant, and should be empty in a deployed environment. It grants nothing past the address checks.

## Migration

`V7__create_tenant_egress_hosts.sql` creates the table and backfills one `ACTIVE` row per distinct
host already in use by a tenant's published HTTP tools, so enforcement does not strand definitions
that were published while the global property was the only gate. Templated hosts are skipped, since
they are refused going forward.
