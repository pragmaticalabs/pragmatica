# Declarative HTTP Client — Design Spec

> ⚠️ **Design only — not implemented in the 1.0.0 line.** This is a design specification with no shipped implementation. It describes intended future work, not current Aether behavior — do not use it as a reference for what currently-shipping Aether does. The `Status` field below reflects design maturity, not build status.

| Field   | Value                                       |
|---------|---------------------------------------------|
| Status  | Approved — ready for implementation         |
| Date    | 2026-04-18                                  |
| Modules | `aether/slice-api`, `jbct/slice-processor`, `aether/resource/http`, `integrations/net/http-client`, `integrations/net/content-type` |

---

## 1. Overview

A compile-time-generated HTTP client that lets slice developers declare outbound API contracts as plain Java interfaces with method-level HTTP mapping annotations. The annotation processor generates implementations at compile time. Configuration (base URL, timeouts, auth) lives in `resources.toml`, not in code.

**Design principles:**
- Consistent with existing resource provisioning (`@ResourceQualifier` + `resources.toml`)
- Compile-time generation via slice-processor (no runtime reflection/proxies)
- JSON via `JsonMapper` integration (no raw Jackson exposure)
- Error handling via `Promise` failure channel with typed `Cause` (no exceptions, never `Promise<Result<T>>`)
- `HttpOperations` as the underlying resource type (implementation-agnostic)
- Built-in observability via Aspect wrapping (same as slices)

---

## 2. User-Facing API

### 2.1 Interface Declaration

The user writes a plain Java interface. Method annotations declare the HTTP contract. The interface itself carries no annotation — binding happens at the slice factory parameter site.

```java
interface PaymentApi {
    @Get("/payments/{id}")
    Promise<Payment> getPayment(String id);

    @Post("/payments")
    Promise<PaymentResult> charge(ChargeRequest request);

    @Get("/payments?status={status}&limit={limit}")
    Promise<List<Payment>> search(String status, Option<Integer> limit);

    @Post("/payments/{id}/refund")
    @Header(name = "Idempotency-Key", value = "{idempotencyKey}")
    Promise<RefundResult> refund(String id, String idempotencyKey, RefundRequest body);

    @Delete("/payments/{id}")
    Promise<Unit> cancel(String id);

    @Get("/payments/{id}")
    Promise<HttpResult<Payment>> getPaymentFull(String id);

    @Post("/reports/generate")
    @SlowOperation
    Promise<Report> generateReport(ReportRequest request);

    @Post("/events")
    @Contract
    void emit(Event event);
}
```

### 2.2 Resource Binding

The user defines a custom qualifier annotation and uses it in the slice factory:

```java
@ResourceQualifier(type = HttpOperations.class, config = "http.payments")
@interface Payments {}

@Slice
interface OrderProcessor {
    Promise<OrderResult> processOrder(OrderRequest request);

    static OrderProcessor orderProcessor(@Sql SqlConnector db,
                                         @Payments PaymentApi payments) {
        return request -> payments.charge(toChargeRequest(request))
                                  .flatMap(result -> persistOrder(db, request, result));
    }
}
```

### 2.3 Configuration

```toml
# resources.toml
[http.payments]
base_url = "https://payments.internal:8443"
path_prefix = "/api/v2"
connect_timeout = "5s"
request_timeout = "30s"
follow_redirects = "normal"
authorization = "Bearer ${env:PAYMENT_API_KEY}"
max_retries = 3
retry_backoff = "exponential"
circuit_breaker_threshold = 5
circuit_breaker_reset = "30s"

[http.payments.json]
naming = "snake_case"
null_inclusion = "exclude"
fail_on_unknown = false

[http.payments.headers]
user-agent = "OrderService/1.0"
x-trace-id = "${env:TRACE_ID}"
```

Variable expansion (`${env:...}`, `${secret:...}`, etc.) in config values is resolved at provisioning time. Two refresh classes:

| Field | Refresh policy |
|---|---|
| `authorization`, `[http.<name>.headers]` values | Re-resolved per request when the underlying provider supports refresh — supports token rotation and external secret stores without reprovisioning the client |
| `base_url`, `path_prefix`, timeouts, retry/circuit-breaker fields, `[http.<name>.json]` | Resolved once at provisioning time. Changing these requires reprovisioning |

Headers and Authorization are designated as "hot" because secret rotation is the common operational case. All other fields define the client's shape and are intentionally cold to keep request hot-paths cheap.

