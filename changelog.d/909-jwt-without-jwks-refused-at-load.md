### Security (2026-10-04 — #909: `security_mode = "jwt"` without `jwks_url` is refused at config load and the node does not start)

- **Action required if you run `security_mode = "jwt"` with `[app-http] enabled = true` and no `jwks_url`.** Such a
  node used to load, boot and answer `401` to every non-public app route (#888's request-time deny floor). It now
  fails config validation with a typed `SecurityMisconfigured` cause whose message names `[app-http] jwks_url`; a
  `--config=` file that fails validation refuses the boot with exit code 65 (#2052), a FATAL line on stderr naming
  the file and the cause. A server with `enabled = false` serves nothing and is not refused.
  [verified: `ConfigLoaderJwtRefusalTest`, `MainConfigGivenBootTest#aGivenConfigWithJwtButNoJwksUrl_refusesToStart`]
- **Cluster bootstrap catches it before provisioning (PF-28)** on the per-source `node_config` overlay, so a cloud
  bootstrap does not create a fleet of nodes that will all refuse to start.
- The #888 deny floor stays: in-process builders (`AppHttpConfig.withSecurityMode`, Forge, Ember) construct the
  record directly and bypass the load check, so the floor is still what protects them.
- `AppHttpSecurityIntegrationTest#jwtMode_noJwksUrl_...` asserted the load succeeded with an empty `jwtConfig`, which
  specified the defect; it now asserts the refusal.
- Siblings found and NOT changed: `[app-http.tls]` with only one of `cert_path`/`key_path` is silently dropped
  (`ConfigLoader.parseAppTls`, `Option.all`), contrary to the comment above it; `jwks_url` set under a non-jwt mode
  is ignored; `issuer`/`audience` absent under jwt skip claim validation by documented design.
