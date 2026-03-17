# Resource Reference

## How Resource Provisioning Works

Slices access infrastructure — databases, HTTP clients, caches — through **resource qualifiers**. You annotate factory method parameters, and the annotation processor generates the provisioning code. You never create connections or clients manually.

### Built-In Qualifiers

Aether ships three built-in qualifiers:

| Annotation | Resource Type | Config Section |
|------------|--------------|----------------|
| `@Sql` | `SqlConnector` | `"database"` |
| `@Http` | `HttpClient` | `"http"` |
| `@Notify` | `NotificationSender` | `"notification"` |

```java
import org.pragmatica.aether.resource.db.Sql;
import org.pragmatica.aether.resource.db.SqlConnector;

@Slice
public interface UserRepository {

    record FindRequest(long userId) {}
    record User(long id, String name, String email) {}

    Promise<User> findUser(FindRequest request);

    static UserRepository userRepository(@Sql SqlConnector db) {
        return request -> db.query(
            "SELECT id, name, email FROM users WHERE id = ?",
            request.userId()
        ).map(UserRepository::toUser);
    }

    private static User toUser(/* row mapping */) {
        // Map database row to User record
    }
}
```

The `@Sql` annotation tells the processor this parameter is a resource dependency configured from the `"database"` section of `aether.toml`. At runtime, the generated factory calls `ctx.resources().provide(SqlConnector.class, "database")` and passes the result to your factory.

### Custom Qualifiers

When you need multiple databases or want descriptive naming, create custom qualifier annotations:

```java
@ResourceQualifier(type = SqlConnector.class, config = "database.orders")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@interface OrderDb {}

@ResourceQualifier(type = SqlConnector.class, config = "database.analytics")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@interface AnalyticsDb {}
```

Use them on factory parameters:

```java
@Slice
public interface OrderAnalytics {

    record ReportRequest(String period) {}
    record Report(int orderCount, double totalRevenue) {}

    Promise<Report> generateReport(ReportRequest request);

    static OrderAnalytics orderAnalytics(@OrderDb SqlConnector orders,
                                         @AnalyticsDb SqlConnector analytics) {
        record orderAnalytics(SqlConnector orders,
                              SqlConnector analytics) implements OrderAnalytics {
            @Override
            public Promise<Report> generateReport(ReportRequest request) {
                return Promise.all(
                    countOrders(request.period()),
                    sumRevenue(request.period())
                ).map(Report::new);
            }

            private Promise<Integer> countOrders(String period) {
                return orders.query("SELECT count(*) FROM orders WHERE period = ?", period)
                             .map(OrderAnalytics::extractCount);
            }

            private Promise<Double> sumRevenue(String period) {
                return analytics.query("SELECT sum(revenue) FROM sales WHERE period = ?", period)
                                .map(OrderAnalytics::extractSum);
            }
        }
        return new orderAnalytics(orders, analytics);
    }

    private static Integer extractCount(/* row */) { /* ... */ }
    private static Double extractSum(/* row */) { /* ... */ }
}
```

Each qualifier maps to a separate configuration section in `aether.toml`:

```toml
[database.orders]
jdbc_url = "jdbc:postgresql://localhost:5432/orders"
username = "app"
password = "secret"

[database.analytics]
jdbc_url = "jdbc:postgresql://localhost:5432/analytics"
username = "app"
password = "secret"
```

### Dependency Classification

The annotation processor classifies factory parameters automatically:

| Parameter Pattern | Classification | What Happens |
|-------------------|----------------|--------------|
| `@Sql SqlConnector db` | Resource dependency | Provisioned from `aether.toml` |
| `InventoryService inventory` | Slice dependency | Proxy generated for remote calls |
| `OrderValidator validator` | Plain interface | Factory method called directly |

### Configuration Section Naming

Resource configuration lives in `aether.toml` using the pattern `[type.qualifier]`:

```toml
[http.payment-gateway]
base_url = "https://api.payments.example.com"

[database.orders]
type = "postgresql"
host = "db.example.com"
database = "orders"
```

The `type` prefix determines which `ResourceFactory` handles provisioning. The `qualifier` suffix distinguishes multiple instances of the same resource type.

### Secret Handling

### Secret Handling

String values containing `${secrets:path/to/secret}` are resolved at configuration load time by `SecretResolvingConfigurationProvider`. The secret resolver function maps paths to values asynchronously. All placeholders are resolved eagerly before the configuration is made available to resource factories.

```toml
[database.orders]
username = "app_user"
password = "${secrets:database/orders/password}"
```

### Environment Variable Layering

`EnvironmentConfigSource` imports environment variables with the `AETHER_` prefix at priority 100. Variables are converted from `SCREAMING_SNAKE_CASE` to `dot.notation.lowercase`:

| Environment Variable | Config Key |
|---------------------|------------|
| `AETHER_DATABASE_HOST` | `database.host` |
| `AETHER_SERVER_PORT` | `server.port` |
| `AETHER_HTTP_PAYMENT_GATEWAY_BASE_URL` | `http.payment.gateway.base.url` |

The system does **not** support `${env:...}` syntax. Environment variables are imported automatically via `EnvironmentConfigSource` and merged with other configuration sources by priority.

---

## HTTP Client

**Resource type:** `HttpClient`
**Config prefix:** `http`
**Built-in qualifier:** `@Http`
**Factory:** `HttpClientFactory`

### Configuration

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `base_url` | `String` (optional) | none | Base URL prepended to all request paths |
| `connect_timeout` | duration | `10s` | TCP connection timeout |
| `request_timeout` | duration | `30s` | Full request timeout |
| `follow_redirects` | `NORMAL` / `ALWAYS` / `NEVER` | `NORMAL` | HTTP redirect policy |
| `default_headers.*` | `Map<String, String>` | empty | Headers added to every request |
| `json.naming` | `CAMEL_CASE` / `SNAKE_CASE` / `KEBAB_CASE` | `CAMEL_CASE` | JSON property naming strategy |
| `json.null_inclusion` | `INCLUDE` / `EXCLUDE` / `NON_EMPTY` | `NON_EMPTY` | Null value inclusion policy |
| `json.fail_on_unknown` | `boolean` | `false` | Fail on unknown JSON properties |

### API

`HttpClient` provides raw HTTP methods that return `Promise<HttpResult<String>>` (or `Promise<HttpResult<byte[]>>` for binary):

| Method | Signature |
|--------|-----------|
| GET | `get(path)`, `get(path, headers)` |
| POST | `post(path, body)`, `post(path, body, headers)` |
| PUT | `put(path, body)`, `put(path, body, headers)` |
| DELETE | `delete(path)`, `delete(path, headers)` |
| PATCH | `patch(path, body)`, `patch(path, body, headers)` |
| GET (binary) | `getBytes(path)`, `getBytes(path, headers)` |

All methods accept an optional `Map<String, String> headers` parameter that is merged with `defaultHeaders` from configuration.

### Error Handling

`HttpClientError` is a sealed interface with four variants:

| Variant | When |
|---------|------|
| `SerializationFailed` | JSON serialization of request body failed |
| `DeserializationFailed` | JSON deserialization of response body failed |
| `RequestFailed` | HTTP response returned non-2xx status code |
| `RequestFailedWithBody` | Non-2xx status with a parseable error body |

### TOML Examples

**Payment gateway:**
```toml
[http.payment-gateway]
base_url = "https://api.payments.example.com/v1"
connect_timeout = "5s"
request_timeout = "15s"
follow_redirects = "NEVER"

[http.payment-gateway.default_headers]
Authorization = "Bearer ${secrets:payment/api-key}"
X-Idempotency-Version = "2024-01"

[http.payment-gateway.json]
naming = "SNAKE_CASE"
null_inclusion = "EXCLUDE"
fail_on_unknown = false
```