### 2.4 Per-Method Timeout via Resource Provisioning

Methods can declare a timeout override via a custom `@ResourceQualifier` annotation pointing at `TimeoutConfig`:

```java
@ResourceQualifier(type = TimeoutConfig.class, config = "timeout.slow-ops")
@interface SlowOperation {}
```

```toml
[timeout.slow-ops]
request_timeout = "60s"
connect_timeout = "10s"
```

Methods without a timeout annotation use the client-level default from `[http.<name>]`.

The processor records the (method → config-section) mapping in the slice manifest. At provisioning time, the SPI provisions one `TimeoutConfig` per referenced section and threads them into the generated adapter as a single `Map<MethodName, TimeoutConfig>` constructor parameter. The adapter looks up its method's `MethodName` on each invocation; absent entry → fall back to the client-level default. `MethodName` is the existing value object from `aether/slice-api`.

---

## 3. Annotations

All annotations are in `aether/slice-api` to keep slice interfaces free of runtime dependencies.

### 3.1 HTTP Method Annotations

| Annotation | HTTP Method |
|------------|-------------|
| `@Get(template)` | GET |
| `@Post(template)` | POST |
| `@Put(template)` | PUT |
| `@Delete(template)` | DELETE |
| `@Patch(template)` | PATCH |

Each takes a single `String value()` — the path template with optional query parameters.

### 3.2 Path Template

The template uses `{paramName}` placeholders for both path segments and query parameters:

```
/payments/{id}                              → path variable
/payments?status={status}&limit={limit}     → query parameters
/payments/{id}/items?page={page}            → both
```

Every `{name}` must have a matching method parameter. Compile-time error if not.

### 3.3 Parameter Binding Rules

Before classifying parameters, the processor collects all template variables from BOTH the path template (path segments + query portion) AND every `@Header(value = "{...}")` template into a single matched-names set. Each parameter is then classified by name:

1. **Path variables** — parameter name matches `{name}` in path segment portion of template
2. **Header values** — parameter name matches `{name}` in any `@Header` value template
3. **Query parameters** — parameter name matches `{name}` in query portion of template
4. **Request body** — the remaining unmatched parameter (at most one per method, or all unmatched parameters when `@Consumes` declares the `FORM` category)

If more than one parameter is unmatched and the request is not `FORM`-encoded, the processor emits a compile-time error. If a `{name}` template variable has no matching parameter, the processor emits a compile-time error.

### 3.4 Query Parameter Type Handling

| Parameter Type | Expansion |
|---------------|-----------|
| `String`, primitives | `name=value` |
| `Option<T>` | Omitted entirely when empty (both name and value) |
| `List<T>` | Repeated: `name=v1&name=v2&name=v3` |

### 3.5 Content Type Annotations

```java
@Consumes(CommonContentType.APPLICATION_JSON)            // what we send (default)
@Produces(CommonContentType.APPLICATION_JSON)             // what we expect back (default)

@Post("/oauth/token")
@Consumes(CommonContentType.APPLICATION_FORM_URLENCODED)  // form-encoded POST
Promise<TokenResponse> getToken(String grant_type, String client_id, String client_secret);
```

Both default to `APPLICATION_JSON`. Only annotate when deviating.

The `ContentCategory` drives parameter binding and serialization:

| Category | Request body | Response body |
|----------|-------------|---------------|
| `JSON` | Serialize via `JsonMapper` | Deserialize via `JsonMapper` |
| `TEXT` | Pass `String` directly | Return `String` |
| `BINARY` | Pass `byte[]` directly | Return `byte[]` |
| `XML` | Future — `XmlMapper` integration | Future |
| `FORM` | All unmatched parameters become `key=value` form fields | N/A |

When `@Consumes` specifies `FORM` category, the binding convention changes: instead of one body parameter, **all unmatched parameters** are serialized as URL-encoded form fields.

### 3.6 Content Type Infrastructure

`ContentType`, `ContentCategory`, and `CommonContentType` are moved to a new shared module `integrations/net/content-type` so both `http-server` and `slice-api` can depend on them without circular dependencies.

`ContentCategory` gains a new value: `FORM`.

### 3.7 Header Annotation

```java
@Header(name = "Idempotency-Key", value = "{key}")    // dynamic — bound to parameter
@Header(name = "Accept", value = "application/json")   // static — literal value
```

Applied at method level. Multiple `@Header` annotations via `@Headers` container.

