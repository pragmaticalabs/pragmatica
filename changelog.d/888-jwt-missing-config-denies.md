### Security (2026-09-06 — #888: `security_mode = "jwt"` with no jwt config installed `permitAllValidator`: every caller received ADMIN + SERVICE authority)

- **Action required if you run `security_mode = "jwt"` and your `[app-http]` section has no `jwks_url`.**
  Every non-`public` app route on such a node — `authenticated`, `role:*`, undeclared (`unspecified`),
  and the credential-type policies — **used to answer `200` to any caller with no credential inspected,
  and now answers `401`** with `WWW-Authenticate: Bearer`. Requests that worked yesterday will fail on
  upgrade. This is not a regression: every one of those `200`s was served to an unauthenticated caller
  holding an injected `admin` role, and the `401` is the correction. **What to do:** set `[app-http]
  jwks_url` (plus `issuer`/`audience`) so tokens can actually be verified, or change `security_mode`
  to the credential type you do provision. `public` routes are unaffected; `security_mode = "none"`
  and `"api-key"` deployments are unaffected
  [verified: `AppHttpServerSecurityGridTest#anonymousRequest_perGridCell` — the `NONE`, `API_KEY`,
  `API_KEY_WITHOUT_KEYS` and `JWT_WITH_CONFIG` rows, 24 cells, all of which stay green when the
  production hunk is reverted; only the `JWT_WITHOUT_CONFIG` row moves].
- **The no-config fallback of the `jwt` arm was `permitAllValidator`**, a validator with no policy
  switch that hands every request a `SecurityContext` carrying `Role.ADMIN` + `Role.SERVICE`. So on a
  node whose operator enabled JWT mode but omitted or mistyped the `[app-http] jwks_url` line, a
  `role:admin` route was served to an anonymous request — `enforceRoleIfRequired` asked the injected
  context `hasRole("admin")` and it said yes. The `SecurityMode.NONE` pre-check does not fire (the
  mode is `JWT`), so nothing stood between the request and the grant. This was strictly MORE
  permissive than `security_mode = "none"`, which refuses every auth-requiring route. The fallback is
  now `denyUnlessPublicValidator`, the same validator the management plane, Ember and Forge install
  when they have nothing to check with: `public` routes pass with an empty context, everything else
  is refused with `NO_VALIDATOR_CONFIGURED` (`401`), and a bearer token presented anyway is refused
  too — nothing can verify it
  [verified: `AppHttpServerSecurityGridTest#jwtModeWithoutJwtConfig_refusesRoleAdminRouteToAnonymousCaller`,
  `#jwtModeWithoutJwtConfig_refusesRoleAdminRoute_evenWithABearerTokenPresented`; reverting the
  production hunk turns both red, plus the five `JWT_WITHOUT_CONFIG x <non-public>` grid cells].
