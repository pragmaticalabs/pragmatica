# Aether Infrastructure: Rate Limit

Rate limiting service for the Aether distributed runtime.

## Overview

Provides distributed rate limiting for overload protection using token bucket or sliding window algorithms. Supports per-key rate limiting (user ID, API key, IP, etc.), configurable limits and windows, checking without consuming permits, and custom limits per key.

## Usage

```java
var config = RateLimitConfig.rateLimitConfig()
    .maxRequests(100)
    .windowDuration(Duration.ofMinutes(1))
    .build();
var limiter = RateLimiter.inMemory(config);

// Acquire permit
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

// Custom limit for specific key
limiter.configure("premium-user:456", RateLimitConfig.rateLimitConfig()
    .maxRequests(1000)
    .windowDuration(Duration.ofMinutes(1))
    .build()).await();
```

## Dependencies

- `pragmatica-lite-core`