Static headers from configuration (`[http.<name>.headers]` section and `authorization` field) are applied at the `HttpOperations` level during provisioning and do not need `@Header` annotations.

### 3.8 Error Factory Annotation

```java
@ErrorFactory(PaymentErrorFactory.class)
interface PaymentApi { ... }
```

Applied at interface level. Optional — if absent, the default error factory is used. The factory receives `JsonMapper` in its constructor for structured error body parsing.

---

## 4. Return Types

### 4.1 Body Only

```java
Promise<Payment> getPayment(String id);
```

On 2xx: deserialize response body as `Payment` → Promise success.
On non-2xx: run error factory → Promise failure with typed `Cause`.

### 4.2 Full Response

```java
Promise<HttpResult<Payment>> getPaymentFull(String id);
```

Always succeeds (any status code). The `HttpResult<T>` contains status, headers, and deserialized body. The caller inspects status and decides how to handle.

When return type is `Promise<HttpResult<T>>`, the error factory is NOT invoked — the caller takes full responsibility.

### 4.3 No Body

```java
Promise<Unit> cancel(String id);
```

On 2xx: Promise success with `Unit.unit()`. Response body is discarded.
On non-2xx: run error factory.

### 4.4 Fire-and-Forget

```java
@Contract
void emit(Event event);
```

Requires `@Contract` annotation — compile-time error without it. Sends the request, does not process completion. Errors and failures are not tracked. Documented consequence: no error factory invocation, no failure propagation. The Aspect still captures the call at entry.

### 4.5 Compile-Time Restrictions

The processor rejects with a clear error:
- `void` return without `@Contract`
- Generic methods (`<T> Promise<T> get(...)`) — use plain `HttpClient` for dynamic typing
- Interface inheritance (`interface PaymentApi extends BaseApi`) — keep interfaces flat for now; relaxing this is not a breaking change

---

## 5. Error Handling

### 5.1 HttpStatus Enum

Replaces raw `int statusCode` in `HttpResult`.

```java
public enum HttpStatus {
    // 2xx
    OK(200), CREATED(201), ACCEPTED(202), NO_CONTENT(204),
    // 3xx
    MOVED_PERMANENTLY(301), FOUND(302), NOT_MODIFIED(304),
    // 4xx
    BAD_REQUEST(400), UNAUTHORIZED(401), FORBIDDEN(403), NOT_FOUND(404),
    METHOD_NOT_ALLOWED(405), CONFLICT(409), GONE(410),
    UNPROCESSABLE_ENTITY(422), TOO_MANY_REQUESTS(429),
    // 5xx
    INTERNAL_SERVER_ERROR(500), BAD_GATEWAY(502),
    SERVICE_UNAVAILABLE(503), GATEWAY_TIMEOUT(504);

    private final int code;

    public int code() { return code; }
    public boolean isSuccess() { return code >= 200 && code < 300; }
    public boolean isClientError() { return code >= 400 && code < 500; }
    public boolean isServerError() { return code >= 500; }

    public static Option<HttpStatus> fromCode(int code) { ... }
}
```

### 5.2 ErrorResponse

Utility record passed to error factories. Pure data — no mapper reference.

```java
public record ErrorResponse(HttpStatus status, String body, Map<String, String> headers) {}
```

### 5.3 HttpErrorFactory Interface

```java
public interface HttpErrorFactory<E extends Cause> {
    Option<E> fromResponse(ErrorResponse response);
}
```

Return values:
- `Option.some(error)` → Promise failure with that error
- `Option.none()` → treat response as success despite non-2xx status

The factory receives `JsonMapper` in its constructor for parsing structured error bodies:

```java
class PaymentErrorFactory implements HttpErrorFactory<PaymentError> {
    private final JsonMapper json;

    PaymentErrorFactory(JsonMapper json) { this.json = json; }

    @Override
    public Option<PaymentError> fromResponse(ErrorResponse response) {
        return switch (response.status()) {
            case NOT_FOUND -> Option.some(new PaymentError.NotFound());
            case CONFLICT  -> json.readString(response.body(), ConflictDetail.class)
                                  .option()
                                  .map(PaymentError.Conflict::new);
            default -> Option.some(new PaymentError.Unexpected(response.status(), response.body()));
        };
    }
}
```

### 5.4 Default Error Factory

Used when no `@ErrorFactory` annotation is present:

