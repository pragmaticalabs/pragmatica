# Aether AI Integration — Design Spec

| Field   | Value                                                                                 |
|---------|---------------------------------------------------------------------------------------|
| Status  | Draft — design in progress                                                            |
| Date    | 2026-06-07                                                                            |
| Modules | `integrations/ai/*`, `aether/slice-api`, `jbct/slice-processor`, `aether/resource/*`, `integrations/net/http-client`, `integrations/json/jackson` |

---

## 1. Overview

An AI/LLM integration that gives slice developers the **declarative ergonomics** of Spring AI /
langchain4j, implemented the Aether way: **compile-time code generation**, no runtime
reflection/proxies, JBCT-native API (`Promise`/`Result`/`Option`, sealed + record, typed `Cause`,
never `Promise<Result<T>>`). Spring AI and langchain4j are used **only as feature-set references**, not
as dependencies and not as API templates.

**This module is the third instance of one established pattern.** It maps slot-for-slot onto the two
shipped declarative-adapter specs:

| Concern | HTTP client | PG "Aether Store" | **AI (this module)** |
|---|---|---|---|
| Low-level resource (native, `Promise`-returning) | `HttpOperations` | `PgSqlConnector` | **`ChatModel` / `EmbeddingModel`** |
| Resource qualifier → config | `@Payments` → `[http.payments]` | `@PgSql` → `[database]`+`schema/` | **`@Ai` → `[ai.<name>]`** |
| High-level declarative interface | `PaymentApi` (`@Get`/`@Post`) | `OrderPersistence` (`@Query`/derived) | **`Assistant` (`@System`/`@User`)** |
| Param binding, no annotation | `{id}` → `id` | `:email` → `email` | **`{topic}` → `topic`** |
| Output coercion | `JsonMapper` | schema row-mapper | **`JsonMapper` (structured output)** |
| Generated artifact | `…HttpAdapter` + Aspect | `…Factory` + Aspect | **`…AiAdapter` + Aspect** |
| Test seam | plain interface | plain interface | **plain interface** |

**Why compile-time wins over langchain4j's `AiServices`:** langchain4j binds at runtime via dynamic
proxy + reflection, which forces parameter annotations (`@V`, `@UserMessage`, `@P`, `@MemoryId`) because
parameter names are unreliable at runtime and templates can't be validated until invoked. The
slice-processor reads real parameter names off `VariableElement` at compile time (independent of the
`-parameters` flag, which only affects *runtime* reflection), so prompt template variables bind to
parameters **by name with zero annotations**, and a missing variable is a **compile error** — exactly
as SQL `:email`→`email` and HTTP `{id}`→`id` already do.

**Design principles** (inherited from the declarative-HTTP spec):
- Compile-time generation via slice-processor (no runtime reflection/proxies).
- JSON via `JsonMapper` integration (no raw Jackson exposure).
- Errors via `Promise` failure channel with typed `Cause` (no exceptions, never `Promise<Result<T>>`).
- Provider-specific wire format lives in the low-level client; the generated adapter is
  provider-agnostic.
- Configuration (model, API key, temperature, timeouts, retry/circuit-breaker) lives in
  `resources.toml`, reusing the `[http.<name>]` machinery.
- Built-in observability via Aspect wrapping (same as slices).

---

## 2. Two Tiers

### 2.1 Low-level — native provider client (resource type)

`ChatModel` / `EmbeddingModel` are the AI analog of `HttpOperations` / `PgSqlConnector`: native,
async, `Promise`-returning clients over `HttpOperations` + `JsonMapper`, one per provider
(`ai-openai`, `ai-anthropic`, `ai-ollama`), each registered as a `ResourceFactory`. Precedent:
`integrations/db/postgres-async` (native async, not a wrapper) and `integrations/net/email-http`
(REST-over-`HttpOperations`).

```java
interface ChatModel {
    Promise<ChatResponse> exchange(Conversation conversation, List<ToolSchema> tools);
    Promise<TokenStream>  stream(Conversation conversation, List<ToolSchema> tools);  // see §4
}

sealed interface ChatResponse {
    record Text(String content, Usage usage, FinishReason finish) implements ChatResponse {}
    record ToolCalls(List<ToolCall> calls, Usage usage)           implements ChatResponse {}
    @SuppressWarnings("unused") record unused()                   implements ChatResponse {}
}

record Completion(String text, Usage usage, FinishReason finish) {}
record Usage(int promptTokens, int completionTokens) {}
record ToolCall(String id, String name, String argumentsJson) {}
record ToolResult(String id, String contentJson) {}
```

