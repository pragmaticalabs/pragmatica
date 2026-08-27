# Developer Guide Corrections

Comparison of `https://pragmaticalabs.io/docs/developer-guide.html` against actual codebase (release-1.0.0-rc1).

Generated: 2026-04-05

---

## 1. Pub-Sub Messaging — Incorrect Annotation Pattern

**Website shows:**
```java
@Subscription
Promise<Unit> onOrderPlaced(OrderPlacedEvent event);
```

**Actual pattern:** There is no `@Subscription` annotation. Pub-Sub uses custom `@ResourceQualifier` annotations — one for publishing (targets PARAMETER) and one for subscribing (targets METHOD):

```java
// Publisher qualifier — custom annotation per topic
@ResourceQualifier(type = Publisher.class, config = "messaging.order-events")
@Retention(RUNTIME) @Target(PARAMETER)
public @interface OrderEvents {}

// Subscriber qualifier — custom annotation per topic
@ResourceQualifier(type = Subscriber.class, config = "messaging.order-events")
@Retention(RUNTIME) @Target(METHOD)
public @interface OnOrderEvent {}

// Usage in slice
@Slice
public interface InventoryUpdater {
    @OnOrderEvent  // NOT @Subscription
    Promise<Unit> onOrderPlaced(OrderPlacedEvent event);

    static InventoryUpdater inventoryUpdater(@Sql SqlConnector db) {
        return event -> db.update("UPDATE inventory SET reserved = reserved + ? WHERE item_id = ?",
                                   event.quantity(), event.itemId())
                          .map(_ -> Unit.unit());
    }
}
```

`Subscriber` is a marker interface (`public interface Subscriber {}`), not an annotation. The subscriber qualifier annotation targets `METHOD`, not `PARAMETER`.

**Also:** The publisher example on the website is vague. Should show the actual pattern:
```java
static OrderService orderService(@OrderEvents Publisher<OrderPlacedEvent> events) {
    return request -> processOrder(request)
                         .onSuccess(order -> events.publish(new OrderPlacedEvent(order.id())));
}
```

---

## 2. Scheduled Tasks — Incorrect Annotation Pattern

**Website shows:**
```java
@Scheduled(cron = "0 6 * * *")
Promise<Unit> generateReport();

@Scheduled(interval = "5m", mode = ExecutionMode.SINGLE)
Promise<Unit> checkAlerts();
```

**Actual pattern:** `Scheduled` is a marker interface (`public interface Scheduled {}`), not an annotation with attributes. Scheduled tasks use the same `@ResourceQualifier` pattern as everything else:

```java
@ResourceQualifier(type = Scheduled.class, config = "scheduling.daily-report")
@Retention(RUNTIME) @Target(METHOD)
public @interface DailyReport {}

@ResourceQualifier(type = Scheduled.class, config = "scheduling.alert-check")
@Retention(RUNTIME) @Target(METHOD)
public @interface AlertCheck {}

@Slice
public interface ReportService {
    @DailyReport
    Promise<Unit> generateReport();

    @AlertCheck
    Promise<Unit> checkAlerts();
}
```

Schedule parameters (cron, interval, mode) are configured in `aether.toml` or `resources.toml`:
```toml
[scheduling.daily-report]
cron = "0 6 * * *"
mode = "SINGLE"

[scheduling.alert-check]
interval = "5m"
mode = "SINGLE"
```

The `@Scheduled(cron = "...")` syntax shown on the website does not exist.

---

## 3. SqlConnector API — Missing RowMapper Parameter

**Website shows:**
```java
db.query("SELECT id, name, email FROM users WHERE id = ?", request.userId())
    .map(UserRepository::toUser);
```

**Actual API:** `SqlConnector` has no `query()` method. The actual methods require a `RowMapper<T>`:
```java
db.queryOne(sql, mapper, params...)           // Promise<T>
db.queryOptional(sql, mapper, params...)      // Promise<Option<T>>
db.queryList(sql, mapper, params...)          // Promise<List<T>>
db.update(sql, params...)                     // Promise<Integer>
db.batch(sql, paramsList)                     // Promise<int[]>
db.transactional(callback)                    // Promise<T>
```