**Webhook sender:**
```toml
[http.webhook-sender]
base_url = "https://hooks.example.com"
connect_timeout = "3s"
request_timeout = "10s"

[http.webhook-sender.json]
naming = "CAMEL_CASE"
null_inclusion = "NON_EMPTY"
```

**Inventory service (internal):**
```toml
[http.inventory]
base_url = "http://inventory.internal:8080/api"
request_timeout = "5s"
```

---

## Database Connector

**Resource types:** `SqlConnector`, `JooqConnector`
**Config prefix:** `database`
**Built-in qualifiers:** `@Sql`, `@Jooq`
**Factories:** `AsyncSqlConnectorFactory` (priority 20), `R2dbcSqlConnectorFactory`, `JdbcSqlConnectorFactory`, `JdbcJooqConnectorFactory`, `R2dbcJooqConnectorFactory`

Transport is selected automatically by priority:
1. If `async_url` is present, **postgres-async** transport is used (native Netty, highest priority)
2. If `r2dbc_url` is present, **R2DBC** transport is used
3. Otherwise, **JDBC** transport (default)

### Configuration

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `name` | `String` | required | Connector name for identification and metrics |
| `type` | `DatabaseType` | required | Database type (see table below) |
| `host` | `String` | required | Database host |
| `port` | `int` | type default | Database port (0 uses type default) |
| `database` | `String` | required | Database name |
| `username` | `String` (optional) | none | Connection username |
| `password` | `String` (optional) | none | Connection password |
| `jdbc_url` | `String` (optional) | none | Override JDBC URL (replaces host/port/database) |
| `r2dbc_url` | `String` (optional) | none | Override R2DBC URL (replaces host/port/database) |
| `async_url` | `String` (optional) | none | Override async URL — selects postgres-async transport (highest priority) |
| `properties.*` | `Map<String, String>` | empty | Additional driver-specific properties |

### Database Types

| Type | Default Port | JDBC Driver |
|------|-------------|-------------|
| `POSTGRESQL` | 5432 | `org.postgresql.Driver` |
| `MYSQL` | 3306 | `com.mysql.cj.jdbc.Driver` |
| `MARIADB` | 3306 | `org.mariadb.jdbc.Driver` |
| `H2` | 9092 | `org.h2.Driver` |
| `SQLITE` | N/A | `org.sqlite.JDBC` |
| `ORACLE` | 1521 | `oracle.jdbc.OracleDriver` |
| `SQLSERVER` | 1433 | `com.microsoft.sqlserver.jdbc.SQLServerDriver` |
| `DB2` | 50000 | `com.ibm.db2.jcc.DB2Driver` |
| `COCKROACHDB` | 26257 | `org.postgresql.Driver` |

### Connection Pool Configuration

Nested under `pool_config` in the database section.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `min_connections` | `int` | `4` | Minimum connections to maintain |
| `max_connections` | `int` | `20` | Maximum connections allowed |
| `connection_timeout` | duration | `30s` | Maximum wait time for a connection |
| `idle_timeout` | duration | `10m` | Maximum idle time before closing |
| `max_lifetime` | duration | `30m` | Maximum lifetime of a connection |
| `validation_query` | `String` (optional) | none | SQL query to validate connections |
| `leak_detection_timeout` | duration | `0` (disabled) | Time after which leak warnings are logged |
| `io_threads` | `int` | `0` (auto) | Netty IO threads for async transport. `0` = `max(availableProcessors, 8)` |

### SqlConnector API