The provider client formats `tools` into the provider's function-calling wire shape and parses SSE for
`stream`. None of that leaks into the generated adapter.

> **Open:** an optional `ai-langchain4j` adapter implementing the same `ChatModel` SPI by delegating to
> langchain4j provider modules, for breadth without native churn. langchain4j types stay quarantined
> behind the adapter. Deferred — native OpenAI/Anthropic/Ollama first.

### 2.2 High-level — `@Ai` declarative interface

A plain interface; method annotations declare the prompt; binding happens at the slice factory
parameter site (same as `@PgSql`/`@Payments`). The `@Ai` qualifier resolves a `ChatModel` from
`[ai.<name>]`.

```java
@Ai   // @ResourceQualifier(type = ChatModel.class, config = "ai.support")
interface SupportAssistant {
    @System("You are a support agent for {product}.")
    @User("Customer asks: {question}")
    Promise<String> answer(String product, String question);          // buffered text

    @User("Summarize in 3 bullets:\n{text}")
    Promise<Summary> summarize(String text);                          // structured output → JsonMapper

    @User("Classify sentiment: {text}")
    Promise<Sentiment> classify(String text);                        // Sentiment = generated enum/sealed
}
```

`@System`/`@User` are **semantic-role** markers (not binding markers) — justified exactly as `@Get`/
`@Post` are: they carry information not derivable from the signature. Template variables bind to
parameters by name; an unmatched `{var}` or an unused parameter is a compile error.

### 2.3 Return-type selects mode

Mirrors pg-persistence's cardinality table — the return type, not a flag, selects behavior:

| Declared return type | Mode |
|---|---|
| `Promise<String>` | buffered text |
| `Promise<T>` (record / sealed) | structured output via `JsonMapper` |
| `Promise<Option<T>>` | structured, nullable |
| `Promise<Completion>` + `@Streaming` | streamed (terminal aggregate; deltas via `@OnToken`, see §4) |

Compile-time rejections: streaming combined with structured output (v1); generic methods; interface
inheritance (same restrictions as the HTTP spec).

---

## 3. Tools — a tool *is* a slice

A tool is an ordinary slice method marked `@Tool`. No annotated-POJO registry (Spring AI), no reflective
proxy (langchain4j) — the unit is the slice you already have: typed, `Result`-returning, manifest-
bearing, Aspect-wrapped, and **location-transparent**.

```java
@Slice
interface WeatherService {
    @Tool("Current weather for a city")
    Promise<Weather> currentWeather(String city);

    @Tool("N-day forecast for a city")
    Promise<Forecast> forecast(String city, int days);
}

@Ai(tools = { WeatherService.class, OrderLookup.class })
interface TravelConcierge {
    @System("You are a travel concierge for {region}.")
    @User("{request}")
    Promise<TripPlan> plan(String region, String request);
}
```

**Compile-time generation:**
- **Tool schema** per `@Tool` method: name = method name, description = annotation text (fallback:
  javadoc via `Elements.getDocComment`), parameter JSON-schema from real parameter names + types
  (enums → enum schema, records → object schema). Return type → serialized back to the model via
  `JsonMapper`. No `@P`/`@V`.
- **Exhaustive dispatch** over the compile-time toolset — no reflection; a hallucinated tool name is a
  typed `Cause`, not a runtime surprise:

```java
// generated tool-call loop inside the adapter
Promise<X> runWithTools(Conversation convo, int budget) {
    if (budget == 0) return ToolError.BUDGET_EXHAUSTED.promise();
    return model.exchange(convo, TOOL_SCHEMAS).flatMap(resp -> switch (resp) {
        case ChatResponse.Text t       -> coerce(t.content(), RETURN_TYPE);       // JsonMapper → Promise
        case ChatResponse.ToolCalls tc -> dispatch(tc.calls())                    // Promise<List<ToolResult>>, parallel
                                            .flatMap(rs -> runWithTools(convo.append(resp).append(rs), budget - 1));
        case ChatResponse.unused u     -> ToolError.UNREACHABLE.promise();
    });
}
// dispatchOne: generated exhaustive switch on call.name() → JsonMapper-deserialized args → direct slice call
```

