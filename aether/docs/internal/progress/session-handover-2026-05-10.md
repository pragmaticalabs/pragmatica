# Session Handover — 2026-05-10

**Branch:** `release-1.0.0-rc1` · **HEAD:** `1faf27573` (pushed) · **Tag:** `v1.0.0-rc1-candidate` at HEAD

Continuation of [`session-handover-2026-05-09.md`](session-handover-2026-05-09.md). The bulk of this session implemented the **D2 structural fix**: split the unified `TopologyChangeNotification` into two type-distinct streams (`TransportObservation` for local fast-path observations, `MembershipDecision` for cluster-canonical decisions). Eliminates dual-emission confusion structurally — the compiler now enforces non-confusion via sealed-exhaustive checking. Also: 3 PR merges from in-flight work, test-infra fixes, stale test rewrite.

---

## ⚡ TL;DR for next session

**The D2 structural fix landed but introduced a management-forwarding regression that needs investigation.**

docker-remote went from 13/15 (yesterday's baseline) → 12/15 today. Net regression of 1 (1 of today's 3 failures is the known SCALING flake; 2 are new D2-attributable management-forwarding 503s).

Pattern: schema status endpoints (`/api/schema/status/default`, `/api/schema/status`) return 503 with "Management forward failed: Request failed after all retries". Reproduces across both 06-deployment (test-schema-migration) and 10-database (test-schema-baseline). Other forwards (blueprint deploy, schema migrate) sometimes 503 too but eventually succeed via retry. Schema status forwards consistently fail.

Likely root cause: `TaskAssignmentCoordinator` was migrated from `TopologyChangeNotification` to `MembershipDecision`, but may be missing the initial-state seeding that previously fired on `NodeAdded`. Without that seeding, task-group ownership for schema-related task groups is unresolved at first-forward time → resolver returns "not assigned" → forward fails after retries.

Also: 12-network/test-quic-connectivity fails with "No NODE_JOINED event for replacement of node-2 within 90s". MembershipDecision.NodeJoined likely not reaching ClusterEventAggregator's NODE_JOINED emitter for CTM-provisioned replacements.

Investigation start points listed in §5 below.

---

## 1 · State at session end

| Item | Value |
|---|---|
| Branch HEAD | `1faf27573` (pushed) |
| Tag `v1.0.0-rc1-candidate` | at `1faf27573` (pushed, force-updated) |
| Hetzner inventory | only PG VM `130122272` (off) |
| docker-remote (this session) | **12/15** |
| docker-remote baseline (yesterday) | 13/15 |
| Full reactor `mvn test` | **green** (3050+ tests, 136 modules) |
| Working tree | clean |

---

## 2 · This session's commits (15 — all pushed)

```
1faf27573 docs: changelog for typed observation/decision streams refactor
071c73028 docs(specs): membership architecture v2 — typed observation/decision streams
f04ef03c8 chore(consensus): delete obsolete TopologyChangeNotification + clean up doc references
97b02c0ac test: migrate test fixtures to typed observation/decision streams
c60292f02 refactor: migrate 15 subscribers to typed observation/decision streams
ce83aa48b refactor(consensus,swim): emission sites cut over to typed observation/decision streams
7c4d57762 feat(consensus): typed observation + decision streams (foundation for membership refactor)
fa3904d54 fix(test-infra): kill_node/start_node JVM-mode dispatch + slice_owner_for owner parsing
fbccf3b7a test(deployment-health): rewrite stale cold-boot suppression tests for new contract
267f903cc feat(postgres-async)!: SslConfig with libpq-mode taxonomy + SCRAM-SHA-256-PLUS
4b391cff5 refactor(postgres-async)!: PgValue/PgWriter SPI + binary array path
5506af952 docs: event stream namespaces and versioning spec (#165)
82ef6cea1 docs: changelog and feature catalog for VM snapshot support + timeout tightening
e63d0b075 refactor(tests): tighten chaos test timeouts (TIMEOUT_SCALE-aware)
492144571 feat(tests): wire AETHER_VM_SNAPSHOT_ID into run-tests.sh
1d91d78f1 feat(tools,docs): VM snapshot build script + operator workflow doc
a581108a3 fix(cli): apply --restart no consistently in SSH deploy + update stale tests
70dcf73bc feat(cli): cloud-init idempotency for pre-pulled VM snapshots
```

---

## 3 · D2 structural fix — what landed

### Type definitions (`integrations/consensus/src/main/java/org/pragmatica/consensus/topology/`)

