---
RFC: 0003
Title: HTTP Layer
Status: Implemented
Author: Sergiy Yevtushenko
Created: 2026-01-15
Updated: 2026-01-15
Affects: [jbct-cli, aether]
---

## Summary

Defines the optional HTTP layer for slices that expose REST endpoints. Covers the `routes.toml` configuration format, route DSL syntax, parameter binding, error-to-HTTP-status mapping, and ServiceLoader-based discovery.

## Motivation

Many slices need to expose HTTP endpoints. Rather than hand-writing boilerplate routing code, the slice-processor generates it from a declarative configuration. This RFC establishes the contract between the configuration format (consumed by jbct-cli) and the runtime interfaces (provided by aether).

**Note:** This layer is optional. Slices without `routes.toml` get no HTTP routes generated.

## Design

### Boundaries

- **jbct-cli**: Reads `routes.toml`, generates route classes implementing aether interfaces
- **aether**: Provides `RouteSource`, `SliceRouterFactory`, discovers routes via ServiceLoader

### 1. Configuration Format

#### File Location

`src/main/resources/{package-path}/routes.toml`

Example: For `org.example.users.UserService`, file at:
`src/main/resources/org/example/users/routes.toml`

#### TOML Structure

```toml
prefix = "/api/v1/users"

[routes]
getUser = "GET /{id:Long}"
createUser = "POST /"
updateUser = "PUT /{id:Long}"
deleteUser = "DELETE /{id:Long}"
searchUsers = "GET /?name&limit:Integer&offset:Integer"

[errors]
HTTP_404 = ["*NotFound*", "*Missing*"]
HTTP_400 = ["*Invalid*", "*Validation*", "*BadRequest*"]
HTTP_409 = ["*Conflict*", "*Duplicate*"]
HTTP_403 = ["*Forbidden*", "*Unauthorized*"]
```

### 2. Route DSL Syntax

Format: `"METHOD /path/{param:Type}?query1&query2:Type"`

#### HTTP Methods

Supported: `GET`, `POST`, `PUT`, `DELETE`, `PATCH`, `HEAD`, `OPTIONS`

#### Path Parameters

Syntax: `{name:Type}` or `{name}` (defaults to String)

```
/users/{id:Long}           → Long id
/users/{id:Long}/orders    → Long id
/items/{category}/{slug}   → String category, String slug
```

**Supported types:** `String`, `Integer`, `Long`, `Boolean`, `UUID`

#### Query Parameters

After `?`, separated by `&`:

```
?name                      → Option<String> name
?name:String               → Option<String> name (explicit)
?limit:Integer             → Option<Integer> limit
?active:Boolean            → Option<Boolean> active
```

**All query parameters are optional** - wrapped in `Option<T>`.

#### Body Parameter

Implicit for `POST`, `PUT`, `PATCH` - the slice method's request type.

### 3. Parameter Binding

Generated route handler extracts and binds parameters:

```java
// Route: "POST /users/{orgId:Long}?notify:Boolean"
// Method: createUser(CreateUserRequest request)

// Generated binding:
route.handler((ctx) -> {
    Long orgId = ctx.pathParam("orgId", Long.class);
    Option<Boolean> notify = ctx.queryParam("notify", Boolean.class);
    CreateUserRequest body = ctx.body(new TypeToken<CreateUserRequest>() {});

    // Construct actual request (if needed) or pass body directly
    return slice.createUser(body);
});
```

#### Parameter Limits

- Maximum 5 total parameters (path + query + body)
- Path parameters: positional, required
- Query parameters: named, optional (Option<T>)
- Body: single object, uses TypeToken for generics

### 4. Error Mapping

Maps domain `Cause` types to HTTP status codes via glob patterns.

#### Pattern Syntax

- `*` matches any sequence of characters
- Pattern matches against simple class name

```toml
[errors]
HTTP_404 = ["*NotFound*", "*Missing*"]
# Matches: UserNotFound, OrderNotFound, ResourceMissing, etc.

HTTP_400 = ["*Invalid*", "*Validation*"]
# Matches: InvalidEmail, ValidationError, InvalidRequest, etc.
```

#### Error Discovery

Generator scans slice package for types implementing `Cause`:

```java
// Scans org.example.users.** for:
sealed interface UserError extends Cause {
    record UserNotFound(UserId id) implements UserError { ... }
    record InvalidEmail(String email) implements UserError { ... }
}
```

#### Generated ErrorMapper

```java
private static final ErrorMapper ERROR_MAPPER = cause -> {
    String name = cause.getClass().getSimpleName();

    if (matches(name, "*NotFound*", "*Missing*")) {
        return HttpError.notFound(cause.message());
    }
    if (matches(name, "*Invalid*", "*Validation*")) {
        return HttpError.badRequest(cause.message());
    }
    // Default: 500 Internal Server Error
    return HttpError.internalError(cause.message());
};
```

#### Conflict Detection

Generator fails compilation if same error type matches multiple status codes:

```toml
[errors]
HTTP_404 = ["*NotFound*"]
HTTP_400 = ["UserNotFound"]  # ERROR: UserNotFound matches both patterns
```

### 5. Generated Artifacts

#### Routes Class

