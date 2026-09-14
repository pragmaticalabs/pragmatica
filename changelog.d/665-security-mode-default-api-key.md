### Fixed (2026-09-14 — #665: in-process `AppHttpConfig` builders defaulted to `SecurityMode.NONE`; now `API_KEY`, insecurity only by name)
- **Owner ruling 2026-08-27: insecurity is explicit, never a default.** `ConfigLoader.populateAppHttpConfig`
  already defaulted an `aether.toml` with no `security_mode` to `API_KEY` (#290), but the four bare builders
  — `appHttpConfig()`, `appHttpConfig(boolean)`, `appHttpConfig(int)`, `appHttpConfig(int, ApiVersioningDetection,
  String)` — hard-coded `NONE`, and `appHttpConfig(int, Set<String>)` mapped an empty key set to `NONE`. Under
  `NONE` the server's global policy is `publicRoute()`, so every route with an unspecified policy was served
  unauthenticated. `Main.resolveAppHttp` falls back to `appHttpConfig()` whenever `[app-http]` is absent or
  disabled, so that default also reached a production node's Management API validator selection
  [mechanism: `AppHttpServerAdapter.globalSecurityPolicy` → `NONE -> publicRoute()`; `AetherNode` picks the
  management validator from `config.appHttp().securityEnabled()`].
- All five builders now yield `API_KEY`. With no keys that is fail-closed: nothing authenticates until a key
  exists (the cluster bootstrap admin key for the Management API) — the same posture an `aether.toml` with no
  `security_mode` already had. The only unnamed route to `NONE` is the new `AppHttpConfig.insecureAppHttpConfig(int
  port)`, whose name is the opt-in
  [verified: `aether/aether-config/src/test/java/org/pragmatica/aether/config/AppHttpConfigSecurityDefaultTest.java`
  — every bare builder and the empty-key-set builder yield `API_KEY`, the insecure builder yields `NONE`;
  `AppHttpServerSecurityModeTest.BareBuilderIsFailClosedTests` — a server built from `appHttpConfig(port)`
  answers 401 to `/api/anything` (404 at the base: the request passed security) and 200 to `/health`. Both red at
  the base; restoring `NONE` in the builders reddens them again].
- Harness opt-ins, each explicit at its call site: `EmberCluster` already passes its `appHttpSecurityMode`
  (default `NONE`) to the full factory by name — its javadoc no longer blames a convenience factory for the
  posture; Forge overrides it through `withAppHttpSecurity` as before. Unit tests that need an open listener
  (`AppHttpServerTest`, `AppHttpServerLocalDispatchTest`, `AppHttpServerLocalDeadlineTest`,
  `AppHttpServerForwardBudgetTest`, `AppHttpServerSecurityModeTest.NoSecurityServerTests`) call
  `insecureAppHttpConfig`. Three assertions that specified the old default — `securityEnabled()` false for a
  bare config in `AppHttpConfigApiKeysTest` (two tests) and `securityModeNone_isDefault` — now assert the ruled
  default; the tests were right for the old spec and the spec changed.
- `SECURITY.md` no longer says the in-process builders "still default to `NONE`"; it states the API_KEY default,
  the bootstrap-key-only consequence, and the named opt-out.
- **Behaviour change for a production node with no `[app-http]` section or `enabled = false`:** its Management API
  was open, not "refused unless bootstrap key" as the #573 comment in `AetherNode` claims — under `NONE`,
  `ManagementServerImpl.handleRequest` never consults a validator (H1, forwarded and websocket paths alike), and
  `MavenProtocolRoutes.admitPush` returned `SECURITY_DISABLED` (unauthenticated artifact PUT accepted). After #665
  such a node answers 401 until the cluster bootstrap admin key is presented, and `admitPush` yields `DENIED`
  without an ADMIN key or `AETHER_INSECURE_DEV_MODE` [verified: review probe at e7aa32372 —
  old fallback shape: 0 validator calls, request dispatched; new shape: 1 validator call, 401]. The #573
  `denyUnlessPublicValidator` on the `NONE` arm is therefore inert for its own case — tracked separately.
- Not changed: `SecurityMode` handling in `AppHttpServer`, the Management API validator chain itself (it is now
  *consulted* where before it was bypassed), `ConfigLoader`.
  `[design intent — unverified: no Forge or Ember cluster was booted for this change; Ember's mode is passed by
  name and is unaffected by the builder defaults, established by reading `EmberCluster` line 1152]`
