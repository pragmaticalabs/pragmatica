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
  [verified: `AppHttpServerSecurityGridTest#anonymousRequest_perGridCell` — the `JWT_WITHOUT_CONFIG` column].
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
  too — nothing can verify it. The node also logs a startup warning naming the missing key
  [verified: `AppHttpServerSecurityGridTest#jwtModeWithoutJwtConfig_refusesRoleAdminRouteToAnonymousCaller`,
  `#jwtModeWithoutJwtConfig_refusesRoleAdminRoute_evenWithABearerTokenPresented`; reverting the
  production hunk turns both red, plus the five `JWT_WITHOUT_CONFIG x <non-public>` grid cells].
- **The whole `security_mode` x effective-policy grid is now pinned against a live server**, one
  anonymous `GET` per cell, expected status written as a literal table so a future two-cell change
  cannot silently move a cell nobody touched — which is exactly how #888 was found. Before/after,
  anonymous caller:

  | configuration | `public` | `authenticated` | `role:admin` | `unspecified` | `api_key` | `bearer_token` |
  |---|---|---|---|---|---|---|
  | `none` | 200 | 401 | 401 | 200 | 401 | 401 |
  | `api-key` | 200 | 401 | 401 | 401 | 401 | 401 |
  | `jwt`, no `jwks_url` — **before** | 200 | **200** | **200** | **200** | **200** | **200** |
  | `jwt`, no `jwks_url` — **after** | 200 | 401 | 401 | 401 | 401 | 401 |
  | `jwt`, `jwks_url` set | 200 | 401 | 401 | 401 | 401 | 401 |

  Only the `jwt`-without-config row changes. `unspecified` under `none` is `200` by design: an
  undeclared route inherits the global policy, and `none`'s global policy is `public` (#763)
  [verified: `AppHttpServerSecurityGridTest#anonymousRequest_perGridCell` (24 cells),
  `#grid_coversEveryConfigurationAndEveryPolicyExactlyOnce`,
  `#jwtModeWithoutJwtConfig_isNeverMorePermissiveThanNone` — #888 acceptance (4), compared live per policy].
- **Config loading does not reject this configuration, so the window was the whole boot path, not a
  corner.** `ConfigLoader` derives `jwtConfig` solely from the presence of `jwks_url`, and
  `AppHttpConfig.appHttpConfig(...)` succeeds unconditionally, so `security_mode = "jwt"` with no
  `jwks_url` loads, boots, and reaches the fallback on every request
  [mechanism: `ConfigLoader.parseJwtConfig` maps `getString("app-http", "jwks_url")`; `AppHttpConfig`'s
  factory returns `success(...)` with no cross-field check].
- **Residual, stated plainly: the node still boots.** A JWT node with nothing to verify tokens against
  now refuses rather than grants, but it comes up, logs one warning, and serves `401`s — a
  misconfigured security mode is a runtime degradation, not a boot failure. Refusing at boot
  (`ConfigLoader`/`AppHttpConfig` returning a failure naming `[app-http] jwks_url`) is the preferred
  end state and is deferred: it needs `ConfigLoader.populateAppHttpConfig` to stop `unwrap()`-ing the
  config result, and the in-process builders (`AppHttpConfig.withSecurityMode`, #665) bypass the
  factory, so the deny fallback must exist regardless as the floor beneath it. Until then, grep node
  logs for `no JWT configuration is present` after enabling JWT mode.
- **Other `permitAllValidator` sites, audited.** `AppHttpServerAdapter`'s `NONE` arm is guarded by the
  request-time pre-check that refuses every auth-requiring policy before the validator runs (pinned by
  the `none` row above). `ForgeServer`'s three websocket handlers pass `authRequired = false`, the
  documented local-dev case. The management plane already installs `denyUnlessPublicValidator` (#573).
  No other site installs it. `KvStoreApiKeyValidator` (management plane) still has a `BearerTokenRequired
  -> success(anonymous context)` arm of the #866 G1 family — reported to the tracker, not changed here:
  no management route carries that policy and it grants no role, so it is a different defect
  [mechanism: repo-wide grep for `permitAllValidator` and for unconditional `Result.success(SecurityContext`].
