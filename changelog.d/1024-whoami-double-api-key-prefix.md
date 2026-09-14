### Fixed (2026-09-12 — #1024: /whoami reported a double-prefixed api-key principal)
- **`GET /api/v1/whoami` returned the principal as `api-key:api-key:ak_09e4c3ad`** for every key
  authenticated against the KV store — the string an operator reads to answer "which credential was
  this?", and the value `AuditLog` and `OperationalEvent.AccessDenied` key on.
- The second prefix came from the FACTORY, not from the stored key id. `SecurityContext.securityContext(String, Set, AuthorizationRole)`
  is already typed to an api-key subject and runs its argument through `Principal.principal(name, PrincipalType.API_KEY)`,
  which applies `api-key:` itself; `KvStoreApiKeyValidator.buildContext` prepended it a second time.
  The key id is stored bare, and the sibling `ApiKeySecurityValidator.toSecurityContext` has always
  passed its bare name through the same factory — only the KV-backed path doubled.
  `[mechanism: Principal.PrincipalType#prefixed, applied once per factory call]`
- The KV path now passes the bare `keyId`, so the principal is `api-key:<keyId>` exactly once.
  `[verified: aether/node/src/test/java/org/pragmatica/aether/http/security/KvStoreApiKeyValidatorPrincipalTest.java]`
- **No consumer parsed the principal's colon arity**, so the shape change is safe: every reader
  (`AppHttpServer`, `ManagementServer`, `WebSocketAuthenticator`, `StatusRoutes`) passes the value
  through opaquely, and the only prefix tests in the codebase — `Principal.isApiKey`/`isUser`/`isService`
  — are `startsWith` checks that accepted the doubled form too. That is also why no existing test
  caught this: `StatusRoutesWhoamiTest` hand-builds its `Principal` through the same factory, so it
  states what a correct principal looks like and never runs the validator that produced the wrong one.
  `[mechanism: repo-wide search for colon-splitting of a principal value; the one exactly-one-colon
  check, SliceLoadingContext#isGeneratedBaseOf, grades Maven artifact ids and never sees a principal]`