| Method | Return Type | Description |
|--------|-------------|-------------|
| `queryOne(sql, mapper, params...)` | `Promise<T>` | Single result (fails if 0 or >1 rows) |
| `queryOptional(sql, mapper, params...)` | `Promise<Option<T>>` | Optional result |
| `queryList(sql, mapper, params...)` | `Promise<List<T>>` | All matching rows |
| `update(sql, params...)` | `Promise<Integer>` | Affected row count |
| `batch(sql, paramsList)` | `Promise<int[]>` | Batch update counts |
| `transactional(callback)` | `Promise<T>` | Auto-commit/rollback |
| `isHealthy()` | `Promise<Boolean>` | Health check |

### JooqConnector API

| Method | Return Type | Description |
|--------|-------------|-------------|
| `dsl()` | `DSLContext` | jOOQ context for type-safe query building |
| `fetchOne(query)` | `Promise<R>` | Single record (fails if 0 or >1) |
| `fetchOptional(query)` | `Promise<Option<R>>` | Optional record |
| `fetch(query)` | `Promise<List<R>>` | All records |
| `execute(query)` | `Promise<Integer>` | Affected row count |
| `transactional(callback)` | `Promise<T>` | Auto-commit/rollback |

### Error Handling

`DatabaseConnectorError` is a sealed interface with these variants:

| Variant | When |
|---------|------|
| `ConnectionFailed` | Connection to database failed |
| `QueryFailed` | Query execution failed |
| `ConstraintViolation` | Unique, foreign key, or other constraint violated |
| `TimedOut` | Operation exceeded timeout |
| `TransactionRolledBack` | Deadlock, serialization failure, etc. |
| `TransactionNotActive` | Transaction required but not active |
| `ResultNotFound` | Query returned 0 rows when 1 expected |
| `MultipleResults` | Query returned >1 rows when 1 expected |
| `ConfigurationError` | Invalid configuration |
| `PoolExhausted` | No connections available |
| `DatabaseFailure` | Catch-all for unexpected errors |

### TOML Examples

**Primary database (postgres-async — recommended for PostgreSQL):**
```toml
[database.primary]
name = "primary"
type = "POSTGRESQL"
host = "db.example.com"
port = 5432
database = "myapp"
username = "app_user"
password = "${secrets:database/primary/password}"
async_url = "postgresql://db.example.com:5432/myapp"

[database.primary.pool_config]
min_connections = 5
max_connections = 20
connection_timeout = "10s"
idle_timeout = "5m"
max_lifetime = "30m"
leak_detection_timeout = "30s"
io_threads = 0
```

**Primary database (JDBC):**
```toml
[database.primary]
name = "primary"
type = "POSTGRESQL"
host = "db.example.com"
port = 5432
database = "myapp"
username = "app_user"
password = "${secrets:database/primary/password}"

[database.primary.pool_config]
min_connections = 5
max_connections = 20
connection_timeout = "10s"
idle_timeout = "5m"
max_lifetime = "30m"
leak_detection_timeout = "30s"
io_threads = 0
```

**Analytics database (R2DBC):**
```toml
[database.analytics]
name = "analytics"
type = "postgresql"
r2dbc_url = "r2dbc:postgresql://analytics.internal:5432/metrics"
username = "analytics_reader"
password = "${secrets:database/analytics/password}"

[database.analytics.pool_config]
min_connections = 2
max_connections = 5
```

**H2 in-memory (testing):**
```toml
[database.test]
name = "test"
type = "h2"
host = "localhost"
database = "mem:testdb"
```

**Using JDBC URL override:**
```toml
[database.legacy]
name = "legacy"
jdbc_url = "jdbc:oracle:thin:@//oracle.corp:1521/LEGACY"
username = "legacy_user"
password = "${secrets:database/legacy/password}"
```

---

## Notification Sender

**Resource type:** `NotificationSender`
**Config prefix:** `notification`
**Built-in qualifier:** `@Notify`
**Factory:** `NotificationSenderFactory`

### Configuration

Two backends are supported: `smtp` (direct SMTP) and `http` (vendor API).

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `backend` | `String` | required | Backend type: `"smtp"` or `"http"` |

#### SMTP Backend

