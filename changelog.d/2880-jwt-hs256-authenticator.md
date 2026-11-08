### Added (2026-11-08 — #2880: HS256 shared-secret JWT and a pluggable authenticator)
- **HS256 JWT mode.** Aether `[app-http] jwt_algorithm = "HS256"` (secret from `AETHER_JWT_SECRET` or
  `AETHER_JWT_SECRET_FILE`, never the TOML) and Terra `[jwt] algorithm = "HS256"` with `secret`. The configured mode
  picks the algorithm, never the token header: HS256-configured refuses RS256/`none`/other headers, JWKS-configured
  refuses an `HS256` token (algorithm confusion) [verified:
  `aether/http-security/src/test/java/org/pragmatica/aether/http/security/JwtHs256ValidatorTest.java`].
- **A missing, empty or shorter-than-32-byte secret, or a `jwks_url` beside HS256, refuses the config load (Aether) or
  the launch (Terra), naming the setting**; there is no degraded fallback. Aether's cluster pre-flight cannot see the
  node's environment, so the presence check is the node's own load [verified:
  `aether/aether-config/src/test/java/org/pragmatica/aether/config/ConfigLoaderHs256Test.java`,
  `terra/launcher/src/test/java/org/pragmatica/terra/launcher/LaunchHttpHs256AndAuthenticatorTest.java`]. The secret
  never appears in `toString` or in refusal messages.
- **Terra: custom authenticator through `ServiceLoader`.** `http.security_mode = "custom"` plus
  `http.authenticator = "<name>"` selects an installed `HttpAuthenticatorFactory`; no match or two factories of one name
  refuse the launch naming them.
- Not included: an HS256 signer for login (follow-up #2895); the 401/403 mapping is unchanged.
