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

| Java Type | TOML Type | ConfigFacade Method |
|-----------|-----------|-------------------|
| `String` | string | `requireString(key)` |
| `int` | integer | `requireInt(key)` |
| `long` | integer | `requireLong(key)` |
| `boolean` | boolean | `requireBoolean(key)` |
| `double` | float | `requireDouble(key)` |
| `Option<String>` | string (optional) | `getString(key)` |