The website example should be:
```java
static UserRepository userRepository(@Sql SqlConnector db) {
    return request -> db.queryOptional(
        "SELECT id, name, email FROM users WHERE id = ?",
        USER_MAPPER,
        request.userId());
}
```

Also applies to the OrderAnalytics example — `orders.query(...)` and `analytics.query(...)` don't exist. Should use `queryOne`, `queryList`, or `queryOptional` with a `RowMapper`.

---

## 4. HttpClient API — Wrong Method Signature

**Website shows:**
```java
http.post("/charges", request)
    .map(PaymentResult::fromResponse);
```

**Actual API:** `HttpClient.post()` takes `String path, String body` and returns `Promise<HttpResult<String>>`:
```java
Promise<HttpResult<String>> post(String path, String body);
Promise<HttpResult<String>> post(String path, String body, Map<String, String> headers);
```

The request object needs to be serialized to JSON first, and the response is a string, not a typed object. Correct example:
```java
static PaymentGateway paymentGateway(@Http HttpClient http) {
    return request -> http.post("/charges", JsonMapper.serialize(request))
                          .map(result -> result.body())
                          .map(PaymentResult::fromJson);
}
```

**Also:** The website mentions sealed error types `SerializationFailed`, `DeserializationFailed`, `RequestFailed`, `RequestFailedWithBody`. Need to verify these exist in `HttpClientError` — could not find a sealed `HttpClientError` in the resource-api module. May be in a different location or may not exist.

---

## 5. HttpClient Configuration — Incorrect Field Names

**Website shows:**
```toml
[http.payment-gateway]
base_url = "https://api.payments.example.com/v1"
connect_timeout = "5s"
request_timeout = "15s"

[http.payment-gateway.default_headers]
Authorization = "Bearer ${secrets:payment/api-key}"
```

**To verify:** The `connect_timeout`, `request_timeout`, and `default_headers` sub-section format needs verification against actual `HttpClientConfig` or equivalent. The `@Http` annotation maps to config section `"http"`, so a custom qualifier would be needed for `"http.payment-gateway"`.

Also: the example uses `@Http` but then configures `[http.payment-gateway]`. The built-in `@Http` maps to `"http"` (no sub-section). For `"http.payment-gateway"`, a custom qualifier is needed:
```java
@ResourceQualifier(type = HttpClient.class, config = "http.payment-gateway")
@Retention(RUNTIME) @Target(PARAMETER)
@interface PaymentHttp {}
```

---

## 6. Configuration Section — Partially Correct but Details Wrong

**Website shows:**
```java
@ResourceQualifier(type = ConfigurationSection.class, config = "payment.gateway")
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.METHOD})
public @interface PaymentGateway {}
```

This is largely correct — `ConfigurationSection` exists as an interface in `slice-api/annotation/`. The pattern of targeting both PARAMETER (for injection) and METHOD (for update notifications) appears correct.

**However:** The config priority table shows:
```
1 (highest): KV-Store
2: aether.toml [app.*]
3 (lowest): META-INF/config.toml
```

The `META-INF/config.toml` path needs verification — no such file was found in any example project. The actual config merge is: `resources.toml` (blueprint, GLOBAL scope) → `aether.toml` (NODE scope) → KV-Store (runtime overrides). The `[app.*]` prefix in `aether.toml` also needs verification.

---

## 7. Configuration Update Method — Wrong Return Type

**Website shows:**
```java
@PaymentGateway
Result<Unit> onConfigUpdate(GatewayConfig newConfig);
```

**Should verify:** Config update methods should return `Promise<Unit>` (all slice methods return Promise), not `Result<Unit>`. The generated `notifyConfigUpdate` in FactoryClassGenerator calls these methods — need to check the expected return type.

---

## 8. Interceptors — Missing Code Example

**Website only shows TOML config:**
```toml
[interceptors.retry]
max_attempts = 3
backoff = "exponential"
initial_delay = "100ms"
```