```java
sealed interface HttpClientError extends Cause {
    record ResponseFailed(HttpStatus status, String body) implements HttpClientError {
        @Override public String message() {
            return "HTTP " + status.code() + " " + status.name() + ": " + body;
        }
    }

    record Timeout(TimeSpan limit, String detail) implements HttpClientError { ... }
    record ConnectionFailed(String detail) implements HttpClientError { ... }
    record DeserializationFailed(String detail) implements HttpClientError { ... }
    record RequestBuildFailed(String detail) implements HttpClientError { ... }
}
```

`Timeout` is a distinct case from `ConnectionFailed` because timeout is a configurable, expected condition (per-method or client-level) whose remediation is to bump `request_timeout`/`connect_timeout`, not to investigate connectivity. Carrying the `TimeSpan limit` in the cause makes the error message self-explanatory and feeds operational dashboards.

### 5.5 Usage Patterns

**Tier 1 — Default factory + inline remapping (zero boilerplate):**

```java
payments.getPayment(id)
        .mapError(cause -> switch (cause) {
            case HttpClientError.ResponseFailed(var status, _) when status == HttpStatus.NOT_FOUND
                -> new OrderError.PaymentNotFound(id);
            default -> cause;
        });
```

**Tier 2 — Custom factory (domain errors from the start):**

```java
// No mapError needed — Promise already carries PaymentError
payments.getPayment(id)
        .flatMap(payment -> processPayment(payment));
```

---

## 6. JSON Integration

### 6.1 Serialization/Deserialization

Generated code uses `JsonMapper` from `integrations/json/jackson` for all JSON operations:

- Request body serialization: `jsonMapper.writeAsString(body)` → `Result<String>`
- Response body deserialization: `jsonMapper.readString(responseBody, TypeToken)` → `Result<T>`

`JsonMapper` returns `Result<T>`, which chains naturally with `Promise` via `flatMap`.

### 6.2 JsonMapper Provisioning

The `JsonMapper` instance is created from the `[http.X.json]` config section during resource provisioning:

```toml
[http.payments.json]
naming = "snake_case"         # camel_case (default), snake_case, kebab_case
null_inclusion = "exclude"    # non_empty (default), include, exclude
fail_on_unknown = false       # false (default)
```

If no `[http.X.json]` section is present, `JsonMapper.defaultJsonMapper()` is used.

---

## 7. Observability

### 7.1 Aspect Wrapping

Every generated HTTP client adapter is wrapped with a built-in Aspect — the same observability infrastructure that slices get. The processor generates `aspect.apply(new PaymentApiHttpAdapter(...))` exactly like it does for slices.

This provides:
- Logging of method entry/exit at DEBUG level
- Metrics collection (call count, latency, error rate)
- Dynamic reconfiguration at runtime (change log level, toggle metrics)

No configuration needed. Every provisioned HTTP client interface automatically gets the Aspect.

### 7.2 Request ID Propagation

The generated adapter automatically propagates the current `InvocationContext.requestId()` as an `X-Request-Id` header on every outbound HTTP request. This is a built-in behavior, not configurable — it's always on.

This enables end-to-end request tracing: an inbound request to the Aether cluster carries a request ID through internal cluster forwarding (QUIC), through slice invocations, and out through declarative HTTP client calls to external services. External services that log the `X-Request-Id` header participate in the trace automatically.

The generated code:
```java
var requestId = InvocationContext.currentRequestId();
var builder = HttpRequest.newBuilder().uri(uri);
requestId.onPresent(id -> builder.header("X-Request-Id", id));
```

No user annotation or configuration required.

---

## 8. Configuration Reference

```toml
[http.<name>]
base_url = "https://..."              # required — base URL for all requests
path_prefix = "/api/v2"               # optional — prepended to all method paths
connect_timeout = "5s"                # optional — default 10s
request_timeout = "30s"               # optional — default 30s
follow_redirects = "normal"           # optional — normal (default), never, always
authorization = "Bearer ${env:TOKEN}" # optional — Authorization header, variable expansion
max_retries = 3                       # optional — default 0 (no retry)
retry_backoff = "exponential"         # optional — exponential, linear, fixed
circuit_breaker_threshold = 5         # optional — failures before opening circuit
circuit_breaker_reset = "30s"         # optional — time before half-open attempt

[http.<name>.json]                    # optional — JSON serialization config
naming = "snake_case"
null_inclusion = "exclude"
fail_on_unknown = false

[http.<name>.headers]                 # optional — static headers added to every request
user-agent = "OrderService/1.0"
x-trace-id = "${env:TRACE_ID}"
```

