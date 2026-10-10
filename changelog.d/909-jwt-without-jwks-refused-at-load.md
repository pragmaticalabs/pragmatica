### Security (2026-10-04 — #909: `security_mode = "jwt"` without `jwks_url` is refused at config load and the node does not start)

- **Action required if you run `security_mode = "jwt"` with `[app-http] enabled = true` and no `jwks_url`.** Such a
  node used to load, boot and answer `401` to every non-public app route (#888's request-time deny floor). It now
  fails config validation with a typed `SecurityMisconfigured` cause whose message names `[app-http] jwks_url`; a
  `--config=` file that fails validation refuses the boot with exit code 65 (#2052), a FATAL line on stderr naming
  the file and the cause. A server with `enabled = false` serves nothing and is not refused.
  [verified: `ConfigLoaderJwtRefusalTest`, `JwksUrlTest`, `MainConfigGivenBootTest#aGivenConfigWithJwtButNoJwksUrl_refusesToStart_namingTheMissingSetting`, `ForgeAppConfigTest`, `AetherCliConfigWarningTest`]
- **Cluster bootstrap catches it before provisioning (PF-34)**, judged on the COMPOSED node config (global default +
  source-type default + the source's `node_config`), the composition a node loads. The global default sets
  `[app-http] enabled = true`, so an overlay that only says `security_mode = "jwt"` is an enabled server and is
  refused; a server disabled with an explicit `enabled = false` is not. A cloud bootstrap therefore does not create a
  fleet of nodes that will all refuse to start.
- **`jwks_url` must be usable, not merely present.** Config load and PF-34 apply one rule (`JwksUrl`): a non-blank
  absolute URL with a host and the `https` scheme; plain `http` is accepted only to a loopback host (`localhost`,
  `127.0.0.1`, `::1`) for local development. A blank, relative, unparseable or remote-`http` URL is refused. This rule
  (https, loopback-http exception) is a stated default, not an owner ruling; say if it should differ.
- **A padded `jwks_url` is stored trimmed** (`JwtConfig` trims), so the string the predicate judged is the string the node fetches.
- **The jwt check has its own bootstrap id, PF-34.** `PF-28` is #2059's cloud-credentials check; no open branch or spec table held PF-34.
- **The message says what is required:** `jwks_url is required (issuer/audience optional)`.
- **Forge no longer drops an unloadable `aether.toml` silently.** A sibling `aether.toml` that exists and fails to
  load or validate used to be discarded, so Forge ran with security `NONE` for a user who had configured `jwt`. It is
  now logged at ERROR with the cause and Forge exits 1 before creating anything. The CLI keeps using its default
  address when its config does not load, and its warning now carries the cause text.
- The #888 deny floor stays: in-process builders (`AppHttpConfig.withSecurityMode`, Forge, Ember) construct the
  record directly and bypass the load check, so the floor is still what protects them.
- `AppHttpSecurityIntegrationTest#jwtMode_noJwksUrl_...` asserted the load succeeded with an empty `jwtConfig`, which
  specified the defect; it now asserts the refusal.
- Siblings found and NOT changed: `[app-http.tls]` with only one of `cert_path`/`key_path` is silently dropped
  (`ConfigLoader.parseAppTls`, `Option.all`), contrary to the comment above it; `jwks_url` set under a non-jwt mode
  is ignored; `issuer`/`audience` absent under jwt skip claim validation by documented design.