Nested under `[notification.smtp]`:

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `host` | `String` | required | SMTP server hostname |
| `port` | `int` | `587` | SMTP server port |
| `tls` | `SmtpTlsMode` | `STARTTLS` | TLS mode: `NONE`, `STARTTLS`, `IMPLICIT` |
| `username` | `String` (optional) | none | AUTH PLAIN username |
| `password` | `String` (optional) | none | AUTH PLAIN password |
| `connect_timeout` | duration | `10s` | TCP connection timeout |
| `command_timeout` | duration | `30s` | SMTP command timeout |

#### HTTP Vendor Backend

Nested under `[notification.http]`:

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `provider` | `String` | required | Vendor: `"sendgrid"`, `"mailgun"`, `"postmark"`, `"resend"` |
| `api_key` | `String` | required | Vendor API key |
| `endpoint` | `String` (optional) | vendor default | Override API endpoint URL |
| `from` | `String` (optional) | none | Default sender address |

#### Retry Configuration

Nested under `[notification.retry]`:

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `max_attempts` | `int` | `3` | Maximum delivery attempts |
| `initial_delay_ms` | `long` | `1000` | Initial retry delay in milliseconds |
| `max_delay_ms` | `long` | `30000` | Maximum retry delay in milliseconds |
| `backoff_multiplier` | `double` | `2.0` | Exponential backoff multiplier |

### API

`NotificationSender` provides a single method:

| Method | Signature | Description |
|--------|-----------|-------------|
| `send` | `Promise<NotificationResult> send(Notification notification)` | Send a notification |

`Notification` is a sealed interface. Phase 1 supports `Notification.Email`:

```java
var notification = Notification.Email.email(
    "noreply@example.com",
    List.of("user@example.com"),
    "Order Confirmed",
    NotificationBody.Text.text("Your order #1234 has been confirmed.")
).withCc(List.of("admin@example.com"))
 .withReplyTo("support@example.com");

sender.send(notification)
      .onSuccess(result -> log.info("Sent via {}: {}", result.backend(), result.messageId()));
```

`NotificationBody` is a sealed interface with two variants:

| Variant | Factory | Description |
|---------|---------|-------------|
| `Text` | `NotificationBody.Text.text(content)` | Plain text body |
| `Html` | `NotificationBody.Html.html(content)` or `html(content, fallback)` | HTML body with optional text fallback |

### Error Handling

`NotificationError` is a sealed interface with three variants:

| Variant | When |
|---------|------|
| `BackendNotConfigured` | Unknown backend or missing backend-specific configuration |
| `UnsupportedChannel` | Notification type not supported by this backend |
| `DeliveryFailed` | All retry attempts exhausted |

### TOML Examples

**SMTP (direct):**
```toml
[notification]
backend = "smtp"

[notification.smtp]
host = "smtp.example.com"
port = 587
tls = "STARTTLS"
username = "noreply@example.com"
password = "${secrets:smtp/password}"

[notification.retry]
max_attempts = 3
```

**SendGrid (HTTP vendor):**
```toml
[notification]
backend = "http"

[notification.http]
provider = "sendgrid"
api_key = "${secrets:sendgrid/api-key}"
from = "noreply@example.com"

[notification.retry]
max_attempts = 5
initial_delay_ms = 2000
```

**Mailgun (HTTP vendor):**
```toml
[notification]
backend = "http"

[notification.http]
provider = "mailgun"
api_key = "${secrets:mailgun/api-key}"
```

### Slice Usage

```java
import org.pragmatica.aether.resource.notification.Notify;
import org.pragmatica.aether.resource.notification.NotificationSender;

@Slice
public interface AlertService {

    record AlertRequest(String recipient, String subject, String message) {}

    Promise<Unit> sendAlert(AlertRequest request);

    static AlertService alertService(@Notify NotificationSender sender) {
        record alertService(NotificationSender sender) implements AlertService {
            @Override
            public Promise<Unit> sendAlert(AlertRequest request) {
                var notification = Notification.Email.email(
                    "alerts@example.com",
                    List.of(request.recipient()),
                    request.subject(),
                    NotificationBody.Text.text(request.message())
                );
                return sender.send(notification).map(_ -> Unit.unit());
            }
        }
        return new alertService(sender);
    }
}
```

