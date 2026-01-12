# fold() Alternatives

## Principle

**Prefer dedicated monadic methods. Reserve fold() for genuine bifurcation at boundaries.**

The `fold()` method is universal but obscures intent. Dedicated methods encode intent directly:
- `toResult(Cause)` → "empty is an error"
- `async(Cause)` → "lift to async, empty is an error"
- `.map().or()` → "transform, with default if empty"
- `.onFailure()` → "side effect on error"

## Decision Guide

| Scenario | Before (fold) | After (dedicated) |
|----------|---------------|-------------------|
| Option empty → error Promise | `opt.fold(() -> err.promise(), this::process)` | `opt.async(err).flatMap(this::process)` |
| Option empty → error Result | `opt.fold(() -> err.result(), this::process)` | `opt.toResult(err).flatMap(this::process)` |
| Option empty → default value | `opt.fold(() -> default, this::transform)` | `opt.map(this::transform).or(default)` |
| Result failure → log + Option | `res.fold(c -> { log(c); return none(); }, Option::some)` | `res.onFailure(this::log).option()` |
| Result failure → fallback | `res.fold(_ -> fallback, identity())` | `res.or(fallback)` |

## Pattern Reference

### Pattern 1: Option → Promise with Error on Empty

```java
// BEFORE: fold obscures intent
return repository()
    .findById(id)
    .fold(
        () -> new NotFoundError(id).promise(),
        order -> validateOrder(order)
    );

// AFTER: intent is explicit
return repository()
    .findById(id)
    .async(new NotFoundError(id))
    .flatMap(order -> validateOrder(order));
```

### Pattern 2: Option → Result with Default

```java
// BEFORE: both branches equally prominent
return body.fold(
    () -> Result.success(none()),
    template -> TemplateProcessor.compile(template).map(Option::some)
);

// AFTER: happy path clear, default at end
return body.map(TemplateProcessor::compile)
           .map(result -> result.map(Option::some))
           .or(Result.success(none()));
```

### Pattern 3: Result with Side Effect + Fallback

```java
// BEFORE: nested fold, mixed concerns
return ConfigLoader.load(path)
    .fold(
        cause -> { log.error("Failed: {}", cause.message()); return Option.none(); },
        Option::option
    );

// AFTER: side effect separate from transformation
return ConfigLoader.load(path)
    .onFailure(cause -> log.error("Failed: {}", cause.message()))
    .option();
```

### Pattern 4: Option in Promise Chain

```java
// BEFORE: verbose fold for Unit results
return managementServer.fold(
    () -> Promise.success(Unit.unit()),
    ManagementServer::start
);

// AFTER: map + or with unitPromise
return managementServer.map(ManagementServer::start)
                       .or(Promise.unitPromise());
```

## When fold() IS Appropriate

Use `fold()` when:
1. **Both cases produce fundamentally different logic paths** that can't be expressed as "transform then fallback"
2. **The empty/failure case requires complex computation** beyond providing a default
3. **At system boundaries** converting to external types (HTTP responses, DTOs)

```java
// APPROPRIATE: genuine bifurcation at boundary
return result.fold(
    cause -> Response.status(mapToHttpStatus(cause))
                     .entity(ErrorDto.from(cause))
                     .build(),
    user -> Response.ok(UserDto.from(user)).build()
);
```

## Method Quick Reference

### Option<T>
| Method | Returns | Purpose |
|--------|---------|---------|
| `.toResult(Cause)` | `Result<T>` | Convert, empty → failure |
| `.async(Cause)` | `Promise<T>` | Convert, empty → failure |
| `.map(fn)` | `Option<U>` | Transform if present |
| `.or(T)` | `T` | Unwrap with default |

### Result<T>
| Method | Returns | Purpose |
|--------|---------|---------|
| `.async()` | `Promise<T>` | Lift to Promise |
| `.option()` | `Option<T>` | Discard error |
| `.or(T)` | `T` | Unwrap with fallback |
| `.onFailure(fn)` | `Result<T>` | Side effect on failure |

### Promise<T>
| Method | Returns | Purpose |
|--------|---------|---------|
| `.map(fn)` | `Promise<U>` | Transform if success |
| `.flatMap(fn)` | `Promise<U>` | Chain async ops |
| `.or(T)` | `T` | Block and unwrap with fallback |
| `.onFailure(fn)` | `Promise<T>` | Side effect on failure |
