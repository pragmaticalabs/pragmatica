# Session Handover — 2026-05-24

**Branch:** `release-1.0.0-rc1` | **HEAD:** `ac0e69fc9` (5 commits this session, **NOT pushed**)
**Predecessor:** [session-handover-2026-05-23.md](session-handover-2026-05-23.md)

## 1. One-line summary

Chased the weeks-long intermittent integration failures to their roots: **cluster A is now 10/10 and reliable** (two clean runs) after fixing a NodeId-ghost bug, a DHT/deploy-timeout bug, and — the breakthrough — a **QUIC CONSENSUS-stream backpressure-drop** that silently starved consensus. **Cluster B's total-collapse cascade is broken** (self-drain quorum-source fix), and its remaining failures are now traced to a single **definitive root cause not yet fixed**: the LifecycleReconciler (the automatic cleanup engine) dies on every tick because an audit-event codec was never registered. 5 fixes committed; the reconciler-codec fix is the next session's first task.

## 2. Commits this session (oldest→newest, all on release-1.0.0-rc1, UNPUSHED)

| Commit | Fix |
|---|---|
| `14b28116e` | **CTM provider-owned NodeId** — two-phase unassigned→assigned provisioning slot + JOINING-aware deficit. Eliminates ghost `aether-core-node-*` JOINING entries that broke cluster formation. |
| `b39af52c6` | **06-deploy resilience** — bounded retry on DHT read path + parallel blueprint dep resolution + configurable `RabiaEngine.apply` timeout (`ProtocolConfig.applyTimeout`, 30s). |
| `0a5211637` | **Consensus messages no longer dropped under QUIC backpressure** — `QuicClusterNetwork.writeIfWritable` wraps CONSENSUS sends in an async short-interval `Retry` (25ms × 200, configurable via `QuicTransportTuning`) + raised CONSENSUS write watermark (256KB/1MB). DHT fast-fail unchanged. **THE cluster-A breakthrough.** |
| `51f7e3054` | **15-delegation test-infra** — reassign extracts node ordinal from full NodeId (`sed 's/.*node-//'`); fixes `aether: unbound variable` at cluster.sh:2144. |
| `ac0e69fc9` | **Self-drain quorum source** — `SelfDrainCoordinator` uses authoritative `TopologyManager.quorumSize()` instead of inflated `topology().size()`. Stops cluster-B cascade collapse on node loss. |

## 3. The chain of root causes (the valuable knowledge)

Five distinct bugs, uncovered by following the evidence layer by layer. Each masqueraded as a flake.

### 3a. NodeId ghosts (FIXED — `14b28116e`)
CTM pre-generated `aether-core-node-<KSUID>` and wrote it to KV as an assigned provisioning slot, but `DockerComputeProvider` ignored it and self-named `aether-{cluster}-node-N`. The slot id became a ghost → `MembershipFsm` wrote JOINING for it → expired → STOPPED → formation churn → intermittent "4 members" gate failures. Fix: provider owns identity (`InstanceInfo.nodeId`), CTM follows the spec §4.2 two-phase unassigned→assigned flow.

### 3b. Consensus dropped under QUIC backpressure (FIXED — `0a5211637`) — the cluster-A breakthrough
`QuicClusterNetwork.writeIfWritable` **silently dropped** CONSENSUS-stream messages when the per-peer QUIC stream hit Netty's high-water mark (relying on "Rabia/SWIM will retransmit" — but retransmits hit the same backpressure and also dropped). Under command bursts (blueprint deploy → slice ACTIVATE) during formation, this starved quorum: the ACTIVATE command never reached enough peers → `cluster.apply` timed out at 30s → 06-deployment 500s; and a peer's ON_DUTY lifecycle failed to propagate → intermittent "4 members". Evidence: all 5 nodes emit `not writable on stream CONSENSUS` under load; bursts to ALL peers deny quorum.
**Result:** 06-deployment 0→5, 09-artifacts 2→3, deploys commit in ~1s (was 30s timeout), formation reliably 5. Cluster A 8/10-flaky → **10/10 reliable**.

### 3c. Self-drain on inflated topology (FIXED — `ac0e69fc9`)
`SelfDrainCoordinator` computed `threshold = (topology().size()/2)+1` over the RAW topology list, which inflates with dead/decommissioned/CTM-replacement nodes during chaos (5 originals + 5 replacements ≈ 9 → threshold 5). A survivor seeing 4 live peers (healthy majority of the real 5) declared "below quorum" and self-drained → every survivor did the same → **total collapse**. The consensus layer tracked the correct `quorumSize=3` throughout. Fix: use the one authoritative `TopologyManager.quorumSize()` directly.
**Result:** cluster B survives initial kills and `restore_cluster_baseline` succeeds (was: never).

### 3d. **THE reconciler is dead — unregistered audit codec (ROOT CAUSE FOUND, NOT YET FIXED)**

This is the cluster-B unblocker and the most valuable finding. **The automatic cleanup never happens because the LifecycleReconciler tick throws on every iteration:**

```
WARN LifecycleReconcilerRecord.reconcile() - Reconciler: tick failed:
  java.lang.IllegalArgumentException: No codec registered for class:
  org.pragmatica.aether.deployment.audit.CommandLifecycleEvent$CommandReceived
```

