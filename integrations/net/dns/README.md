# Asynchronous DNS Resolver

Fully asynchronous DNS resolver with TTL-based caching using Netty UDP.

## Overview

Resolves domain names to IP addresses asynchronously, returning `Promise<DomainAddress>`. Features TTL-based cache eviction without background threads, multi-server failover with parallel queries, and event loop sharing with existing Netty components.

All errors are typed via `ResolverError` (`InvalidIpAddress`, `ServerError`, `RequestTimeout`, `UnknownError`, `UnknownDomain`).

## Usage

```java
import org.pragmatica.net.dns.DomainNameResolver;

// Create resolver with DNS servers
var resolver = DomainNameResolver.domainNameResolver(List.of(
    InetAddress.getByName("8.8.8.8"),
    InetAddress.getByName("1.1.1.1")
));

// Resolve domain
resolver.resolve("example.com")
    .onSuccess(address -> {
        System.out.println("IP: " + address.ip());
        System.out.println("TTL: " + address.ttl());
    });

// Cached lookup (no network if cached)
resolver.resolveCached("example.com");

// Shared event loop variant
var resolver = DomainNameResolver.domainNameResolver(servers, sharedEventLoop);

// Cleanup
resolver.close();
```

### Caching Behavior

- Successful resolutions cached until TTL expires
- Failed resolutions are NOT cached (allows retry)
- TTL eviction uses `promise.async(ttl, ...)` -- no background threads
- `localhost` is pre-cached with infinite TTL

### Multi-Server Failover

Queries are sent to all configured servers in parallel. First successful response wins (`Promise.any()`). If all fail, returns `UnknownDomain` error.

## Dependencies

- Netty (`netty-codec-dns`, `netty-transport`)
- `pragmatica-lite-core`
- SLF4J for logging
