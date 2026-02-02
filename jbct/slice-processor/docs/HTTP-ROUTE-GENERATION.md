# HTTP Route Generation Design

## Overview

This document describes the automatic generation of HTTP route handling code from TOML configuration. The generated code bridges Aether slices with the `http-routing-adapter` module, enabling HTTP API exposure for slice methods.

## Dependency Requirement

**Required dependency**: When using `routes.toml`, the `http-routing-adapter` dependency must be present.

**New projects**: Projects created with `jbct init --slice` include this dependency automatically:

```xml
<!-- HTTP Routing Adapter (required for routes.toml) -->
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>http-routing-adapter</artifactId>
    <version>${aether.version}</version>
    <scope>provided</scope>
</dependency>
```

**Existing projects**: If adding routing to an existing slice project, add the dependency above.

**Compile-time validation**: If `routes.toml` exists but the dependency is missing, compilation fails with:
```
ERROR: HTTP routing configured but dependency missing.
Add to pom.xml:
<dependency>
  <groupId>org.pragmatica-lite.aether</groupId>
  <artifactId>http-routing-adapter</artifactId>
  <version>${aether.version}</version>
  <scope>provided</scope>
</dependency>
```

## Generated Artifacts

When `routes.toml` exists in the slice package resources, the processor generates:

| Artifact | Location | Purpose |
|----------|----------|---------|
| Routes Class | `{package}.{SliceName}Routes` | Implements `RouteSource` and `SliceRouterFactory` |
| Service File | `META-INF/services/...SliceRouterFactory` | Service loader discovery |

## Design Decisions

### D1: Configuration Location

**Decision**: Route configuration lives in `src/main/resources/{slicePackage}/routes.toml`.

**Rationale**:
- Co-located with slice code for easy maintenance
- Follows Maven/Gradle resource conventions
- Accessible during annotation processing

### D2: DSL-Based Route Syntax

**Decision**: Routes use a compact DSL: `"METHOD /path/{param:Type}?query"`.

**Rationale**:
- Human-readable and concise
- Familiar URL-like syntax
- Single line per route

**Syntax**:
```text
"METHOD /path/{param:Type}?query1&query2:Type"
```

| Component | Format | Examples |
|-----------|--------|----------|
| Method | HTTP verb | `GET`, `POST`, `PUT`, `DELETE`, `PATCH` |
| Path | URL path | `/users`, `/{id}`, `/users/{id}/orders` |
| Path param | `{name:Type}` | `{id:Long}`, `{name}` (String default) |
| Query param | `name` or `name:Type` | `status`, `limit:Integer` |

### D3: Supported Parameter Types

**Decision**: Support common types with automatic path parameter extraction.

| Type | Path Parameter Method |
|------|----------------------|
| `String` | `PathParameter.aString()` |
| `Integer`, `int` | `PathParameter.aInteger()` |
| `Long`, `long` | `PathParameter.aLong()` |
| `Boolean`, `boolean` | `PathParameter.aBoolean()` |
| `LocalDate` | `PathParameter.aLocalDate()` |
| `LocalDateTime` | `PathParameter.aLocalDateTime()` |
| `BigDecimal` | `PathParameter.aDecimal()` |

### D4: Pattern-Based Error Mapping

**Decision**: Error types are mapped to HTTP status codes using glob-like patterns.

**Rationale**:
- Convention over configuration
- Reduces boilerplate
- Compile-time conflict detection

**Pattern syntax**:
- `*NotFound*` - contains "NotFound"
- `User*` - starts with "User"
- `*Error` - ends with "Error"
- `Exact` - exact match

### D5: SliceRouterFactory Integration

**Decision**: Generated class implements `SliceRouterFactory<T>` for service loader discovery.

**Rationale**:
- Aether can discover and wire routers automatically
- No manual registration required
- Type-safe factory creation

### D6: Route Naming for Observability

**Decision**: All generated routes include `.named("methodName")` for tracing and metrics.

**Rationale**:
- Enables distributed tracing correlation
- Provides meaningful names in logs and metrics
- Method name directly maps to slice method being invoked

### D7: ErrorMapper Generation

**Decision**: Generate `ErrorMapper` that returns `HttpError` with proper status codes.

**Rationale**:
- Integrates with `http-routing-adapter` error handling
- Preserves original cause for logging/debugging
- Passes through existing `HttpError` instances

## Configuration Format

### Basic Configuration

