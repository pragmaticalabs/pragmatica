# Aether Infrastructure: HTTP Client

HTTP client service for [Aether](https://github.com/siy/aether) distributed runtime.

## Installation

```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>infra-http</artifactId>
    <version>${aether.version}</version>
</dependency>
```

## Overview

Provides outbound HTTP operations with async Promise-based API. Built on Java's HttpClient.

### Key Features

- All HTTP methods (GET, POST, PUT, DELETE, PATCH)
- JSON body support
- Custom headers
- Binary response support
- Configurable timeouts and base URL

### Quick Start

```java
var client = HttpClientSlice.httpClientSlice();

// GET request
var response = client.get("/api/users/123").await();
if (response.isSuccess()) {
    var body = response.body();
}

// GET with headers
var authedResponse = client.get("/api/users/123", Map.of(
    "Authorization", "Bearer token123"
)).await();

// POST with JSON body
var createResponse = client.post("/api/users", """
    {"name": "Alice", "email": "alice@example.com"}
    """).await();

// PUT with headers
client.put("/api/users/123", jsonBody, Map.of(
    "Content-Type", "application/json"
)).await();

// DELETE
client.delete("/api/users/123").await();

// Binary response
var imageResult = client.getBytes("/images/photo.jpg").await();
```

### Configuration

```java
var config = HttpClientConfig.httpClientConfig()
    .baseUrl("https://api.example.com")
    .connectTimeout(Duration.ofSeconds(5))
    .readTimeout(Duration.ofSeconds(30))
    .build();

var client = HttpClientSlice.httpClientSlice(config);
```

### API Summary

| Method | Description |
|--------|-------------|
| `get(path)` | GET request, string response |
| `get(path, headers)` | GET with custom headers |
| `post(path, body)` | POST with JSON body |
| `post(path, body, headers)` | POST with headers |
| `put(path, body)` | PUT with JSON body |
| `delete(path)` | DELETE request |
| `patch(path, body)` | PATCH with JSON body |
| `getBytes(path)` | GET with binary response |

### HttpResult

```java
record HttpResult<T>(int statusCode, T body, Map<String, List<String>> headers) {
    boolean isSuccess();    // 2xx status
    boolean isClientError(); // 4xx status
    boolean isServerError(); // 5xx status
}
```

## License

Apache License 2.0