```java
public final class UserServiceRoutes implements RouteSource, SliceRouterFactory<UserService> {
    private UserService delegate;

    // No-arg constructor for ServiceLoader
    public UserServiceRoutes() {
        this.delegate = null;
    }

    // Factory methods
    @Override
    public SliceRouter create(UserService slice) {
        return create(slice, JsonMapper.defaultMapper());
    }

    @Override
    public SliceRouter create(UserService slice, JsonMapper jsonMapper) {
        this.delegate = slice;
        return new SliceRouter(routes(), ERROR_MAPPER, jsonMapper);
    }

    @Override
    public Class<UserService> sliceType() {
        return UserService.class;
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(
            Route.get("/api/v1/users/{id}")
                .pathParam("id", Long.class)
                .handler(ctx -> {
                    var request = new GetUserRequest(ctx.pathParam("id", Long.class));
                    return delegate.getUser(request);
                }),
            Route.post("/api/v1/users")
                .body(new TypeToken<CreateUserRequest>() {})
                .handler(ctx -> delegate.createUser(ctx.body()))
            // ... more routes
        );
    }

    private static final ErrorMapper ERROR_MAPPER = cause -> { ... };
}
```

#### Service File

Generated: `META-INF/services/org.pragmatica.aether.http.adapter.SliceRouterFactory`

Contents:
```
org.example.users.UserServiceRoutes
```

### 6. Runtime Discovery

Aether discovers routes via ServiceLoader:

```java
ServiceLoader<SliceRouterFactory<?>> loader =
    ServiceLoader.load(SliceRouterFactory.class, sliceClassLoader);

for (SliceRouterFactory<?> factory : loader) {
    Class<?> sliceType = factory.sliceType();
    Object slice = sliceStore.findSlice(sliceType);
    SliceRouter router = factory.create(slice, jsonMapper);
    httpServer.mount(router);
}
```

### 7. Interfaces Contract

#### RouteSource

```java
public interface RouteSource {
    Stream<Route<?>> routes();
}
```

#### SliceRouterFactory

```java
public interface SliceRouterFactory<T> {
    SliceRouter create(T slice);
    SliceRouter create(T slice, JsonMapper jsonMapper);
    Class<T> sliceType();
}
```

#### Route

```java
public interface Route<T> {
    String method();
    String path();
    Function<RequestContext, Promise<T>> handler();

    static Route.Builder get(String path) { ... }
    static Route.Builder post(String path) { ... }
    // etc.
}
```

#### ErrorMapper

```java
public interface ErrorMapper {
    HttpError map(Cause cause);
}
```

### Contracts Summary

| Component | jbct-cli Generates | aether Expects |
|-----------|-------------------|----------------|
| Routes class | `{SliceName}Routes` | Implements `RouteSource`, `SliceRouterFactory<T>` |
| Service file | `META-INF/services/...SliceRouterFactory` | ServiceLoader discovery |
| Route DSL | Parsed from `routes.toml` | `Route<?>` objects with handlers |
| Error mapper | Pattern-based `Cause` → HTTP status | `ErrorMapper` interface |
| No-arg constructor | Required for ServiceLoader | Instantiation before `create()` |

## Examples

### Complete routes.toml

```toml
prefix = "/api/v1/orders"

[routes]
getOrder = "GET /{id:Long}"
createOrder = "POST /"
updateOrder = "PUT /{id:Long}"
cancelOrder = "DELETE /{id:Long}"
listOrders = "GET /?status&limit:Integer&offset:Integer"
searchOrders = "GET /search?customer:Long&from:LocalDate&to:LocalDate"

[errors]
HTTP_404 = ["*NotFound*"]
HTTP_400 = ["*Invalid*", "*Validation*", "*BadRequest*"]
HTTP_409 = ["*Conflict*", "*AlreadyCancelled*"]
HTTP_402 = ["*PaymentRequired*", "*InsufficientFunds*"]
```

### Generated Route Handler

```java
// For: listOrders = "GET /?status&limit:Integer&offset:Integer"
Route.get("/api/v1/orders")
    .queryParam("status", String.class)
    .queryParam("limit", Integer.class)
    .queryParam("offset", Integer.class)
    .handler(ctx -> {
        var request = new ListOrdersRequest(
            ctx.queryParam("status"),      // Option<String>
            ctx.queryParam("limit"),       // Option<Integer>
            ctx.queryParam("offset")       // Option<Integer>
        );
        return delegate.listOrders(request);
    })
```

### Error Mapping Flow

```
1. Slice method returns: Promise.failure(new OrderNotFound(orderId))
2. Route handler catches failure
3. ErrorMapper.map(cause) called
4. Pattern "*NotFound*" matches "OrderNotFound"
5. Returns: HttpError.notFound("Order 123 not found")
6. HTTP Response: 404 Not Found
```

## Edge Cases

### No routes.toml

If `routes.toml` doesn't exist, no HTTP artifacts generated. Slice works normally for inter-slice calls (RFC-0002).

### Empty Error Section

If `[errors]` section omitted, all errors map to 500 Internal Server Error.

### Path Conflicts

Generator detects path conflicts at compile time:

```toml
[routes]
getUser = "GET /{id:Long}"
getUserByName = "GET /{name}"  # ERROR: Ambiguous path pattern
```

### Complex Request Types

For routes with multiple parameters, generator may create synthetic request types or require explicit mapping in slice method.

## Breaking Changes

Changes requiring version bump:

1. Route DSL syntax changes
2. `RouteSource` / `SliceRouterFactory` interface changes
3. Error mapping pattern syntax changes
4. `routes.toml` format changes
5. ServiceLoader contract changes

## References

- [RFC-0001: Core Slice Contract](RFC-0001-core-slice-contract.md) - Slice interface, Promise model
- [RFC-0002: Dependency Protocol](RFC-0002-dependency-protocol.md) - Artifact coordinates (reused in routes)
