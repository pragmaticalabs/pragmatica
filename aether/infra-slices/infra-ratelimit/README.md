# Aether Infrastructure: Rate Limit

Rate limiting service for [Aether](https://github.com/siy/aether) distributed runtime.

## Installation

```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>infra-ratelimit</artifactId>
    <version>${aether.version}</version>
</dependency>
```

## Overview

Provides distributed rate limiting for overload protection using token bucket or sliding window algorithms.

### Key Features

- Per-key rate limiting (user ID, API key, IP, etc.)
- Configurable limits and windows
- Check without consuming permits
- Multiple rate limit strategies

### Quick Start

```java
var defaultConfig = RateLimitConfig.rateLimitConfig()
    .maxRequests(100)
    .windowDuration(Duration.ofMinutes(1))
    .build();

var limiter = RateLimiter.inMemory(defaultConfig);

// Acquire permit (consume 1)
var result = limiter.acquire("user:123").await();
if (result.allowed()) {
    processRequest();
} else {
    return tooManyRequests(result.retryAfter());
}

// Acquire multiple permits
var batchResult = limiter.acquire("user:123", 5).await();

// Check without consuming
var status = limiter.check("user:123").await();
System.out.println("Remaining: " + status.remaining());

// Configure custom limit for specific key
limiter.configure("premium-user:456", RateLimitConfig.rateLimitConfig()
    .maxRequests(1000)
    .windowDuration(Duration.ofMinutes(1))
    .build()).await();
```

### API Summary

| Method | Description |
|--------|-------------|
| `acquire(key)` | Consume 1 permit |
| `acquire(key, permits)` | Consume N permits |
| `check(key)` | Check status without consuming |
| `configure(key, config)` | Set custom limits for key |

### RateLimitResult

```java
record RateLimitResult(boolean allowed, int remaining, int limit,
                       Instant resetAt, Duration retryAfter) {
    boolean allowed();     // Was request allowed?
    int remaining();       // Permits remaining in window
    Duration retryAfter(); // When to retry if denied
}
```

### RateLimitConfig

```java
record RateLimitConfig(int maxRequests, Duration windowDuration,
                       RateLimitStrategy strategy) {
    enum RateLimitStrategy { TOKEN_BUCKET, SLIDING_WINDOW }
}
```

## License

Apache License 2.0
