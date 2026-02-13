# HTTP Client

Promise-based HTTP client wrapper around JDK HttpClient with typed error handling.

## Overview

Wraps JDK `HttpClient` operations to return `Promise<HttpResult<T>>` instead of blocking or using `CompletableFuture`. Supports string, byte array, and custom body handlers. `HttpResult` provides status checking (`isSuccess`, `isClientError`, `isServerError`) and header access.

All network errors are mapped to typed `HttpError` causes (`ConnectionFailed`, `Timeout`, `RequestFailed`, `InvalidResponse`, `Failure`).

## Usage

```java
import org.pragmatica.http.JdkHttpOperations;

var http = JdkHttpOperations.jdkHttpOperations();

var request = HttpRequest.newBuilder()
    .uri(URI.create("https://api.example.com/users"))
    .GET()
    .build();

// String response
Promise<HttpResult<String>> response = http.sendString(request);

// Byte array response
Promise<HttpResult<byte[]>> response = http.sendBytes(request);

// Convert to Result (fails on non-2xx)
http.sendString(request)
    .flatMap(result -> result.toResult().async())
    .flatMap(body -> jsonMapper.readString(body, User.class).async());
```

## Dependencies

- JDK 11+ HttpClient (built-in)
- `pragmatica-lite-core`