- **The node logs the misconfiguration at ERROR, at the top of the log, naming the key and the
  consequence.** `security_mode = "jwt"` with no `jwks_url` is a declared-intent contradiction — the
  operator asked for token verification and nothing can perform it — not a degraded default, so it
  is ERROR rather than the WARN the management plane uses for "nothing configured" (#573). The
  message names `[app-http] jwks_url`, states that every non-public app route will be REFUSED with
  `401`, and says a restart is needed after the fix. It is emitted only for a server that will
  actually serve: a node with `enabled = false` refuses nothing and logs nothing
  [verified: `AppHttpServerJwtMissingConfigLogTest` — level pinned as `ERROR` through an appender
  capturing every level, so a demotion to WARN fails by name; controls for `jwks_url` set, server
  disabled, and the other two modes].
- **The whole `security_mode` x effective-policy grid is now pinned against a live server**, one
  anonymous `GET` per cell, expected status written as a literal table so a future two-cell change
  cannot silently move a cell nobody touched — which is exactly how #888 was found. Before/after,
  anonymous caller:

  | configuration | `public` | `authenticated` | `role:admin` | `unspecified` | `api_key` | `bearer_token` |
  |---|---|---|---|---|---|---|
  | `none` | 200 | 401 | 401 | 200 | 401 | 401 |
  | `api-key`, keys configured | 200 | 401 | 401 | 401 | 401 | 401 |
  | `api-key`, no keys (shipped default) | 200 | 401 | 401 | 401 | 401 | 401 |
  | `jwt`, no `jwks_url` — **before** | 200 | **200** | **200** | **200** | **200** | **200** |
  | `jwt`, no `jwks_url` — **after** | 200 | 401 | 401 | 401 | 401 | 401 |
  | `jwt`, `jwks_url` set | 200 | 401 | 401 | 401 | 401 | 401 |

  Only the `jwt`-without-config row changes. `unspecified` under `none` is `200` by design: an
  undeclared route inherits the global policy, and `none`'s global policy is `public` (#763). The
  five rows are every behaviourally distinct configuration: the validator reads `jwtConfig` only
  under `jwt` and the key set only under `api-key`, and the request path reads the mode alone, so
  the raw mode x jwt-config x api-keys product collapses to these five. `api-key` with no keys is
  the shipped default (`ConfigLoader` defaults `security_mode` to `api-key`) and the api-key-side
  mirror of #888; it was fail-closed by construction — the header is checked before the key map —
  and is now pinned rather than read
  [verified: `AppHttpServerSecurityGridTest#anonymousRequest_perGridCell` (30 cells),
  `#grid_coversEveryConfigurationAndEveryPolicyExactlyOnce`,
  `#jwtModeWithoutJwtConfig_isNeverMorePermissiveThanNone` — #888 acceptance (4), compared live per policy].
- **Config loading does not reject this configuration, so the window was the whole boot path, not a
  corner.** `ConfigLoader` derives `jwtConfig` solely from the presence of `jwks_url`, and
  `AppHttpConfig.appHttpConfig(...)` succeeds unconditionally, so `security_mode = "jwt"` with no
  `jwks_url` loads, boots, and reaches the fallback on every request
  [mechanism: `ConfigLoader.parseJwtConfig` maps `getString("app-http", "jwks_url")`; `AppHttpConfig`'s
  factory returns `success(...)` with no cross-field check].
- **Residual, stated plainly: the node still boots.** A JWT node with nothing to verify tokens against
  now refuses rather than grants, but it comes up, logs one ERROR, and serves `401`s — a
  misconfigured security mode is a runtime refusal, not a boot failure. Refusing at boot
  (`ConfigLoader`/`AppHttpConfig` returning a failure naming `[app-http] jwks_url`) is the preferred
  end state and is deferred: it needs `ConfigLoader.populateAppHttpConfig` to stop `unwrap()`-ing the
  config result, and the in-process builders (`AppHttpConfig.withSecurityMode`, #665) bypass the
  factory, so the deny fallback must exist regardless as the floor beneath it. Until then, grep node
  logs at ERROR for `jwks_url is missing` after enabling JWT mode
  [mechanism: `ConfigLoader.populateAppHttpConfig` calls `.unwrap()` on a factory that returns
  `success(...)` unconditionally, so there is no failure to propagate; `AppHttpConfig.withSecurityMode`
  constructs the record directly].
- **Residuals from the #902 review, recorded not fixed.** (1) #888 acceptance 2 asked that "no path
  installs `permitAllValidator` where the route's policy is the only gate" be structural; it remains
  conventional — the static factory the javadoc forbids is one `.or(...)` away, and only the grid
  catches a reintroduction. (2) `AppHttpServer.start()` on an already-bound port was reported by the
  stream as returning success; the code as read maps a failed `bind()` to `BindFailed`, so this may
  be a macOS wildcard-bind quirk — unreproduced, check on Linux before filing. (3) The management
  plane's `KvStoreApiKeyValidator` has a dead `BearerTokenRequired -> success(anonymous)` arm and a
  reachable no-header, no-keys `-> success(anonymous VIEWER)` arm (#866 G1 family), and the
  `AetherNode` bootstrap warning states the inverse of that code; all pre-existing, outside this
  diff, filed as #908
  [mechanism: review section 5 and 8 of `review-902-888-2026-09-06`; read-verified, not executed].
- **Other `permitAllValidator` sites, audited.** `AppHttpServerAdapter`'s `NONE` arm is guarded by the
  request-time pre-check that refuses every auth-requiring policy before the validator runs (pinned by
  the `none` row above). `ForgeServer`'s three websocket handlers pass `securityEnabled = false`, the
  documented local-dev case. The management plane already installs `denyUnlessPublicValidator` (#573).
  No other site installs it
  [mechanism: repo-wide grep for `permitAllValidator` and for unconditional `Result.success(SecurityContext`].