**Differentiators (Aether-only):**
1. **Distributed tool execution, free.** A tool slice may be deployed on another node; slice invocation
   is location-transparent (cluster transport), so the model on node A transparently calls a tool on
   node B — co-located when the scheduler places them together, remote hop otherwise. Spring AI /
   langchain4j tools are in-process POJOs.
2. **`@Tool` is a security gate, not decoration.** Exposing a method to an LLM means the model can
   *invoke* it. Explicit per-method opt-in (never "all methods") is the authorization boundary.

**Guardrails** in `[ai.<name>]` (reuse http-client knobs): `max_tool_iterations` (loop budget), per-tool
`TimeoutConfig` via `@ResourceQualifier`, tool-error policy (feed error back to model vs fail `Promise`),
parallel dispatch when the model requests several calls (`Promise.all`).

> **Status:** design agreed; detailed schema mapping + wire-format-per-provider to be expanded.

---

## 4. Streaming & Token Delivery

**Decision: token delivery reuses the runtime's universal inbound-event idiom.** Every inbound source
in the runtime — pub/sub (`Subscriber`), scheduler (`Scheduled`), streams — is the same shape: a
method-level `@ResourceQualifier(type = <marker>, config = <section>)` on a `Promise<Unit>` handler that
receives **one item**; the processor records a manifest entry and the runtime dispatches each item to
the method. Tokens are items. **No new core primitive** (the earlier `Source<T>` proposal is dropped),
no new consumption idiom: the runtime is the delivery layer and already owns buffering, consumer-paced
backpressure, threading, Aspect-wrapping, lifecycle, and error flow.

### 4.1 Shape

```java
@Ai   // @ResourceQualifier(type = ChatModel.class, config = "ai.assistant")
interface Assistant {
    @User("Explain {topic}")
    @Streaming
    Promise<Completion> explain(ConversationId conversation, String topic);   // terminal aggregate

    @OnToken                                                                  // @ResourceQualifier(type = TokenSink.class, config = "ai.assistant")
    Promise<Unit> onToken(ConversationId conversation, TokenDelta delta);     // each delta, runtime-dispatched
}

record TokenDelta(String text, Option<FinishReason> finish) {}
```

- **Increments** arrive at `@OnToken`, one delta per dispatch — exactly like `@OnOrderEvent`.
- **Terminal aggregate** is the `@Streaming` method's `Promise<Completion>` — it carries the **full
  assembled text + usage + finish reason** when the stream ends. "Give me the complete response" needs
  no buffering utility; it is the return value.

### 4.2 Mandatory `ConversationId` (compile-enforced)

Slices are multithreaded; `@Streaming` methods may be called concurrently. Interleaving deltas from
multiple in-flight completions into an un-keyed handler corrupts output. Rather than introduce a
runtime failure mode (queue → silent memory/latency; reject → runtime-only error), the unsafe case is
**made unrepresentable**:

- An `@OnToken` handler **MUST** declare a `ConversationId` parameter — **compile error** otherwise.
- A `@Streaming` `@Ai` method **MUST** carry a `ConversationId` — symmetry checked at compile time
  (same shape as "every `{var}` needs a matching parameter"). Cross-slice (gateway) producers supply it
  as the stream partition key, checked at wiring.
- **Enforcement is scoped to streaming handlers and their `@Streaming` producers only.** A buffered
  `Promise<String>` / structured `Promise<T>` method needs no `ConversationId` — no interleaving is
  possible.