**`TransportObservation`** — local fast-path observations, may flap, partial-view:
- `PeerJoined`, `PeerDisconnected`, `PeerReconnected`, `PeerObservedFaulty`, `SelfShutdown`
- `ObservationSource` enum {QUIC, NETTY, SWIM}
- Producers: `QuicClusterNetwork`, `NettyClusterNetwork`, `SwimProtocol`
- Consumers: `LeaderManager`, `ClusterFsmRouter`, `RabiaNode` (bootstrap fast-path)

**`MembershipDecision`** — cluster-canonical decisions, consensus-driven, authoritative:
- `NodeJoined`, `NodeRemoved`, `NodeDecommissioned`
- Sole producer: `TopologyObserver.publishMembershipDeltas`
- Consumers: 11 subscribers (CDM, CTM, LB, HttpForwarder, SliceInvoker, TaskAssignmentCoordinator, ClusterSyncCollector/Scheduler, DeploymentMetricsCollector/Scheduler, ControlLoop, AppHttpServer, DHTTopologyListener, ClusterEventAggregator)

### Migration scope
- 22 src/main files modified across `integrations/` and `aether/`
- 29 test fixtures migrated
- Legacy `TopologyChangeNotification.java` deleted
- Spec `aether/docs/specs/membership-architecture-spec.md` rewritten (v2)
- SwimProtocol uses `TransportObservationEmitter` callback to avoid FQCN collision with local `org.pragmatica.swim.TransportObservation` (legacy SWIM-internal hint type)
- `AetherNode` wires `swimHealthDetector.addTransportObservationEmitter(delegateRouter::route)` to bridge SWIM emissions to the cluster-wide router

