# Application Configuration Provisioning

## Overview

Aether slices can declare typed configuration dependencies via custom annotations. The slice processor generates type-safe parsers at compile time, and the runtime merges configuration from three sources with automatic change notification.

## Quick Start

### 1. Define your config record

```java
public record GatewayConfig(String provider, String apiUrl, int timeoutMs) {
    public static Result<GatewayConfig> gatewayConfig(String provider, String apiUrl, int timeoutMs) {
        return Result.success(new GatewayConfig(provider, apiUrl, timeoutMs));
    }
}
```

### 2. Create a qualifier annotation

```java
@ResourceQualifier(type = ConfigurationSection.class, config = "payment.gateway")
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.METHOD})
public @interface PaymentGateway {}
```

### 3. Use in your slice

```java
@Slice public interface PaymentService {
    // Initial config at factory creation
    static PaymentService paymentService(
            @PaymentGateway GatewayConfig config,
            @PgSql PaymentPersistence persistence) { ... }

    // Config update notification (optional)
    @PaymentGateway
    Result<Unit> onConfigUpdate(GatewayConfig newConfig);
}
```

### 4. Provide defaults in META-INF/config.toml

```toml
[payment.gateway]
provider = "stripe"
api_url = "https://api.stripe.com/v1"
timeout_ms = 5000
```

## Configuration Sources (merge order)

| Priority | Source | Scope | When |
|----------|--------|-------|------|
| 1 (highest) | KV-Store | Runtime | Pushed via API, survives restarts |
| 2 | `aether.toml` `[app.*]` | Environment | Set per deployment |
| 3 (lowest) | `META-INF/config.toml` | Bundled | Developer defaults in slice JAR |

Higher priority overrides lower. All sections and variables must be present in the bundled defaults (validated at compile time).

## Compile-Time Validation

The slice processor validates:
- Config record has a factory method (JBCT pattern: `TypeName.typeName(...)`)
- All factory method parameters are supported types: `String`, `int`, `long`, `boolean`, `double`, `Option<String>`
- `META-INF/config.toml` contains the declared section with all required fields
- Config update methods return `Result<Unit>` with a single config parameter

## Runtime Notification

- **Single-threaded executor** ensures ordered, non-concurrent notifications
- **KV-Store watcher** triggers re-parse on any config change
- **Record equality diff** prevents unnecessary notifications
- **ACTIVATE ordering** ensures config is valid before routes are published
- **Error handling**: parse failures and update rejections go to log + event stream

## Supported Field Types

### Primitives (required)

| Java Type | TOML Type | Generated Code |
|-----------|-----------|----------------|
| `String` | string | `requireString(key)` |
| `int` | integer | `requireInt(key)` |
| `long` | integer | `requireLong(key)` |
| `boolean` | boolean | `requireBoolean(key)` |
| `double` | float | `requireDouble(key)` |

### Optional primitives

| Java Type | TOML Type | Generated Code |
|-----------|-----------|----------------|
| `Option<String>` | string | `getString(key)` |
| `Option<Integer>` | integer | `getInt(key)` |
| `Option<Long>` | integer | `getLong(key)` |
| `Option<Boolean>` | boolean | `getBoolean(key)` |
| `Option<Double>` | float | `getDouble(key)` |

### Collections

| Java Type | TOML Type | Generated Code |
|-----------|-----------|----------------|
| `List<String>` | array | `requireStringList(key)` |

### Core value objects

All core value objects parse from a TOML string via their factory method:

| Java Type | TOML Example | Generated Code |
|-----------|-------------|----------------|
| `TimeSpan` | `"5s"`, `"100ms"`, `"2m"` | `requireString(key).flatMap(TimeSpan::timeSpan)` |
| `NonBlankString` | `"my-value"` | `requireString(key).flatMap(NonBlankString::nonBlankString)` |
| `Email` | `"user@example.com"` | `requireString(key).flatMap(Email::email)` |
| `Url` | `"https://api.example.com"` | `requireString(key).flatMap(Url::url)` |
| `Uuid` | `"550e8400-e29b-..."` | `requireString(key).flatMap(Uuid::uuid)` |
| `IsoDateTime` | `"2026-04-04T12:00:00Z"` | `requireString(key).flatMap(IsoDateTime::isoDateTime)` |

### User-defined parseable types

Any type with a JBCT-standard factory method `TypeName.typeName(String) → Result<T>` is automatically supported. The processor detects the factory at compile time:

```java
// Your custom value object
public record ApiEndpoint(Url baseUrl, int port) {
    public static Result<ApiEndpoint> apiEndpoint(String raw) {
        // parse "https://host:8080" into Url + port
        return ...;
    }
}

// Used directly in config record — no extra registration needed
public record ServiceConfig(ApiEndpoint endpoint, TimeSpan timeout) { ... }
```

The processor generates `requireString(key).flatMap(ApiEndpoint::apiEndpoint)` — validation errors propagate through `Result.all()` with clear per-field messages.