---

## 9. Code Generation

### 9.1 Annotation Processor Responsibilities

The slice-processor, upon encountering a `@ResourceQualifier(type = HttpOperations.class)` parameter with an interface type:

1. Validates the interface: all methods return `Promise<T>` (or `void` with `@Contract`), each has exactly one HTTP method annotation, all template variables have matching parameters, at most one body parameter (or all-unmatched for `FORM` category), no generic methods, no interface inheritance.
2. Generates `<InterfaceName>HttpAdapter` class implementing the interface.
3. Wraps adapter with built-in Aspect.
4. Records the dependency in the slice manifest (resource type + config section).

### 9.2 Generated Adapter Structure

```java
final class PaymentApiHttpAdapter implements PaymentApi {
    private final HttpOperations http;
    private final JsonMapper json;
    private final String baseUrl;
    private final String pathPrefix;
    private final HttpErrorFactory<?> errorFactory;

    PaymentApiHttpAdapter(HttpOperations http, JsonMapper json,
                          String baseUrl, String pathPrefix,
                          HttpErrorFactory<?> errorFactory) { ... }

    @Override
    public Promise<Payment> getPayment(String id) {
        var url = baseUrl + pathPrefix + "/payments/" + encode(id);
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create(url))
                                 .GET()
                                 .build();
        return http.sendString(request)
                   .flatMap(result -> handleResponse(result, TypeToken.typeToken(Payment.class)));
    }

    @Override
    public Promise<PaymentResult> charge(ChargeRequest request) {
        return json.writeAsString(request)
                   .map(body -> HttpRequest.newBuilder()
                                           .uri(URI.create(baseUrl + pathPrefix + "/payments"))
                                           .POST(HttpRequest.BodyPublishers.ofString(body))
                                           .header("Content-Type", "application/json")
                                           .build())
                   .async()
                   .flatMap(http::sendString)
                   .flatMap(result -> handleResponse(result, TypeToken.typeToken(PaymentResult.class)));
    }

    private <T> Promise<T> handleResponse(HttpResult<String> result, TypeToken<T> type) {
        return HttpStatus.fromCode(result.statusCode())
                         .fold(() -> connectionError(result.statusCode()),
                               status -> status.isSuccess()
                                          ? deserialize(result.body(), type)
                                          : applyErrorFactory(status, result));
    }
}
```

### 9.3 Factory Registration

The generated adapter is instantiated during resource provisioning:

1. `SpiResourceProvider` provisions `HttpOperations` from the config section (with retry/circuit-breaker baked in)
2. The generated `SliceFactory` wraps it into the adapter with `JsonMapper` and error factory
3. The adapter is wrapped with the built-in Aspect
4. The adapter is injected as the interface type into the slice

---

## 10. Testing

Since the HTTP client interface is a plain Java interface, testing is straightforward:

```java
var mockPayments = new PaymentApi() {
    @Override public Promise<Payment> getPayment(String id) {
        return Promise.success(new Payment(id, 1000, "USD"));
    }
    // ...
};

var slice = OrderProcessor.orderProcessor(mockDb, mockPayments);
```

No mocking framework needed. No generated code involved in tests. The interface IS the test seam. This is a natural consequence of JBCT's interface-based architecture.

---

## 11. Implementation Layers

### Layer A: Foundation (no processor changes)

1. Create `integrations/net/content-type` shared module with `ContentType`, `ContentCategory` (with `FORM`), `CommonContentType`.
2. Update `http-server` and `http-routing` to depend on shared module, remove duplicates.
3. Add `HttpStatus` enum to `integrations/net/http-client` — replace raw `int` in `HttpResult`.
4. Add `ErrorResponse` record to `aether/resource/api`.
5. Add `HttpErrorFactory<E>` interface to `aether/resource/api`.
6. Add default `HttpClientError` sealed interface to `aether/resource/api`.
7. Add HTTP method annotations (`@Get`, `@Post`, `@Put`, `@Delete`, `@Patch`) to `aether/slice-api`.
8. Add `@Header`, `@Headers`, `@Consumes`, `@Produces`, `@ErrorFactory` annotations to `aether/slice-api`.
9. Add `TimeoutConfig` record to `aether/slice-api` or `aether/resource/api`.

### Layer B: Processor

