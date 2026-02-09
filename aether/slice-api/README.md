# Aether Slice API

Core slice interface definitions for the Aether distributed runtime.

## Overview

Provides the foundational interfaces for building slices -- deployable units in the Aether runtime. Key interfaces: `Slice` (base with lifecycle methods), `SliceMethod<R, T>` (typed method definition), `MethodName` (type-safe method identifier), `SliceRuntime` (runtime access), `SliceInvokerFacade` (inter-slice invocation), and `Aspect` (method interception).

## Usage

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

### Slice Interface

```java
public interface Slice {
    default Promise<Unit> start() { return Promise.unitPromise(); }
    default Promise<Unit> stop() { return Promise.unitPromise(); }
    List<SliceMethod<?, ?>> methods();
}
```

## Dependencies

- `pragmatica-lite-core`
