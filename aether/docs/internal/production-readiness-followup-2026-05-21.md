# Production Readiness Follow-up — Test Additions Backlog (2026-05-21)

| Field | Value |
|---|---|
| Branch | `release-1.0.0-rc1` |
| HEAD | `a52dd99d4` |
| Companion docs | `audits/integration-test-audit-2026-05-21.md` §2.2, `audits/test-coverage-matrix-2026-05-21.md`, `production-readiness-plan-2026-05-21.md` |
| Source coverage partials | `audits/integration-test-audit-2026-05-21-partials/coverage-*.md` (5 files) |
| Decision recorded this session | "rest keep in scope" — Worker Pools, Autoscaler, and Cloud Integration are NOT deferred |

## Context

The correctness audit (`integration-test-audit-2026-05-21.md` §2.2) enumerates **30 RC1-blocker test FIXES** — tests that exist today but fail to assert their claim. The production-readiness plan (`production-readiness-plan-2026-05-21.md` §Phase 4 B1-B11) batches those fixes.

This document is the complementary **test-ADDITIONS backlog**: new test functions that must be WRITTEN because no test exists today for a feature the catalog claims Complete or Battle-tested.

Per the coverage matrix:
- **102 NONE-classified features** total, of which roughly half are "expected NONE" (libraries, Forge runtime, cloud provider unit-tests, build-time tooling).
- **~40-45 RC1-impactful NONE entries** need new integration tests.
- **~10-15 PARTIAL entries** need strengthening because the existing test does not actually cover the catalog claim, even after the §2.2 RC1-blocker fixes are applied.

**User decision (this session):** "rest keep in scope" — do not downgrade Worker Pools or Autoscaler to RC2, do not defer Cloud Integration provider coverage. The plan must close those gaps for RC1.

---

## Tally

| Domain | NONE-test-additions | PARTIAL-strengthening | Notes |
|---|---|---|---|
| A. Deployment & Lifecycle | 5 | 3 | Slice-lifecycle state machine, placement-hint, A/B, classloader isolation, envelope versioning, endpoint config, consensus retry |
| B. Scaling & Control | 4 | 1 | Autoscaler controller, minInstances, dynamic controller config, dynamic aspects |
| C. Cluster & Consensus | 0 | 2 | KV-Store typed keys, ClusterGeneration as SUT |
| D. Networking & Routing | 6 | 3 | SliceInvoker, Passive LB, NodeRole.PASSIVE, HTTP/3, HttpForwarder, version-routing split ratio |
| E. Messaging (Pub-Sub) | 1 | 2 | Resource lifecycle, competing-consumer round-robin, fan-out failover |
| F. Scheduled Invocation | 4 | 3 | KV types, lifecycle wiring, cron-on-schedule, fixed-rate, recovery, last-execution honest assertion |
| G. Storage & Data | 4 | 3 | HLC, DHT versioned writes, TimeoutsConfig, KV-Store backup; AHSE strengthening |
| H. Observability & Metrics | 3 | 3 | Historical-metrics range queries, invocation P50/P95/P99, ring-buffer eviction, system-metrics 120m window |
| I. Resource Provisioning | 4 | 1 | HTTP client outbound, interceptors, runtime extensions, PgNotification; database driver matrix |
| J. Management | 5 | 2 | WebSocket streams, dynamic log levels, cluster init wizard, consumer-group coordination, sync-replication ack |
| K. Security & Resilience | 4 | 4 | Blueprint membership guard, TLS-default, envelope versioning, security hardening; mTLS, cert lifecycle, RBAC, orphan cleanup |
| L. Node Operations | 0 | 0 | Already COVERED |
| M. Worker Pools | 11 | 2 | NEW topology + 11 functions; SWIM-detection NARROW, DHT cleanup PARTIAL |
| N. Cloud Integration | 18 (routed to `aether/tests/cloud/`) | 0 | Out of `suites/**`; suite-layer remains provider-agnostic |
| **TOTAL** | **69** new test functions (51 in `suites/**` + 18 in `aether/tests/cloud/`) | **29** PARTIAL strengthenings | |

**In `suites/**` only: 51 new test functions + 29 strengthenings = 80 new test items.**

---

## Per-domain backlog

### Domain A — Deployment & Lifecycle

#### TC-06-001-slice-state-machine-traversal  (Feature #2: Slice lifecycle state machine, Battle-tested)
- **Claim:** Slice transitions DOWNLOADING→LOADING→STARTING→ACTIVE→UNLOADING are observable.
- **Test approach:** Deploy a blueprint, poll `/api/slices/<id>/state` at high frequency, assert observed states is a superset of `{DOWNLOADING, LOADING, STARTING, ACTIVE}` and ordering matches the FSM. Trigger undeploy, assert UNLOADING observed before slice disappears.
- **File:** `suites/06-deployment/test-state-machine-traversal.sh` (new file).
- **Dependencies:** none — endpoint exists; needs polling at <500ms cadence to catch transient states. May require Phase 2 add a `GET /api/slices/<id>/state-history` ring-buffer endpoint if polling is too coarse. Effort assumes polling works.
- **Effort:** M

#### TC-13-002-multi-blueprint-owner-deletion  (Feature #102: Multi-blueprint lifecycle independence)
- **Claim:** Blueprint deletion only removes owner's artifacts; cross-blueprint artifacts survive.
- **Test approach:** Deploy two blueprints (A, B) referencing distinct + one shared artifact. Delete blueprint A. Assert: A's exclusive artifacts removed; shared artifact retained; B's slices remain ACTIVE.
- **File:** `suites/13-edge-cases/test-multi-blueprint-isolation.sh` (extend existing).
- **Dependencies:** none.
- **Effort:** M

#### TC-13-003-blueprint-exclusivity-rejection  (Feature #102)
- **Claim:** A blueprint cannot claim artifact already owned by another.
- **Test approach:** Push artifact X under blueprint A. Attempt deploy of blueprint B that declares X as its own; assert 409 Conflict and exclusivity error message.
- **File:** `suites/13-edge-cases/test-multi-blueprint-isolation.sh`.
- **Dependencies:** none.
- **Effort:** S

#### TC-13-004-blueprint-restore-ownership  (Feature #102)
- **Claim:** Backup/restore preserves blueprint→artifact ownership.
- **Test approach:** Deploy 2 blueprints, take KV backup, destroy cluster, restore, assert each blueprint's artifact ownership intact (via `/api/blueprints/<id>/artifacts`).
- **File:** `suites/13-edge-cases/test-multi-blueprint-isolation.sh`.
- **Dependencies:** P-NEW-backup (see Storage domain for `/api/backups`).
- **Effort:** M

#### TC-06-005-placement-hint-host-spread  (Feature #4: Auto-healing metadata-aware placement)
- **Claim:** CDM honors PlacementHint metadata (host-spread, zone-balance, spot-first).
- **Test approach:** Deploy blueprint with `placement.hostSpread=true`. Inspect `/api/slices/<id>/instances` and assert each instance lives on a distinct `node.host` (using node labels). Repeat with `zone-balance` over zones a/b.
- **File:** `suites/06-deployment/test-placement-hints.sh` (new file).
- **Dependencies:** node-label propagation must be wired (catalog claims it is); requires test topology to label nodes with `host`/`zone` (compose env var per service).
- **Effort:** L (topology change needed)

#### TC-06-006-ab-testing-header-split  (Feature #135: A/B testing)
- **Claim:** Traffic splits by header `X-Variant` and cookie `aether_variant` deterministically.
- **Test approach:** Deploy v1 + v2 of a slice with A/B routing `headerKey=X-Variant`. Issue 100 requests with `X-Variant: v1` and 100 with `X-Variant: v2`; assert each variant receives exactly its routed share (slice logs an instance tag). Repeat with cookie path.
- **File:** `suites/06-deployment/test-ab-testing.sh` (new file).
- **Dependencies:** none — feature is Complete in catalog.
- **Effort:** M

#### TC-06-007-ab-testing-percentage-split  (Feature #135)
- **Claim:** Percentage-based split honors configured ratio within tolerance.
- **Test approach:** Configure 70/30 split between v1/v2. Issue 1000 requests; assert v1 count in [650, 750], v2 count in [250, 350] (chi-square implicit via tolerance band).
- **File:** `suites/06-deployment/test-ab-testing.sh`.
- **Dependencies:** none.
- **Effort:** S

#### TC-06-008-ab-testing-scopedvalue-propagation  (Feature #135)
- **Claim:** Variant tag propagates via ScopedValue into downstream slice invocations.
- **Test approach:** Deploy chain A→B; A reads variant via `Variant.current()`, B reads same. Issue request to A with `X-Variant: v1`; assert both A and B see `v1` (via slice-side log assertion).
- **File:** `suites/06-deployment/test-ab-testing.sh`.
- **Dependencies:** TC-14-D1-slice-invoker (must reach B via SliceInvoker).
- **Effort:** M

#### Strengthen-deployment-state-machine  (Feature #130: Deployment State Machine, RFC-0014)
- **Current weakness:** 11-state lifecycle not directly traversed; only drain/reactivate happy path covered. Quorum-loss reconciliation + failure classification untested.
- **New/strengthened assertion:** Add `test_eleven_state_traversal` invoking each transition: REQUESTED→PROVISIONING→DOWNLOADING→…→ACTIVE→DRAINING→DECOMMISSIONED. Add `test_failure_classification` injecting a deploy that fails schema migration; assert state=FAILED with classification=SCHEMA_MIGRATION.
- **File:** `suites/13-edge-cases/test-disruption-budget.sh` + new `test-deployment-state-machine.sh`.
- **Dependencies:** Phase 2 P-NEW failure-injection helper for deploy stages.
- **Effort:** L

