# Generic Agent SPI

`GenericAgent` is the provider-neutral boundary between custom-agent orchestration and an LLM provider.

The orchestration layer supplies:

- A provider and model identifier
- System, user, assistant, and tool-result messages
- Managed file references on user messages
- Approved tool definitions expressed as JSON Schema
- Provider-neutral generation options

An adapter returns assistant text, standardized tool calls, a finish reason, and token usage. The custom-agent orchestration layer executes tool calls and appends the assistant/tool-result messages before invoking the generic agent again.

## Adding a provider

Create one Spring bean for each configured provider/model set:

```java
@Component
final class OpenAiGenericAgent extends AbstractGenericAgent {
    private final OpenAiClient client;

    OpenAiGenericAgent(OpenAiClient client) {
        super(ModelProvider.OPENAI, List.of("configured-model-name"));
        this.client = client;
    }

    @Override
    protected GenericAgentResponse doGenerate(GenericAgentRequest request) {
        // Map the common request to the provider SDK, call it, and map the response back.
        return client.generate(request);
    }
}
```

Equivalent adapters can be registered for `GOOGLE_GEMINI`, `ANTHROPIC_CLAUDE`, and `OPEN_SOURCE`. Provider SDK types must remain inside their adapter packages.

Use `GenericAgentRegistry.generate(request)` from application services. The registry selects exactly one adapter that supports the requested provider and model and rejects missing or ambiguous configurations.

## OpenAI and Gemini adapters

Both adapters are disabled by default. Enable either provider through environment variables:

```bash
export OPENAI_ENABLED=true
export OPENAI_API_KEY='...'
export OPENAI_MODELS='gpt-5.6-sol'

export GEMINI_ENABLED=true
export GEMINI_API_KEY='...'
export GEMINI_MODELS='gemini-3.6-flash'
```

`OPENAI_MODELS` and `GEMINI_MODELS` accept comma-separated model identifiers. A run can select any
provider and model supported by an enabled adapter:

```java
new ModelSelection(ModelProvider.OPENAI, "gpt-5.6-sol");
new ModelSelection(ModelProvider.GOOGLE_GEMINI, "gemini-3.6-flash");
```

The run API accepts `provider` and `model` as an optional pair. When omitted, the runtime uses the
configured fallback, which defaults to `GOOGLE_GEMINI` and `gemini-3.6-flash`. Selection is dynamic
per request; credentials, endpoints, and the allowed model lists remain runtime-controlled.

The OpenAI adapter uses `POST /v1/chat/completions`; the Gemini adapter uses `POST /v1beta/models/{model}:generateContent`. API keys are sent in request headers and are never placed in request metadata or provider exceptions.

Gemini function-call thought signatures are stored in `ToolCall.providerMetadata` and replayed on the next model invocation. Tool executors must preserve the returned `ToolCall` when appending its assistant message to conversation history.

OpenAI attachment references are intentionally rejected until the platform defines whether a reference represents an OpenAI file ID, a platform-managed URL, or inline content. Gemini accepts managed file URIs when the attachment includes a MIME type.
