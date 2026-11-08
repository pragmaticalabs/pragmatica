### Changed (2026-11-08 — #2926: invalid or empty credentials answer 401 with a challenge, not 403; BREAKING for clients keyed on 403)
- **A refused credential is unauthenticated: `401` + `WWW-Authenticate`, never `403`.** A malformed, empty
  (`Authorization: Bearer `), badly signed, wrong-issuer/audience, unknown-key or unknown-API-key credential used to
  answer `403` with no challenge on Aether's app HTTP (direct and forwarded refusals) and on the management API, so
  clients that refresh a token on `401` (RFC 6750 §3.1, RFC 7235; the RealWorld/Conduit API) never recovered. They now
  answer `401` with `WWW-Authenticate: <scheme> realm="…", error="invalid_token"`. The scheme is the first configured
  `auth_schemes` entry under JWT (#2156) and `ApiKey` under api-key mode; a request with NO credential keeps `401` with
  the challenge and no `error` (RFC 6750 §3). `403` is now only an authenticated caller lacking a role, and carries no
  challenge. The body never says which check failed.
- **Terra now sends the challenge** (`realm="Terra"`); its statuses were already `401`/`403` as above.
- | Case | Status | Challenge `error` |
  |---|---|---|
  | `MissingCredentials` (no/unaccepted-scheme header, no policy/validator) | 401 | none |
  | `InvalidCredentials`, `TokenExpired`, `SignatureInvalid`, `IssuerMismatch`, `AudienceMismatch`, `KeyNotFound` | 401 | `invalid_token` |
  | `JwksFetchFailed` (the key set could not be fetched) | 503 on every surface (Aether app direct + forwarded, management API, Terra) | **no challenge** |
  | `AccessDenied`, `InsufficientRole` | 403 | no challenge |
- **A JWKS (key) fetch failure is the server failing: `503`, never a challenge.** It used to answer `401` (Aether, with a
  `Bearer` challenge) or `500` (Terra), and an unknown `kid` whose refresh FAILED was reported as `KeyNotFound` (`401
  invalid_token`) because the fetch error was swallowed. A `401` challenge tells a conforming client to re-authenticate, so
  token-refreshing clients looped against a key-server outage. Now a failed refresh surfaces as `JwksFetchFailed` -> `503`
  ("Authentication temporarily unavailable"), same on all surfaces; a healthy JWKS that lacks the `kid` stays `401
  invalid_token`.
- **Not changed:** a malformed `Authorization` header is answered `401 invalid_token`, not `400 invalid_request`
  (RFC 6750 §3.1 allows either; no `SecurityError` distinguishes header-syntax defects, and `401` lets a refreshing
  client recover). Log levels are unchanged.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/http/AppHttpServerSecurityErrorStatusTest.java`,
  `AppHttpServerSecurityGridTest.java`, `AppHttpServerForwardReauthorizationTest.java`,
  `aether/node/src/test/java/org/pragmatica/aether/api/ManagementServerSecurityStatusTest.java`,
  `aether/http-security/src/test/java/org/pragmatica/aether/http/security/SecurityErrorChallengeTest.java`,
  `aether/http-security/src/test/java/org/pragmatica/aether/http/security/JwksKeyStoreFetchFailureTest.java`,
  `terra/http/src/test/java/org/pragmatica/terra/http/TerraHttpServerTest.java`]