```toml
# Route prefix (prepended to all paths)
prefix = "/api/v1/users"

# Route definitions: handler = "METHOD /path"
[routes]
getUser = "GET /{id:Long}"
createUser = "POST /"
updateUser = "PUT /{id:Long}"
deleteUser = "DELETE /{id:Long}"

# Error type to HTTP status mapping
[errors]
HTTP_404 = ["*NotFound*", "*Missing*"]
HTTP_400 = ["*Invalid*", "*Validation*", "*Malformed*"]
HTTP_401 = ["*Unauthorized*", "*Unauthenticated*"]
HTTP_403 = ["*Forbidden*", "*AccessDenied*"]
HTTP_409 = ["*Conflict*", "*Duplicate*", "*AlreadyExists*"]
```

### Advanced Configuration

```toml
prefix = "/api/v1/orders"

[routes]
# Path parameters
getOrder = "GET /{id:Long}"
getOrderItem = "GET /{orderId:Long}/items/{itemId:Long}"

# Query parameters
searchOrders = "GET /?status&from:LocalDate&to:LocalDate&limit:Integer"

# Body routes (POST/PUT/PATCH)
createOrder = "POST /"
updateOrder = "PUT /{id:Long}"

# Mixed path and query
getUserOrders = "GET /{userId:Long}/orders?status&limit:Integer"

[errors]
# Slice-specific patterns
HTTP_423 = ["*Locked*"]
HTTP_429 = ["*RateLimit*", "*TooMany*"]

# Explicit mapping for ambiguous types
[errors.explicit]
OrderNotFoundButValid = 200
```

## Generated Code

### Input

**Slice interface**:
```java
@Slice
public interface UserService {
    Promise<User> getUser(GetUserRequest request);
    Promise<User> createUser(CreateUserRequest request);
    Promise<List<User>> searchUsers(SearchUsersRequest request);
}
```

**Request records**:
```java
public record GetUserRequest(Long id) {}
public record CreateUserRequest(String name, String email) {}
public record SearchUsersRequest(Option<String> name, Option<Integer> limit) {}
```

**Error types**:
```java
public sealed interface UserError extends Cause {
    record UserNotFound(Long id) implements UserError {}
    record InvalidEmail(String email) implements UserError {}
    record DuplicateUser(String email) implements UserError {}
}
```

**Configuration** (`routes.toml`):
```toml
prefix = "/api/v1/users"

[routes]
getUser = "GET /{id:Long}"
createUser = "POST /"
searchUsers = "GET /?name&limit:Integer"

[errors]
HTTP_404 = ["*NotFound*"]
HTTP_400 = ["*Invalid*"]
HTTP_409 = ["*Duplicate*"]
```

### Output

**Generated**: `UserServiceRoutes.java`

```java
package com.example.users;

import org.pragmatica.aether.http.adapter.ErrorMapper;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.adapter.SliceRouterFactory;
import org.pragmatica.http.routing.HttpError;
import org.pragmatica.http.routing.HttpStatus;
import org.pragmatica.http.routing.PathParameter;
import org.pragmatica.http.routing.QueryParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.json.JsonMapper;

import java.util.stream.Stream;

/**
 * RouteSource and SliceRouterFactory implementation for UserService slice.
 * Generated by slice-processor - do not edit manually.
 */
public final class UserServiceRoutes implements RouteSource, SliceRouterFactory<UserService> {
    private final UserService delegate;

    private UserServiceRoutes(UserService delegate) {
        this.delegate = delegate;
    }

    /** No-arg constructor for service loader instantiation. */
    public UserServiceRoutes() {
        this.delegate = null;
    }

    @Override
    public Class<UserService> sliceType() {
        return UserService.class;
    }

    @Override
    public SliceRouter create(UserService slice) {
        return create(slice, JsonMapper.builder().withPragmaticaTypes().build());
    }

    @Override
    public SliceRouter create(UserService slice, JsonMapper jsonMapper) {
        var routes = new UserServiceRoutes(slice);
        return SliceRouter.sliceRouter(routes, routes.errorMapper(), jsonMapper);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(
            Route.<User>get("/api/v1/users/{id}")
                 .withPath(PathParameter.aLong())
                 .to(id -> delegate.getUser(new GetUserRequest(id)))
                 .named("getUser").asJson(),

            Route.<User>post("/api/v1/users/")
                 .withBody(new TypeToken<CreateUserRequest>() {})
                 .to(request -> delegate.createUser(request))
                 .named("createUser").asJson(),

            Route.<List<User>>get("/api/v1/users/")
                 .withQuery(QueryParameter.aString("name"), QueryParameter.aInteger("limit"))
                 .to((name, limit) -> delegate.searchUsers(new SearchUsersRequest(name, limit)))
                 .named("searchUsers").asJson()
        );
    }

    /**
     * Maps domain errors to HTTP errors.
     *
     * @return error mapper for this slice
     */
    public ErrorMapper errorMapper() {
        return cause -> switch (cause) {
            case UserNotFound _ -> HttpError.httpError(HttpStatus.NOT_FOUND, cause);
            case InvalidEmail _ -> HttpError.httpError(HttpStatus.BAD_REQUEST, cause);
            case DuplicateUser _ -> HttpError.httpError(HttpStatus.CONFLICT, cause);
            case HttpError he -> he;
            default -> HttpError.httpError(HttpStatus.INTERNAL_SERVER_ERROR, cause);
        };
    }
}
```

