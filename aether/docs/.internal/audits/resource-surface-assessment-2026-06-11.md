# Aether Slice Resource Surface — Deep Assessment

**Date:** 2026-06-11 · **Against:** `analysis/cluster-topology-audit` (rc1 `dd5a2187f` + audit commits) · **Method:** 3 agents (SPI core/@Http/@Notify; @Scheduled/pub-sub/facades; interceptors/artifact-repo). Companion to the DB and streaming assessments (those resources assessed separately). Covers everything else a slice can be provisioned.

## Verdict

The resource model's **shape is good** — priority-ordered `ResourceFactory` SPI, layered per-slice config (`resources.toml ⊕ KV-overlay ⊕ node.toml`), refcounting with close-on-last-consumer, transient/fatal failure classification feeding the deployment FSM retry loop, and a clean qualifier-annotation surface. But the assessment found a **consistent pattern: the happy path is wired and the lifecycle/cluster-semantics edges are broken**, plus two cross-cutting root causes that each break multiple resources at once.

**Two cross-cutting root causes:**
1. **SPI dual-path lifecycle bug** — the context-aware `provide()` overload (which *every* slice-generated call is upgraded to) bypasses the cache/refcount/close triad: resources are never closed (leak on every unload — DB pools, SMTP/Netty event loops), failed promises are cached forever (defeating FSM retry), and a cross-slice use-after-close window exists. Root cause behind resource leaks across DB, @Notify, interceptors.
2. **Missing `nodeId` in `ScheduledTaskKey` and `TopicSubscriptionKey`** — NDM publishes/unpublishes per-node symmetrically, so any multi-instance lifecycle event (scale-down, rebalance) corrupts the cluster-global registration: one instance deactivating deletes the schedule/subscription for all.