### What this resolves
- Dual-emission anti-pattern eliminated structurally — compiler enforces non-confusion
- Subscriber category clear at the type level (no more "is this transport-level or canonical?" ambiguity)
- Single-writer rule refined: applies to `MembershipDecision` only; `TransportObservation` has no single writer by design
- Bootstrap chicken-egg resolved: `LeaderManager` consumes transport stream during bootstrap (when consensus doesn't exist yet), then transitions to consuming `MembershipDecision` once consensus is up

### What's deferred to RC2
- `PeerObservationStore` (audit Step 7) — natural transducer between TransportObservation and MembershipDecision via cross-node quorum aggregation. Not strictly required; HealthReconciler's existing SWIM aggregation continues to function as-is. Estimated 2-3 days focused effort.
- `MembershipDecision.NodeDecommissioned` is defined but not yet emitted by `TopologyObserver` — wiring it requires projecting from lifecycle KV-Store. Subscribers can pattern-match the variant; will activate when wired.

---

## 4 · docker-remote validation results (12/15)

| Suite | Result | Cause |
|---|---|---|
| **06-deployment/test-schema-migration** | 4p/1f | "Global schema status returns data" — endpoint returned empty (503 Management forward failed) — **D2-attributable regression** |
| **10-database/test-schema-baseline** | 2p/1f | "Schema status endpoint responds for default" + "Global schema status returns data" — same 503 forwarding pattern — **D2-attributable** |
| **10-database/test-schema-baseline** + others | (counted in 10-db) | "SCALING reassigned from dead node node-2 to node-2" — **pre-existing flake, NOT D2** |
| **12-network/test-quic-connectivity** | 2p/1f | "No NODE_JOINED event for replacement of node-2 within 90s" — **D2-attributable** |
| **02-chaos** | 4p/0f ✓ | All chaos tests passed (kill-leader, kill-multiple, kill-node, kill-under-load) — strong signal that core SWIM-FAULTY-leader bridge + post-consensus eviction still work |
| **All other suites** | green | No regressions |

The fact that 02-chaos passes 4p/0f indicates the core failure-handling architecture (SWIM-FAULTY → bridge → re-election → DECOMMISSIONED) is intact. The regressions are in management-forwarding paths that depend on task-group ownership resolution.

---

## 5 · Investigation starting points for D2 regressions

### A. Forwarding 503s on schema status (HIGHEST PRIORITY)

Endpoints affected: `GET /api/schema/status/default`, `GET /api/schema/status`. Reproduces consistently in 06-deployment and 10-database.

The 503 chain: `ManagementServer.handleForward` → `HttpForwarder.forwardManagement` → fails after retries → `ManagementServer:763` returns 503 with cause "Management forward failed: Request failed after all retries".

Hypothesis: `TaskAssignmentCoordinator` migration to `MembershipDecision` may have lost the initial-state seeding that previously fired on `TopologyChangeNotification.NodeAdded` for newly-joined nodes. The schema-related task groups need their owner-resolution to be populated at startup; if that path now waits for a `MembershipDecision.NodeJoined` that fires only on snapshot-publish-after-consensus, there's a window where forwards have no owner to dispatch to.

Investigation steps:
1. Compare pre/post-migration `TaskAssignmentCoordinator.java` carefully — look for whether `onNodeAdded` had setup logic that `onMembershipDecision` (post-migration) doesn't replicate.
2. Add WARN-level instrumentation in `HttpForwarder.forwardManagement` and `taskGroupOwnerResolver` callback. Re-run 10-database test-schema-baseline. Determine: is the resolver returning empty (no owner mapped) or is the resolver returning an owner but the forward fails to reach it?
3. If empty-owner: trace TaskAssignmentRegistry seeding path. May need to re-add an `onMembershipDecision.NodeJoined` handler that does what `onNodeAdded` used to do.

### B. Missing NODE_JOINED event for CTM replacement (12-network/test-quic-connectivity)

The flow: `kill_node node-2` → SWIM detects FAULTY → leader writes DECOMMISSIONED → CTM provisions replacement → replacement reaches ON_DUTY → `MembershipDecision.NodeJoined` for replacement → `ClusterEventAggregator.onNodeJoined` → emit `NODE_JOINED` cluster event → test polls `/api/events` and finds it.

Test asserts NODE_JOINED arrives within 90s. Today: NODE_LEFT for the killed node IS observed (line 1977 PASS), and the cluster reports 5 nodes after start_node (line 1988 PASS) — so MembershipDecision.NodeRemoved IS reaching ClusterEventAggregator (NODE_LEFT works), and replacements DO arrive (5-node count restored). But NODE_JOINED for the replacement is not emitted on `/api/events`.

Hypothesis: `ClusterEventAggregator` was renamed `onNodeAdded` → `onNodeJoined` in the migration. Either:
- The MessageRouter dispatch is not finding the new handler (annotation/registration issue)
- The handler signature differs from what `MembershipDecision.NodeJoined` actually delivers
- The event-stream emission code (the `NODE_JOINED` part of /api/events) doesn't fire from `onNodeJoined`

Investigation steps:
1. Read the migrated `ClusterEventAggregator.onNodeJoined` — confirm it emits NODE_JOINED to the event stream.
2. Verify the MessageRouter registration in `AetherNode` still dispatches `MembershipDecision.NodeJoined` to ClusterEventAggregator's handler.
3. Add WARN log inside `onNodeJoined` to confirm it's invoked. Re-run 12-network.

### C. Pre-existing SCALING flake (NOT D2)

`SCALING reassigned from dead node node-2 to node-2: expected NOT 'node-2', got 'node-2'` — unchanged from prior session §7.B. Test-side cosmetic issue: deterministic node-id slot naming makes the assertion always fire when CTM provisions a replacement with the same id. Test should compare against VM-id / IP, or accept "same node-id is OK if VM is fresh." Cosmetic; doesn't affect actual recovery behavior.

---

## 6 · Other validation results

### Cloud JVM + Cloud Container — NOT RUN this session

Reason: docker-remote regression surfaced first; cloud validation should follow once docker-remote is green. Cloud also costs €5-30 per full run; better to debug on docker-remote (free) first.

### Reactor `mvn test`

Full reactor unit tests pass. 136 modules. 3 min 31s. No failures. Aether Node alone: 368 tests pass. The unit-level gate confirms the typed-streams refactor is structurally sound.

---

## 7 · 3 PRs merged earlier this session

```
267f903cc feat(postgres-async)!: SslConfig with libpq-mode taxonomy + SCRAM-SHA-256-PLUS    (#200)
4b391cff5 refactor(postgres-async)!: PgValue/PgWriter SPI + binary array path              (#199)
5506af952 docs: event stream namespaces and versioning spec (#165)                          (#186)
```

Each gated through worktree-isolated `mvn test` validation before merge.

### Open PRs remaining
- **#187** — `feat: stream-addressing foundation types for #165` (100 files, draft, depends on #186 which is merged). Plan: merge LAST after RC1 hardening completes (per agreed PR strategy). Currently in draft state.
- **#213** — `refactor: jbct single-pass processing + peglib 0.2.2 → 0.5.0`. Plan: defer to RC2 (other agent doing further perf work; isolation reduces format-cascade risk).

---

## 8 · Test-infra fixes earlier this session (all in `lib/cluster.sh`)

| Fix | Commit |
|---|---|
| A1: `kill_node` JVM mode dispatch (`pkill -f` instead of `docker kill`) | `fa3904d54` |
| A1: `start_node` JVM mode dispatch (cmdline capture/replay) | `fa3904d54` |
| A2: `slice_owner_for` awk parser fix (was returning empty for ACTIVE owner) | `fa3904d54` |
| A2: `retarget_app_endpoint_to_active_slice` diagnostic on miss | `fa3904d54` |

Plus stale-test rewrite:
| Fix | Commit |
|---|---|
| `ObservationAggregatorTest.aggregator_unknownOnly_neverEmitsFaulty` rewrite | `fbccf3b7a` |
| `HealthReconcilerTest$StepFailures.reconciler_neverWritesFaulty_*` rewrite | `fbccf3b7a` |

---

## 9 · Outstanding RC1 work (beyond D2 regression fix)

From earlier session plan (still relevant):
- **A3** start_node single-writer reconciliation — may need product-side fix or test redesign. Pending.
- **A4** SWIM detection 60s margin — investigate post-§10 timing impact. Pending.
- **D1-D5** doc cleanup — partially done in spec rewrite v2; remaining items: ObservationAggregator quorum-gap honesty comment, HealthReconcilerImpl escape hatch rationale comment, SWIM-FAULTY-leader bridge intent comment, three-layers cold-boot rationale near BOOTING phase code.
- **Process gap** — `release-check` skill to actually run `mvn test` per affected module. Small fix; pending.

---

## 10 · Quick start for next session

```bash
# 1. Sanity
git log --oneline 8e721c625..HEAD          # 18 commits this + prior session
git status --short                          # should be clean
git tag --points-at HEAD                    # v1.0.0-rc1-candidate

# 2. Hetzner inventory (should be just PG, off)
curl -s -H "Authorization: Bearer $HCLOUD_TOKEN" 'https://api.hetzner.cloud/v1/servers' | \
  jq -r '.servers[] | "\(.id)\t\(.name)\t\(.status)"'

# 3. Reproduce the schema-status forwarding 503 quickly
#    Single suite, single test, fastest signal:
cd aether/tests/integration && \
  ./run-tests.sh --env remote --skip-build --suites 10
# Look for 503 lines in test-schema-baseline. Specifically:
#   "[FAIL] Schema status endpoint responds for default: expected NOT '', got ''"

# 4. Add WARN instrumentation in TaskAssignmentCoordinator + HttpForwarder
#    re-run, observe whether owner is resolved or not.

# 5. Once root cause confirmed, fix + re-run docker-remote full 15-suite.
#    Target: green 14/15 (SCALING flake remains; not D2-attributable).

# 6. After docker-remote green, run:
#    - Cloud JVM full 15-suite
#    - Cloud Container full 15-suite (with optional VM snapshot pre-pulled)
```

---

## 11 · Architectural notes for future contributors

The typed-streams split (D2 fix) is the foundation of a CQRS-style event architecture for Aether membership. The pattern is:

- **Observation streams** (`TransportObservation`): per-node, fast, may be wrong. Typed by source (QUIC / NETTY / SWIM). Subscribers consume when they need fast local reactions and accept partial-view semantics.
- **Decision streams** (`MembershipDecision`): cluster-canonical, slower, authoritative. Single-writer (consensus-driven). Subscribers consume when they need canonical truth.
- **Transducers**: components that aggregate observations into decisions. Currently: `HealthReconciler` for SWIM observations. Planned (RC2): `PeerObservationStore` for cross-cutting transport observations.

This pattern can be applied elsewhere — `SwimProtocol` observations vs `SwimDecisions`, `HealthReconciler` observations vs lifecycle decisions. Each layer becomes a typed input + typed output, with explicit transducers between. Postmortem clarity baked in.

---

## 12 · Score card

| Metric | Start of session | End of session |
|---|---|---|
| Branch HEAD | `8e721c625` | `1faf27573` |
| Commits ahead of session-start | 0 | 18 |
| Open PRs (rc1-targeted) | 5 | 2 (#187 draft, #213 deferred to RC2) |
| docker-remote (best run) | 13/15 (yesterday) | 12/15 today (D2 regression) |
| Reactor `mvn test` | green | green (3050+ tests, 136 modules) |
| Architecture (membership) | dual-emission via single TopologyChangeNotification | typed split, compiler-enforced non-confusion |
| Spec for membership architecture | v1 (legacy) | v2 (observation/decision model) |
| Cloud spend | €0 | ~€2-3 (3 PR merge gate runs) |

**Net:** D2 structural fix shipped end-to-end (foundation types, emission cutover, 22 subscriber migrations, 29 test fixtures, deletion, spec rewrite, full reactor unit-test green). Integration validation surfaced 2 regressions in management-forwarding path that need investigation next session — likely in `TaskAssignmentCoordinator` initial-state seeding and `ClusterEventAggregator.onNodeJoined` wiring. Both are wiring bugs, not architectural; the typed-streams architecture itself is sound (reactor unit tests + 02-chaos + 12 of 15 integration suites all green).
