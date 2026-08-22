# Weather report tool

Two `HTTP` tools and one agent, built on [Open-Meteo](https://open-meteo.com) — free, no API key,
no signup, JSON responses. Chosen deliberately: `HttpToolExecutor` parses every response as
`JsonNode` and sets no request headers beyond `User-Agent`, so a keyless JSON API is the only kind
that works without changing the executor.

| File | What it is |
|---|---|
| `tool-weather-geocode.json` | `weather_geocode_place` — place name → coordinates |
| `tool-weather-forecast.json` | `weather_get_forecast` — coordinates → current + daily forecast |
| `agent-weather-reporter.json` | `Weather Reporter` — chains both and returns a schema-checked report |
| `seed.sh` | Creates and publishes the tools, creates the agent as a draft |

## Required before anything works

The allowlist is **empty by default** (`application.yml` line 57 binds
`AGENT_HTTP_TOOL_ALLOWED_HOSTS` with no fallback), so every HTTP tool fails until you set it:

```bash
AGENT_HTTP_TOOL_ALLOWED_HOSTS=api.open-meteo.com,geocoding-api.open-meteo.com
```

Add it to your `.env`, then restart the runtime. Without it you get
`HTTP tool host is not allowlisted: api.open-meteo.com`.

## Seed it

```bash
./examples/weather-agent/seed.sh
```

The agent is left as a `DRAFT` so you can exercise it through `POST /api/v1/agent-test-runs`
first. Pass `--publish` to publish it too.

## Why two tools instead of one

Open-Meteo's forecast endpoint takes coordinates, not place names. Handing the model a single
tool would force it to recall coordinates from memory — the exact failure the platform exists to
prevent. Splitting the work means the model must resolve a real coordinate before it can ask for
weather, and the agent's rules forbid guessing.

It also makes a good validation case: two `READ` tools, both in `allowedTools`, both expected to
appear in the draft-test trace.

## Executor constraints these definitions work around

Three behaviours of `HttpToolExecutor` shaped the design.

**Every URL placeholder must be a required input.** The executor calls
`UriComponentsBuilder.buildAndExpand(variables)`, which throws when a `{placeholder}` has no
matching argument — surfacing as `Unable to expand HTTP tool URL template`. That is why
`forecast_days` is in `required` even though it reads like an optional parameter. If you add an
optional query parameter, either make it required too or bake it into the template.

**Arguments not named in the template are dropped.** They are not appended as query parameters, so
an input that does not appear as a `{placeholder}` has no effect. Both schemas set
`additionalProperties: false` to make that explicit to the model.

**Field lists are baked into the URL, not parameterized.** `current=` and `daily=` are fixed, so
the response shape is stable and the model cannot ask for fields the agent's output schema does
not cover. Encoding is not the reason — a percent-encoded comma works fine against Open-Meteo —
the reason is a predictable contract.

## Behaviour worth knowing

**A failed geocode has no `results` key at all.** Open-Meteo returns `{"generationtime_ms": 0.53}`
for an unmatched name, not an empty array. Both the tool description and the agent's rules call
this out, because a model that treats a missing key as "no weather available" will invent a report.

**Ambiguous names are common.** `weather_geocode_place` returns up to five candidates. The agent
prefers one matching a country or region the user mentioned, and otherwise calls
`request_clarification` rather than guessing between countries.

**`request_clarification` is not in `allowedTools`.** The runtime injects it into the tool list on
every run (`AgentExecutionService.clarificationTool()`). Listing it would break publishing, because
dependency validation looks for a published `custom_tools` row of that name and finds none.

## Verified against the live API

Template expansion was checked against the same `UriComponentsBuilder` the executor uses:

```
{place}="Bengaluru"   -> ?name=Bengaluru                       OK
{place}="New York"    -> ?name=New%20York                      OK
{place}="A&B Town"    -> ?name=A%26B%20Town                    OK   (ampersand encoded, not a separator)
all three forecast args                                        OK   (literal commas preserved)
extra argument not in the template                             OK   (silently dropped)
forecast_days omitted -> IllegalArgumentException: Map has no value for 'forecast_days'
```

The last line is why every placeholder is a required input. The ampersand case is worth noting for
a different reason: a place name cannot inject extra query parameters, because expansion encodes
the value before the URI is built.

Then the expanded URLs were called for real:

```
GET geocoding-api.open-meteo.com/v1/search?name=New%20York&count=5&...      200
GET api.open-meteo.com/v1/forecast?latitude=12.97194&longitude=77.59369&... 200
GET .../search?name=zzzznotaplace&...                                      200   (no results key)
```

Open-Meteo is free for non-commercial use and asks for no key. Rate limits are generous but real;
check their terms before using this for anything beyond local validation.

## Trying it

In Agent Studio, **Draft test**, paste the agent id, then:

- task `Give me a three day weather report for Bengaluru.`, input `{}` — the happy path. Expect two
  tool calls in the trace and a passing output-schema check.
- task `What is the weather in Springfield?` — ambiguous, expect a `request_clarification`
  interaction and status `NEEDS_INPUT`.
- task `Weather for Zzzznotaplace please.` — expect clarification, not an invented report.
- Load the definition first so the allowlist and output-schema checks actually run.

Both tools are `READ`, so a draft test calls the real API rather than mocking it. That is what you
want here, and the draft-test checklist flags it under "Tools that executed for real".