**Generated**: `META-INF/services/org.pragmatica.aether.http.adapter.SliceRouterFactory`
```text
com.example.users.UserServiceRoutes
```

## Request Mapping Strategies

The generator supports up to 5 parameters total in any combination of path, query, and body.

### Path Parameters Only

```toml
getUser = "GET /{id:Long}"
```

```java
Route.<User>get("/api/v1/users/{id}")
     .withPath(PathParameter.aLong())
     .to(id -> delegate.getUser(new GetUserRequest(id)))
     .named("getUser").asJson()
```

### Multiple Path Parameters

```toml
getOrderItem = "GET /{orderId:Long}/items/{itemId:Long}"
```

```java
Route.<OrderItem>get("/api/v1/orders/{orderId}/items/{itemId}")
     .withPath(PathParameter.aLong(), PathParameter.aLong())
     .to((orderId, itemId) -> delegate.getOrderItem(
         new GetOrderItemRequest(orderId, itemId)))
     .named("getOrderItem").asJson()
```

### Query Parameters Only

```toml
searchUsers = "GET /?name&limit:Integer"
```

```java
Route.<List<User>>get("/api/v1/users/")
     .withQuery(QueryParameter.aString("name"), QueryParameter.aInteger("limit"))
     .to((name, limit) -> delegate.searchUsers(new SearchUsersRequest(name, limit)))
     .named("searchUsers").asJson()
```

Query parameters are wrapped in `Option<T>` since they are optional by nature.

### Body Only (POST/PUT/PATCH)

```toml
createUser = "POST /"
```

```java
Route.<User>post("/api/v1/users/")
     .withBody(new TypeToken<CreateUserRequest>() {})
     .to(request -> delegate.createUser(request))
     .named("createUser").asJson()
```

### Path + Body Combined

```toml
updateUser = "PUT /{id:Long}"
```

```java
Route.<User>put("/api/v1/users/{id}")
     .withPath(PathParameter.aLong())
     .withBody(new TypeToken<UpdateUserRequest>() {})
     .to((id, body) -> delegate.updateUser(body))
     .named("updateUser").asJson()
```

### Path + Query Combined

```toml
getUserOrders = "GET /{userId:Long}/orders?status&limit:Integer"
```

```java
Route.<List<Order>>get("/api/v1/users/{userId}/orders")
     .withPath(PathParameter.aLong())
     .withQuery(QueryParameter.aString("status"), QueryParameter.aInteger("limit"))
     .to((userId, status, limit) -> delegate.getUserOrders(
         new GetUserOrdersRequest(userId, status, limit)))
     .named("getUserOrders").asJson()
```

### Query + Body Combined

```toml
searchWithFilter = "POST /?includeDeleted:Boolean"
```

```java
Route.<SearchResult>post("/api/v1/search")
     .withQuery(QueryParameter.aBoolean("includeDeleted"))
     .withBody(new TypeToken<SearchRequest>() {})
     .to((includeDeleted, body) -> delegate.search(body))
     .named("searchWithFilter").asJson()
```

### Path + Query + Body Combined

```toml
updateOrderItem = "PUT /{orderId:Long}/items/{itemId:Long}?notify:Boolean"
```

