# Session Handover — 2026-06-06c (continuation of 2026-06-06b)

**Branch:** `release-1.0.0-rc1` · **HEAD:** `a990a19a1` (= the 2026-06-06b handover commit; **unchanged** — the Issue-2 structural fix is IN FLIGHT, uncommitted). Tree: clean except untracked `aether/tests/integration/suites/02z-killonly/` (local scaffolding).

Read **2026-06-06b first** for the merge + the 7c/7b/scale-up/11/A1 fixes. This doc is the delta since.

## ▶ NEXT SESSION — immediate
1. **Issue 2 structural fix is IN FLIGHT** (background coder `aca5557fb5b215886`). When it returns: review the diff, `env -u HCLOUD_TOKEN mvn -pl aether/node -am install -DskipTests`, **Docker-validate `11-observability/All_nodes_agree_on_order`** (cluster A, `--suites 11`), then commit. Details below.
2. Then: A3 (LOW, root-caused), B5/05 (tuning/infra — confirm disposition).

## 🚨 CORRECTION — my committed 11 fix (`b5de60993`) is TIMING-FRAGILE
`AetherNode.registerSystemStreamsOnLeaderChange` registers `system:cluster-events` **fire-once on leader-gain**; its `.onFailure(LOG.warn("…will retry on next leader change"))` is **a log, NOT a retry**. If `createStream`'s consensus commit times out in the ~10s after leader-gain (consensus not yet ready), the `StreamConfigKey` is never committed and a stable cluster never has another LeaderChange → **the cluster-events stream stays dead** (owner-gate never arms, `totalEvents:0` everywhere, `/api/events|alerts|traces` → `200 []`). It passed the 11-validation runs only because consensus happened to be ready at leader-gain. This is the **edge-vs-level lesson again**: registration is edge-triggered (fire-once) when it must be **level-triggered** (reconcile: ensure the config is committed, re-attempt until it is). The in-flight Issue-2 **Fix #1** hardens this. Until it lands, observability reliability is timing-dependent.

## Issue 2 — full structural fix IN FLIGHT (agent `aca5557fb5b215886`)
Root cause (instrumented, verified): the cluster-events stream never receives events because registration is fire-once-no-retry (above). Three layers being implemented:
- **#1 (primary, hardens the 11 fix):** make system-stream registration self-healing/level-triggered — leader re-attempts `createStream` + `streamNamespacesService.bootstrap()` until `StreamConfigKey` is committed; idempotent (`STREAM_ALREADY_EXISTS` = stop), retry only on transient commit failures, stop on leadership loss, bounded/backed-off. Hook: a leader-pinned reconcile tick (`ClusterSyncContext.broadcastPing` ~1s cadence, or `ReplicaSetController`) or a scheduled backoff from leader-gain. Replaces the fragile `registerSystemStreamsOnLeaderChange`.
- **#2 (local):** `StreamPartitionManager.createStream` (`:112-133`) currently `rollbackOptimisticEntry` on consensus-publish failure → wipes the local partition. Decouple local materialization (keep it) from the cluster-config publish (retryable); no double-allocation on the later `ALREADY_EXISTS` retry.
- **#3 (read path):** `SystemStreamFactories.systemStreamConsumer` (`:67-80`) wires `PartitionedStreamAccess` with `Option.none()` (no replica-registry/forward-client/self) → a node OUTSIDE the system stream's replica set reads empty instead of forwarding to the owner. Wire it like the app path (`StreamReadRouter.streamReadRouter(…, Option.some(replicaRegistry), Option.some(forwardClient), self, …)`) and make `ClusterEventAggregator.events()` use the forwarding fetch. Fail-soft to local during bootstrap. This is the actual `All_nodes_agree` cross-node-read gap.

## A3 (drain budget, LOW) — root-caused, NOT fixed
`13-edge`: first drain → 500 "Node lifecycle not found"; third → 500 (expected 409). One root: `NodeLifecycleRoutes.drainNode` → `guardAndRequestDrain` → `resolveLifecycleState(nodeId)` reads `collector.reportedStates()` (the leader-broadcast readiness view); when the target isn't there it returns `LIFECYCLE_NOT_FOUND` → 500 **before** `enqueueDrainCommand`. So no drain ever enqueues → `pendingDrainsSupplier` stays empty → `checkDisruptionBudget` never rejects the 3rd (it would return 409 — `budgetExceededError` is correct). **Fix direction:** make the drain path resolve lifecycle reliably, or handle readiness-unavailable gracefully (the LIST path returns 503 for that; drain 500s). Needs a 13-suite Docker probe to confirm *why* the target is absent from the readiness view (not-converged / partial / wrong node handling the request). `NodeLifecycleRoutes.java:209-265`.

## B5 / 05 — likely tuning/infra (confirm, don't assume)
- **B5 (MED):** `12-network 4+ cores READY (target=5)` 600s timeout + `Kill_node…NODE_FAILED within 60s`. Cluster heals but exceeds thresholds — readiness-convergence + detection-latency (#68 family). Probably timing/threshold tuning, not a clean code bug.
- **05 (config):** TLS + admin-auth fail under `AETHER_INSECURE_DEV_MODE`; needs a secure-mode cluster-B variant. Test-infra.

## Tag-gate scorecard (RC1)
- ✅ A1 scale-down data-loss (`e6952646d` — PUT-discard, not drain-durability)
- ✅ A2 forwarding (passes; was variance)
- ⚠️ 11 system:cluster-events registration (`b5de60993`) — **works but timing-fragile**; Issue-2 #1 hardens it
- 🔄 Issue 2 cross-node delivery (#89) — full structural fix IN FLIGHT
- ⬜ A3 drain-budget (LOW, root-caused) · B5 readiness-latency (tuning) · 05 secure-mode (infra)
- ⬜ physical-node-drain DHT durability (#91, RC2; barrier patch at `/tmp/aether-issue7/a1-drain-handoff-barrier.patch`)

## Learnings (delta)
- **Edge-vs-level keeps recurring.** Both the scale-up provisioning fix AND my 11 registration were edge-triggered (fire-once on an event) where level-triggered (reconcile-until-converged) is correct. When a fix "registers/decides once on an event," ask: what re-converges it if that one attempt fails?
- **Instrumented Docker runs keep correcting theoretical analyses** — A1 (drain-handoff theory → real PUT-discard bug) and Issue 2 (cross-node-delivery theory → fire-once-registration root) were both re-pointed by empirical runs. Instrument before fixing.
- **This env scales via config-churn, not physical provisioning** (no container create/destroy on `scale_cluster`) — drain/handoff code paths are NOT exercised here; needs a real-provisioning env.
