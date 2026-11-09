### Added (2026-11-09 — #2946: optional-authentication route policy)
- **`SecurityPolicy.optional()` / routes.toml `security = "optional"`: authenticate if a credential is presented.** No
  credential is served anonymously (the slice's `SecurityContext` is the empty context); a credential that fails
  validation (a bare `Authorization: Bearer`, an empty or unknown-scheme `Authorization` header, an empty `X-API-Key`, malformed, expired, bad signature, wrong `alg`, wrong issuer/audience) is a `401` with
  `WWW-Authenticate: <first auth_schemes entry> realm=..., error="invalid_token"` (the #2926 mapping), never downgraded
  to anonymous; a valid credential reaches the slice through its `Principal` / `SecurityContext` parameters (#2157).
  Only an ABSENT credential header is anonymous. Across nodes `optional` never outranks `unspecified` (the
  inherited global mode), so a stricter node's route is not weakened by an `optional` declaration elsewhere.
  Aether (JWT, API key, KV-held API keys) and Terra. The names `optional` / `OptionalAuth` are a guess.
- **`public` is unchanged and still ignores a presented credential; `authenticated`, `role:<name>`, `api_key` and
  `bearer_token` are unchanged.** `optional` ranks between `public` and `authenticated` for route-policy strength; a
  security override to `optional` on an undeclared route is refused like one to `public` (`strengthen_only`).
- **Under `security_mode = "none"` (and `jwt` without a `jwks_url`) an `optional` route is served anonymously and any
  credential is ignored**: there is nothing to validate it against. `none` hands the slice an empty context, not the
  system grant. A custom `HttpAuthenticator` that switches over `SecurityPolicy` without a `default` arm must handle
  `OptionalAuth`; one with a `default` arm refuses it. A slice built with this release's `jbct/slice-processor`
  and an `optional` route needs a node of this release (`SecurityPolicy.optional()` does not exist on older nodes).
  [verified: `aether/http-security/.../OptionalAuthValidatorTest.java`, `aether/node/.../AppHttpServerOptionalAuthTest.java`,
  `terra/http/.../TerraHttpServerTest.java` (`serve_optionalAuth*`), `jbct/slice-processor-tests/.../GeneratedRouteOptionalSecurityTest.java`]
