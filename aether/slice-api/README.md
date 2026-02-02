# Aether Slice API

Core slice interface definitions for [Aether](https://github.com/siy/aether) distributed runtime.

## Installation

```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>slice-api</artifactId>
    <version>${aether.version}</version>
</dependency>
```

## Overview

This module provides the foundational interfaces for building slices - deployable units in the Aether runtime.

### Key Interfaces

| Interface | Description |
|-----------|-------------|
| `Slice` | Base interface for all deployable units with lifecycle methods |
| `SliceMethod<R, T>` | Typed method definition with request/response types |
| `MethodName` | Type-safe method identifier |
| `SliceRuntime` | Runtime access for slice invocation and configuration |
| `SliceInvokerFacade` | Inter-slice invocation API |
| `Aspect` | Cross-cutting concern interface for method interception |

### Slice Interface

```java
public interface Slice {
    // Lifecycle
    default Promise<Unit> start() { return Promise.unitPromise(); }
    default Promise<Unit> stop() { return Promise.unitPromise(); }

    // Method registry
    List<SliceMethod<?, ?>> methods();
}
```

### Example

```java
public record GreetingSlice() implements Slice {
    public record Request(String name) {}
    public record Response(String message) {}

    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of(
            new SliceMethod<>(
                MethodName.methodName("greet").unwrap(),
                this::greet,
                new TypeToken<Response>() {},
                new TypeToken<Request>() {}
            )
        );
    }

    private Promise<Response> greet(Request request) {
        return Promise.success(new Response("Hello, " + request.name() + "!"));
    }
}
```

## License

Apache License 2.0