### Custom Qualifiers

For multiple notification backends:

```java
@ResourceQualifier(type = NotificationSender.class, config = "notification.transactional")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@interface TransactionalEmail {}

@ResourceQualifier(type = NotificationSender.class, config = "notification.marketing")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@interface MarketingEmail {}
```

### Supported HTTP Vendors

| Vendor | ID | Auth | Format |
|--------|----|------|--------|
| SendGrid | `sendgrid` | Bearer token | JSON (`personalizations` array) |
| Mailgun | `mailgun` | Basic auth (`api:<key>`) | Form-encoded |
| Postmark | `postmark` | `X-Postmark-Server-Token` header | JSON |
| Resend | `resend` | Bearer token | JSON |

Custom vendors can be added via `VendorMapping` SPI (ServiceLoader in `integrations/email-http`).

---

## Cache Interceptor

**Resource type:** `CacheMethodInterceptor`
**Config prefix:** interceptor-specific (applied via aspect configuration)
**Factory:** `CacheInterceptorFactory`

### Configuration

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `cache_name` | `String` | `"default"` | Logical cache name (shared name = shared cache instance) |
| `strategy` | `CacheStrategy` | `CACHE_ASIDE` | Caching strategy (see table below) |
| `ttl_seconds` | `int` | `300` | Time-to-live for cached entries |
| `max_entries` | `int` | `10000` | Maximum number of entries |
| `mode` | `CacheMode` | `LOCAL` | Cache storage mode |

### Cache Strategies

| Strategy | On Hit | On Miss/Write |
|----------|--------|---------------|
| `CACHE_ASIDE` | Return cached | Call method, cache result |
| `READ_THROUGH` | Return cached | Call method, cache result |
| `WRITE_THROUGH` | N/A | Call method, cache result in chain |
| `WRITE_BACK` | N/A | Call method, cache result as side-effect |
| `WRITE_AROUND` | N/A | Call method, invalidate cached entry |

### Cache Modes

| Mode | Storage | Description |
|------|---------|-------------|
| `LOCAL` | In-memory on local node | Fastest, no network overhead |
| `DISTRIBUTED` | DHT across the cluster | Shared cache, survives node loss |
| `TIERED` | Local L1 + distributed L2 | Best of both: fast reads with cluster-wide consistency |

### TOML Example

```toml
[cache.product-catalog]
cache_name = "products"
strategy = "CACHE_ASIDE"
ttl_seconds = 600
max_entries = 50000
mode = "TIERED"
```

---

## Aspects (Interceptors)

Aspects are cross-cutting concerns applied to slice method invocations via configuration. Each aspect is a `ResourceFactory` that provisions a method interceptor.

### Retry

**Resource type:** `RetryMethodInterceptor`
**Factory:** `RetryInterceptorFactory`

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `max_attempts` | `int` | required | Maximum retry attempts (must be positive) |
| `backoff_strategy` | `BackoffStrategy` | exponential | Backoff strategy between retries |

Built-in backoff strategies:
- **Exponential:** initial delay 100ms, max delay 10s, factor 2.0, no jitter
- **Fixed:** constant interval between retries

```toml
[retry.payment-calls]
max_attempts = 3
```

### Circuit Breaker

**Resource type:** `CircuitBreakerMethodInterceptor`
**Factory:** `CircuitBreakerInterceptorFactory`

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `failure_threshold` | `int` | `5` | Failures before opening the circuit |
| `reset_timeout` | duration | `30s` | Time in open state before half-open transition |
| `test_attempts` | `int` | `3` | Successful calls in half-open needed to close |

