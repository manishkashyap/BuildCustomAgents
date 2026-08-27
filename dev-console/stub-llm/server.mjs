/**
 * Stub Gemini provider for local testing.
 *
 * agent-runtime's Gemini adapter takes its base URL from GEMINI_BASE_URL, so pointing it here lets
 * the whole platform - multi-turn generation, tool dispatch, and human-in-the-loop - be exercised
 * deterministically with no external API key and no spend.
 *
 * It is a test double, not an emulator: it implements exactly the slice of
 * POST /v1beta/models/{model}:generateContent that the adapter reads.
 *
 * What it returns is driven by the conversation it is given, plus directives you type into the
 * task text of a run:
 *
 *   [[clarify]]  ask the human a question first, via the built-in request_clarification tool
 *   [[notools]]  answer immediately without calling any tool
 *
 * Default behaviour: call the first non-control tool that is declared, then summarize its result.
 */
import { createServer } from 'node:http'

const PORT = Number(process.env.STUB_LLM_PORT ?? 4010)
const CONTROL_TOOL = 'request_clarification'

function collectText(body) {
  const parts = []
  const system = body?.systemInstruction?.parts ?? []
  for (const part of system) if (typeof part?.text === 'string') parts.push(part.text)
  for (const content of body?.contents ?? []) {
    for (const part of content?.parts ?? []) {
      if (typeof part?.text === 'string') parts.push(part.text)
    }
  }
  return parts.join('\n')
}

/** Names of tools the runtime declared for this turn, control tool separated out. */
function declaredTools(body) {
  const names = []
  for (const tool of body?.tools ?? []) {
    for (const fn of tool?.functionDeclarations ?? []) {
      if (typeof fn?.name === 'string') names.push(fn.name)
    }
  }
  return {
    all: names,
    control: names.includes(CONTROL_TOOL),
    business: names.filter((n) => n !== CONTROL_TOOL),
  }
}

/** Every tool result already present in the conversation, as {name, response}. */
function toolResults(body) {
  const results = []
  for (const content of body?.contents ?? []) {
    for (const part of content?.parts ?? []) {
      if (part?.functionResponse?.name) {
        results.push({ name: part.functionResponse.name, response: part.functionResponse.response })
      }
    }
  }
  return results
}

/** First argument object satisfying a declared tool: enough to look plausible in the trace. */
function argumentsFor(body, toolName) {
  for (const tool of body?.tools ?? []) {
    for (const fn of tool?.functionDeclarations ?? []) {
      if (fn?.name !== toolName) continue
      const props = fn?.parameters?.properties ?? {}
      const required = fn?.parameters?.required ?? Object.keys(props)
      const args = {}
      for (const key of required) {
        const type = props[key]?.type
        args[key] =
          type === 'number' || type === 'integer' ? 1
          : type === 'boolean' ? true
          : type === 'array' ? []
          : type === 'object' ? {}
          : `stub-${key}`
      }
      return args
    }
  }
  return {}
}

function usage(promptChars, outputChars) {
  // Roughly four characters per token - enough for the console's usage display to be meaningful.
  const promptTokens = Math.max(1, Math.ceil(promptChars / 4))
  const outputTokens = Math.max(1, Math.ceil(outputChars / 4))
  return {
    promptTokenCount: promptTokens,
    candidatesTokenCount: outputTokens,
    totalTokenCount: promptTokens + outputTokens,
  }
}

function respond(model, parts, finishReason, promptChars) {
  const outputChars = parts.reduce((n, p) => n + (p.text?.length ?? 40), 0)
  return {
    responseId: `stub-${Date.now().toString(36)}-${Math.floor(Math.random() * 1e6).toString(36)}`,
    modelVersion: model,
    candidates: [{ content: { role: 'model', parts }, finishReason }],
    usageMetadata: usage(promptChars, outputChars),
  }
}

function plan(body, model) {
  const text = collectText(body)
  const tools = declaredTools(body)
  const results = toolResults(body)
  const answered = results.some((r) => r.name === CONTROL_TOOL)
  const promptChars = text.length

  // 1. Human boundary first, when asked for and still unanswered.
  if (text.includes('[[clarify]]') && tools.control && !answered) {
    return respond(model, [{
      functionCall: {
        name: CONTROL_TOOL,
        args: {
          category: 'MISSING_TASK_INPUT',
          question: 'Which campaign id should I review?',
          reason: 'The task did not name a campaign, and guessing one could report on the wrong send.',
          responseType: 'FREE_TEXT',
          audienceHint: 'RUN_REQUESTER',
        },
      },
    }], 'STOP', promptChars)
  }

  // 2. Call one business tool, unless suppressed or one has already run.
  const pending = tools.business.filter((name) => !results.some((r) => r.name === name))
  if (!text.includes('[[notools]]') && pending.length > 0) {
    const name = pending[0]
    return respond(model, [{
      functionCall: { name, args: argumentsFor(body, name) },
    }], 'STOP', promptChars)
  }

  // 3. Summarize whatever the conversation now contains.
  const lines = ['Stub model response.']
  if (answered) {
    const answer = results.find((r) => r.name === CONTROL_TOOL)?.response
    lines.push(`The human answered the clarification: ${JSON.stringify(answer)}.`)
  }
  if (results.some((r) => r.name !== CONTROL_TOOL)) {
    for (const result of results.filter((r) => r.name !== CONTROL_TOOL)) {
      lines.push(`Tool ${result.name} returned ${JSON.stringify(result.response)}.`)
    }
  } else {
    lines.push('No tool was called for this task.')
  }
  lines.push('Verdict: READY (this text is generated by the stub, not a real model).')
  return respond(model, [{ text: lines.join('\n\n') }], 'STOP', promptChars)
}

const server = createServer((req, res) => {
  const match = req.url?.match(/^\/v1beta\/models\/([^:]+):generateContent/)
  if (req.method !== 'POST' || !match) {
    res.writeHead(404, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ error: { message: `stub-llm has no route for ${req.method} ${req.url}` } }))
    return
  }

  let raw = ''
  req.on('data', (chunk) => { raw += chunk })
  req.on('end', () => {
    let body
    try {
      body = JSON.parse(raw || '{}')
    } catch {
      res.writeHead(400, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ error: { message: 'stub-llm could not parse the request body' } }))
      return
    }
    const model = decodeURIComponent(match[1])
    const answer = plan(body, model)
    const summary = answer.candidates[0].content.parts
      .map((p) => (p.functionCall ? `call ${p.functionCall.name}` : 'text'))
      .join(', ')
    console.log(
      `[stub-llm] ${model} · ${body?.contents?.length ?? 0} contents · ` +
      `${declaredTools(body).all.length} tools → ${summary} ` +
      `(${answer.usageMetadata.totalTokenCount} tokens)`,
    )
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(answer))
  })
})

server.listen(PORT, () => {
  console.log(`[stub-llm] listening on http://localhost:${PORT}`)
  console.log('[stub-llm] point agent-runtime at it with GEMINI_BASE_URL=http://host.docker.internal:' + PORT)
})