**Two design premises not yet realized:**
- **Runtime aspect switching does not exist.** The aspect-based observability philosophy is the design intent and the *seam* is structurally present (every generated factory method's first param is an `Aspect`), but at HEAD `SliceFactory.invokeFactory` always passes `Aspect.identity()` (`SliceFactory.java:143`); interceptors are wired at annotation-processing time and frozen into an immutable generated wrapper at slice creation; no KV-driven config or management route re-provisions them. The static interceptor path works; runtime toggling requires a redeploy. (Reported as a gap to wire, not a design flaw.)
- **Distributed scoping** for rate-limit/circuit-breaker is config-shaped (`RateGuardConfig.type="local"`) but only per-node exists; cluster RPS = limit × instance-count, silently.

**One security finding:** the Maven artifact-push endpoint has no authentication — anyone reaching the management port can publish artifacts the cluster loads as code.

## Tickets filed (2026-06-11, all rc1)

#268 (SPI lifecycle R1-R3) · #269 (slice secrets R4-R5) · #270 (@Http R6-R7) · #271 (@Notify R8-R10) · #272 (@Scheduled lifecycle R11-R13) · #273 (@Scheduled state/ops R14-R15) · #274 (pub/sub R16-R18) · #275 (SliceInvoker R19) · #276 (facades R20-R21) · #277 (runtime aspect switching R22) · #278 (interceptor config R23-R24) · #279 (cache R25) · #280 (retry/CB/rate-limit/logging R26-R28) · #281 (artifact keying R30) · #282 (artifact push auth — SECURITY R29) · #283 (docs R32).

## Per-resource findings (ranked)

### Resource SPI core (`aether/resource/api`)
- **R1 (Critical) [#268]** — Dual-path lifecycle: context overload (`SpiResourceProvider.java:90-98`) bypasses `promiseCache`; `releaseAll` (`:117-129`) only closes cached entries → every context-provisioned resource leaks on unload; cross-slice use-after-close (only context-path callers register in `consumers`); `releaseAll` closes via `factoryList.getFirst()` (`:122-127`) not the factory that matched.
- **R2 (High) [#268]** — Failed promises cached forever on the plain path (`:86` `computeIfAbsent`) → transient failures classified for FSM retry return the same poisoned promise every retry.
- **R3 (Medium) [#268]** — release/provide race (check-then-act over `consumers`, `:109-131`); consumer registered before provisioning succeeds (`:93`); partial-provision leak (param 1 provisions uncloseable, param 2 fails).
- **R4 (Medium) [#269]** — Slice-level `${secrets:}` not resolved: node.toml gets eager resolution (`AetherNode.java:3663-3667`) but slice-intrinsic `resources.toml` is composed raw (`SliceStore.java:235-242`) → placeholders stay literal while docs imply they work.
- **R5 (Low) [#269]** — `logShadowedKeys` logs config *values* at INFO (`SliceStore.java:255-259`, secret-leak vector); dead `resource/aspect/` package (6 files: `TransactionContext` et al., referenced nowhere).

### @Http (`aether/resource/http`)
- **R6 (High) [#270]** — `Network.parseURI(uri).unwrap()` (`JdkHttpClient.java:161-163`) throws synchronously from `get()/post()` on a malformed URI instead of failing the Promise — breaks Promise-native contract + expect-not-unwrap rule.
- **R7 (Medium) [#270]** — No TLS/mTLS/truststore, no proxy, no per-request timeout override, no pool limits → APIs requiring mTLS are unreachable from slices; raw `post/put/patch` hardcode `Content-Type: application/json` and duplicate caller-supplied headers (`:84-85,177-180`).

### @Notify (`aether/resource/notification` + `integrations/email-http`)
- **R8 (High) [#271]** — `NotificationSenderFactory.close()` returns `unitPromise()` (`:35-37`), overriding AutoCloseable → `SmtpClient`'s Netty `EventLoopGroup` never closed (leak per unload, compounds R1).
- **R9 (High) [#271]** — Indiscriminate retry: permanent failures (HTTP 401/403 `AuthError`, SMTP 5xx) get the full retry schedule; no transient/permanent split.
- **R10 (Medium) [#271]** — Config-key mismatches: the sole example (`notification-hub`) sets `username`/`password`/`pool_size` under `[notification.smtp]` but `SmtpConfig` expects nested `auth` (`SmtpConfig.java:27-33`) → keys silently ignored, would **send unauthenticated**. Retry helpers duplicated verbatim across both senders, raw virtual thread per retry.

### @Scheduled (`ScheduledTaskManager`, aether-invoke)
- **R11 (Critical) [#272]** — `ScheduledTaskKey` has no nodeId (`AetherKey.java:511`); `unpublishScheduledTasks` removes unconditionally (`NodeDeploymentState.java:891-894`) → scale-down N→N-1 deletes the schedule cluster-wide, no republish.
- **R12 (Critical) [#272]** — SINGLE-mode `executeTask` doesn't gate on `hasLocalSlice` (`:332-337`); when the leader doesn't host the slice → `SENDER_BRIDGE_NOT_FOUND` every fire. ALL-mode: every non-hosting quorum node fails every interval, spamming consensus failure-state writes. (`ScheduledTaskRoutes` has the guard the manager lacks, `:237-245`.)
- **R13 (High) [#272]** — Pause silently reset: republish hardcodes `paused=false` (`AetherValue.java:197-205`) → operator pause lost on any reactivation/rebalance.
- **R14 (High) [#273]** — Execution state is fiction: timer path writes `successState(0,0)`/`failureState(0,1,0,…)` (`:351,360`) → `totalExecutions` stays 0, `consecutiveFailures` never accumulates, `nextFireAt` always 0.
- **R15 (Medium) [#273]** — No overlap protection (fixed-rate fires regardless of outstanding invocation; cron reschedules after launch not completion); no drain awareness (no Draining sub-state, #189 unimplemented — draining nodes keep firing); leader-change duplicate/missed fires; cron uses local `Instant.now()` UTC, no HLC/skew handling.

### Pub/sub topics (`aether-invoke`)
- **R16 (Critical) [#274]** — `TopicSubscriptionKey` has no nodeId (`AetherKey.java:428`) → multi-instance subscribers collapse to one entry; any single instance deactivation removes the subscription cluster-wide while others remain ACTIVE.
- **R17 (High) [#274]** — Namespaces cosmetic at runtime: matching uses bare name only (`TopicSubscriptionRegistry.java:48-51`), publisher uses raw declared string → an explicitly-namespaced publish (`ns:topic:1.0.0`) matches nothing → **silent zero-delivery success**. (`resolveTopicName` cited in javadoc doesn't exist.)
- **R18 (High) [#274]** — Lossy: zero subscribers → silent success (events vanish during activation/rebalance); plain `invoke` (no retry, 20s timeout); one failing subscriber fails the whole publish; no DLQ/backpressure/ordering.

### Facades
- **R19 (High) [#275]** — `SliceInvokerFacade` slice-to-slice routing has no liveness filter (`AccessibilityFilter` exists only on the HTTP-forward path) → calls to ghost nodes hang until `MembershipDecision` cleanup or the 20s timeout (audit H7 at the invoke layer). Remote failures collapse to `RemoteInvocationError(String)` (`:878-880`) — Cause taxonomy lost across the wire. `invokeWithRetry` exists but isn't exposed; no caller-configurable timeout/retry.
- **R20 (Medium) [#276]** — `ConfigFacade.requireLong/requireDouble` run unguarded `parseLong/parseDouble` inside `.map` → malformed values throw `NumberFormatException` instead of a failed `Result` (violates Result discipline).
- **R21 (Medium) [#276]** — `RateGuard` is node-local only; `type="local"` validated but never read (no distributed limiter) → cluster rate = limit × instances, changes silently on rebalance; `remaining` hardcoded 0.

### Interceptors / aspects (`aether/resource/interceptors`)
- **R22 (High, design) [#277]** — Runtime aspect switching not wired (see Verdict): `SliceFactory.java:143` always `Aspect.identity()`; frozen at annotation-processing time; no `DynamicAspectKey`/management route. The runtime-switchable observability premise is unrealized.
- **R23 (Critical) [#278]** — Silent config-default corruption: missing `[cache.*]` sections bind to `CacheConfig.DEFAULTS` (`ProviderBasedConfigService.java:187,399-404`) → the banking flagship's invalidation annotations *cache* instead of invalidate, `getBalance` entry returned for `credit` as `Unit` (`(R) cached`, heap pollution), repeat `credit` **skips the method body** (dropped transaction).
- **R24 (Critical) [#278]** — retry + metrics interceptors unprovisionable from TOML: `RetryConfig.backoffStrategy` (interface) and `MetricsConfig.registry` (MeterRegistry) are unparseable and lack `DEFAULTS` → binding fails → slice load FAILED. Doc's "registry injected programmatically" has no implementing code. Two of six interceptors dead on arrival.
- **R25 (High) [#279]** — Cache correctness: fail-closed on backend failure (`CacheMethodInterceptor.java:28-31` — DHT outage = service outage); keyExtractor dedup bug (one provisioned instance captures the first method's extractor, reused on all → wrong keys/CCE); `DHTCacheBackend.put` ignores TTL (`:45-50`, entries never expire) + no cross-node invalidation broadcast (`TieredCache.remove` local-only → stale L1 elsewhere; "cluster-wide consistency" doc claim false); `InMemoryCache` unbounded (`maxEntries` not a cap).
- **R26 (Medium) [#280]** — retry idempotency-blind: retries every failure incl. business errors and non-idempotent methods (`RetryMethodInterceptor.java:16`, no retryable predicate).
- **R27 (Medium) [#280]** — CB/rate-limit: no metrics emission (state transitions operationally invisible); per-node scope undocumented.
- **R28 (Medium) [#280]** — logging: `logArgs=true` default + `summarize()` leak request/result content (PII); no MDC/request-id correlation; `MetricsConfig.recordTiming/recordCounts` flags ignored.

### Artifact-repo (`aether/resource/services/artifact-repo`)
- **R29 (Critical, security) [#282]** — `MavenProtocolRoutes.handle` accepts GET/PUT/POST with **zero authentication** (`MavenProtocolRoutes.java:39-57`) → anyone reaching the management port publishes artifacts the cluster loads as code.
- **R30 (High) [#281]** — Classifier/extension-blind keying: `metaKey` is GAV-only (`ArtifactStore.java:631-638`), classifier/extension parsed then discarded → `mvn deploy` pushes jar then pom, pom returns "already-present" and is **silently dropped**, `GET ….pom` serves jar bytes. `delete` removes only the meta key (chunks leak, deleted versions persist); `<latest>` is insertion-order not version order.
- **R31 (positive)** — SHA1 verify on resolve, content-addressed idempotent chunks, transient-only DHT retry are sound. No GPG/signature support.

### Docs
- **R32 [#283]** — `resource-reference.md`: "three built-in qualifiers" lists four (line 9 vs 13-16); @Notify SMTP/HTTP key tables match neither code nor example (`tls` vs `tls_mode`, top-level `username` vs nested `auth`, `provider`/`from` vs `provider_hint`/`from_address`); TIERED "cluster-wide consistency" false; retry/metrics TOML examples cannot provision; `${secrets:}` example misleading for slice config.

## Test coverage holes
- SPI: zero tests for `releaseAll`/refcounting/close-on-last-consumer/transient classification — exactly where R1-R3 live.
- @Http: no request/response/JSON round-trip test (no mock server).
- @Notify: no retry-schedule/failure-classification/delivery test.
- @Scheduled: unit + suite-08 E2E exist, but **no example uses it**.
- Pub/sub: suite-08 `test-pub-sub.sh` tests **streams, not topics** — no test ever verifies a topic subscriber was invoked.
- Interceptors: zero behavior tests for retry/CB/rate-limit/logging/metrics; no TOML-binding test (why R24 is latent); no wrapper-composition/ordering test.
- Artifact-repo: no jar+pom collision test, no classifier test, no auth test.

## Recommended priorities
1. **SPI dual-path lifecycle (R1/R2)** — single fix stops resource leaks across DB/@Notify/interceptors and the cached-failure FSM defeat. Highest leverage.
2. **The two missing-nodeId keys (R11/R16)** — one fix-shape (key includes nodeId, or refcount removals through CDM) closes both critical cluster-corruption bugs.
3. **Interceptor config provisioning (R24) + silent-default (R23)** — two of six interceptors are unusable and the silent-default makes misconfig corrupt data; fix binding to fail loud on missing required config.
4. **Artifact push auth (R29)** — security.
5. **@Scheduled SINGLE-mode fire (R12)** — the feature doesn't work when the leader doesn't host the slice.
6. The rest as ranked; docs (R32) cheap and high-confusion.
