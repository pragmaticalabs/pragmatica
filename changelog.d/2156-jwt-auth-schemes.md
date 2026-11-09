### Added (2026-11-08 — #2156: configurable JWT auth scheme prefix)
- **JWT mode accepts a configured list of `Authorization` schemes.** `auth_schemes` in Aether's `[app-http]` and in
  Terra's `[jwt]` names the schemes accepted in front of the token; the default stays `["Bearer"]`, so an existing
  configuration accepts exactly what it accepted before. The scheme is matched case-insensitively (RFC 7235 §2.1); a
  scheme outside the list is a missing credential, answered `401`
  [verified: `aether/http-security/src/test/java/org/pragmatica/aether/http/security/JwtSecurityValidatorTest.java`,
  `terra/launcher/src/test/java/org/pragmatica/terra/launcher/LaunchHttpTest.java`].
- **Aether's `WWW-Authenticate` challenge names the first configured scheme**, on direct and on forwarded refusals
  [verified: `aether/node/src/test/java/org/pragmatica/aether/http/AppHttpServerSecurityGridTest.java`,
  `aether/node/src/test/java/org/pragmatica/aether/http/AppHttpServerForwardReauthorizationTest.java`].
  challenge header, before or after this change.
- **An empty list, a scheme named twice (case-insensitively) or an entry that is not an auth-scheme token refuses the
  config load** (Aether) or the launch (Terra); it is never replaced by the default. A blank entry such as `["Bearer", ""]` is refused in both runtimes. One rule, `AuthSchemes`, serves both
  runtimes [verified: `aether/aether-config/src/test/java/org/pragmatica/aether/config/ConfigLoaderAuthSchemesTest.java`].
- **Removed: the raw `HttpRequestHandlerFactory` / `HttpRequestHandler` route path.** No production code implemented it;
  its only reach was a `ServiceLoader` fallback in `HttpRoutePublisher` that advertised a slice's routes cluster-wide
  while no node ever dispatched to the handler. A slice that no generated `SliceRouterFactory` serves now publishes no
  routes [verified: `aether/aether-invoke/src/test/java/org/pragmatica/aether/http/HttpRoutePublisherUnmatchedSliceTest.java`].
  This is a breaking change for any external implementor of the removed SPI (pre-GA, intended): such an implementation
  no longer compiles and is no longer discovered.