**Missing:** How interceptors are actually applied to slice methods. Interceptors use `@ResourceQualifier` method-level annotations. The website should show:
```java
@ResourceQualifier(type = MethodInterceptor.class, config = "interceptors.retry")
@Retention(RUNTIME) @Target(METHOD)
@interface WithRetry {}

@Slice
public interface PaymentService {
    @WithRetry
    Promise<PaymentResult> processPayment(PaymentRequest request);
}
```

The TOML config section names also need verification against actual interceptor implementation.

---

## 9. Missing Sections

The developer guide is missing several important topics that are implemented:

### Streaming (StreamPublisher, StreamSubscriber, StreamAccess)
No mention of the streaming API. Should include:
- `StreamPublisher<T>` for publishing to partitioned streams
- `StreamSubscriber` for push-based consumption
- `StreamAccess<T>` for pull-based consumption
- `@PartitionKey` annotation for ordering
- Consumer groups, dead-letter, retention policies

### PostgreSQL LISTEN/NOTIFY
No mention of `PgNotificationSubscriber` pattern:
```java
@ResourceQualifier(type = PgNotificationSubscriber.class, config = "pg-notifications.order-changes")
@Retention(RUNTIME) @Target(METHOD)
@interface OnOrderChange {}
```

### Inter-Slice Invocation Details
Brief mention but no explanation of how dependencies are proxied. Should explain:
- Non-annotated interface parameters = slice dependencies
- Annotation processor generates `MethodHandle`-based proxy records
- Calls route local-first, remote with retry and failover

### Schema Migration Directory Convention
Mentioned but not detailed. Should show:
```
schema/                    → [database] (default)
schema/analytics/          → [database.analytics]
```

### routes.toml
Referenced but never shown. Should include a complete example:
```toml
prefix = "/api/v1/orders"

[routes]
placeOrder = "POST /"
getOrder = "GET /{id}"

[errors]
default = 500
HTTP_404 = ["*NotFound*"]
HTTP_400 = ["*Invalid*"]
```

### Error Modeling
No section on sealed `Cause` hierarchies and how they map to HTTP status codes via `routes.toml [errors]`.

### Forge / Local Development
No mention of Forge, `run-forge.sh`, `start-postgres.sh`, or k6 load testing.

### Step Composition (New in rc1)
No mention of the Slice → Step → Leaf pattern with transitive resource provisioning and transitive method-level annotations.

---

## 10. Spring Comparison Table — Minor Issues

**Website shows:**

| Spring Pattern | Aether Equivalent |
|---|---|
| `@RestController` | `routes.toml` |

This is correct but incomplete. Should also mention:
- `@RequestMapping` → route entries in `[routes]` section
- `@ExceptionHandler` → `[errors]` section in routes.toml with glob patterns
- `@Configuration` / `@Bean` → not needed (annotation processor generates factories)
- `@Transactional` → `db.transactional(callback)`

---

## 11. Feature Count Discrepancy

Website mentions "172 capabilities" in the feature catalog reference. Repo has 173.

---

## Summary of Actions

| Priority | Issue | Action |
|----------|-------|--------|
| **Critical** | Pub-Sub annotation pattern wrong | Replace `@Subscription` with `@ResourceQualifier(type = Subscriber.class)` pattern |
| **Critical** | Scheduled annotation pattern wrong | Replace `@Scheduled(cron=...)` with `@ResourceQualifier(type = Scheduled.class)` pattern |
| **Critical** | SqlConnector API wrong | Add `RowMapper` parameter, use correct method names |
| **Critical** | HttpClient API wrong | Fix to `post(path, body)` returning `Promise<HttpResult<String>>` |
| **High** | Missing sections | Add: Streaming, PG LISTEN/NOTIFY, routes.toml, error modeling, Forge, step composition |
| **High** | HttpClient config uses wrong qualifier | Show custom qualifier for non-default config section |
| **Medium** | Config priority table | Verify `META-INF/config.toml` vs `resources.toml`, verify `[app.*]` prefix |
| **Medium** | Config update return type | Verify `Result<Unit>` vs `Promise<Unit>` |
| **Medium** | Interceptor code example missing | Add `@ResourceQualifier(type = MethodInterceptor.class)` pattern |
| **Low** | Feature count | Update "172" → "173" |