10. Detect `@ResourceQualifier(type = HttpOperations.class)` on interface-typed parameters.
11. Validate interface methods (return types, template variables, body parameters, restrictions).
12. Generate `<Interface>HttpAdapter` implementing the interface.
13. Generate Aspect wrapping for the adapter.
14. Generate adapter instantiation in `SliceFactory`.

### Layer C: Config + Provisioning

15. Create HTTP-specific config record for `[http.<name>]` sections (base URL, timeouts, retry, circuit-breaker).
16. Register `HttpOperations` factory in SPI that reads from the config section and applies retry/circuit-breaker.
17. Wire `JsonMapper` creation from `[http.<name>.json]` config.

### Layer D: Tests + Examples

18. Unit tests for generated adapter (mock `HttpOperations`).
19. Integration test with Forge (real HTTP calls between slices).
20. Example slice demonstrating the pattern.

---

## 12. Decisions Locked

| Decision | Value | Rationale |
|----------|-------|-----------|
| Resource type | `HttpOperations` | Implementation-agnostic, suitable for codegen |
| Binding site | Slice factory parameter | Consistent with `@Sql`, `@PgSql` |
| Interface annotation | None needed | Qualifier on parameter is sufficient |
| Method annotations | `@Get`, `@Post`, etc. | Self-documenting contract in code |
| Config location | `resources.toml` `[http.<name>]` | Consistent with all other resources |
| Path prefix | In TOML config | Environment-specific, not code |
| JSON integration | `JsonMapper` from `integrations/json/jackson` | No raw Jackson exposure |
| Error model | `Promise` failure channel | No exceptions, never `Promise<Result<T>>` |
| Error factory | Per-interface, optional, receives `JsonMapper` | Tier 1 default + Tier 2 custom |
| Query params | In path template | Consistent expansion, `Option`/`List` conventions |
| Content type | `@Consumes`/`@Produces` with `CommonContentType` enum | Type-safe, no strings |
| Content type module | `integrations/net/content-type` shared module | Avoids circular deps, not in core |
| Compile-time generation | Annotation processor | Consistent with existing slice-processor |
| Observability | Built-in Aspect wrapping | Same as slices, dynamically reconfigurable |
| Per-method timeout | `TimeoutConfig` via `@ResourceQualifier` | Consistent resource provisioning pattern |
| Retry/circuit-breaker | Config in TOML, applied at `HttpOperations` provisioning | No annotation needed, infrastructure concern |
| Void methods | Allowed with `@Contract` | Intent explicit, consequences documented |
| Interface inheritance | Rejected at compile time | Can be relaxed later without breaking change |
| Generic methods | Rejected at compile time | Use plain `HttpClient` for dynamic typing |
| `HttpResult.statusCode` | `int` → `HttpStatus` enum (big bang) | No users yet, clean break |

---

## 13. Future Enhancements

- **Request/response interceptors** — `HttpOperations` decorators for cross-cutting concerns (request signing, compression, correlation ID injection). Post-GA ticket.
- **Streaming responses** — `Promise<Stream<T>>` for SSE/chunked. Defer to streaming subsystem.
- **Multipart/form uploads** — use low-level `HttpOperations` API directly.
- **XML content type** — wire `XmlMapper` integration when `ContentCategory.XML` is used.
- **Interface inheritance** — walk supertypes if demand arises.
- **Typed error body via `@Produces` on error** — specify error response type separately from success type.

---

## 14. References

### Internal
- `aether/resource/api/.../HttpClient.java` — current imperative HTTP client
- `integrations/net/http-client/.../HttpOperations.java` — low-level transport interface
- `integrations/net/http-client/.../HttpResult.java` — response wrapper (to be updated with `HttpStatus`)
- `integrations/json/jackson/.../JsonMapper.java` — JSON serialization integration
- `integrations/net/http-server/.../ContentType.java` — content type interface (to be moved)
- `aether/slice-api/.../ResourceQualifier.java` — resource binding annotation

### External
- [Micronaut @Client](https://docs.micronaut.io/latest/api/io/micronaut/http/client/annotation/Client.html) — compile-time declarative client (closest prior art)
- [Spring @HttpExchange](https://www.baeldung.com/spring-6-http-interface) — Spring 6 declarative HTTP interface
- [OpenFeign](https://cloud.spring.io/spring-cloud-openfeign/multi/multi_spring-cloud-feign.html) — runtime proxy declarative client
