# Aether Infrastructure: HTTP Client

HTTP client service for the Aether distributed runtime.

## Overview

Provides outbound HTTP operations with async Promise-based API built on Java's HttpClient. Supports all HTTP methods (GET, POST, PUT, DELETE, PATCH), JSON body, custom headers, binary responses, and configurable timeouts and base URL.

## Usage

```java
var client = HttpClientSlice.httpClientSlice();

// GET request
var response = client.get("/api/users/123").await();

// GET with headers
var authed = client.get("/api/users/123", Map.of("Authorization", "Bearer token")).await();

// POST with JSON body
var created = client.post("/api/users", jsonBody).await();

// PUT, DELETE, PATCH
client.put("/api/users/123", jsonBody, headers).await();
client.delete("/api/users/123").await();
client.patch("/api/users/123", jsonBody).await();

// Binary response
var image = client.getBytes("/images/photo.jpg").await();

// Configuration
var config = HttpClientConfig.httpClientConfig()
    .baseUrl("https://api.example.com")
    .connectTimeout(Duration.ofSeconds(5))
    .readTimeout(Duration.ofSeconds(30))
    .build();
var client = HttpClientSlice.httpClientSlice(config);
```

## Dependencies

- JDK HttpClient (built-in)
- `pragmatica-lite-core`