`ConversationId` is a framework value type in `slice-api`. It is the **same correlation/session key that
conversation memory will use** (langchain4j's `@MemoryId`) — not a streaming-only concept. A stateless
one-shot stream mints an ephemeral instance.

> This honors the "no boolean params — typed/named variants" rule: the *typed parameter*, not a flag,
> carries the contract, and the contract is mandatory rather than implicit.

### 4.3 Delivery semantics

`ConversationId` is the **partition key**: delivery is **ordered within a `ConversationId`, concurrent
across `ConversationId`s** — verbatim the Aether stream partition model (ordered within partition,
parallel across). No new delivery machinery. Consequences fall out for free:

- **Error scoping:** a failing `onToken` (`Promise` failure) cancels only that conversation's
  completion.
- **Backpressure scoping:** a slow consumer for conversation A does not stall B.
- **Ordering guarantee:** deltas within one `ConversationId` are delivered in order, single-flight per
  id.

### 4.4 Cross-slice / SSE-gateway variant

No new mechanism: point deltas at a named stream and consume it with the **standard** `@Subscriber`
(partition key = `ConversationId`). Heavier transport (replay, fan-out, durability) only when wanted;
the `@OnToken` same-slice path stays lightweight.

### 4.5 `ConversationRouter` utility

Enforcing the id means the app receives interleaved `(ConversationId, TokenDelta)` callbacks across N
conversations, each typically destined for its own client connection (SSE/WebSocket). One framework
utility removes that boilerplate. **Per-conversation memory risk relocates here** (out of the runtime,
into visible, app-controlled code) — so the utility is **bounded by default**:

- `cid → registered sink`; routes each delta to the sink for its conversation.
- Bounded: explicit per-conversation cap + eviction (on-completion / TTL) — mirrors `RetentionPolicy`
  (count + age). An unbounded accumulator would reintroduce the failure we designed out.
- Per-conversation backpressure.
- JBCT-idiomatic: `Result`/`Option` returns, no exceptions; plain, independently testable app-side
  class — **not** runtime machinery (the runtime's job ends at ordered per-id delivery).

**Ship one utility (the router) first.** "Accumulate full text" is already free via the terminal
`Promise<Completion>`; additional policy variants are deferred until real use asks (named-variant
classes, not boolean flags, when they come).

### 4.6 Compile-time visibility

To keep the typed-parameter contract from being a refactor footgun, the processor records the binding in
the slice manifest and emits an info diagnostic, e.g. `[AI] onToken: partitioned by ConversationId`, so
delivery behavior is answerable at build time.

---

## 5. Open Decisions

| # | Decision | Options | Lean |
|---|---|---|---|
| 1 | v1 provider scope | OpenAI only / OpenAI + Anthropic | **+Anthropic** — two wire formats validate the portability SPI |
| 2 | v1 build order | completions → structured → tools → streaming / tools-first | completions+structured first (low risk), then tools, then streaming |
| 3 | Structured-output repair | strict-fail (`Cause`) / feed-parse-error-back retry | **strict** default, repair opt-in later |
| 4 | `ConversationId` naming | `ConversationId` / `SessionId` / `CorrelationId` | `ConversationId` (chat-facing; doubles as memory key) |
| 5 | Token marker type name | `TokenSink` / `TokenConsumer` | `TokenSink` (consistent with `Subscriber`/`Scheduled` markers) |
| 6 | `ai-langchain4j` breadth adapter | ship / defer | defer until native provider coverage is insufficient |

---

## 6. References

### Internal
- `aether/docs/specs/pg-persistence-spec.md` — "Aether Store" declarative persistence adapter (pattern source)
- `aether/docs/specs/declarative-http-client-spec.md` — declarative HTTP client (pattern source; config/JSON/observability reuse)
- `aether/docs/specs/in-memory-streams-spec.md` — stream partition / consumer-paced delivery model (token delivery semantics)
- `aether/slice-api/.../Subscriber.java`, `Scheduled.java` — inbound-event marker resource types (precedent for `TokenSink`)
- `examples/step-composition/.../OrderEventListener.java` — `@OnOrderEvent` handler precedent (`Promise<Unit> handler(Item)`)
- `integrations/db/postgres-async/`, `integrations/net/email-http/` — native async client precedent
- `integrations/net/http-client/.../HttpOperations.java`, `integrations/json/jackson/.../JsonMapper.java`

### External (feature-set references only — not dependencies)
- Spring AI — ChatClient/ChatModel split, Advisor chain, VectorStore SPI (patterns only)
- langchain4j — `ChatLanguageModel`, `AiServices`, tool calling, RAG (feature checklist; sample apps → conformance suite)
</content>
</invoke>