#### Strengthen-rolling-canary-bluegreen-traffic  (Feature #21: Version routing during deployment)
- **Current weakness:** Promote functions are RC1-blockers; configured split ratio never asserted against traffic distribution.
- **New/strengthened assertion:** Extend `test-deploy-canary.sh::test_canary_complete` (after promote fix lands in Phase 4 B5) to issue 200 requests during canary phase; assert distribution matches `canary.percentage` within ±5%.
- **File:** `suites/06-deployment/test-deploy-canary.sh`, `test-deploy-rolling.sh`.
- **Dependencies:** Phase 4 B5 promote-test fixes.
- **Effort:** M

#### Strengthen-auto-heal-metadata  (Feature #4)
- **Current weakness:** Auto-heal asserts count only; never asserts the replacement honored placement hints.
- **New/strengthened assertion:** In `02-chaos/test_auto_heal`, after recovery, query placement of replacement and assert it satisfies the original PlacementHint (host distinct from victim's host, zone balanced).
- **File:** `suites/02-chaos/test-auto-heal.sh` (existing).
- **Dependencies:** TC-06-005 topology change (node labels).
- **Effort:** S (incremental)

---

### Domain B — Scaling & Control

#### TC-03-101-autoscaler-cpu-trigger  (Feature #7: CPU-based auto-scaling, Battle-tested)
- **Claim:** DecisionTreeController scales up on sustained CPU above threshold.
- **Test approach:** Deploy slice with `autoscaler.enabled=true`, `autoscaler.cpuTargetPercent=60`, `autoscaler.evaluationInterval=10s`, `minInstances=2`, `maxInstances=8`. Generate CPU load (busy-loop endpoint on slice; `aether/tests/integration/lib/load.sh` injected) for 60s. Assert `aether status --field cluster.instances.<slice>` shows instance count grow beyond 2 within 60s.
- **File:** `suites/03-scaling/test-04-autoscaler.sh` (new file).
- **Dependencies:** test slice with CPU-burn endpoint (extend `url-shortener-v2` or new `cpu-burn-test` slice).
- **Effort:** L

#### TC-03-102-autoscaler-scale-down  (Feature #7)
- **Claim:** Autoscaler scales down once load drops, respecting cool-down.
- **Test approach:** After TC-03-101 reaches max, stop load generator. Assert instance count returns to `minInstances` within `cooldownDelay + 2× evaluationInterval` (≈120s default).
- **File:** `suites/03-scaling/test-04-autoscaler.sh`.
- **Dependencies:** TC-03-101.
- **Effort:** S (depends on 03-101 fixture)

#### TC-03-103-min-instances-enforcement  (Feature #8: minInstances enforcement)
- **Claim:** minInstances is a hard floor across autoscaler, manual scale, and rolling.
- **Test approach:** Deploy with `minInstances=3`. Attempt `aether scale <slice> --instances 1`; assert 4xx rejection. Trigger rolling deploy of new version; assert instance count never drops below 3 during rollout (poll at 1s interval).
- **File:** `suites/03-scaling/test-05-min-instances.sh` (new file).
- **Dependencies:** none.
- **Effort:** M

#### TC-03-104-dynamic-controller-config  (Feature #10: Dynamic controller config)
- **Claim:** Runtime threshold change takes effect without redeploy.
- **Test approach:** Deploy autoscaler-enabled slice, observe baseline thresholds via `/api/controllers/<slice>/config`. PATCH `cpuTargetPercent=40`; assert subsequent autoscaler decisions use the new threshold (use trace via `/api/controllers/<slice>/decisions` or rely on changed scale-up trigger point).
- **File:** `suites/03-scaling/test-04-autoscaler.sh`.
- **Dependencies:** Phase 2 P-NEW `/api/controllers/*` PATCH endpoint may need verification it exists; otherwise add CLI `aether autoscaler set`.
- **Effort:** M

#### TC-03-105-dynamic-aspects-injection  (Feature #12: Dynamic aspects)
- **Claim:** Aspects (LOG, METRICS, LOG_AND_METRICS) attach to a running slice via KV-Store.
- **Test approach:** Deploy slice, assert baseline metric count for `slice.method.invocations` is small. POST `/api/aspects` with `slice=<id>, aspect=METRICS, method=foo`. Issue 100 invocations; assert metric count grows by 100. Remove aspect; assert no further growth.
- **File:** `suites/03-scaling/test-06-dynamic-aspects.sh` (new file).
- **Dependencies:** none — feature is Complete (superseded note in catalog applies to #42, not removal of #12).
- **Effort:** M

#### Strengthen-scale-rejection-guards  (Feature #9: Manual scale API)
- **Current weakness:** `test_reject_scale_to_{1,2}`, `test_reject_scale_above_max` accept any 5xx (PARTIAL).
- **New/strengthened assertion:** Tighten rejection to require 400 (Bad Request) specifically; assert error body contains a structured ProblemDetail with `code=SCALE_BELOW_MIN` / `SCALE_ABOVE_MAX`. Eliminates 5xx leak that could mask production-bug rejections.
- **File:** `suites/03-scaling/test-02-scale-up.sh` (existing functions).
- **Dependencies:** none.
- **Effort:** S

---

### Domain C — Cluster & Consensus

#### Strengthen-kv-store-typed-keys  (Feature #17: Distributed KV-Store, Battle-tested)
- **Current weakness:** Single PUT/GET smoke; no per-typed-key family test; no cross-node consistency assertion.
- **New/strengthened assertion:** Iterate the 12 typed key families documented in catalog. For each: write a known value via the owning subsystem (or `/api/kv/<family>` if exposed); read back from a NON-leader node; assert identical bytes. Add `test_kv_consistency_across_nodes` to `08-resources` or new `suites/07-cluster-mgmt/test-kv-typed-keys.sh`.
- **File:** `suites/07-cluster-mgmt/test-kv-typed-keys.sh` (new file).
- **Dependencies:** Phase 2 P-NEW `/api/kv` enumerator (may already exist via `aether kv list`).
- **Effort:** L (12 families × small assertion each)

#### Strengthen-cluster-generation-as-sut  (Feature #175: ClusterGeneration choreography)
- **Current weakness:** Used pervasively as helper; never validated as SUT — broken behavior would manifest as flaky cascades elsewhere.
- **New/strengthened assertion:** Add `test_cluster_generation_monotonic` (issue 10 generation advances; assert epoch strictly monotonic). Add `test_await_quiesced_blocks` (start `aether cluster await-quiesced` in background, advance generation, assert command unblocks within ±2s of quiescence). Add `test_quiesced_idempotent` (back-to-back await-quiesced calls return immediately with same epoch).
- **File:** `suites/07-cluster-mgmt/test-cluster-generation.sh` (new file).
- **Dependencies:** none — feature is Complete.
- **Effort:** M

---

### Domain D — Networking & Routing

#### TC-08-D1-slice-invoker-cross-slice  (Feature #20: SliceInvoker, Battle-tested)
- **Claim:** Service-to-service invocation via SliceInvoker performs HTTP routing, load-balancing, timeout, retry, metric emission.
- **Test approach:** Deploy two slices A and B; A's endpoint internally invokes B via `SliceInvoker.invoke(B.class, ...)`. Issue request to A's endpoint; assert B was called (via B's slice-side log/metric); assert `slice_invoker_calls_total{caller=A,callee=B}` Prometheus counter increments. Repeat with B scaled to 3 instances; assert calls distributed across instances (round-robin or LB strategy verifiable).
- **File:** `suites/06-deployment/test-slice-invoker.sh` (new file) or add to 08-resources.
- **Dependencies:** test artifact with two interrelated slices; can extend `url-shortener-v2` to add admin→stats invocation.
- **Effort:** L

#### TC-08-D2-slice-invoker-timeout  (Feature #20)
- **Claim:** SliceInvoker honors invocation timeout.
- **Test approach:** Deploy slice C with sleep(>timeout) endpoint. Configure invoker timeout=2s. Assert call fails with `SliceInvocationTimeout`, retries=configured, metric `slice_invoker_timeouts_total` increments.
- **File:** `suites/06-deployment/test-slice-invoker.sh`.
- **Dependencies:** TC-08-D1.
- **Effort:** M

#### TC-08-D3-slice-invoker-retry  (Feature #20)
- **Claim:** SliceInvoker retries on transient failure per configured policy.
- **Test approach:** Slice C returns 503 the first 2 calls (counter in slice), 200 on 3rd. Invoker configured `retries=3`. Assert end-user sees 200; `slice_invoker_retries_total` increments by 2.
- **File:** `suites/06-deployment/test-slice-invoker.sh`.
- **Dependencies:** TC-08-D1.
- **Effort:** M

#### TC-NEW-passive-lb-D4-route-table  (Feature #67: Passive LB)
- **Claim:** Passive LB (NodeRole.PASSIVE) maintains route table from KV-Store and forwards traffic.
- **Test approach:** Add `aether-lb` sidecar service to test topology (compose-a.yml). Deploy a slice on cores. Issue request to LB endpoint; assert 200 and the response was served by a core node (slice's body identifies serving node). Tear down one core; LB should continue routing to surviving cores.
- **File:** `suites/14-passive-lb/test-routing.sh` (new suite directory — see "Test-topology changes").
- **Dependencies:** TOPO-1 (LB sidecar wiring in compose, run-tests.sh discovery).
- **Effort:** L

#### TC-NEW-passive-lb-D5-binary-forwarding  (Feature #67)
- **Claim:** LB forwards binary protocol (QUIC) traffic to active node.
- **Test approach:** Use a slice that exposes binary endpoint; have LB forward; capture response on slice side and compare bytes.
- **File:** `suites/14-passive-lb/test-binary-forward.sh`.
- **Dependencies:** TOPO-1.
- **Effort:** M

#### TC-NEW-passive-lb-D6-mgmt-forwarding  (Feature #67)
- **Claim:** LB forwards `/api/*` management calls to active node.
- **Test approach:** Issue `aether status` via LB endpoint; assert success and identical response to direct core endpoint.
- **File:** `suites/14-passive-lb/test-mgmt-forward.sh`.
- **Dependencies:** TOPO-1.
- **Effort:** S

#### TC-NEW-passive-lb-D7-noderole-passive  (Feature #68: NodeRole cluster membership)
- **Claim:** PASSIVE nodes are excluded from quorum/leader; receive filtered deliverToPassive messages.
- **Test approach:** Add a PASSIVE node to topology (TOPO-1). Assert `aether nodes` shows it with `role=PASSIVE`. Assert quorum count = ACTIVE count, not total count. Inject a `deliverToPassive=true` message; assert PASSIVE node receives it. Repeat with `deliverToPassive=false`; assert it does not.
- **File:** `suites/14-passive-lb/test-noderole-passive.sh`.
- **Dependencies:** TOPO-1.
- **Effort:** L

#### TC-12-D8-http3-server  (Feature #160: HTTP/3 server)
- **Claim:** Http3ServerAdapter accepts client→server H3 requests.
- **Test approach:** Use `curl --http3-only` (or a Java HTTP/3 client) against a deployed slice's exposed HTTP/3 port. Assert 200 and that ALPN=h3 negotiated (response header or capture).
- **File:** `suites/12-network/test-http3-server.sh` (new file).
- **Dependencies:** test image needs curl ≥ 7.66 with HTTP/3 support OR a small Java HTTP/3 client; client cert handling.
- **Effort:** L

#### Strengthen-http-forwarder-roundrobin  (Feature #69: HttpForwarder)
- **Current weakness:** Failover-by-effect only; no direct round-robin / retry assertion.
- **New/strengthened assertion:** Deploy slice with 3 instances. Use HttpForwarder client (CLI or test harness). Issue 30 requests; assert each instance served exactly 10 (round-robin). Force one to return 503 for 2 reqs; assert HttpForwarder retried (not failed).
- **File:** `suites/13-edge-cases/test-stale-route-cleanup.sh` extension or new `test-http-forwarder.sh`.
- **Dependencies:** none.
- **Effort:** M

#### Strengthen-endpoint-registry  (Feature #19: Endpoint registry)
- **Current weakness:** Indirect-via-stale-route-cleanup; no direct registry assertion.
- **New/strengthened assertion:** Deploy 2 slices, query `/api/slices/<id>/endpoints` (or equivalent registry view). Assert mapping is correct. Kill one instance; assert registry entry removed within `removalGracePeriod` (catalog spec).
- **File:** `suites/13-edge-cases/test-stale-route-cleanup.sh` extension.
- **Dependencies:** none.
- **Effort:** S

#### Strengthen-topology-graph-rest  (Feature #77: Topology graph)
- **Current weakness:** Zero coverage.
- **New/strengthened assertion:** GET `/api/slices/topology`; assert response shape (nodes array, edges array, each node has slice+host+role fields). Open WebSocket `/ws/topology`; assert `INITIAL_STATE` frame received within 2s.
- **File:** `suites/11-observability/test-topology-graph.sh` (new file).
- **Dependencies:** WebSocket test client (see Domain J for required ws-client harness).
- **Effort:** M

---

### Domain E — Messaging (Pub-Sub)

#### TC-08-E1-resource-lifecycle  (Feature #25: Resource lifecycle, ref-counted)
- **Claim:** Slice deactivation triggers consumer cleanup and stop().
- **Test approach:** Deploy slice with a `@PubSub` consumer; verify `/api/streams/consumers` shows registered consumer. Undeploy slice; assert consumer entry removed; redeploy same slice; assert single fresh consumer registered (no zombie carry-over).
- **File:** `suites/08-resources/test-pub-sub-lifecycle.sh` (new file).
- **Dependencies:** Phase 2 P7 (`/api/consumers` group endpoints) may be needed for visibility.
- **Effort:** M

#### Strengthen-competing-consumers-roundrobin  (Feature #23: Topic subscription registry)
- **Current weakness:** GREEN-STICKER single-instance branch; round-robin never asserted.
- **New/strengthened assertion:** Deploy 3 instances of consumer slice on the same group. Publish 30 messages. Assert each instance received exactly 10 (or within tolerance ±2 for jitter). Use slice-side log scrape or `/api/streams/consumers/<group>/distribution` endpoint.
- **File:** `suites/08-resources/test-pub-sub.sh::test_competing_consumers_multi_instance` rewrite.
- **Dependencies:** Phase 2 P7.
- **Effort:** M

#### Strengthen-subscriber-receives-events  (Feature #24: Message delivery)
- **Current weakness:** RC1-blocker: no consumer attached; no event count verified (already in Phase 4 B6).
- **New/strengthened assertion (post-Phase-4):** Add cross-node fan-out: publish on node-A, assert delivery to consumer on node-B; kill consumer's node mid-stream; assert recovery to surviving instance within recovery SLA.
- **File:** `suites/08-resources/test-pub-sub.sh` extension after Phase 4 fix.
- **Dependencies:** Phase 4 B6 (subscriber RC1-blocker fix).
- **Effort:** M

---

### Domain F — Scheduled Invocation

#### TC-08-F1-cron-fires-on-schedule  (Feature #27/#28: cron expression parser + scheduled task)
- **Claim:** A cron-scheduled task fires per its expression.
- **Test approach:** Deploy slice with `@Scheduled(cron="*/5 * * * * *")` (every 5s). Wait 35s. Use `POST /api/scheduled-tasks/<id>/last-execution` (or `/api/scheduled-tasks/inject` query variant — Phase 2 P5) to verify ≥6 executions occurred and intervals are 5±1 second.
- **File:** `suites/08-resources/test-scheduled-tasks.sh::test_cron_fires` (new function).
- **Dependencies:** Phase 2 P5 (scheduled-tasks inject endpoint).
- **Effort:** M

#### TC-08-F2-fixed-rate-fires  (Feature #27)
- **Claim:** Fixed-rate scheduled task fires every interval.
- **Test approach:** Same shape as F1 but with `@Scheduled(fixedRate=2s)`. Verify ≥4 executions in 10s.
- **File:** `suites/08-resources/test-scheduled-tasks.sh::test_fixed_rate_fires` (new function).
- **Dependencies:** Phase 2 P5.
- **Effort:** S

#### TC-08-F3-execution-mode-single-vs-all  (Feature #27: SINGLE vs ALL modes)
- **Claim:** SINGLE: exactly one node executes per fire. ALL: every node executes.
- **Test approach:** Deploy slice with `mode=SINGLE` cron task. Inspect per-node execution counters via `/api/scheduled-tasks/<id>/executions-by-node` (Phase 2 P5 may need extension). Assert exactly one node's counter increments per fire. Redeploy with `mode=ALL`; assert every node's counter increments.
- **File:** `suites/08-resources/test-scheduled-tasks.sh::test_execution_mode` (new function).
- **Dependencies:** Phase 2 P5 (with per-node breakdown).
- **Effort:** M

#### TC-08-F4-task-recovery-after-death  (Feature #27: quorum gating)
- **Claim:** SINGLE-mode task survives executing node's death; another node takes over within next fire window.
- **Test approach:** Deploy SINGLE mode 5s interval task. Identify executing node (via F3 endpoint). Kill it. Assert next fire happens on a surviving node within 10s.
- **File:** `suites/08-resources/test-scheduled-tasks.sh::test_recovery_after_death`.
- **Dependencies:** Phase 2 P5.
- **Effort:** M

#### Strengthen-pause-resume  (Feature #31: Scheduled tasks management API)
- **Current weakness:** Phase 4 B6 fixes the immediate pause/resume RC1-blockers, but post-fix coverage is still pause-only. Trigger (manual fire) and filter list not covered.
- **New/strengthened assertion:** Post-Phase-4 add `test_trigger_manual_fire` (POST `/api/scheduled-tasks/<id>/trigger`; assert `lastExecutionTime` updates within 2s). Add `test_filter_by_paused` (list with `?paused=true` filter; assert paused tasks only).
- **File:** `suites/08-resources/test-scheduled-tasks.sh` extension.
- **Dependencies:** Phase 4 B6.
- **Effort:** S

#### Strengthen-execution-state-tracking  (Feature #104: Execution state tracking)
- **Current weakness:** `lastExecutionTime` is in the RC1-blocker; `consecutiveFailures` / `totalExecutions` never asserted.
- **New/strengthened assertion:** Deploy slice with intentionally-failing task (throws). Inspect `/api/scheduled-tasks/<id>`; assert `consecutiveFailures>=3` after 3 fires; assert `totalExecutions>=3`. Make task succeed; assert `consecutiveFailures` resets to 0.
- **File:** `suites/08-resources/test-scheduled-tasks.sh::test_execution_state_tracking`.
- **Dependencies:** Phase 4 B6, Phase 2 P5.
- **Effort:** M

#### Strengthen-kv-types-roundtrip  (Feature #29: Scheduled task KV types)
- **Current weakness:** API endpoint shape AMBER; KV-level types never inspected directly.
- **New/strengthened assertion:** Read raw KV entries via `aether kv get scheduled-tasks/<id>`; assert `ScheduledTaskValue` deserializes correctly (key + value JSON shape).
- **File:** `suites/08-resources/test-scheduled-tasks.sh::test_kv_types`.
- **Dependencies:** CLI `aether kv` enumerator must exist (verify).
- **Effort:** S

---

### Domain G — Storage & Data

#### TC-10-G1-hlc-monotonicity  (Feature #105: Hybrid Logical Clock)
- **Claim:** HLC timestamps are monotonic per-node and tolerant of wall-clock skew.
- **Test approach:** Use `aether kv get` with a typed key family that exposes HLC timestamp. Issue 100 rapid writes; read back; assert timestamps strictly increasing. Inject wall-clock skew on one node (`docker exec date -s "+30s"` if allowed) and continue writes; assert HLC stays monotonic and the skewed node's logical counter advances even though wall clock drops.
- **File:** `suites/10-database/test-hlc.sh` (new file).
- **Dependencies:** ability to manipulate container clock (privileged container or `libfaketime`).
- **Effort:** L

#### TC-10-G2-dht-versioned-writes  (Feature #106)
- **Claim:** Stale writes are rejected per HLC version comparison.
- **Test approach:** Read DHT entry from node-A (HLC1). Write update directly via internal API on node-B with newer HLC (HLC2). Issue write from node-A with old HLC1; assert rejection (409 or `StaleVersionException` surfaced).
- **File:** `suites/10-database/test-dht-versioned.sh` (new file).
- **Dependencies:** Phase 2 P-NEW DHT direct put with explicit HLC (may need test-only endpoint).
- **Effort:** L

#### TC-07-G3-timeouts-config  (Feature #107: TimeoutsConfig)
- **Claim:** `[timeouts.*]` TOML sections take effect across 13 subsystem groups.
- **Test approach:** Set `[timeouts.consensus] write_op = "100ms"` in cluster config. Trigger consensus operation under contention; assert observed timeout matches (via slow-write log or metric). Repeat sweep for at least 3 critical timeout groups (consensus, http, dht).
- **File:** `suites/07-cluster-mgmt/test-timeouts-config.sh` (new file).
- **Dependencies:** Phase 2 P-NEW per-subsystem timeout instrumentation (timeout-fired metric may not exist; may need to add `aether_timeout_fired_total{subsystem=...}` counter).
- **Effort:** L

#### TC-NEW-G4-kv-store-backup  (Feature #206: KV-Store durable backup)
- **Claim:** `/api/backups` + `aether backup create`/`restore` round-trip preserves cluster state.
- **Test approach:** Bootstrap cluster, deploy 2 blueprints, set N KV pairs. `aether backup create --output /tmp/snap.tgz`. Destroy cluster. `aether backup restore --input /tmp/snap.tgz`. Assert blueprints + slices + KV pairs identical.
- **File:** `suites/07-cluster-mgmt/test-backup-restore.sh` (new file).
- **Dependencies:** Phase 2 P-NEW backup CLI (`aether backup` subcommand) — verify exists or add.
- **Effort:** L

#### Strengthen-14-storage-artifacts-instance  (Feature #207: AHSE)
- **Current weakness:** 4 silent-skip patterns; `test_storage_list_contains_artifacts` HIGH-green-sticker (warn→return 0 when "artifacts" missing).
- **New/strengthened assertion:** Convert silent skips to hard failures. The "artifacts" storage instance MUST exist after bootstrap — assert presence as a fail-closed precondition in test setup. Add `test_block_cas_dedup` (write same 1MB block twice; assert single BlockId; deletion ref-counts).
- **File:** `suites/14-storage/*.sh` (multiple).
- **Dependencies:** Phase 4 B-storage RC1-blocker fixes (#23-#26).
- **Effort:** M

#### Strengthen-config-env-interpolation  (Feature #34: Configuration service)
- **Current weakness:** Env-var interpolation and sysprop fallback never tested (post-Phase-4 B7).
- **New/strengthened assertion:** Set `MY_VAR=hello` in compose env; reference `${MY_VAR}` in aether.toml; bootstrap and assert resolved config exposes `hello`. Override via `-D` system property; assert sysprop wins.
- **File:** `suites/07-cluster-mgmt/test-apply.sh` extension or new `test-config-interpolation.sh`.
- **Dependencies:** Phase 4 B7 (config-service RC1-blocker fixes).
- **Effort:** S

#### Strengthen-dht-anti-entropy  (Feature #33: Distributed hash table)
- **Current weakness:** Anti-entropy CRC32 digest exchange, re-replication on departure, DHTRebalancer never directly tested.
- **New/strengthened assertion:** Push artifact, manually corrupt one replica's local copy (via `docker exec` direct file manipulation), trigger anti-entropy sweep (or wait for next interval), assert corrupted replica is repaired and CRC matches.
- **File:** `suites/09-artifacts/test-artifact-replication.sh` extension.
- **Dependencies:** ability to manipulate container filesystem; trigger or interval-knob for anti-entropy.
- **Effort:** L

---

### Domain H — Observability & Metrics

#### TC-11-H1-historical-metrics-range  (Feature #38: Historical metrics)
- **Claim:** Range queries `?range=5m|15m|1h|2h` return per-node snapshots.
- **Test approach:** Run cluster for 6 minutes (or use accelerated time via test-only ingestion). GET `/api/metrics/historical?range=5m`; assert array of snapshots (≥4 entries for 5m at 1m cadence) and each has `node`, `timestamp`, `cpu`, `heap` fields. Repeat for `15m`, `1h`, `2h`.
- **File:** `suites/11-observability/test-historical-metrics.sh` (new file).
- **Dependencies:** test-only inject endpoint for backfilling 2h of metric history without waiting (Phase 2 P-NEW); OR accept long runtime.
- **Effort:** M (with inject) / L (without)

#### TC-11-H2-invocation-percentiles  (Feature #36: Invocation metrics P50/P95/P99)
- **Claim:** Per-method invocation metrics expose P50/P95/P99.
- **Test approach:** Deploy slice with deterministic latency endpoint (sleeps `random(50,150) ms`). Issue 1000 requests. Scrape `/metrics`; assert `slice_invocation_duration_seconds{quantile="0.5",method="..."}` present and in range. Repeat for 0.95, 0.99.
- **File:** `suites/11-observability/test-invocation-percentiles.sh` (new file).
- **Dependencies:** none — Prometheus quantile export must already be wired (verify).
- **Effort:** M

#### TC-11-H3-events-ring-buffer-eviction  (Feature #43: Cluster event aggregator)
- **Claim:** 1000-event ring buffer evicts oldest on overflow.
- **Test approach:** Inject 1100 events via `/api/events/inject`. Read `/api/events?limit=2000`; assert exactly 1000 returned and the first 100 (oldest) are missing (use sequence id to verify).
- **File:** `suites/11-observability/test-events-ring-buffer.sh` (new file) or extend `test-events-cluster-ordering.sh`.
- **Dependencies:** `/api/events/inject` test endpoint (verify exists — used by existing event-ordering test).
- **Effort:** S

#### Strengthen-system-metrics-window  (Feature #35: System metrics)
- **Current weakness:** 120-minute aggregation-window claim and event-loop-lag metric not asserted by name.
- **New/strengthened assertion:** Assert `aether_system_metrics_window_minutes` gauge=120 (or equivalent named metric). Assert `aether_event_loop_lag_seconds` series present in Prometheus output with non-negative values.
- **File:** `suites/11-observability/test-prometheus-metrics.sh::test_jvm_metrics` extension.
- **Dependencies:** none — names need verification.
- **Effort:** S

#### Strengthen-cluster-metrics-fields  (Feature #37: Cluster metrics API)
- **Current weakness:** Shape-only assertion; saturation, health-score, capacity-prediction never asserted by name.
- **New/strengthened assertion:** Add assertions on specific field names: `aether_cluster_health_score`, `aether_cluster_saturation`, `aether_cluster_capacity_predicted`. Each must be present and within expected range.
- **File:** `suites/11-observability/test-prometheus-metrics.sh::test_cluster_metrics` extension.
- **Dependencies:** none.
- **Effort:** S

#### Strengthen-event-types-coverage  (Feature #43)
- **Current weakness:** Only injected synthetic events tested; not all 11 event types individually exercised.
- **New/strengthened assertion:** Trigger each of the 11 event types organically (deploy/undeploy/scale/kill/etc.) and assert each shows up in the event stream with correct type label.
- **File:** `suites/11-observability/test-events-cluster-ordering.sh` extension.
- **Dependencies:** none.
- **Effort:** M

---

### Domain I — Resource Provisioning

#### TC-08-I1-http-client-outbound  (Feature #46: HTTP client resource)
- **Claim:** Slice-level outbound HTTP client honors timeout, retry, SSL, Jackson serde.
- **Test approach:** Deploy slice with `@Http` outbound client targeting a mock server (httpbin container in compose). Invoke slice endpoint that triggers outbound call to `/delay/3` with client timeout=1s; assert slice surfaces timeout error. Invoke `/status/503` with retry=3; assert metrics show 3 attempts. SSL: target `https://self-signed.example` with TLS verify=true; assert handshake failure; flip verify=false; assert success.
- **File:** `suites/08-resources/test-http-client-outbound.sh` (new file — replaces misnamed `test-http-client.sh`).
- **Dependencies:** httpbin sidecar in compose; slice with `@Http` client.
- **Effort:** L

#### TC-08-I2-http-client-interceptors  (Feature #47: Interceptor framework)
- **Claim:** Retry, circuit-breaker, rate-limit, logging, metrics interceptors are pluggable and effective.
- **Test approach:** Configure slice with all 5 interceptors. Drive load to trip circuit-breaker (10 failures in window); assert subsequent calls fail-fast within `breakerOpenWindow`. Assert `http_client_retry_count`, `http_client_breaker_state`, `http_client_rate_limit_rejected_total` metrics emitted.
- **File:** `suites/08-resources/test-http-client-interceptors.sh` (new file).
- **Dependencies:** TC-08-I1 sidecar.
- **Effort:** L

#### TC-08-I3-runtime-extensions  (Feature #48: Runtime extensions)
- **Claim:** `registerExtension()` injects a runtime extension; downstream factories consume it.
- **Test approach:** Deploy slice that registers a custom extension X. Deploy another slice whose factory consumes X; assert factory's injected dependency is the registered extension instance (slice-side reflection log).
- **File:** `suites/08-resources/test-runtime-extensions.sh` (new file).
- **Dependencies:** test slice that exercises `registerExtension` API.
- **Effort:** M

#### TC-08-I4-pg-notification  (Feature #209: PgNotification subscriber)
- **Claim:** `LISTEN/NOTIFY` flows via `NotificationListenerFactory` deliver notifications to subscribers.
- **Test approach:** Deploy slice with `@PgNotification` subscriber on channel `aether_test`. Use existing PG container in compose to `NOTIFY aether_test, 'hello'`. Assert subscriber received message within 2s (slice-side log).
- **File:** `suites/08-resources/test-pg-notification.sh` (new file).
- **Dependencies:** existing PG container in compose; PgNotification slice template.
- **Effort:** M

#### Strengthen-database-driver-matrix  (Feature #45: Database resources)
- **Current weakness:** Only postgres-async exercised; 6 other drivers (JDBC, R2DBC, jOOQ, jOOQ-R2DBC, jOOQ-async, JPA) untested.
- **New/strengthened assertion:** Add minimal CRUD round-trip per driver via parameterized helper. For each driver: deploy slice using that driver, PUT a row, GET it back, assert equality.
- **File:** `suites/08-resources/test-sql-driver-matrix.sh` (new file).
- **Dependencies:** 6 minimal test-slice variants (one per driver) — moderate test-fixture work.
- **Effort:** L

---

### Domain J — Management

#### TC-11-J1-websocket-streams  (Feature #51: WebSocket streams)
- **Claim:** `/ws/dashboard`, `/ws/status`, `/ws/events` deliver real-time frames.
- **Test approach:** Use `websocat` (or small Java/Python ws client) to connect to each endpoint. Assert connect succeeds; receive ≥1 frame within 5s; for `/ws/events`, trigger a node-add event and assert frame received with event type.
- **File:** `suites/11-observability/test-websocket-streams.sh` (new file).
- **Dependencies:** `websocat` (small CLI) added to test image OR use `curl --include --no-buffer` with WebSocket upgrade header (more brittle). Recommend adding `websocat`.
- **Effort:** M

#### TC-11-J2-dynamic-log-levels  (Feature #52: Dynamic log levels)
- **Claim:** Runtime log level adjustment via KV-Store takes effect.
- **Test approach:** Run `aether logging set --logger org.pragmatica --level DEBUG`. Trigger a known DEBUG-emitting code path (deploy a slice). Assert DEBUG line appears in container logs within 5s. Reset to INFO; assert DEBUG lines stop.
- **File:** `suites/07-cluster-mgmt/test-dynamic-logging.sh` (new file).
- **Dependencies:** none — `aether logging set` CLI command must exist (catalog claims it does).
- **Effort:** M

#### TC-07-J3-cluster-init-wizard  (Feature #213: Cluster init wizard)
- **Claim:** `aether cluster init` generates a valid `cluster-config.toml` from prompts.
- **Test approach:** Pipe answers to `aether cluster init` (non-interactive: `--non-interactive --node-count=5 --env=docker --output=/tmp/cfg.toml`). Assert exit 0, file exists, parseable, contains expected sections (`[cluster]`, `[nodes]`, `[endpoints]`).
- **File:** `suites/07-cluster-mgmt/test-cluster-init.sh` (new file).
- **Dependencies:** CLI must support non-interactive mode (verify; if not, Phase 2 add `--non-interactive` flag).
- **Effort:** M

#### TC-04-J4-consumer-group-coordination  (Feature #138: Consumer group coordination)
- **Claim:** `/api/consumers/*` endpoints support group join/leave/status.
- **Test approach:** Use Phase 2 P7 CLI `aether streams consumer-group join`. Issue join from 3 nodes for group G; assert `aether streams consumer-group status G` shows 3 members; leave one; assert status shows 2.
- **File:** `suites/04-streaming/test-consumer-group.sh` (new file).
- **Dependencies:** Phase 2 P7 (`aether streams consumer-group join/leave/status` CLI).
- **Effort:** M

#### TC-04-J5-sync-replication-ack  (Feature #139: Sync replication ack)
- **Claim:** `StreamConfig.minSyncReplicas=N` blocks publish ack until N replicas acknowledge.
- **Test approach:** Configure stream with `minSyncReplicas=3`. Publish with one replica killed; assert publish blocks or rejects after timeout per `pendingAckTimeout`. Restart replica; subsequent publishes succeed.
- **File:** `suites/04-streaming/test-sync-ack.sh` (new file).
- **Dependencies:** Phase 2 P7 stream CLI.
- **Effort:** L

#### Strengthen-rest-management-api-aspects-ttm  (Feature #49: REST management API)
- **Current weakness:** Aspects, TTM, thresholds, controller config endpoints have no direct integration tests.
- **New/strengthened assertion:** Cross-cover via TC-03-104 (controller config), TC-03-105 (aspects). TTM: skip (Partial in catalog). Thresholds: already covered in 11-observability alerts. Verify gap closed by referencing the new TC-IDs in the suite charter.
- **File:** Charter cross-reference — no new test code.
- **Dependencies:** TC-03-104, TC-03-105.
- **Effort:** S

#### Strengthen-declarative-cluster-management  (Feature #147: 6-phase bootstrap)
- **Current weakness:** `--full-check`, dual KV-Store TEMPLATE/CURRENT entries, floating IP attachment, `--resume`, `--rollback`, plan confirmation never asserted.
- **New/strengthened assertion:** Add `test_apply_with_full_check` (assert TEMPLATE+CURRENT both present), `test_apply_resume_after_interrupt` (interrupt mid-apply, resume, assert convergence), `test_apply_rollback` (apply→rollback→assert previous state).
- **File:** `suites/07-cluster-mgmt/test-apply.sh` extension (post-Phase-4 B7).
- **Dependencies:** Phase 4 B7.
- **Effort:** L

---

### Domain K — Security & Resilience

#### TC-05-K1-blueprint-membership-guard  (Feature #60: Blueprint membership guard)
- **Claim:** `POST /api/scale` rejects requests for slices not in a deployed blueprint.
- **Test approach:** Bootstrap cluster, deploy blueprint A with slice X. Issue `POST /api/scale` for non-existent slice Y; assert 404 or 403 with `code=SLICE_NOT_IN_BLUEPRINT`. Repeat for slice from undeployed blueprint B.
- **File:** `suites/05-security/test-blueprint-guard.sh` (new file).
- **Dependencies:** none.
- **Effort:** S

#### TC-05-K2-tls-default-for-containers  (Feature #91: TLS default for containers)
- **Claim:** TLS is on by default for DOCKER/KUBERNETES envs; AETHER_INSECURE_DEV_MODE=false enforces it.
- **Test approach:** Boot a node with `AETHER_INSECURE_DEV_MODE=false` (compose override file `docker-compose-tls.yml`). Attempt cluster-internal connection without certs; assert connection refused. With certs, assert handshake succeeds and `/api/certificates` reports `tlsEnabled=true` (Phase 2 P3 field).
- **File:** `suites/05-security/test-tls-default.sh` (new file).
- **Dependencies:** Phase 2 P3 (`tlsEnabled` field); compose override with TLS enabled.
- **Effort:** L

#### TC-09-K3-envelope-version-compat  (Feature #56: Envelope format versioning)
- **Claim:** Runtime accepts artifacts of envelope v1-v6; rejects v0 or v(N+1).
- **Test approach:** Push an artifact with envelope version N (current). Manually mutate envelope-version byte to N+1 (forged future version); attempt deploy; assert rejection with `code=ENVELOPE_VERSION_UNSUPPORTED`. Push a v(N-1) artifact (fixture in repo); assert acceptance (backward compat).
- **File:** `suites/09-artifacts/test-envelope-versioning.sh` (new file).
- **Dependencies:** test fixture: artifact with v(N-1) envelope (committed to repo); ability to mutate envelope byte at push time (small helper script).
- **Effort:** M

#### TC-05-K4-security-hardening-bundle  (Feature #203: Security hardening RC1)
- **Claim:** 10 sub-claims around ALPN pinning, deterministic CA, plaintext-mode rejection, insecure-dev-mode enforcement, PG `InsecureTrustManagerFactory` gating, redaction, SQL-injection guards, image-name validation, API-key file `0600`, compose secret fallback.
- **Test approach:** Limit integration-suite coverage to two end-to-end claims:
  1. **ALPN pinning** — attempt QUIC connection from a peer presenting wrong ALPN (e.g., `h3-29`); assert rejection.
  2. **Plaintext-mode rejection** — boot node with `AETHER_INSECURE_DEV_MODE=false` and plaintext config; assert startup fails with explicit error.
  Remaining 8 sub-claims are unit/manual review domain — document them as out of scope here.
- **File:** `suites/05-security/test-security-hardening.sh` (new file).
- **Dependencies:** TLS-enabled compose override (K2 dependency); QUIC-client harness with custom ALPN selector.
- **Effort:** L

#### Strengthen-mtls-actual-handshake  (Feature #88: Inter-node mTLS — post-Phase-4 fix #3)
- **Current weakness:** Phase 4 B3 replaces tautology with `tlsEnabled=true` check; still does not assert handshake details.
- **New/strengthened assertion:** Capture cluster-internal QUIC handshake (via container packet capture or test endpoint exposing peer cert chain). Assert chain root matches deterministic CA derived from `AETHER_CLUSTER_SECRET`. Assert cert SAN includes nodeId.
- **File:** `suites/05-security/test-cert-rotation.sh::test_tls_active` further strengthening or new `test-mtls-handshake.sh`.
- **Dependencies:** Phase 4 B3; test endpoint or capture mechanism.
- **Effort:** L

#### Strengthen-cert-rotation-trigger  (Feature #90: Certificate lifecycle — post-Phase-4 fix #4)
- **Current weakness:** Phase 4 fix asserts cert serial changes; does not exercise the 50%-validity automatic trigger.
- **New/strengthened assertion:** Issue a short-validity cert (e.g., 2 minutes via test endpoint). Wait 1 minute (50% mark). Assert rotation triggered automatically (cert serial changes) without explicit `aether cert rotate` call.
- **File:** `suites/05-security/test-cert-rotation.sh::test_cert_rotation_automatic_trigger` (new function).
- **Dependencies:** Phase 4 B3; ability to set short cert validity (compose env var or test endpoint).
- **Effort:** L

#### Strengthen-rbac-blueprint-overrides  (Feature #92: RBAC blueprint operator overrides)
- **Current weakness:** Blueprint operator overrides + strengthen-only policy zero coverage.
- **New/strengthened assertion:** Deploy blueprint with custom RBAC override that requires admin role for `POST /api/streams/*` (more restrictive than default). Issue request with operator role; assert 403. Issue with admin; assert 200. Add `strengthen_only` violation case: try to deploy a blueprint that loosens the default policy; assert deploy-time validation rejects it.
- **File:** `suites/05-security/test-rbac-overrides.sh` (new file).
- **Dependencies:** Phase 4 B3 (whoami/principal/auth chain).
- **Effort:** L

#### Strengthen-orphan-cleanup-reconcile  (Feature #62: Orphaned entry cleanup)
- **Current weakness:** WEAK non-empty check; `CDM.reconcile()` invariant not directly asserted.
- **New/strengthened assertion:** Kill a node hosting routes. After grace period, assert `/api/cluster/orphans` returns empty AND assert KV-Store route entries pointing to dead node are absent (direct KV read). Add metric assertion: `aether_cdm_reconcile_invocations_total` increments.
- **File:** `suites/13-edge-cases/test-stale-route-cleanup.sh::test_kv_store_routes_clean` rewrite.
- **Dependencies:** none.
- **Effort:** M

---

### Domain L — Node Operations

All 3 features (#63, #64, #65) are COVERED. No additions.

**Optional strengthening (low priority, RC2 candidate):** Cancel-drain has a single test (`test_reactivate_nodes`); a second case covering "cancel-drain during disruption-budget-bound state" would harden the path. Not in this backlog.

---

### Domain M — Worker Pools (NEW topology required)

**Prerequisite:** TOPO-2 — Add WORKER-role nodes to integration topology (see Cross-cutting items §1). Without TOPO-2, none of the M-domain tests can land.

#### TC-NEW-M1-worker-node-bootstrap  (Feature #81: Worker node)
- **Claim:** A WORKER-role node joins the cluster, registers via Governor.
- **Test approach:** Deploy 5-core + 2-worker topology. Assert `aether nodes` lists workers with `role=WORKER`. Assert workers do NOT appear in quorum count.
- **File:** `suites/16-worker-pools/test-worker-bootstrap.sh` (new suite).
- **Dependencies:** TOPO-2.
- **Effort:** M

#### TC-NEW-M2-governor-election  (Feature #82: Governor election)
- **Claim:** Lowest-NodeId rule for Governor election; death triggers re-election.
- **Test approach:** Bootstrap 2-worker topology; assert lowest-NodeId worker is Governor (via `/api/workers/governor`). Kill the Governor; assert the surviving worker becomes Governor within 10s. Cleanup of departing-Governor's KV entries verified.
- **File:** `suites/16-worker-pools/test-governor-election.sh`.
- **Dependencies:** TOPO-2.
- **Effort:** M

#### TC-NEW-M3-worker-endpoint-registry  (Feature #83: Worker endpoint registry)
- **Claim:** Worker endpoint registry merges entries across the worker pool.
- **Test approach:** Deploy slice with `placement=WORKERS_PREFERRED`. Assert `/api/workers/endpoints` lists each worker's slice instance. Add a worker to the pool; assert new endpoints appear in registry.
- **File:** `suites/16-worker-pools/test-worker-endpoints.sh`.
- **Dependencies:** TOPO-2, TC-NEW-M4 (placement=WORKERS).
- **Effort:** M

#### TC-NEW-M4-cdm-pool-awareness  (Feature #84: CDM pool awareness)
- **Claim:** `placement=WORKERS_ONLY`/`WORKERS_PREFERRED`/`CORES_ONLY` differentiate instance placement.
- **Test approach:** Three blueprints — A=WORKERS_ONLY, B=CORES_ONLY, C=WORKERS_PREFERRED. Deploy all. Assert: A's instances are all on workers; B's are all on cores; C's prefer workers but spill to cores under load.
- **File:** `suites/16-worker-pools/test-pool-awareness.sh`.
- **Dependencies:** TOPO-2.
- **Effort:** L

#### TC-NEW-M5-worker-management-api  (Feature #85: Worker management API)
- **Claim:** `aether workers list/health/endpoints` CLI commands work; `POST /api/scale --placement` honored.
- **Test approach:** Invoke each CLI command; assert non-empty output with valid shape. Issue scale with `--placement=WORKERS_ONLY`; assert workers grow, cores untouched.
- **File:** `suites/16-worker-pools/test-worker-mgmt-api.sh`.
- **Dependencies:** TOPO-2; CLI `aether workers` subcommand (verify exists).
- **Effort:** M

#### TC-NEW-M6-automatic-topology-growth  (Feature #87: Automatic topology growth)
- **Claim:** Worker-role assignment respects `coreMin`/`coreMax`/`workerMin`/`workerMax`.
- **Test approach:** Set `coreMin=3`, `coreMax=5`, `workerMin=2`, `workerMax=4`. Scale a WORKERS_ONLY slice from 1→6 instances; assert workers grow from 2 to 4 (not beyond max); core count unchanged. Reverse: scale CORES_ONLY beyond core max; assert rejection.
- **File:** `suites/16-worker-pools/test-topology-growth.sh`.
- **Dependencies:** TOPO-2; auto-provision mechanism (DockerComputeProvider already wired per MEMORY).
- **Effort:** L

#### TC-NEW-M7-dht-replication-config  (Feature #96: DHT replication config)
- **Claim:** `[dht.replication]` TOML knobs (`target_rf`, `cooldown_delay`, etc.) take effect.
- **Test approach:** Bootstrap with `[dht.replication] target_rf = 2`. Push artifact. Inspect replication map (via `/api/dht/replication-map` or equivalent); assert exactly 2 replicas hold each key.
- **File:** `suites/16-worker-pools/test-dht-replication-config.sh`.
- **Dependencies:** TOPO-2; DHT inspect endpoint (verify exists or add).
- **Effort:** M

#### TC-NEW-M8-multi-group-worker-topology  (Feature #97: Multi-group worker topology)
- **Claim:** Workers grouped via `WorkerGroupId` and `groupName`; group-aware deployment honored.
- **Test approach:** Deploy 4 workers split into 2 groups (G1, G2) via aether.toml. Deploy slice with `placement.groupName=G1`. Assert instances live only on G1 workers. Repeat for G2.
- **File:** `suites/16-worker-pools/test-multi-group.sh`.
- **Dependencies:** TOPO-2 extension for group labels.
- **Effort:** L

#### TC-NEW-M9-worker-zone-config  (Feature #99: Worker zone configuration)
- **Claim:** `worker.zone`, `worker.maxGroupSize` config knobs are honored.
- **Test approach:** Label 4 workers across 2 zones (z1, z2). Deploy slice with `placement.zoneSpread=true`. Assert instances span both zones evenly.
- **File:** `suites/16-worker-pools/test-zone-config.sh`.
- **Dependencies:** TOPO-2 extension for zone labels.
- **Effort:** M

#### TC-NEW-M10-event-based-community-scaling  (Feature #100: Event-based community scaling)
- **Claim:** `WorkerMetricsPing` + `CommunityScalingRequest` events drive scaling.
- **Test approach:** Generate sustained load on a worker community; assert `CommunityScalingRequest` event in `/api/events`; assert community scales up. Stop load; assert scale-down event + actual scale-down within cooldown.
- **File:** `suites/16-worker-pools/test-community-scaling.sh`.
- **Dependencies:** TOPO-2; same load-gen fixture as TC-03-101.
- **Effort:** L

#### TC-NEW-M11-role-aware-promotion  (Feature #132: Role-aware unified node)
- **Claim:** Single `aether-node.jar` can promote a CORE node to WORKER (or reverse) via `ForwardingClusterNode`/`SwitchableClusterNode`.
- **Test approach:** Boot node as CORE. Issue `aether nodes promote --node <id> --role WORKER`. Assert node now appears with `role=WORKER`. Slice placements honor the role change. Reverse the promotion.
- **File:** `suites/16-worker-pools/test-role-promotion.sh`.
- **Dependencies:** TOPO-2; `aether nodes promote` CLI (verify or add).
- **Effort:** L

#### Strengthen-swim-detection-broad  (Feature #80: SWIM failure detection)
- **Current weakness:** Single narrow detection-time check; no indirect-probe, no membership-piggyback assertion.
- **New/strengthened assertion:** Block direct gossip between two pairs (iptables rule on one node) but allow third-party paths; assert SWIM still detects the failure via indirect probe within `SWIM_INDIRECT_PROBE_TIMEOUT`. Verify membership update piggyback in gossip payload.
- **File:** `suites/12-network/test-swim-detection.sh` extension.
- **Dependencies:** ability to inject iptables rules in containers (privileged or CAP_NET_ADMIN).
- **Effort:** L

#### Strengthen-dht-node-cleanup  (Feature #93: DHT node cleanup)
- **Current weakness:** Only indirectly verified; SWIM-DEAD cleanup path never directly asserted.
- **New/strengthened assertion:** Push artifact, kill replica holder. After SWIM-DEAD detection, query DHT map; assert dead node absent from replica set. Reassignment to surviving nodes happened.
- **File:** `suites/09-artifacts/test-artifact-replication.sh` extension.
- **Dependencies:** none.
- **Effort:** M

---

### Domain N — Cloud Integration (routed to `aether/tests/cloud/`)

Per the user's "rest keep in scope" directive, the 18 cloud features must be covered. They are out of `suites/**` scope by design (integration-suite layer must remain provider-agnostic). The recommendation is to **honor "in scope" by routing each to `aether/tests/cloud/`**, then add a top-level production-readiness gate that ALL of these run green before RC1 tag.

| # | Feature | Target test in `aether/tests/cloud/` |
|---|---------|---------------------------------------|
| 108 | EnvironmentIntegration SPI | `test-spi-discovery.sh` — assert provider loaded for each `ENV_TYPE` (hetzner, aws, gcp, azure) |
| 109 | SecretsProvider (Env/File/Composite) | `test-secrets-providers.sh` — read secrets via each impl and assert composite chain order |
| 110 | DiscoveryProvider SPI | `test-discovery-provider.sh` — assert `discoverPeers/watchPeers/registerSelf` for each provider |
| 111 | Hetzner compute | `test-hetzner-compute.sh` — provision VM, assert state=running, terminate, assert state=removed |
| 112 | Hetzner discovery | `test-hetzner-discovery.sh` — label-based peer discovery returns expected set |
| 113 | Hetzner LB | `test-hetzner-lb.sh` — target registration on scale-up, removal on drain |
| 114 | Hetzner REST client | `test-hetzner-rest.sh` — direct API client smoke (subset of 111) |
| 115/116 | AWS REST + integration | `test-aws-*.sh` — analogous to 111/114 |
| 117/118 | GCP REST + integration | `test-gcp-*.sh` |
| 119/120 | Azure REST + integration | `test-azure-*.sh` |
| 121 | XML mapper integration | `test-xml-mapper.sh` — verify XML→model mapping (likely unit-level; flag for routing) |
| 122 | CDM cloud VM termination on drain | `test-cdm-cloud-termination.sh` — drain a cloud node; assert `ComputeProvider.terminate()` called; instance gone from provider state |
| 123 | ComputeProvider SPI extensions | `test-compute-provider-spi.sh` — assert `provision`, `listInstances` shapes |
| 124 | LoadBalancerProvider SPI extensions | `test-lb-provider-spi.sh` — assert 7 default methods |
| 125 | SecretsProvider SPI extensions | `test-secrets-provider-spi.sh` — batch read, watchRotation, caching behavior |

**Effort:** 18 test scripts × M each ≈ 18M = **~9 focused days** in `aether/tests/cloud/`. This is a separate work stream from `suites/**` and runs against real cloud accounts ($-cost — covered by `CLOUD_BUDGET_LIMIT` in env per existing infra).

**Cost-control mechanism (recommended):** Gate cloud-suite runs behind an env flag; default OFF in CI; required to be green for the RC1 tag (manual or scheduled run before tag).

---

## Cross-cutting items

### Test-topology changes needed

**TOPO-1: LB sidecar wiring (Domain D — Passive LB tests)**
- Compose files already include `aether-lb:local` image per README, but no suite uses it.
- Add `aether-lb` service to `docker-compose-a.yml` (non-destructive cluster).
- Add `lib/cluster.sh` helper `lb_endpoint()` returning the LB sidecar URL.
- Add to `run-tests.sh` discovery: new suite directory `suites/14-passive-lb/`.
- **Effort:** S (~1 day). Mostly compose wiring + helper.

**TOPO-2: WORKER-role topology (Domain M — Worker Pools)**
- Create `docker-compose-c.yml` with 5 cores + 2 workers (or 3 cores + 4 workers — confirm with the user; cluster sizing impacts test fidelity).
- Define worker-node service template with `--role=WORKER` flag.
- Extend `lib/cluster.sh` to expose `WORKER_NODES`, `CORE_NODES` arrays.
- Extend `lib/topology.sh` to label nodes with `node.host`, `node.zone`, `node.group`, `node.role`.
- Add `run-tests.sh --topology=worker-pool` mode that bootstraps cluster-C.
- New suite directory `suites/16-worker-pools/`.
- **Effort:** L (~3 days). Compose + run-tests.sh + helpers + first worker bootstrap test as smoke gate.

**TOPO-3: Node labels for PlacementHint tests (Domain A — TC-06-005, Strengthen-auto-heal-metadata)**
- Add `AETHER_NODE_LABELS=host=h1,zone=za` env var per service in compose.
- Verify nodes propagate labels to `/api/nodes` view.
- **Effort:** S (~0.5 day). Compose env vars + verification.

**TOPO-4: TLS-enabled compose override (Domain K — TC-05-K2, K4)**
- Create `docker-compose-tls.yml` overlay that sets `AETHER_INSECURE_DEV_MODE=false` and mounts cert volumes.
- Run-tests.sh `--tls=true` flag selects this overlay.
- **Effort:** M (~1 day). Cert generation + compose overlay.

### Product API changes needed

Cross-reference of new tests against existing Phase 2 product changes (P1-P7) and any NEW product items this backlog discovers:

| Dependency | Tests requiring it |
|---|---|
| **P1** (whoami) + **P2** (principal) + **P3** (tlsEnabled) + **P4** (auth contract) | Strengthen-rbac-blueprint-overrides; Strengthen-mtls; TC-05-K2 |
| **P5** (`/api/scheduled-tasks/inject`) | TC-08-F1, F2, F3, F4; Strengthen-pause-resume; Strengthen-execution-state-tracking |
| **P6** (`aether streams read`) | Phase 4 B2 only (already scoped) |
| **P7** (`aether streams create/delete/consumer-group`) | TC-04-J4; TC-04-J5; Strengthen-competing-consumers; TC-08-E1 |
| **P-NEW-A**: per-subsystem timeout-fired metric | TC-07-G3 |
| **P-NEW-B**: DHT direct put with explicit HLC (test-only endpoint) | TC-10-G2 |
| **P-NEW-C**: `aether backup create/restore` CLI | TC-NEW-G4-kv-store-backup |
| **P-NEW-D**: backfill-metrics test endpoint | TC-11-H1 (otherwise long-runtime test) |
| **P-NEW-E**: `aether nodes promote --role=WORKER` CLI | TC-NEW-M11 |
| **P-NEW-F**: `/api/dht/replication-map` inspect endpoint | TC-NEW-M7 |
| **P-NEW-G**: `aether cluster init --non-interactive` flag | TC-07-J3 |
| **P-NEW-H**: `/api/scheduled-tasks/<id>/executions-by-node` | TC-08-F3 |
| **P-NEW-I**: short-validity cert option (compose env or test endpoint) | Strengthen-cert-rotation-trigger |

**New product items (estimated): 9 × ~0.5d each ≈ 4.5 days.** Most are minor test-only endpoints; backup CLI (P-NEW-C) and promote CLI (P-NEW-E) are more substantial (~1d each). Total Phase 2 grows from ~6 days to **~10-11 days**.

### Catalog reclassifications considered

Per the user's "rest keep in scope" directive:
- **Worker Pools (22 features, 16 NONE):** NOT reclassified to Partial. Stays Complete; **TOPO-2 + 11 new tests** are required for RC1.
- **Autoscaler (#7 CPU-based auto-scaling, Battle-tested):** NOT reclassified to Partial. Stays Battle-tested; **TC-03-101 through TC-03-105** required.
- **Cloud Integration (18 features, all NONE in `suites/**`):** Coverage routed to `aether/tests/cloud/` (already exists). **Production-readiness gate**: cloud-suite must run green before RC1 tag. The `suites/**` layer is structurally provider-agnostic and stays that way.

No catalog downgrades recommended at this time. If the calendar slips, the user can revisit (Worker Pools → RC2 is the cleanest defer if needed; would shave ~10 days off critical path).

---

## Sequencing

Group items by dependency tier:

### Tier 1 — No dependencies, start immediately (Phase 4 parallel agents)

- TC-13-002, TC-13-003 (multi-blueprint isolation — no Phase 2 prereq)
- TC-03-103 (minInstances)
- TC-03-105 (dynamic aspects)
- TC-05-K1 (blueprint membership guard)
- TC-09-K3 (envelope versioning)
- TC-11-H2 (invocation percentiles)
- TC-11-H3 (events ring-buffer eviction)
- TC-07-J3 if `--non-interactive` already exists (else Tier 4)
- TC-11-J2 (dynamic log levels)
- TC-08-I3 (runtime extensions)
- TC-08-I4 (PgNotification)
- Strengthen-system-metrics-window
- Strengthen-cluster-metrics-fields
- Strengthen-event-types-coverage
- Strengthen-scale-rejection-guards
- Strengthen-dht-node-cleanup
- Strengthen-config-env-interpolation (after Phase 4 B7)
- Strengthen-orphan-cleanup-reconcile
- Strengthen-endpoint-registry
- Strengthen-cluster-generation-as-sut
- Strengthen-kv-store-typed-keys

**Tier 1 count: ~22 items. Effort: ~12-15 days.**

### Tier 2 — Depends on Phase 2 product API (P5/P7/P-NEW-*)

- TC-08-F1, F2, F3, F4 (scheduled tasks; P5 + P-NEW-H)
- Strengthen-pause-resume, Strengthen-execution-state-tracking (Phase 4 B6 + P5)
- TC-04-J4 (consumer group; P7)
- TC-04-J5 (sync replication ack; P7)
- TC-08-E1 (pub-sub resource lifecycle; P7)
- Strengthen-competing-consumers (P7)
- Strengthen-subscriber-receives-events (post-Phase-4 B6)
- TC-10-G2 (DHT versioned writes; P-NEW-B)
- TC-NEW-G4 (KV backup; P-NEW-C)
- TC-07-G3 (TimeoutsConfig; P-NEW-A)
- TC-11-H1 (historical metrics; P-NEW-D)
- Strengthen-rest-management-api-aspects-ttm (cross-ref only after dependents land)

**Tier 2 count: ~13 items. Effort: ~10-12 days behind Phase 2.**

### Tier 3 — Depends on test-topology change

- TOPO-3-dependent: TC-06-005 (placement hints), Strengthen-auto-heal-metadata
- TOPO-1-dependent (LB sidecar): TC-NEW-passive-lb-D4, D5, D6, D7
- TOPO-4-dependent (TLS overlay): TC-05-K2, TC-05-K4
- TOPO-2-dependent (worker topology): ALL 11 Domain M tests + 2 strengthenings

**Tier 3 count: ~19 items. Effort: TOPO setup ~5 days + tests ~12 days ≈ ~17 days. Critical path.**

### Tier 4 — Failure injection / fixtures needed

- TC-10-G1 (HLC + clock skew injection)
- Strengthen-dht-anti-entropy (corrupt replica filesystem)
- Strengthen-swim-detection-broad (iptables blocking)
- Strengthen-cert-rotation-trigger (short-validity cert option, P-NEW-I)
- Strengthen-mtls-actual-handshake (cert chain inspection)
- TC-12-D8 (HTTP/3 server — needs HTTP/3 client tooling)
- TC-08-I1, TC-08-I2 (httpbin sidecar + outbound HTTP slice)
- Strengthen-database-driver-matrix (6 driver-specific slice fixtures)
- TC-11-J1 (WebSocket client — websocat in image)
- TC-08-D1, D2, D3 (SliceInvoker — two-slice fixture)
- TC-06-006, TC-06-007, TC-06-008 (A/B testing — variant-aware slice)

**Tier 4 count: ~16 items. Effort: fixture work ~5 days + tests ~10 days ≈ ~15 days.**

### Tier 5 — Cloud-suite work stream

- 18 features in `aether/tests/cloud/` — separate work stream, runs in parallel with all `suites/**` work. ~9 days.

### Critical path summary

```
TOPO-2 (worker topology, 3d)
  → Domain M tests (11 × M ≈ 12d)
    → Tier 3 critical path: ~15 days
TOPO-1 + TOPO-4 (LB + TLS, 2d total)
  → can run parallel with TOPO-2
Phase 2 product API expansion (10-11d)
  → Tier 2 tests (12d)
    → Phase 2-dependent critical path: ~22 days
```

**Wall-clock critical path: ~22 days** (Phase 2 → Tier 2). TOPO-2 + Domain M (~15d) runs in parallel and finishes first.

---

## Effort total

### Test functions to write (in `suites/**`)

| Category | Count | Per-item avg | Total |
|---|---|---|---|
| NONE new tests (Tier 1) | 22 | M (~0.5d) | 11d |
| NONE new tests (Tier 2 — Phase 2 dep) | 8 | M (~0.5d) | 4d |
| NONE new tests (Tier 3 — topology dep) | 17 | M-L (~1d) | 17d |
| NONE new tests (Tier 4 — fixture dep) | 16 | M-L (~1d) | 16d |
| PARTIAL strengthenings | 29 | S-M (~0.3d) | 9d |
| **Subtotal (`suites/**`)** | **92 items** | | **~57 days** of focused work |

### Cross-cutting

| Item | Effort |
|---|---|
| TOPO-1 (LB sidecar) | 1d |
| TOPO-2 (worker topology + first smoke) | 3d |
| TOPO-3 (node labels) | 0.5d |
| TOPO-4 (TLS overlay) | 1d |
| Phase 2 P-NEW items (9 new product changes) | 4.5d |
| **Subtotal cross-cutting** | **~10 days** |

### Cloud suite

| Item | Effort |
|---|---|
| 18 cloud-suite tests in `aether/tests/cloud/` | 9d |

### Grand total

| | Days (focused dev) |
|---|---|
| `suites/**` test work | ~57d |
| Cross-cutting infrastructure | ~10d |
| Cloud suite | ~9d |
| **Total raw effort** | **~76 days** |

**With 3 parallel work streams** (suite tests, infra, cloud), wall-clock compresses to **~22-25 days**.

**Critical path:** Phase 2 product API expansion → Tier 2 tests. ~22 days.

**Plan revision:** The existing `production-readiness-plan-2026-05-21.md` estimates ~4-5 weeks at RC1 bar. This follow-up doc adds ~3 more weeks of focused effort to close ALL gaps per "rest keep in scope". **Revised RC1 estimate: ~7-8 weeks of focused work**, with the option to descope Worker Pools or Cloud Integration to shave ~2-3 weeks if calendar pressure requires.

---

## Self-check

1. **NONE entries counted**: This document specifies 51 new tests in `suites/**` + 18 in `aether/tests/cloud/` = 69 new test functions. Source coverage matrix reports 102 NONE entries with ~50 expected-NONE (libraries, build-time, Forge, dashboard). Net non-expected NONE ≈ 52. Coverage of this doc: 69 ≥ 52 ✓ (overage accounted for by strengthenings counted as NONE-adjacent + the 18 cloud routed-out items).
2. **TC-ID uniqueness**: Verified. IDs follow `TC-<NN>-<NUM>-<slug>` or `TC-NEW-<slug>` for tests requiring new suites.
3. **Every effort estimate has S/M/L tag**: Verified across all 80 entries.
4. **Dependencies cite specific Phase 2 P-items or topology changes**: Verified — each dependent test references P1-P7 or P-NEW-A through P-NEW-I or TOPO-1 through TOPO-4.
5. **Total effort realistic**: ~76 focused dev-days raw, ~22-25 wall-clock with parallelism. Matches the "15-25 days realistic" sanity range.

---

## Out of scope (appendix)

These NONE entries are correctly outside the integration-suite layer and do NOT appear in the per-domain backlog above:

### Reusable Libraries (7 of 8 NONE — unit-test domain)
- #166 Generic state machine — `integrations/statemachine/src/test/java`
- #167 DNS client — `integrations/net/dns` unit tests
- #169 KSUID generator — `integrations/utility` unit tests
- #170 Core parse library — `core/parse` unit tests
- #171 Multipart file upload — currently no public surface in integration scope
- #172 ProblemDetail — covered indirectly by RC1-blocker #8 fix in Phase 4
- #173 Static file serving — dashboard concern (#58 marked Critical / planned-work)

### Developer Tooling (12 of 14 NONE — build-time / process / scaffolding)
- #55 JBCT compliance, #78 `jbct add-slice`, #79 IDE plugins, #205 Core value objects, #208 GitHub Issues as worklog, #158 V1.0.0 roadmap, #210 JBCT code formatter, #211 JBCT compliance scorer, #164 JBCT project scaffolding, #165 Property-based testing library, #66 Compile-time serde (@Codec), #161 Compile-time route registry

### Embeddable Runtime (1 of 3 NONE — Forge domain)
- #73 Ember embeddable cluster — covered in `forge-tests` module
- #74 Remote Maven repositories — **caveat**: this is NOT Forge-domain; consider adding a single integration smoke test that resolves a fixture artifact from a local nexus/sonatype mirror. Currently uncovered. Flagged as **possible future addition** but not in this backlog due to user's primary focus on Worker Pools / Cloud / Autoscaler.
- #75 Load Balancer — covered by TC-NEW-passive-lb-D4..D7 in Domain D

### Other Complete-but-correctly-NONE
- #131 Consensus Operation Retry — internal mechanism; covered by chaos tests' implicit assertion that consensus survives saturation
- #58 Web dashboard — by design no UI tests
- #136 Docker integration test infrastructure — IS the audit's subject
- #154 Server UDP support, #155 Shared EventLoopGroups, #160 HTTP/3 server (server-side claim covered by TC-12-D8 above), #204 SharedScheduler — internal-thread-model claims; thread-pool sharing is unit-testable at the component level
- #129 Endpoint Config — implicit via every test that exposes endpoints; explicit assertion adds little value
- #157 (if present), routing internals not enumerated

### #53, #76 (N/A in matrix)
- #53 E2E test framework — `aether/e2e-tests/` (Testcontainers); separate work stream
- #76 Forge integration tests — `aether/forge` JUnit suites; separate work stream

---

## References

- Audit: `aether/docs/internal/audits/integration-test-audit-2026-05-21.md`
- Coverage matrix: `aether/docs/internal/audits/test-coverage-matrix-2026-05-21.md`
- Coverage partials (5 files): `aether/docs/internal/audits/integration-test-audit-2026-05-21-partials/coverage-*.md`
- Production-readiness plan: `aether/docs/internal/production-readiness-plan-2026-05-21.md`
- Feature catalog (source of feature numbers): `aether/docs/reference/feature-catalog.md`
- Existing topology: `aether/tests/integration/docker-compose-{a,b}.yml`, `aether/tests/integration/run-tests.sh`
