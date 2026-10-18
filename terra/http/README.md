# Terra HTTP host

`TerraHttpServer` takes ownership of a started `TerraApplication` and an `HttpAuthenticator`. It discovers generated `SliceRouterFactory` providers and binds only the selected application slices. Use the host's `close()` operation for shutdown; do not independently close its application or authenticator while serving requests.

```java
var config = TerraHttpConfig.terraHttpConfig(
    HttpServerConfig.httpServerConfig("my-app", 8080),
    RouteMountMode.pathMode(),
    SecurityPolicy.apiKeyRequired());

return config.async().flatMap(http -> TerraHttpServer.start(
    application, http,
    HttpAuthenticator.apiKeyValidator(namedApiKeys)));
```

The transport is buffered HTTP/1.1, optionally TLS through `HttpServerConfig.withTls(...)`. Port `0` selects an ephemeral port; `host.port()` reports the actual port. Body-size limits use the existing transport configuration. WebSockets and streaming responses need separate hosts and cannot bypass this host's policy through transport options. HTTP/2 and HTTP/3 are not exposed by this host.

Both path-based and header-based API versioning use the generated version registry, including missing/invalid version handling and lifecycle response headers. `RouteMountMode.headerMode("API-Version")` selects header mode. Each actual selected handler authenticates and enforces its own policy. Routes without explicit security inherit the configured policy. Explicit public routes stay public. API-key, JWT/JWKS, and custom authenticators are available from the shared authentication module. Authentication runs off transport event loops. Slice calls see the verified request context and `SecurityContextHolder` scope.

Duplicate factories, duplicate route identities, incompatible security contracts, unknown policy implementations, and cross-slice ownership of the same method/base path fail startup. Different shapes and versions under a base path belong to one slice. `/__terra/` is reserved. Failed startup releases the application and authenticator, preserving cleanup failures alongside the original error.

The liveness endpoint is `/__terra/health/live`; readiness is `/__terra/health/ready`. Both are credential-free probes that expose no application data. Readiness is published after application startup and successful listener binding. During shutdown readiness returns 503, admission closes, and accepted handlers plus response flushes drain before stopping the listener and releasing slice resources. New requests receive 503 while the listener remains available. A response flush means transport completion, not peer acknowledgement.

`host.status()` exposes readiness, in-flight and accepted request counts, and failed response writes. Shutdown is idempotent; timing out a caller's shutdown promise does not release resources early. There is no forced drain deadline: a handler that never settles keeps shutdown pending. Authentication resources close after the application, and cleanup continues after failures.

The embedding process owns signal handling. The executable distribution layer supplies that process lifecycle.
