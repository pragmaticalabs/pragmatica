# RBAC Design Specification for Aether Distributed Runtime

**Version:** 1.0
**Target Release:** 0.18.0 (Tier 1 - API Keys)
**Date:** 2026-02-23
**Status:** DRAFT

---

## Table of Contents

1. [Overview](#1-overview)
2. [Existing Infrastructure Inventory](#2-existing-infrastructure-inventory)
3. [Tier 1: API Key Security (0.18.0)](#3-tier-1-api-key-security-0180)
4. [TOML Configuration Format](#4-toml-configuration-format)
5. [Wiring SecurityValidator into AppHttpServer](#5-wiring-securityvalidator-into-apphttpserver)
6. [Principal Propagation in InvocationContext](#6-principal-propagation-in-invocationcontext)
7. [Default Security Policies per Route Category](#7-default-security-policies-per-route-category)
8. [CLI Authentication](#8-cli-authentication)
9. [Dashboard and WebSocket Authentication](#9-dashboard-and-websocket-authentication)
10. [Inter-Slice Security Context Propagation](#10-inter-slice-security-context-propagation)
11. [Audit Logging](#11-audit-logging)
12. [Management Server Security](#12-management-server-security)
13. [Error Responses](#13-error-responses)
14. [Testing Strategy](#14-testing-strategy)
15. [Migration Path](#15-migration-path)
16. [Tier 2: External Identity Provider (Future)](#16-tier-2-external-identity-provider-future)
17. [Tier 3: Built-in User Management (Out of Scope)](#17-tier-3-built-in-user-management-out-of-scope)
18. [Open Questions](#18-open-questions)
19. [References](#19-references)

---

## 1. Overview

### 1.1 Problem Statement

Aether has a comprehensive security type system already implemented (SecurityContext, Principal, Role, ApiKey, RouteSecurityPolicy, SecurityValidator) but **none of it is wired into the actual request handling pipeline**. The `AppHttpServer` constructs an `ApiKeySecurityValidator` from config but never invokes it. The `ManagementServer` has no security at all. All endpoints are open.

### 1.2 Goals

- **REQ-001:** Wire existing security infrastructure into the request pipeline with zero new security primitives needed.
- **REQ-002:** Propagate Principal through InvocationContext ScopedValues so every log, trace, and metric can be tagged with WHO made the request.
- **REQ-003:** Secure management API endpoints by default while keeping application routes open by default.
- **REQ-004:** Support configuration-driven API key management with per-key roles.
- **REQ-005:** Maintain backward compatibility -- existing deployments without security config continue to work unchanged.
- **REQ-006:** Provide audit trail for authenticated operations.

### 1.3 Non-Goals

- Token issuance. Aether never issues credentials.
- Password management, session management, MFA.
- Fine-grained per-field authorization.
- Rate limiting (this spec enables it via Principal propagation, but does not implement it).

### 1.4 Tiered Approach Summary

| Tier | Scope | Target |
|------|-------|--------|
| **Tier 1** | API Keys wired into pipeline, Principal in InvocationContext, audit logging | 0.18.0 |
| **Tier 2** | JWT validation from external IdP, JwtSecurityPolicy variant | Future |
| **Tier 3** | Built-in user management | Explicitly out of scope (probably never) |

---

## 2. Existing Infrastructure Inventory

All security types are already implemented and tested. This section maps each type to its file, current state, and what needs to change.

### 2.1 Types (http-handler-api module) -- NO CHANGES NEEDED

| Type | File | Lines | State |
|------|------|-------|-------|
| `SecurityContext` | `aether/http-handler-api/.../security/SecurityContext.java` | 1-108 | Complete. Factory methods for anonymous, API key, bearer token contexts. Role checking. |
| `Principal` | `aether/http-handler-api/.../security/Principal.java` | 1-96 | Complete. PrincipalType enum (API_KEY, USER, SERVICE). ANONYMOUS constant. Prefix-based type detection. |
| `Role` | `aether/http-handler-api/.../security/Role.java` | 1-59 | Complete. ADMIN, USER, SERVICE constants. Validation. |
| `ApiKey` | `aether/http-handler-api/.../security/ApiKey.java` | 1-78 | Complete. Alphanumeric 8-64 chars. Pattern validation. |
| `RouteSecurityPolicy` | `aether/http-handler-api/.../security/RouteSecurityPolicy.java` | 1-66 | Complete. Sealed interface: `Public()`, `ApiKeyRequired()`. Serialization to/from string for KV-Store. |
| `HttpRequestContext` | `aether/http-handler-api/.../handler/HttpRequestContext.java` | 1-132 | Complete. Already carries `SecurityContext security` field. Has `withSecurity()` for immutable copy. Has `isAuthenticated()` and `hasRole()`. |
| `HttpRouteDefinition` | `aether/http-handler-api/.../handler/HttpRouteDefinition.java` | 1-80 | Complete. Already carries `RouteSecurityPolicy security` field. Default factory creates `publicRoute()`. |

### 2.2 Validators (node module) -- NO CHANGES NEEDED

| Type | File | Lines | State |
|------|------|-------|-------|
| `SecurityValidator` | `aether/node/.../security/SecurityValidator.java` | 1-36 | Complete. Interface with `validate(HttpRequestContext, RouteSecurityPolicy)`. Factory methods: `apiKeyValidator(Set<String>)`, `noOpValidator()`. |
| `ApiKeySecurityValidator` | `aether/node/.../security/ApiKeySecurityValidator.java` | 1-61 | Complete. Checks `X-API-Key` header (case-insensitive). Validates against configured key set. Returns `SecurityContext` with API_KEY principal and SERVICE role. |
| `SecurityError` | `aether/node/.../security/SecurityError.java` | 1-20 | Complete. Sealed interface: `MissingCredentials`, `InvalidCredentials`. Pre-defined constants for missing/invalid API key. |

### 2.3 Infrastructure -- CHANGES NEEDED

| Type | File | Lines | Change Required |
|------|------|-------|-----------------|
| `AppHttpServer` / `AppHttpServerImpl` | `aether/node/.../http/AppHttpServer.java` | 1-911 | **MAJOR**: Wire `securityValidator` into `handleLocalRoute()` and `handleRemoteRoute()`. Currently constructs validator on line 171-173 but never calls it. |
| `InvocationContext` | `aether/aether-invoke/.../invoke/InvocationContext.java` | 1-196 | **MAJOR**: Add `PRINCIPAL` ScopedValue. Add `runWithContext()` overloads accepting Principal. Add MDC key "principal". |
| `AppHttpConfig` | `aether/aether-config/.../config/AppHttpConfig.java` | 1-110 | **MODERATE**: Add `Map<String, ApiKeyConfig>` for per-key role assignments. Current `Set<String> apiKeys` only stores raw key strings. |
| `ConfigLoader` | `aether/aether-config/.../config/ConfigLoader.java` | 1-306 | **MODERATE**: Add `populateAppHttpConfig()` method to parse `[app-http]` and `[app-http.api-keys]` TOML sections. |
| `ManagementServer` / `ManagementServerImpl` | `aether/node/.../api/ManagementServer.java` | 1-367 | **MODERATE**: Add SecurityValidator. Validate requests in `handleRequest()`. |
| `AetherCli` | `aether/cli/.../cli/AetherCli.java` | 1-2045 | **MODERATE**: Add `--api-key` option. Attach `X-API-Key` header to all HTTP requests. |
| `DashboardWebSocketHandler` | `aether/node/.../api/DashboardWebSocketHandler.java` | 1-81 | **MINOR**: Validate API key on `onOpen()` from query param or first message. |

### 2.4 Tests (already implemented)

| Test | File | Coverage |
|------|------|----------|
| `SecurityValueObjectsTest` | `aether/http-handler-api/.../security/SecurityValueObjectsTest.java` | Principal, Role, ApiKey, RouteSecurityPolicy, SecurityContext -- all value object behavior. 23 tests. |
| `ApiKeySecurityValidatorTest` | `aether/node/.../security/ApiKeySecurityValidatorTest.java` | Public route access, valid key, invalid key, missing key, case-insensitive header, no-op validator. 6 tests. |

---

## 3. Tier 1: API Key Security (0.18.0)

### 3.1 Design Principles

- **REQ-010:** Security is opt-in. If no API keys are configured, all endpoints remain open. The `noOpValidator()` path is preserved.
- **REQ-011:** When security IS enabled (api keys configured), management endpoints require API key by default.
- **REQ-012:** Application routes remain public by default. Slice developers can set `RouteSecurityPolicy.apiKeyRequired()` on their `HttpRouteDefinition`.
- **REQ-013:** Health/readiness endpoints are always public (required for orchestrators).
- **REQ-014:** API keys support per-key role assignment. A key can be ADMIN, USER, SERVICE, or any combination.

### 3.2 API Key Model

An API key configuration entry has:

```
key_value  -> the secret string (8-64 alphanumeric + underscore/hyphen)
name       -> human-readable identifier (used as Principal value)
roles      -> set of roles granted to this key
```

The `name` becomes the Principal value (prefixed with `api-key:` by `Principal.principal(name, PrincipalType.API_KEY)`). This means audit logs show `api-key:deploy-bot` rather than the raw key value.

### 3.3 AppHttpConfig Changes

**File:** `aether/aether-config/src/main/java/org/pragmatica/aether/config/AppHttpConfig.java`

Current state (line 16-20):
```java
public record AppHttpConfig(boolean enabled,
                            int port,
                            Set<String> apiKeys,      // <-- flat set of raw key strings
                            long forwardTimeoutMs,
                            int forwardMaxRetries)
```

**Required change:** Replace `Set<String> apiKeys` with a richer structure that maps key values to their metadata.

New type to add in the `config` module:

```java
/// Configuration for a single API key.
///
/// @param name  human-readable identifier (becomes Principal value)
/// @param roles roles granted to this key
public record ApiKeyEntry(String name, Set<String> roles) {
    public static ApiKeyEntry apiKeyEntry(String name, Set<String> roles) {
        return new ApiKeyEntry(name, Set.copyOf(roles));
    }

    /// Default entry: name derived from key prefix, SERVICE role only.
    public static ApiKeyEntry defaultEntry(String keyValue) {
        var name = keyValue.length() > 8
                   ? keyValue.substring(0, 8) + "..."
                   : keyValue;
        return apiKeyEntry(name, Set.of("service"));
    }
}
```

Updated `AppHttpConfig`:

```java
public record AppHttpConfig(boolean enabled,
                            int port,
                            Map<String, ApiKeyEntry> apiKeys,  // key_value -> entry
                            long forwardTimeoutMs,
                            int forwardMaxRetries) {
    // ...
    public boolean securityEnabled() {
        return !apiKeys.isEmpty();
    }

    // Backward compatibility: extract raw key set for SecurityValidator
    public Set<String> apiKeyValues() {
        return Set.copyOf(apiKeys.keySet());
    }
}
```

**Backward compatibility:** The existing `Set<String>` factory methods (`appHttpConfig(int port, Set<String> apiKeys)`) are preserved but wrap each key string with `ApiKeyEntry.defaultEntry(keyValue)`.

---

## 4. TOML Configuration Format

### 4.1 New Configuration Section

**File to modify:** `aether/aether-config/src/main/java/org/pragmatica/aether/config/ConfigLoader.java`

Add `populateAppHttpConfig()` to `populateBuilder()` (after line 89).

TOML format:

```toml
[app-http]
enabled = true
port = 8070
forward_timeout_ms = 5000
forward_max_retries = 2

# Simple format: list of key strings (backward compatible)
# All keys get default name and SERVICE role
# api_keys = ["my-secret-key-12345", "another-key-67890"]

# Rich format: named keys with roles
[app-http.api-keys.admin-key-abc12345]
name = "cluster-admin"
roles = ["admin", "service"]

[app-http.api-keys.deploy-bot-key-xyz]
name = "deploy-bot"
roles = ["service"]

[app-http.api-keys.dashboard-key-123]
name = "dashboard"
roles = ["user"]
```

### 4.2 Environment Variable Override

For containerized deployments where TOML editing is impractical:

```
AETHER_API_KEYS=key1:name1:role1,role2;key2:name2:role3
```

Format: semicolon-separated entries, each entry is `keyValue:name:comma-separated-roles`.

**Parsing priority (highest first):**
1. Environment variable `AETHER_API_KEYS`
2. TOML `[app-http.api-keys.*]` sections
3. TOML `app-http.api_keys` string array (backward compat)

### 4.3 Management Server Configuration

```toml
[management]
port = 8080
# When app-http.api-keys is configured, management API requires
# API key by default. This flag overrides that behavior.
# security = "inherit"  # default: use app-http.api-keys
# security = "disabled" # explicitly disable for management
# security = "required" # always require, even if app-http has no keys
```

---

## 5. Wiring SecurityValidator into AppHttpServer

### 5.1 Current Flow (no security)

**File:** `aether/node/src/main/java/org/pragmatica/aether/http/AppHttpServer.java`

```
handleRequest (line 253)
  -> handleRequestInScope (line 259)
    -> findMatchingLocalRoute / findMatchingRemoteRoute
    -> handleLocalRoute (line 342)
      -> invokeLocalRouter (line 360)
        -> toHttpRequestContext (line 379)  // CREATES HttpRequestContext WITH ANONYMOUS SECURITY
        -> router.handle(context)
    -> handleRemoteRoute (line 392)
      -> forwardRequestWithRetry
        -> forwardRequestInternal (line 548)
          -> toHttpRequestContext (line 564)  // AGAIN: ANONYMOUS SECURITY
          -> serialize and forward
```

The critical observation: `toHttpRequestContext()` at line 379-389 always creates an anonymous context:

```java
private HttpRequestContext toHttpRequestContext(RequestContext request, String requestId) {
    return HttpRequestContext.httpRequestContext(request.path(),
                                                 request.method().name(),
                                                 request.queryParams().asMap(),
                                                 request.headers().asMap(),
                                                 request.body(),
                                                 requestId);
    // ^^ This calls the factory that uses SecurityContext.securityContext() = ANONYMOUS
}
```

### 5.2 New Flow (with security)

**REQ-050:** Security validation happens BEFORE route dispatch, not inside route handling. This ensures even "route not found" responses are security-aware.

**REQ-051:** The validator is called once per incoming HTTP request, producing a `SecurityContext`. That context is threaded through the entire request lifecycle.

Modified `handleRequestInScope`:

```java
private void handleRequestInScope(RequestContext request, ResponseWriter response, String requestId) {
    var method = request.method().name();
    var path = request.path();
    log.trace("Received {} {} [{}]", method, path, requestId);

    var routeTable = routeTableRef.get();
    var normalizedPath = normalizePath(path);

    // Step 1: Determine the route's security policy
    var policy = resolveSecurityPolicy(method, normalizedPath, routeTable);

    // Step 2: Build preliminary HttpRequestContext (needed by SecurityValidator)
    var preliminaryContext = toHttpRequestContext(request, requestId);

    // Step 3: Validate security
    var validationResult = securityValidator.validate(preliminaryContext, policy);

    if (validationResult.isFailure()) {
        handleSecurityFailure(response, validationResult, path, requestId);
        return;
    }

    // Step 4: Create authenticated context
    var securityContext = validationResult.unwrap();
    var authenticatedContext = preliminaryContext.withSecurity(securityContext);

    // Step 5: Dispatch to route handler with authenticated context
    dispatchToRoute(request, response, authenticatedContext, routeTable,
                    method, normalizedPath, requestId);
}
```

### 5.3 Security Policy Resolution

**REQ-052:** Route security policy is determined by the route's `HttpRouteDefinition.security()` field, which is stored in the KV-Store alongside the route. For management routes, the policy is always `ApiKeyRequired` when security is enabled.

```java
private RouteSecurityPolicy resolveSecurityPolicy(String method,
                                                    String normalizedPath,
                                                    RouteTable routeTable) {
    // Health/readiness endpoints are always public
    if (isHealthEndpoint(normalizedPath)) {
        return RouteSecurityPolicy.publicRoute();
    }

    // Look up the route's declared policy
    // For local routes, get it from HttpRoutePublisher's LocalRouteInfo
    var localRouteInfo = httpRoutePublisher
        .flatMap(pub -> pub.findLocalRoute(method, normalizedPath));

    if (localRouteInfo.isPresent()) {
        return localRouteInfo.unwrap().security();
    }

    // For remote routes, the default is Public (policy is enforced at the
    // destination node, not the forwarding node)
    return RouteSecurityPolicy.publicRoute();
}

private boolean isHealthEndpoint(String normalizedPath) {
    return normalizedPath.startsWith("/health/") ||
           normalizedPath.startsWith("/readiness/") ||
           normalizedPath.startsWith("/liveness/");
}
```

### 5.4 Security Failure Handling

```java
private void handleSecurityFailure(ResponseWriter response,
                                    Result<SecurityContext> validationResult,
                                    String path,
                                    String requestId) {
    var cause = validationResult.cause();
    var status = switch (cause) {
        case SecurityError.MissingCredentials _ -> HttpStatus.UNAUTHORIZED;
        case SecurityError.InvalidCredentials _ -> HttpStatus.FORBIDDEN;
        default -> HttpStatus.UNAUTHORIZED;
    };
    log.warn("Security validation failed [{}]: {} {}", requestId, status, cause.message());
    sendProblem(response, status, cause.message(), path, requestId);
}
```

### 5.5 Updated Route Dispatch

The dispatch methods now receive an already-authenticated `HttpRequestContext`:

```java
private void dispatchToRoute(RequestContext request,
                              ResponseWriter response,
                              HttpRequestContext authenticatedContext,
                              RouteTable routeTable,
                              String method,
                              String normalizedPath,
                              String requestId) {
    // Try local route
    if (httpRoutePublisher.isPresent()) {
        var localRouteOpt = findMatchingLocalRoute(routeTable.localRoutes(), method, normalizedPath);
        if (localRouteOpt.isPresent()) {
            handleLocalRouteAuthenticated(response, localRouteOpt.unwrap(),
                                          authenticatedContext, requestId);
            return;
        }
    }
    // Try remote route
    var remoteRouteOpt = findMatchingRemoteRoute(routeTable.remoteRoutes(), method, normalizedPath);
    if (remoteRouteOpt.isPresent()) {
        handleRemoteRouteAuthenticated(request, response, remoteRouteOpt.unwrap(),
                                        authenticatedContext, requestId);
        return;
    }
    // No route found (same logic as current)
    // ...
}
```

### 5.6 Changes to invokeLocalRouter

**Current (line 360-377):**
```java
private void invokeLocalRouter(RequestContext request,
                               ResponseWriter response,
                               SliceRouter router,
                               String requestId) {
    var context = toHttpRequestContext(request, requestId);  // ANONYMOUS
    router.handle(context)
          .onSuccess(responseData -> sendResponse(response, responseData, requestId))
          // ...
}
```

**New:**
```java
private void invokeLocalRouterAuthenticated(ResponseWriter response,
                                             SliceRouter router,
                                             HttpRequestContext authenticatedContext,
                                             String requestId) {
    // Context already has security populated
    router.handle(authenticatedContext)
          .onSuccess(responseData -> sendResponse(response, responseData, requestId))
          .onFailure(cause -> {
              log.error("Failed to handle local route [{}]: {}", requestId, cause.message());
              sendProblem(response, HttpStatus.INTERNAL_SERVER_ERROR,
                          "Request processing failed: " + cause.message(),
                          authenticatedContext.path(), requestId);
          });
}
```

### 5.7 Changes to forwardRequestInternal

**Current (line 548-600):** The forwarding path creates a new `HttpRequestContext` from the raw `RequestContext` (line 564). The forwarded context must carry the already-validated `SecurityContext`.

**New:** Accept `HttpRequestContext` (already authenticated) instead of building a new one:

```java
private void forwardRequestInternal(HttpRequestContext authenticatedContext,
                                     ResponseWriter response,
                                     NodeId targetNode,
                                     String requestId,
                                     Runnable onFailure) {
    // authenticatedContext already has SecurityContext populated
    // Serialize it -- SecurityContext is part of HttpRequestContext and will
    // be deserialized on the receiving node
    byte[] requestData;
    try {
        requestData = ser.encode(authenticatedContext);
    } catch (Exception e) {
        // ...
    }
    // ... rest of forwarding logic unchanged
}
```

**REQ-053:** The receiving node (in `onHttpForwardRequest`, line 612-652) does NOT re-validate the forwarded request. The originating node already validated it. The deserialized `HttpRequestContext` carries the authenticated `SecurityContext` intact.

This is secure because:
- Cluster communication is internal (not exposed to external traffic).
- Cluster communication uses the consensus protocol (Raft), which already has node authentication via cluster membership.
- [ASSUMPTION] TLS between nodes encrypts forwarded data in transit.

---

## 6. Context Propagation in InvocationContext

### 6.1 Design Principle: Propagate Identity and Provenance, Not Authorization State

`SecurityContext` carries three categories of data: Principal, Roles, and Claims. Not all of these belong in the invocation context that propagates through the call chain.

| Field | Propagate? | Rationale |
|-------|-----------|-----------|
| **Principal** (type + identity) | **Yes** (ScopedValue + MDC) | WHO made the request. Essential for traces, logs, audit, rate limiting. Principal.type (API_KEY vs USER vs SERVICE) distinguishes machine-to-machine from human traffic -- useful for metrics segmentation. |
| **Roles** | **No** | Authorization is a point-in-time decision at the entry point. Downstream slices must NOT make auth decisions based on upstream roles -- that's a confused deputy antipattern. Each slice should make its own auth decisions based on the Principal if needed. |
| **Claims** | **Selective (future)** | Most claims are auth-time-only. One future exception: tenant ID for multi-tenancy. Not needed for Tier 1. |
| **Origin node ID** | **Yes** (ScopedValue + MDC) | WHERE the request entered the cluster. Combined with Principal, gives full provenance. Useful for debugging routing issues and geographic traffic analysis. |

**Key rule:** Propagate identity and provenance. Never propagate authorization state.

### 6.2 Current InvocationContext ScopedValues

**File:** `aether/aether-invoke/src/main/java/org/pragmatica/aether/invoke/InvocationContext.java`

Current ScopedValues (lines 40-42):
```java
private static final ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();
private static final ScopedValue<Integer> DEPTH = ScopedValue.newInstance();
private static final ScopedValue<Boolean> SAMPLED = ScopedValue.newInstance();
```

MDC key (line 43):
```java
private static final String MDC_KEY = "requestId";
```

### 6.3 Adding PRINCIPAL and ORIGIN_NODE ScopedValues

**REQ-060:** Add a `PRINCIPAL` ScopedValue that carries the Principal from the SecurityContext through the entire invocation chain.

**REQ-061:** Add an `ORIGIN_NODE` ScopedValue that carries the ID of the node that first received the request. This provides provenance -- combined with Principal, every trace entry shows WHO requested it and WHERE it entered the cluster.

New fields:
```java
private static final ScopedValue<String> PRINCIPAL = ScopedValue.newInstance();
private static final ScopedValue<String> ORIGIN_NODE = ScopedValue.newInstance();
private static final String MDC_PRINCIPAL_KEY = "principal";
private static final String MDC_ORIGIN_NODE_KEY = "originNode";
```

After this change, the full set of InvocationContext ScopedValues is:
```
REQUEST_ID   (existing) -- unique request identifier
DEPTH        (existing) -- invocation chain depth
SAMPLED      (existing) -- tracing sample flag
PRINCIPAL    (new)      -- WHO made the request (e.g., "api-key:deploy-bot")
ORIGIN_NODE  (new)      -- WHERE the request entered the cluster (node ID)
```

And MDC keys: `requestId`, `principal`, `originNode` -- every log line automatically tagged with all three.

### 6.4 Updated runWithContext Methods

The `runWithContext` methods (lines 117-146) get overloads that accept Principal and origin node:

```java
/// Run a supplier within a full invocation context scope including principal and origin node.
public static <T> T runWithContext(String requestId, int depth, boolean sampled,
                                    String principal, String originNode,
                                    Supplier<T> supplier) {
    MDC.put(MDC_KEY, requestId);
    MDC.put(MDC_PRINCIPAL_KEY, principal);
    MDC.put(MDC_ORIGIN_NODE_KEY, originNode);
    try {
        return ScopedValue.where(REQUEST_ID, requestId)
                          .where(DEPTH, depth)
                          .where(SAMPLED, sampled)
                          .where(PRINCIPAL, principal)
                          .where(ORIGIN_NODE, originNode)
                          .call(supplier::get);
    } finally {
        MDC.remove(MDC_KEY);
        MDC.remove(MDC_PRINCIPAL_KEY);
        MDC.remove(MDC_ORIGIN_NODE_KEY);
    }
}
```

Similarly for the `Runnable` variant and `runWithRequestId` variants.

### 6.5 Accessors

```java
/// Get the current principal if set.
public static Option<String> currentPrincipal() {
    return PRINCIPAL.isBound()
           ? Option.option(PRINCIPAL.get())
           : Option.empty();
}

/// Get the origin node ID if set.
public static Option<String> currentOriginNode() {
    return ORIGIN_NODE.isBound()
           ? Option.option(ORIGIN_NODE.get())
           : Option.empty();
}
```

### 6.6 ContextSnapshot Update

The `ContextSnapshot` record (line 170) gains `principal` and `originNode` fields:

```java
public record ContextSnapshot(String requestId, int depth, boolean sampled,
                               String principal, String originNode) {
    public <T> T runWithCaptured(Supplier<T> supplier) {
        if (requestId == null) {
            return supplier.get();
        }
        return runWithContext(requestId, depth, sampled, principal, originNode, supplier);
    }
    // ... similarly for Runnable variant
}
```

### 6.7 Integration Point: AppHttpServer.handleRequest

**Current (line 253-257):**
```java
private void handleRequest(RequestContext request, ResponseWriter response) {
    var requestId = request.requestId();
    InvocationContext.runWithRequestId(requestId,
        () -> handleRequestInScope(request, response, requestId));
}
```

**New:** After security validation produces a `SecurityContext`, the principal and origin node are added to the InvocationContext:

```java
private void handleRequest(RequestContext request, ResponseWriter response) {
    var requestId = request.requestId();
    // Initial scope without principal (security not yet validated)
    InvocationContext.runWithRequestId(requestId,
        () -> {
            var securityContext = validateAndGetSecurityContext(request, requestId);
            var principal = securityContext.principal().value();
            var originNode = nodeId.toString(); // this node received the request
            // Re-enter scope with principal and origin node
            InvocationContext.runWithContext(requestId, 0, false, principal, originNode,
                () -> {
                    handleRequestInScope(request, response, requestId, securityContext);
                    return null;
                });
        });
}
```

**Important:** Use the ScopedValue approach (not MDC-only) because Aether already uses `ContextSnapshot.runWithCaptured()` for async propagation across thread boundaries. MDC alone would not survive virtual thread handoffs.

### 6.8 Origin Node on Forwarded Requests

When a request is forwarded from node A to node B, the origin node remains node A (where the request first entered the cluster). The receiving node must preserve the original origin node, NOT replace it with its own ID:

```java
// On the receiving node (onHttpForwardRequest):
var principal = context.security().principal().value();
var originNode = extractOriginNode(request); // from forwarded headers, NOT nodeId
InvocationContext.runWithContext(requestId, 1, false, principal, originNode, () -> {
    // process forwarded request
});
```

**REQ-062:** The origin node ID is propagated via an `X-Aether-Origin-Node` header on forwarded requests. The originating node sets this header; receiving nodes read it instead of using their own node ID.

---

## 7. Default Security Policies per Route Category

### 7.1 Route Categories

| Category | Path Pattern | Default Policy (no security configured) | Default Policy (security enabled) |
|----------|-------------|----------------------------------------|----------------------------------|
| **Health** | `/health/*`, `/readiness/*`, `/liveness/*` | `Public` | `Public` |
| **Management API** | `/api/*`, `/status`, `/nodes`, `/slices`, `/metrics`, `/scale`, `/repository/*`, `/alerts/*`, `/thresholds/*`, `/rolling-update/*`, `/invocation-metrics/*`, `/controller/*` | `Public` | `ApiKeyRequired` |
| **Dashboard** | `/dashboard`, `/dashboard/*` | `Public` | `ApiKeyRequired` |
| **WebSocket** | `/ws/dashboard`, `/ws/status`, `/ws/events` | `Public` | `ApiKeyRequired` |
| **Application routes** | Slice-defined paths | `Public` (or slice-declared) | `Public` (or slice-declared) |

### 7.2 Rationale

- **REQ-070:** Health endpoints must always be public. Kubernetes, load balancers, and monitoring tools probe these without credentials.
- **REQ-071:** Management endpoints control the cluster (deploy, scale, update, config changes). When security is enabled, these must require authentication.
- **REQ-072:** Application routes default to public because slice developers have their own security requirements and may use framework-level security (e.g., Spring Security inside a slice). They can opt into API key protection by setting `RouteSecurityPolicy.apiKeyRequired()` on their `HttpRouteDefinition`.

### 7.3 Role-Based Access for Management Endpoints

When Tier 1 is implemented, the role check is simple: **any authenticated principal can access management endpoints**. Per-endpoint role restrictions are deferred to Tier 2 when JWT claims provide richer role information.

[ASSUMPTION] For Tier 1, the role hierarchy is flat. A key with `SERVICE` role can do everything a key with `ADMIN` role can do, within the scope of "authenticated access to management API." The role information is recorded in audit logs for future filtering.

**Future consideration (Tier 2):** Introduce per-endpoint required roles:

| Endpoint Category | Required Role |
|-------------------|---------------|
| Read-only (status, nodes, metrics) | `USER` or `SERVICE` or `ADMIN` |
| Mutating (scale, deploy, update, config) | `ADMIN` |
| Observability (traces, logging, alerts) | `USER` or `ADMIN` |

---

## 8. CLI Authentication

### 8.1 Current State

**File:** `aether/cli/src/main/java/org/pragmatica/aether/cli/AetherCli.java`

The CLI builds HTTP requests at lines 241-286 using `java.net.http.HttpClient`. None of the request builders (`buildGetRequest`, `buildPostRequest`, `buildPutRequest`, `buildDeleteRequest`) attach an `X-API-Key` header.

### 8.2 Changes Required

**REQ-080:** Add `--api-key` command-line option to `AetherCli`.

```java
@CommandLine.Option(names = {"--api-key", "-k"},
    description = "API key for authentication")
private String apiKey;
```

**REQ-081:** Add environment variable support: `AETHER_API_KEY`.

**REQ-082:** Add config file support: read from `[cli]` section in `aether.toml`.

```toml
[cli]
api_key = "my-admin-key-12345"
```

### 8.3 Resolution Priority

1. `--api-key` flag (highest)
2. `AETHER_API_KEY` environment variable
3. `[cli].api_key` in config file
4. No key (unauthenticated)

### 8.4 Applying the Key

All request builder methods gain the API key header:

```java
private HttpRequest buildGetRequest(URI uri) {
    var builder = HttpRequest.newBuilder().uri(uri).GET();
    attachApiKey(builder);
    return builder.build();
}

private void attachApiKey(HttpRequest.Builder builder) {
    resolveApiKey().onPresent(key -> builder.header("X-API-Key", key));
}

private Option<String> resolveApiKey() {
    // Priority: flag > env > config
    return option(apiKey)
        .or(() -> option(System.getenv("AETHER_API_KEY")))
        .or(() -> readApiKeyFromConfig());
}
```

### 8.5 Error Handling

When the CLI receives a 401/403 response from a secured endpoint:

```
Error: Authentication required. Use --api-key or set AETHER_API_KEY environment variable.
```

When the CLI receives a 403 with an invalid key:

```
Error: Invalid API key. Check your --api-key value or AETHER_API_KEY environment variable.
```

---

## 9. Dashboard and WebSocket Authentication

### 9.1 HTTP Dashboard

**REQ-090:** The dashboard HTML page at `/dashboard` is served as a static resource. When security is enabled, accessing `/dashboard` requires API key authentication via `X-API-Key` header or `?api_key=...` query parameter.

**REQ-091:** The dashboard JavaScript stores the API key in the browser's `sessionStorage` (not `localStorage`) and includes it in subsequent API calls and WebSocket connections.

### 9.2 WebSocket Authentication

**File:** `aether/node/src/main/java/org/pragmatica/aether/api/DashboardWebSocketHandler.java`

WebSocket connections cannot use custom headers during the handshake (browser limitation). Two authentication mechanisms:

**REQ-092 (Primary):** Query parameter authentication for WebSocket upgrade:
```
ws://host:8080/ws/dashboard?api_key=my-secret-key-12345
```

**REQ-093 (Alternative):** First-message authentication. The client sends an auth message immediately after connection:
```json
{"type":"AUTH","apiKey":"my-secret-key-12345"}
```

### 9.3 WebSocket Handler Changes

**File:** `aether/node/src/main/java/org/pragmatica/aether/api/DashboardWebSocketHandler.java`

The `onOpen()` method (line 38-43) currently unconditionally adds the session and sends initial state. When security is enabled:

```java
private void onOpen(WebSocketSession session) {
    if (!securityEnabled) {
        acceptSession(session);
        return;
    }
    // Try query param auth
    var apiKeyParam = session.queryParam("api_key");
    if (apiKeyParam.isPresent()) {
        var result = securityValidator.validateKey(apiKeyParam.unwrap());
        if (result.isSuccess()) {
            acceptSession(session);
            return;
        }
    }
    // Mark as pending auth -- will be authenticated on first message
    pendingSessions.put(session.id(), session);
    session.send("{\"type\":\"AUTH_REQUIRED\"}");
}
```

**REQ-094:** If authentication is not completed within 5 seconds of connection, the session is closed with code 4001 (Authentication Timeout).

### 9.4 WebSocket Endpoint Configuration

**File:** `aether/node/src/main/java/org/pragmatica/aether/api/ManagementServer.java` (lines 162-170)

WebSocket endpoints are configured in `ManagementServerImpl.start()`. The `WebSocketEndpoint` record currently only has `path` and `handler`. No change to `WebSocketEndpoint` is needed -- the handler itself manages authentication.

---

## 10. Inter-Slice Security Context Propagation

### 10.1 Local Inter-Slice Calls

When a slice invokes another slice on the same node, the InvocationContext's `PRINCIPAL` ScopedValue is automatically available to the callee because ScopedValues propagate through the call stack.

No additional work needed.

### 10.2 Remote Inter-Slice Calls (HTTP Forwarding)

**File:** `aether/node/src/main/java/org/pragmatica/aether/http/forward/HttpForwardMessage.java`

When a request is forwarded to another node, the `HttpRequestContext` is serialized (line 567-569 of AppHttpServer). Since `HttpRequestContext` already carries a `SecurityContext security` field, the principal propagates through serialization.

**REQ-100:** On the receiving node, when `onHttpForwardRequest` (line 612) deserializes the `HttpRequestContext`, it must set the Principal from the deserialized SecurityContext into the InvocationContext:

```java
@Override
public void onHttpForwardRequest(HttpForwardRequest request) {
    // ... deserialize context ...
    var principal = context.security().principal().value();
    InvocationContext.runWithContext(request.requestId(), 1, false, principal, () -> {
        // ... process request with principal in scope ...
        routerOpt.unwrap().handle(context)
                 .onSuccess(responseData -> sendForwardSuccess(network, request, ser, responseData))
                 .onFailure(cause -> sendForwardError(network, request, cause.message()));
    });
}
```

### 10.3 HTTP Header Propagation (External Calls)

**REQ-101:** When a slice makes an outbound HTTP call to an external service, the principal is NOT automatically propagated. Slices that need to propagate identity must explicitly read from `InvocationContext.currentPrincipal()` and set appropriate headers on their outbound requests.

This is by design -- external propagation must be explicit because:
- The external service may use a different identity format.
- Leaking internal principal identifiers externally is a security concern.
- The slice may need to use a different credential for the external call.

---

## 11. Audit Logging

### 11.1 What Gets Logged

**REQ-110:** The following events produce audit log entries:

| Event | Log Level | Fields |
|-------|-----------|--------|
| Successful authentication | `INFO` | requestId, principal, method, path, sourceIP |
| Failed authentication (missing key) | `WARN` | requestId, method, path, sourceIP, reason |
| Failed authentication (invalid key) | `WARN` | requestId, method, path, sourceIP, reason |
| Management API access | `INFO` | requestId, principal, method, path, response_status |
| Slice deployment/undeployment | `INFO` | requestId, principal, artifact, action |
| Configuration change | `INFO` | requestId, principal, config_key, old_value, new_value |
| WebSocket connection with auth | `INFO` | sessionId, principal, endpoint |
| WebSocket auth failure | `WARN` | sessionId, endpoint, reason |

### 11.2 Log Format

Audit log entries use structured logging via SLF4J MDC. The `principal` and `originNode` keys are added alongside `requestId`:

```
2026-02-23T10:15:32.456Z INFO  [req=2aBcDe...] [principal=api-key:cluster-admin] [origin=node-1] Security: authenticated via API key for GET /api/status
2026-02-23T10:15:33.789Z WARN  [req=3fGhIj...] [principal=anonymous] [origin=node-2] Security: missing API key for POST /api/blueprint
2026-02-23T10:15:34.012Z INFO  [req=4kLmNo...] [principal=api-key:deploy-bot] [origin=node-1] Management: POST /scale {"artifact":"org.example:my-slice:1.0.0","instances":3} -> 200
```

### 11.3 Dedicated Audit Logger

**REQ-111:** Create a dedicated audit logger named `org.pragmatica.aether.audit` that can be independently configured (e.g., routed to a separate file or external system).

```java
public final class AuditLog {
    private static final Logger AUDIT = LoggerFactory.getLogger("org.pragmatica.aether.audit");

    private AuditLog() {}

    public static void authSuccess(String requestId, String principal,
                                    String method, String path) {
        AUDIT.info("[req={}] [principal={}] auth.success {} {}",
                   requestId, principal, method, path);
    }

    public static void authFailure(String requestId, String method, String path,
                                    String reason) {
        AUDIT.warn("[req={}] auth.failure {} {} reason={}",
                   requestId, method, path, reason);
    }

    public static void managementAccess(String requestId, String principal,
                                         String method, String path, int status) {
        AUDIT.info("[req={}] [principal={}] management.access {} {} -> {}",
                   requestId, principal, method, path, status);
    }
}
```

**File location:** `aether/node/src/main/java/org/pragmatica/aether/http/security/AuditLog.java`

---

## 12. Management Server Security

### 12.1 Current State

**File:** `aether/node/src/main/java/org/pragmatica/aether/api/ManagementServer.java`

The `ManagementServerImpl` has no security. The `handleRequest` method (line 350-366) directly dispatches to the router and legacy handlers.

### 12.2 Changes Required

**REQ-120:** `ManagementServerImpl` receives a `SecurityValidator` in its constructor.

**REQ-121:** The `handleRequest` method validates security before routing:

```java
private void handleRequest(RequestContext ctx, ResponseWriter response) {
    var path = ctx.path();
    var method = ctx.method();
    log.debug("Received {} {}", method, path);

    // Health endpoints bypass security
    if (isHealthEndpoint(path)) {
        dispatchRequest(ctx, response);
        return;
    }

    // Build request context for validation
    var requestContext = toHttpRequestContext(ctx);
    var policy = securityEnabled
                 ? RouteSecurityPolicy.apiKeyRequired()
                 : RouteSecurityPolicy.publicRoute();
    var result = securityValidator.validate(requestContext, policy);

    if (result.isFailure()) {
        handleSecurityFailure(response, result, path, ctx.requestId());
        return;
    }

    // Set principal in MDC
    var principal = result.unwrap().principal().value();
    MDC.put("principal", principal);

    AuditLog.managementAccess(ctx.requestId(), principal,
                               method.name(), path, 200);

    dispatchRequest(ctx, response);
}
```

### 12.3 Constructor Changes

The `ManagementServer.managementServer()` factory method needs `SecurityValidator` (or the config to construct one):

```java
static ManagementServer managementServer(int port,
                                          Supplier<AetherNode> nodeSupplier,
                                          SecurityValidator securityValidator,
                                          // ... rest of params
                                          ) {
    // ...
}
```

---

## 13. Error Responses

### 13.1 Security Error Response Format

All security error responses use RFC 9457 Problem Detail format (already used by Aether):

**401 Unauthorized (missing credentials):**
```json
{
  "type": "about:blank",
  "title": "Unauthorized",
  "status": 401,
  "detail": "X-API-Key header required",
  "instance": "/api/status",
  "requestId": "2aBcDeFgHiJk..."
}
```

**403 Forbidden (invalid credentials):**
```json
{
  "type": "about:blank",
  "title": "Forbidden",
  "status": 403,
  "detail": "Invalid API key",
  "instance": "/api/blueprint",
  "requestId": "3fGhIjKlMnOp..."
}
```

### 13.2 Mapping SecurityError to HTTP Status

| SecurityError | HTTP Status | Response Header |
|---------------|-------------|-----------------|
| `MissingCredentials` | 401 Unauthorized | `WWW-Authenticate: ApiKey realm="Aether"` |
| `InvalidCredentials` | 403 Forbidden | (none) |

**REQ-130:** The 401 response includes a `WWW-Authenticate` header indicating the expected authentication scheme.

---

## 14. Testing Strategy

### 14.1 Existing Tests (No Changes)

- `SecurityValueObjectsTest` (23 tests) -- value object validation
- `ApiKeySecurityValidatorTest` (6 tests) -- validator logic

### 14.2 New Unit Tests

**Test class:** `AppHttpServerSecurityTest`

| Test ID | Description |
|---------|-------------|
| T-001 | Request to public route without API key succeeds with anonymous SecurityContext |
| T-002 | Request to ApiKeyRequired route without API key returns 401 |
| T-003 | Request to ApiKeyRequired route with valid API key succeeds with authenticated SecurityContext |
| T-004 | Request to ApiKeyRequired route with invalid API key returns 403 |
| T-005 | Health endpoint always returns 200 regardless of security config |
| T-006 | Security disabled (no API keys) allows all requests through noOpValidator |
| T-007 | SecurityContext propagates through to SliceRouter.handle() |
| T-008 | SecurityContext propagates through HTTP forwarding (serialize/deserialize) |
| T-009 | Principal appears in MDC after successful authentication |
| T-010 | Forwarded request preserves SecurityContext on receiving node |

**Test class:** `ManagementServerSecurityTest`

| Test ID | Description |
|---------|-------------|
| T-020 | Management endpoint returns 401 when security enabled and no key provided |
| T-021 | Management endpoint returns 200 when security enabled and valid key provided |
| T-022 | Management endpoint returns 200 when security disabled (no keys configured) |
| T-023 | Health endpoint on management server bypasses security |

**Test class:** `InvocationContextPrincipalTest`

| Test ID | Description |
|---------|-------------|
| T-030 | Principal ScopedValue is bound within runWithContext |
| T-031 | Principal is absent outside of context scope |
| T-032 | ContextSnapshot captures and restores principal across threads |
| T-033 | MDC "principal" key is set within context scope |
| T-034 | MDC "principal" key is cleared after context scope exits |
| T-035 | Origin node ScopedValue is bound within runWithContext |
| T-036 | Origin node is absent outside of context scope |
| T-037 | ContextSnapshot captures and restores origin node across threads |
| T-038 | MDC "originNode" key is set within context scope and cleared after |
| T-039 | Forwarded request preserves origin node (does not replace with receiving node ID) |

**Test class:** `ConfigLoaderSecurityTest`

| Test ID | Description |
|---------|-------------|
| T-040 | Parse TOML with simple api_keys string list |
| T-041 | Parse TOML with rich api-keys sections (name + roles) |
| T-042 | Environment variable AETHER_API_KEYS overrides TOML |
| T-043 | Empty api_keys results in securityEnabled() == false |
| T-044 | Invalid key format in TOML produces config validation error |

**Test class:** `AuditLogTest`

| Test ID | Description |
|---------|-------------|
| T-050 | Successful auth produces INFO audit log entry |
| T-051 | Failed auth produces WARN audit log entry |
| T-052 | Management access is logged with principal, method, path, status |

### 14.3 Integration Tests (E2E)

Add to existing E2E test suite (80+ tests):

| Test ID | Description |
|---------|-------------|
| T-060 | Cluster with security enabled rejects unauthenticated management API calls |
| T-061 | Cluster with security enabled accepts authenticated management API calls |
| T-062 | Cluster without security config accepts all calls (backward compat) |
| T-063 | Application HTTP route works without API key when route policy is Public |
| T-064 | Application HTTP route requires API key when route policy is ApiKeyRequired |
| T-065 | CLI with --api-key flag successfully communicates with secured cluster |
| T-066 | HTTP forwarding preserves SecurityContext across nodes |
| T-067 | Principal appears in distributed traces |

### 14.4 Security-Specific Tests

| Test ID | Description |
|---------|-------------|
| T-070 | API key is NOT logged in any log output (ensure key masking) |
| T-071 | Different API keys resolve to different Principal values |
| T-072 | API key with ADMIN role and API key with SERVICE role both access management API in Tier 1 |
| T-073 | Case-insensitive header matching for X-API-Key (already tested, verify in integration) |

---

## 15. Migration Path

### 15.1 Upgrade Without Breaking

**REQ-150:** Existing deployments that upgrade to 0.18.0 continue to work without any configuration changes.

The migration is safe because:

1. **No API keys configured = no security enforced.** The `securityEnabled()` check (AppHttpConfig line 76-77) returns `false` when `apiKeys` is empty. The `noOpValidator()` returns anonymous SecurityContext for all requests.

2. **InvocationContext changes are additive.** New `PRINCIPAL` ScopedValue is unbound when no security context is set. `currentPrincipal()` returns `Option.empty()`. Existing code that doesn't use it is unaffected.

3. **MDC "principal" key is only set when security is active.** Log patterns that don't include `%X{principal}` see no change.

### 15.2 Enabling Security

To enable security, operators add API keys to their configuration:

**Step 1:** Generate API keys:
```bash
# Generate a random 32-character key
openssl rand -hex 16
# Output: a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6
```

**Step 2:** Add to `aether.toml`:
```toml
[app-http]
enabled = true
port = 8070

[app-http.api-keys.a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6]
name = "admin"
roles = ["admin", "service"]
```

**Step 3:** Configure CLI:
```bash
export AETHER_API_KEY=a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6
# Or: aether --api-key a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6 status
```

**Step 4:** Restart cluster nodes (rolling restart supported).

### 15.3 Rolling Upgrade Behavior

During a rolling upgrade where some nodes have security enabled and others don't:

- **Management API:** Each node enforces its own security independently. A request to a secured node requires API key; a request to an unsecured node does not.
- **HTTP Forwarding:** A secured originating node validates the request and forwards the authenticated context. The receiving node (whether secured or not) does not re-validate.
- **CLI:** The CLI should always send the API key if configured. Unsecured nodes ignore the `X-API-Key` header (noOpValidator treats all requests as anonymous).

**REQ-151:** Sending an API key to an unsecured node is a no-op, not an error.

---

## 16. Tier 2: External Identity Provider (Future)

### 16.1 Scope

- Aether validates JWTs issued by an external IdP (Keycloak, Auth0, Okta, etc.).
- Aether NEVER issues tokens.
- Zero credential storage in Aether.

### 16.2 New RouteSecurityPolicy Variant

Add to the sealed interface in `RouteSecurityPolicy.java`:

```java
/// JWT bearer token required.
record JwtRequired(Set<String> requiredRoles) implements RouteSecurityPolicy {}
```

### 16.3 New SecurityValidator Implementation

```java
class JwtSecurityValidator implements SecurityValidator {
    // Validates JWT signature using JWKS endpoint
    // Extracts claims, maps to SecurityContext with Role set
    // Caches JWKS for performance
}
```

### 16.4 Configuration

```toml
[security]
type = "jwt"  # or "api-key" (default)

[security.jwt]
issuer = "https://auth.example.com"
jwks_url = "https://auth.example.com/.well-known/jwks.json"
audience = "aether-cluster"
roles_claim = "realm_access.roles"
```

### 16.5 Composite Validator

When both API keys and JWT are configured, a composite validator tries API key first, then JWT:

```java
class CompositeSecurityValidator implements SecurityValidator {
    private final SecurityValidator apiKeyValidator;
    private final SecurityValidator jwtValidator;

    @Override
    public Result<SecurityContext> validate(HttpRequestContext request, RouteSecurityPolicy policy) {
        return switch (policy) {
            case Public() -> Result.success(SecurityContext.securityContext());
            case ApiKeyRequired() -> apiKeyValidator.validate(request, policy);
            case JwtRequired() -> jwtValidator.validate(request, policy);
            default -> Result.success(SecurityContext.securityContext());
        };
    }
}
```

---

## 17. Tier 3: Built-in User Management (Out of Scope)

Explicitly out of scope. Aether will never implement:
- User registration
- Password hashing and storage
- Session management
- Multi-factor authentication
- Password reset flows

Rationale: Every customer already has an identity provider. Building another one creates maintenance burden and security liability with zero customer value.

---

## 18. Open Questions

| ID | Question | Status |
|----|----------|--------|
| Q-001 | Should the `noOpValidator` still produce anonymous `SecurityContext`, or should it produce a "system" principal? Anonymous is simpler but means unsecured deployments have `anonymous` in all audit logs, which is low-information. | [TBD] |
| Q-002 | Should per-endpoint role restrictions be part of Tier 1 (e.g., ADMIN for mutating operations) or deferred entirely to Tier 2? | [DECISION: Deferred to Tier 2. Tier 1 is "authenticated = authorized for management API."] |
| Q-003 | Should the `SecurityContext` be serialized as part of `HttpForwardMessage.HttpForwardRequest.requestData` (current approach: it's inside HttpRequestContext) or as a separate field? Separate field would allow the receiving node to independently verify. | [DECISION: Keep inside HttpRequestContext. Cluster-internal trust model.] |
| Q-004 | Should API key rotation be supported (multiple keys active simultaneously with key expiration)? | [TBD -- not needed for 0.18.0, but the Map-based config structure supports it] |
| Q-005 | Should the WebSocket auth timeout (5 seconds) be configurable? | [TBD -- hardcoded is fine for now] |

---

## 19. References

### Internal References

| File | Module | Description |
|------|--------|-------------|
| `aether/http-handler-api/src/main/java/org/pragmatica/aether/http/handler/security/SecurityContext.java` | http-handler-api | Security context record with Principal, roles, claims |
| `aether/http-handler-api/src/main/java/org/pragmatica/aether/http/handler/security/Principal.java` | http-handler-api | Principal value object with type prefixing |
| `aether/http-handler-api/src/main/java/org/pragmatica/aether/http/handler/security/Role.java` | http-handler-api | Role value object (ADMIN, USER, SERVICE) |
| `aether/http-handler-api/src/main/java/org/pragmatica/aether/http/handler/security/ApiKey.java` | http-handler-api | API key value object with format validation |
| `aether/http-handler-api/src/main/java/org/pragmatica/aether/http/handler/security/RouteSecurityPolicy.java` | http-handler-api | Sealed interface: Public, ApiKeyRequired |
| `aether/http-handler-api/src/main/java/org/pragmatica/aether/http/handler/HttpRequestContext.java` | http-handler-api | Request context carrying SecurityContext |
| `aether/http-handler-api/src/main/java/org/pragmatica/aether/http/handler/HttpRouteDefinition.java` | http-handler-api | Route metadata with security policy |
| `aether/node/src/main/java/org/pragmatica/aether/http/security/SecurityValidator.java` | node | Validator interface with apiKeyValidator/noOpValidator factories |
| `aether/node/src/main/java/org/pragmatica/aether/http/security/ApiKeySecurityValidator.java` | node | X-API-Key header validation implementation |
| `aether/node/src/main/java/org/pragmatica/aether/http/security/SecurityError.java` | node | MissingCredentials, InvalidCredentials errors |
| `aether/node/src/main/java/org/pragmatica/aether/http/AppHttpServer.java` | node | Application HTTP server -- PRIMARY CHANGE TARGET |
| `aether/node/src/main/java/org/pragmatica/aether/api/ManagementServer.java` | node | Management HTTP server -- needs security addition |
| `aether/node/src/main/java/org/pragmatica/aether/api/DashboardWebSocketHandler.java` | node | WebSocket handler -- needs auth on connect |
| `aether/aether-invoke/src/main/java/org/pragmatica/aether/invoke/InvocationContext.java` | aether-invoke | ScopedValue context -- needs PRINCIPAL addition |
| `aether/aether-config/src/main/java/org/pragmatica/aether/config/AppHttpConfig.java` | aether-config | App HTTP config -- needs rich API key entries |
| `aether/aether-config/src/main/java/org/pragmatica/aether/config/AetherConfig.java` | aether-config | Root config record |
| `aether/aether-config/src/main/java/org/pragmatica/aether/config/ConfigLoader.java` | aether-config | TOML config loader -- needs app-http section parsing |
| `aether/cli/src/main/java/org/pragmatica/aether/cli/AetherCli.java` | cli | CLI tool -- needs --api-key option |
| `aether/node/src/main/java/org/pragmatica/aether/http/forward/HttpForwardMessage.java` | node | HTTP forwarding messages (SecurityContext travels inside requestData) |
| `aether/aether-invoke/src/main/java/org/pragmatica/aether/http/HttpRoutePublisher.java` | aether-invoke | Route publisher with LocalRouteInfo.security() |
| `aether/http-handler-api/src/test/java/org/pragmatica/aether/http/handler/security/SecurityValueObjectsTest.java` | http-handler-api (test) | 23 tests for security value objects |
| `aether/node/src/test/java/org/pragmatica/aether/http/security/ApiKeySecurityValidatorTest.java` | node (test) | 6 tests for API key validation |

### Technical Standards

- [RFC 9457 - Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457) -- Error response format already used by Aether
- [RFC 7235 - HTTP/1.1 Authentication](https://www.rfc-editor.org/rfc/rfc7235) -- WWW-Authenticate header for 401 responses
- [RFC 6750 - Bearer Token Usage](https://www.rfc-editor.org/rfc/rfc6750) -- Relevant for Tier 2 JWT support
- [JEP 429 - Scoped Values](https://openjdk.org/jeps/429) -- ScopedValue mechanism used by InvocationContext

---

## Appendix A: Implementation Checklist

### Phase 1: Core Pipeline (AppHttpServer)

- [ ] Wire `securityValidator.validate()` into `handleRequestInScope()` at `AppHttpServer.java` line 259
- [ ] Extract security policy resolution into `resolveSecurityPolicy()` using `LocalRouteInfo.security()`
- [ ] Modify `invokeLocalRouter()` to accept pre-authenticated `HttpRequestContext`
- [ ] Modify `forwardRequestInternal()` to accept pre-authenticated `HttpRequestContext`
- [ ] Add `handleSecurityFailure()` with proper 401/403 mapping
- [ ] Add `WWW-Authenticate` header to 401 responses

### Phase 2: InvocationContext

- [ ] Add `PRINCIPAL` ScopedValue to `InvocationContext.java`
- [ ] Add `ORIGIN_NODE` ScopedValue to `InvocationContext.java`
- [ ] Add `MDC_PRINCIPAL_KEY` and `MDC_ORIGIN_NODE_KEY` constants
- [ ] Add `currentPrincipal()` accessor
- [ ] Add `currentOriginNode()` accessor
- [ ] Update `runWithContext()` overloads to accept `principal` and `originNode` (both Supplier and Runnable)
- [ ] Update `ContextSnapshot` to include `principal` and `originNode`
- [ ] Update `captureContext()` to capture `principal` and `originNode`
- [ ] Set `X-Aether-Origin-Node` header on forwarded requests in AppHttpServer
- [ ] Read `X-Aether-Origin-Node` header in `onHttpForwardRequest` to preserve origin across hops

### Phase 3: Configuration

- [ ] Create `ApiKeyEntry` record in `aether-config` module
- [ ] Update `AppHttpConfig` to use `Map<String, ApiKeyEntry>`
- [ ] Add backward-compatible factory methods
- [ ] Add `populateAppHttpConfig()` to `ConfigLoader`
- [ ] Add `AETHER_API_KEYS` environment variable parsing
- [ ] Add config validation for API key format

### Phase 4: Management Server

- [ ] Add `SecurityValidator` parameter to `ManagementServer.managementServer()`
- [ ] Wire security validation into `ManagementServerImpl.handleRequest()`
- [ ] Exempt health endpoints from security
- [ ] Create `AuditLog` utility class

### Phase 5: CLI

- [ ] Add `--api-key` / `-k` option to `AetherCli`
- [ ] Add `resolveApiKey()` with priority: flag > env > config
- [ ] Attach `X-API-Key` header to all request builders
- [ ] Add user-friendly error messages for 401/403 responses
- [ ] Add `[cli].api_key` config parsing

### Phase 6: WebSocket

- [ ] Add query parameter auth to `DashboardWebSocketHandler.onOpen()`
- [ ] Add first-message AUTH protocol
- [ ] Add pending session timeout (5s)
- [ ] Apply same pattern to `StatusWebSocketHandler` and `EventWebSocketHandler`

### Phase 7: Audit

- [ ] Create `AuditLog` class with structured logging methods
- [ ] Add audit logging to security validation success/failure in AppHttpServer
- [ ] Add audit logging to management API access
- [ ] Add audit logging to WebSocket auth events
- [ ] Ensure API key values are NEVER logged (only Principal names)

### Phase 8: Testing

- [ ] `AppHttpServerSecurityTest` (T-001 through T-010)
- [ ] `ManagementServerSecurityTest` (T-020 through T-023)
- [ ] `InvocationContextPrincipalTest` (T-030 through T-034)
- [ ] `ConfigLoaderSecurityTest` (T-040 through T-044)
- [ ] `AuditLogTest` (T-050 through T-052)
- [ ] E2E integration tests (T-060 through T-067)
- [ ] Security-specific tests (T-070 through T-073)

---

## Appendix B: Summary of File Changes

| File | Change Type | Scope |
|------|-------------|-------|
| `InvocationContext.java` | Modified | Add PRINCIPAL and ORIGIN_NODE ScopedValues, update all runWithContext/captureContext |
| `AppHttpServer.java` | Modified | Wire securityValidator into request pipeline |
| `AppHttpConfig.java` | Modified | Map-based API key config with ApiKeyEntry |
| `ConfigLoader.java` | Modified | Parse [app-http.api-keys] TOML sections |
| `ManagementServer.java` | Modified | Add SecurityValidator, validate in handleRequest |
| `DashboardWebSocketHandler.java` | Modified | Add auth on WebSocket open |
| `AetherCli.java` | Modified | Add --api-key option, attach header |
| `ApiKeyEntry.java` | **New** | API key configuration entry |
| `AuditLog.java` | **New** | Structured audit logging utility |
| `AppHttpServerSecurityTest.java` | **New** | Security pipeline tests |
| `ManagementServerSecurityTest.java` | **New** | Management security tests |
| `InvocationContextPrincipalTest.java` | **New** | Principal propagation tests |
| `ConfigLoaderSecurityTest.java` | **New** | Config parsing tests |
| `AuditLogTest.java` | **New** | Audit log tests |