```toml
[circuit-breaker.external-api]
failure_threshold = 10
reset_timeout = "60s"
test_attempts = 5
```

### Rate Limit

**Resource type:** `RateLimitMethodInterceptor`
**Factory:** `RateLimitInterceptorFactory`

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `max_requests` | `int` | `100` | Maximum requests allowed in the window |
| `window` | duration | `1m` | Time window for rate limiting |
| `burst` | `int` | `0` | Additional burst capacity above base rate |

```toml
[rate-limit.api-calls]
max_requests = 200
window = "1m"
burst = 50
```

### Metrics

**Resource type:** `MetricsMethodInterceptor`
**Factory:** `MetricsInterceptorFactory`

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `name` | `String` | required | Metric name prefix |
| `record_timing` | `boolean` | `true` | Record execution timing |
| `record_counts` | `boolean` | `true` | Record success/failure counts |
| `tags` | `List<String>` | empty | Additional metric tags (key-value pairs) |

The `registry` field (Micrometer `MeterRegistry`) is injected programmatically, not via TOML.

```toml
[metrics.order-processing]
name = "order.processing"
record_timing = true
record_counts = true
```

### Logging

**Resource type:** `LoggingMethodInterceptor`
**Factory:** `LoggingInterceptorFactory`

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `name` | `String` | required | Logger name prefix |
| `level` | `LogLevel` | `INFO` | Log level (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`) |
| `log_args` | `boolean` | `true` | Log method arguments |
| `log_result` | `boolean` | `true` | Log method results |
| `log_duration` | `boolean` | `true` | Log execution duration |

```toml
[logging.payment-flow]
name = "payment.flow"
level = "DEBUG"
log_args = true
log_result = false
log_duration = true
```

---

## Pub-Sub Messaging (Subscriber)

**Marker type:** `Subscriber`
**Config prefix:** user-defined (e.g., `messaging.orders`)

Pub-sub subscriptions are declared on methods using `@ResourceQualifier(type = Subscriber.class, ...)`. The annotated method becomes a message handler that receives events published to the configured topic.

### Declaration

```java
@ResourceQualifier(type = Subscriber.class, config = "messaging.orders")
@Retention(RUNTIME) @Target(METHOD)
public @interface OrderEvents {}
```

### Usage

```java
@Slice
public interface OrderProcessor {
    @OrderEvents
    Promise<Unit> handleOrderEvent(OrderEvent event);
}
```

### Configuration

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `topic` | `String` | required | Topic name for message routing |

### TOML Example

```toml
[messaging.orders]
topic = "order-events"
```

### Behavior

- The runtime registers the handler in the cluster KV-Store when the slice activates
- Messages published to the topic are routed to any node with a subscriber loaded
- Multiple slices can subscribe to the same topic
- Subscriptions are automatically removed when the slice deactivates

---

## Scheduled Invocation (Scheduled)

**Marker type:** `Scheduled`
**Config prefix:** user-defined (e.g., `scheduling.cleanup`)

Scheduled invocations are declared on zero-parameter methods using `@ResourceQualifier(type = Scheduled.class, ...)`. The annotated method is invoked periodically by the runtime.

### Declaration

```java
@ResourceQualifier(type = Scheduled.class, config = "scheduling.cleanup")
@Retention(RUNTIME) @Target(METHOD)
public @interface CleanupSchedule {}
```

### Usage

```java
@Slice
public interface OrderService {
    @CleanupSchedule
    Promise<Unit> cleanupExpiredOrders();
}
```

### Configuration

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `interval` | `String` | — | Fixed-rate interval: `"30s"`, `"5m"`, `"1h"`, `"1d"`, `"2w"` |
| `cron` | `String` | — | Standard 5-field cron: `minute hour dom month dow` |
| `leaderOnly` | `boolean` | `true` | Whether only the leader node triggers the task |

Exactly one of `interval` or `cron` must be specified.

### TOML Examples

**Interval-based (every 5 minutes):**
```toml
[scheduling.cleanup]
interval = "5m"
leaderOnly = true
```

**Cron-based (daily at midnight):**
```toml
[scheduling.report]
cron = "0 0 * * *"
leaderOnly = true
```

**Non-leader task (runs on every node):**
```toml
[scheduling.local-cache-refresh]
interval = "30s"
leaderOnly = false
```

### Cron Expression Format

Standard 5-field format: `minute hour day-of-month month day-of-week`

| Field | Allowed Values | Special Characters |
|-------|---------------|-------------------|
| Minute | 0-59 | `*`, `,`, `-`, `/` |
| Hour | 0-23 | `*`, `,`, `-`, `/` |
| Day of Month | 1-31 | `*`, `,`, `-`, `/` |
| Month | 1-12 | `*`, `,`, `-`, `/` |
| Day of Week | 0-6 (0=Sunday) | `*`, `,`, `-`, `/` |

Examples: `*/5 * * * *` (every 5 min), `0 9 * * 1-5` (weekdays at 9am), `0 0 1 * *` (monthly)

### Behavior

- Scheduled tasks are registered in the cluster KV-Store on slice activation
- `leaderOnly = true`: the leader starts a timer and invokes via `SliceInvoker`; any node with the slice may execute
- `leaderOnly = false`: each node with the slice starts its own timer
- Timers are quorum-gated: cancelled on quorum loss, restarted on quorum establishment
- Schedule changes via Management API trigger automatic timer restart
- Interval tasks use fixed-rate scheduling; cron tasks use one-shot timers that re-schedule after each execution
- Supported interval units: `s` (seconds), `m` (minutes), `h` (hours), `d` (days), `w` (weeks)

### Operational Controls

Scheduled tasks can be managed at runtime via the Management API and CLI:

- **Pause** — suspends a task's timer without removing the schedule. The task will not fire until resumed
- **Resume** — restarts a paused task's timer from the current time
- **Manual trigger** — fires a task immediately regardless of schedule or paused state

Pause/resume state is persisted in the KV-Store through consensus — it survives leader failover and node restarts.

See [Management API](../reference/management-api.md) and [CLI Reference](../reference/cli.md) for endpoint and command details.

### Execution State

The runtime tracks execution metrics for each scheduled task:

| Metric | Description |
|--------|-------------|
| `lastExecutionAt` | Epoch millis of the most recent execution |
| `nextFireAt` | Epoch millis of the next scheduled fire (cron tasks) |
| `consecutiveFailures` | Number of consecutive failed executions (resets on success) |
| `totalExecutions` | Lifetime execution count |
| `lastFailureMessage` | Error message from the most recent failure |

Execution state is written to the KV-Store after each execution (fire-and-forget). Query via `GET /api/scheduled-tasks/{config}/{artifact}/{method}/state`.

### Method Constraints

Validated at compile time by the annotation processor:
- Zero parameters
- Return type `Promise<Unit>`

---

## ResourceFactory SPI

All resource types are discovered via Java `ServiceLoader`. Each factory implements `ResourceFactory<T, C>`:

```java
public interface ResourceFactory<T, C> {
    Class<T> resourceType();
    Class<C> configType();
    Promise<T> provision(C config);

    default int priority() { return 0; }       // Higher = preferred
    default boolean supports(C config) { return true; }
}
```

When multiple factories support the same resource type, the one with the highest `priority()` that returns `true` from `supports(config)` is selected. This is how JDBC vs R2DBC transport is chosen automatically for database connectors.

Factories are registered in `META-INF/services/org.pragmatica.aether.resource.ResourceFactory`.

---

## Related Documents

- [Slice Patterns](slice-patterns.md) — structural patterns, error modeling, routing
- [Getting Started](getting-started.md) — build your first slice from scratch
- [Testing Slices](testing-slices.md) — unit and integration testing
