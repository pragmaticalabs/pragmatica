# Aether Infrastructure: HTTP Server

HTTP server service for [Aether](https://github.com/siy/aether) distributed runtime.

## Installation

```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>infra-server</artifactId>
    <version>${aether.version}</version>
</dependency>
```

## Overview

Provides inbound HTTP handling with Netty-based server and fluent route definition.

### Key Features

- High-performance Netty server
- Fluent route builder API
- Path parameters and query parameters
- JSON request/response handling
- Custom JSON codec support

### Quick Start

```java
import static org.pragmatica.http.routing.Route.*;

var routes = Stream.of(
    Route.get("/health")
         .withoutParameters()
         .toJson(() -> Map.of("status", "healthy")),

    Route.get("/users/{id}")
         .withPath(PathParameter.STRING)
         .toJson(userId -> userService.findById(userId)),

    Route.post("/users")
         .withBody(CreateUserRequest.class)
         .toJson(request -> userService.create(request)),

    Route.put("/users/{id}")
         .withPath(PathParameter.STRING)
         .withBody(UpdateUserRequest.class)
         .toJson((userId, request) -> userService.update(userId, request)),

    Route.delete("/users/{id}")
         .withPath(PathParameter.STRING)
         .toJson(userId -> userService.delete(userId))
);

var config = HttpServerSliceConfig.httpServerSliceConfig(8080).unwrap();
var server = HttpServerSlice.httpServerSlice(config, routes);

// Start server
server.start().await();

// Check status
System.out.println("Running: " + server.isRunning());
System.out.println("Port: " + server.boundPort());
System.out.println("Routes: " + server.routeCount());

// Stop server
server.stop().await();
```

### Configuration

```java
var config = HttpServerSliceConfig.httpServerSliceConfig(8080)
    .unwrap();

// With custom JSON codec
var server = HttpServerSlice.httpServerSlice(config, routes, customJsonCodec);
```

### API Summary

| Method | Description |
|--------|-------------|
| `httpServerSlice(config, routes)` | Create server with routes |
| `start()` | Start the server |
| `stop()` | Stop the server |
| `isRunning()` | Check if server is running |
| `boundPort()` | Get bound port |
| `routeCount()` | Get number of registered routes |

### Route Builder

```java
// GET without parameters
Route.get("/path").withoutParameters().toJson(handler)

// GET with path parameter
Route.get("/users/{id}").withPath(PathParameter.STRING).toJson(handler)

// POST with body
Route.post("/users").withBody(Request.class).toJson(handler)

// PUT with path and body
Route.put("/users/{id}").withPath(PathParameter.STRING).withBody(Request.class).toJson(handler)
```

## License

Apache License 2.0
