# HTTP tool authentication contract

An HTTP tool can present a credential, so a tool can reach an API that requires one. Before this,
`HttpToolExecutor` sent only `User-Agent` and `Accept`, which meant no agent on the platform could
reach any third-party API needing a key.

## The invariant everything else follows from

A tool's `configuration` is returned verbatim by `GET /api/v1/tools` and rendered in the dev console,
so **configuration may reference a credential but must never contain one**. The secret lives in
`tenant_credentials`, encrypted, and no endpoint returns it.

Auth is also configuration rather than `inputSchema`. Only a tool's name, description and input
schema reach the model (`PublishedToolDefinition.toLlmDefinition`), so configuration is invisible to
it — but a credential field in the input schema would be visible, and the model would try to fill one
in.

## Credential resource

`/api/v1/credentials` on Agent Management, scoped by `X-Agent-License-Code`.

Open to any role that can author a tool — the same four roles the tool endpoints admit — because no
response carries the secret. A credential can be created, rotated, disabled and attached, but never
read back.

```json
{
  "name": "google-sheets",
  "type": "GOOGLE_SERVICE_ACCOUNT",
  "description": "Ledger spreadsheet",
  "secret": "{ ...service account JSON... }",
  "settings": {"scopes": "https://www.googleapis.com/auth/spreadsheets"}
}
```

`name` and `type` are immutable: changing either would silently re-point every tool that references
the credential, or change how the stored secret is interpreted. `PATCH` rotates the secret in place,
so tools keep working against the same name. `DELETE` is refused with `409 credential-in-use` while a
non-deleted tool still references it.

### Types and their settings

| Type | Secret | Required settings |
| --- | --- | --- |
| `API_KEY_HEADER` | the key | `headerName`; optional `valuePrefix` |
| `API_KEY_QUERY` | the key | `queryParameter` |
| `BEARER_STATIC` | the token | none |
| `BASIC` | the password | `username` |
| `OAUTH2_CLIENT_CREDENTIALS` | client secret | `tokenUrl` (https), `clientId`; optional `scopes` |
| `GOOGLE_SERVICE_ACCOUNT` | service-account JSON | `scopes` |

`valuePrefix` is used exactly as written, including trailing whitespace: a prefix of `"Token "` needs
its space, and stripping it produces `Tokenabc`, which an API rejects.

Settings are validated on create, not on first use — otherwise a credential looks correct in the
console and fails inside an unattended run hours later.

User-delegated OAuth (authorization code plus refresh token) is deliberately absent. It needs a
browser redirect and a per-user token store, and "on behalf of which user?" has no answer inside a
scheduled run. Google APIs are reachable through `GOOGLE_SERVICE_ACCOUNT` instead.

## Referencing a credential from a tool

```json
{
  "method": "GET",
  "url": "https://sheets.googleapis.com/v4/spreadsheets/{spreadsheetId}",
  "auth": {"credential": "google-sheets"}
}
```

`auth` accepts only `credential`. Any other key is rejected, as is a configuration key whose name
suggests an inlined secret (`token`, `apiKey`, `password`, an `Authorization` entry under `headers`,
and similar). That is a guardrail rather than a boundary, but it catches the common mistake before
the value becomes readable by every reader in the tenant.

Publishing a tool whose credential is missing or disabled is refused with `404 credential-not-found`.
Checked at publish, not on save, so a draft can be written before its credential exists — the same
shape as the egress and budget checks.

## Encryption at rest

AES-GCM, 12-byte IV per record, 128-bit tag, serialised as
`v1.<base64url iv>.<base64url ciphertext+tag>`. Implemented once in `agent-contracts`
(`CredentialCipher`) so Management, which writes, and Runtime, which reads, cannot disagree about the
format.

The key comes from `AGENT_CREDENTIAL_KEY` — 32 bytes, base64 — and is never stored beside the
ciphertext it protects. `key_id` is recorded on each row, so rotating the key means changing both and
leaving history readable under the old id.

Both services need the same key. Management encrypts; Runtime decrypts. Runtime starts without one,
and reports the missing setting on the first authenticated tool call rather than failing at boot, so
a deployment with no authenticated tools is unaffected.

## Execution

Runtime reads the credential over the read-only management connection it already holds, decrypts it
just in time, and caches the record for `agent-platform.credentials.cache-ttl` (30–60s). That TTL is
also the revocation delay: disabling a credential stops tool calls within one TTL.

Order of operations matters. The URL is expanded from tool arguments, the egress guard vets the host
and its resolved addresses, and **only then** is the credential attached. A secret can therefore never
be sent to a host the tenant has not approved.

For the two exchange types, the long-lived credential is swapped for a short-lived access token,
cached until shortly before expiry so a five-minute schedule does not exchange on every tick. A `401`
or `403` invalidates the cached token and retries once; a rejected static key is reported as-is,
because a retry will not improve it.

`GOOGLE_SERVICE_ACCOUNT` builds and RS256-signs the JWT assertion with the JDK rather than a JOSE
library — a fixed header, a fixed claim set and one signature — keeping the credential path free of
another dependency.

### The token URL is an SSRF surface

`tokenUrl` is tenant-supplied configuration and produces an outbound request, so it goes through the
same `HttpEgressGuard` as a tool URL: allowlist, then resolved-address checks. Without that it would
be a direct path to link-local metadata, bypassing every protection the tool URL itself receives. The
practical consequence is that a tenant using `OAUTH2_CLIENT_CREDENTIALS` must allowlist its token host
as well as its API host.

## What is not protected

**Credentials are not host-scoped, and anyone who can author a tool can manage them.** That is a
deliberate configuration choice, and it makes the **per-tenant egress allowlist the only barrier
between a credential and an arbitrary server**. The allowlist is administered by `AGENT_ADMIN` only,
so an editor cannot point a tool at an unapproved host — but a broad entry weakens this: allowlisting
`*.example.com` where an editor controls a subdomain is enough to walk a credential out.

Two mitigations are in place, neither of which prevents it:

- `tenant_credentials.allowed_hosts` exists and is **not enforced**. Populating and enforcing it later
  needs no migration.
- `credential_binding_events` records every create, update, delete and tool binding with its actor,
  so misuse is reconstructable afterwards.

**A response body that echoes a credential is persisted.** `agent_tool_invocations.result_json` stores
tool results, so an API that reflects its own auth header puts the secret in the run trace. Auth
headers themselves are never recorded — they are not tool arguments — but results are.

Tightening either of these is a policy change, not a rewrite.