```java
Route.<OrderItem>put("/api/v1/orders/{orderId}/items/{itemId}")
     .withPath(PathParameter.aLong(), PathParameter.aLong())
     .withQuery(QueryParameter.aBoolean("notify"))
     .withBody(new TypeToken<UpdateItemRequest>() {})
     .to((orderId, itemId, notify, body) -> delegate.updateItem(body))
     .named("updateOrderItem").asJson()
```

### No Parameters

```toml
healthCheck = "GET /health"
```

```java
Route.<HealthStatus>get("/api/v1/health")
     .withoutParameters()
     .to(_ -> delegate.healthCheck(new HealthCheckRequest()))
     .named("healthCheck").asJson()
```

## Error Type Discovery

### Compile-Time Processing

1. **Scan package** for types implementing `Cause`
2. **Match patterns** from configuration
3. **Detect conflicts** (type matches multiple patterns)
4. **Generate switch** with all discovered mappings

### Conflict Detection

If a type matches multiple patterns with different status codes:

```text
ERROR: Ambiguous error mapping for 'UserNotFoundInvalid':
  - Matches HTTP_404 pattern "*NotFound*"
  - Matches HTTP_400 pattern "*Invalid*"
  Add explicit mapping in [errors.explicit] section.
```

**Resolution**:
```toml
[errors.explicit]
UserNotFoundInvalid = 404
```

## http-routing-adapter Integration

The generated code integrates with Aether's `http-routing-adapter` module:

### SliceRouter

Bridges `HttpRequestContext`/`HttpResponseData` with Route handlers:

```java
// Create router from generated factory
var router = new UserServiceRoutes().create(userServiceImpl);

// Handle HTTP request
Promise<HttpResponseData> response = router.handle(httpRequestContext);
```

### Request Flow

1. `SliceRouter.handle(HttpRequestContext)` receives HTTP request
2. Internal `RequestRouter` finds matching route
3. `SliceRequestContext` wraps HTTP context
4. Route handler invokes slice method
5. Success → 200 with JSON body
6. Failure → `ErrorMapper` converts to HTTP status

### Service Loader Discovery

```java
ServiceLoader<SliceRouterFactory> loader = ServiceLoader.load(SliceRouterFactory.class);

var factory = loader.stream()
    .map(ServiceLoader.Provider::get)
    .filter(f -> f.sliceType() == UserService.class)
    .findFirst()
    .orElseThrow();

var router = factory.create(userServiceImpl);
```

## File Structure

```text
slice-processor/
├── src/main/java/org/pragmatica/jbct/slice/
│   ├── SliceProcessor.java              # Annotation processor entry point
│   └── routing/
│       ├── RouteDsl.java                # DSL parser
│       ├── PathParam.java               # Path parameter model
│       ├── QueryParam.java              # Query parameter model
│       ├── RouteConfig.java             # Loaded config model
│       ├── RouteConfigLoader.java       # TOML loader
│       ├── ErrorPatternConfig.java      # Error pattern config
│       ├── ErrorTypeDiscovery.java      # Package scanner for Cause types
│       ├── ErrorTypeMatcher.java        # Glob pattern matcher
│       ├── ErrorTypeMapping.java        # Type to status mapping
│       ├── ErrorConflict.java           # Ambiguous match record
│       └── RouteSourceGenerator.java    # Code generator
└── docs/
    └── HTTP-ROUTE-GENERATION.md         # This document
```

## Test Coverage

| Test | Coverage |
|------|----------|
| `RouteDslTest` | DSL parsing (37 tests) |
| `ErrorTypeMatcherTest` | Pattern matching (32 tests) |

### DSL Parser Tests

- Simple routes (`GET /users`)
- Path parameters (`GET /{id:Long}`)
- Query parameters (`GET /?name&limit:Integer`)
- Mixed parameters
- Invalid inputs (empty, malformed, invalid method)

### Pattern Matcher Tests

- Exact match (`UserNotFound`)
- Prefix match (`User*`)
- Suffix match (`*Error`)
- Contains match (`*NotFound*`)
- Case sensitivity
- Edge cases (null, empty)

## Revision History

| Date | Author | Changes |
|------|--------|---------|
| 2026-01-14 | Claude | Initial design |
| 2026-01-14 | Claude | Implemented: DSL parser, config loader, error discovery |
| 2026-01-14 | Claude | Added SliceRouterFactory integration |
| 2026-01-14 | Claude | Added service loader file generation |
| 2026-01-15 | Claude | Full parameter support: query params, path+body, path+query, all combinations |
| 2026-02-01 | Claude | Added `.named()` for route tracing/observability |