**Chain:** reconciler tick → `applyEnforcing` / `publishAuditOnly` → `LifecycleWriter.applyCommand(...)` → **`auditPublisher.publish(new CommandReceived(...))`** (`LifecycleWriter.java:200`, on ENTRY, before the lifecycle command is applied) → the `audit.lifecycle.commands` `StreamPublisher` serializes the event → **no codec registered** → `publish()` throws synchronously → the whole tick aborts → the cleanup command (`ForceDecommission`, etc.) is **never submitted**.

**Why unregistered:** `CommandLifecycleEvent` *has* `@Codec` (codec generated at compile time), but the deployment module **is not a slice**, so it bypassed the slice-DI `resources.toml` → `StreamPublisherFactory` codec-registration path (see the comment at `AetherNode.java:1248-1253`). The manual node-boot wiring (`AetherNode.java:~1259`) binds the publisher but **never registers the codec** with the streaming serializer / `StreamPartitionManager`.

**Consequence:** `OnDutyFaulty`/`ForceDecommission` fired **0 times** on cluster B; dead nodes never decommissioned, stuck replacements never `JoiningTimeout`-cleaned → `topology()` inflates with stale ON_DUTY entries → drives `pick_non_leader: 0 candidates`, sustained-churn quiesce failures, and (pre-3c) the self-drain cascade.

**Why it hid for weeks / looked cluster-B-specific:** on a quiet cluster A the reconciler has no actions to take → never publishes `CommandReceived` → never hits the codec error. Only chaos (which creates cleanup actions) triggers it, so it looked like a destructive-test "cleanup race" (#5) rather than the cleanup engine being dead on arrival.

## 4. NEXT SESSION — first task: fix the reconciler codec (two layers)

- **(A) Direct fix:** register `CommandLifecycleEvent`'s codec with the streaming serializer the audit publisher uses. Since the deployment module isn't a slice, the node-boot code that binds the `audit.lifecycle.commands` publisher (`AetherNode.java:~1254-1263`, after `streamPartitionManager` is built) must also register the `@Codec` with the serializer / `StreamPartitionManager` — replicating what slice-DI does automatically for slice payloads. Check how slice payload codecs get registered and mirror it for `CommandLifecycleEvent` (+ `CommandReceived`, `CommandApplied`).
- **(B) Robustness:** make `auditPublisher.publish(...)` failure **non-fatal** to `LifecycleWriter.applyCommand`. Audit is observability — a serialization/publish failure must never abort a lifecycle/cleanup command. Wrap the publish so it logs-and-swallows (or returns a failed Promise that's isolated from the command path). `RecentCommandsBuffer.record` already runs first, so `GET /api/audit/commands` keeps working.

Either fix restores cleanup; do both for robustness. **Then re-validate cluster B** (`./run-tests.sh --env remote --skip-build --suites 02,03,05,12,13`) — expect `OnDutyFaulty`/`ForceDecommission` to fire, dead nodes to decommission, topology to stay accurate, and `pick_non_leader` / quiesce to recover.

## 5. Validation results

| Run | Scope | Result |
|---|---|---|
| #9f | cluster A | 9/10 (only 15-delegation failed — test-infra bug, then fixed) |
| #10 | full 15 | cluster A **10/10** (15-delegation now passes); cluster B cascaded (codec bug) |
| #11 | cluster B | baseline restores after first kill cycles (cascade broken); degrades under sustained churn (codec bug → no cleanup) |

Cluster A: **10/10, formation reliable** (two consecutive clean runs). Cluster B: cascade broken, **not green pending the §4 codec fix**.

## 6. State at handover

- **5 commits unpushed** on `release-1.0.0-rc1` (HEAD `ac0e69fc9`). Push when ready.
- Remote clusters torn down; remote clean.
- **Stray artifacts:** 9 untracked `ScheduledTaskRoutes$*.class` files under `aether/node/src/main/java/.../api/routes/` — build artifacts in the source tree; should be `rm`'d / gitignored (out of scope this session).
- `./build.sh` Step 2 still RED on the pre-existing Task #13 JBCT-RET-01 baseline (not session-introduced); focused `mvn -pl <module>` builds clean.
- Preserved evidence logs: `/tmp/v10-aether-b-node-{1,3,8}.log` (cluster-B reconciler-codec failures), `/tmp/v9c-node1.log` (consensus backpressure timeline).

## 7. Open follow-ups

1. **Reconciler codec fix** (§4) — cluster-B unblocker. FIRST.
2. **Replacements stuck JOINING** during chaos — partly downstream of #1 (JoiningTimeout never fires); re-check after the codec fix.
3. **`QuicTransportTuning`** — new config; confirm wired into any external config surface if needed.
4. **6 HIGH hardcoded timeouts** (task #8 inventory) — aether-stream consumer runtime (4), `NodeDeploymentState` consensus op timeout, `ForwardingClusterNode` — make configurable + TimeSpan when convenient.
5. **Stray `.class` cleanup** + gitignore.
6. **Push the 5 commits** + move `v1.0.0-rc1-candidate` tag.

## 8. Constraints carry-over (unchanged)

- Single-line commits, no body/trailers/Co-Authored-By. Never `-Djbct.skip=true` for aether. Never `mvn verify` with `HCLOUD_TOKEN` set. Commit directly on the release branch (no feature branches). All timeouts configurable + `TimeSpan`. Delegate Maven to `build-runner`; non-trivial Java to `jbct-coder`.
