# Session Handover — 2026-05-07 (continuation, "b")

**Branch:** `release-1.0.0-rc1` · **HEAD:** `29b0e7905` (pushed) · **Tag:** `v1.0.0-rc1-candidate` at `29b0e7905` (pushed, force-updated)

Continuation of [`session-handover-2026-05-07.md`](session-handover-2026-05-07.md). That session ended with 13/13 sub-tests still pending validation on cloud due to Hetzner capacity exhaustion. This session attempted to close the remaining backlog via:

1. RC1 quality fixes (RC1-2 cleanup-401; RC1-1 CTM cluster-tag; RC1-8 node-count race; RC1-4 08-resources retarget; cloud-reaper orphan capture)
2. Audit Steps 1–5+8 of the membership-state-tracker consolidation
3. Cloud validation of all of the above

**Outcome: stop the consolidation. Pivot to 5 focused architectural fixes.** This document spells out exactly what to do next.

---

## ⚡ TL;DR for next session

**Read §3 (architectural diagnosis) and §4 (the 5-fix plan) FIRST.** Don't restart audit Steps 6–7 — they're blocked-by Step 7's `PeerObservationStore` reducer which doesn't exist.

**If you want to abandon the audit entirely:** revert commits in this range:
```
git revert --no-commit 29b0e7905..fbed2e95b   # Steps 1+2-revert+3+4+5-partial+8
git commit -m "revert: audit steps 1-5 partial — pause consolidation"
```
Then start §4 fix #1 (`ClusterIdentity`).

**If you want to keep audit progress (current state) and start with §4:** HEAD is fine as-is. Begin §4 fix #2 (CTM circuit breaker) — it's the bug class actively burning Hetzner spend.

**Hetzner state at session end:**
- 17 orphan VMs destroyed during this session (12 CTM-runaway + 5 bootstrap-without-aether-cluster-label)
- PG VM `129807252` (name `aether-test-pg-e3896b`, IP `188.34.158.168`) **running** (not powered off — power off if you don't plan to test soon)
- No firewall on PG VM today (reaper killed earlier). Re-`init` before next test run.
- Hetzner cost burn this session estimated **~€8–€12** (CTM runaway provisioning was the bulk)

```bash
# Power off PG to stop billing while you read this:
curl -s -X POST -H "Authorization: Bearer $HCLOUD_TOKEN" \
  'https://api.hetzner.cloud/v1/servers/129807252/actions/poweroff' | jq -r '.action.status'
```

---

## 1 · State at session end

| Item | Value |
|---|---|
| Branch HEAD | `29b0e7905` (pushed) |
| Tag `v1.0.0-rc1-candidate` | at `29b0e7905` (pushed, force-updated) |
| `./build.sh` | green at `78c67ed66` (last run); steps 5/5 pass |
| Local CLI binary | `~/.aether/lib/aether.jar` updated to current branch (RC1-2 fix active) |
| Hetzner account | clean (only PG VM left, running) |
| 12-network cloud last run | **1 PASS / 2 FAIL** (gossip OK; replacement-provision FAIL; SWIM-detect-time FAIL on second-kill) |
| Working tree | clean |
| Stash | none |
| Open PRs | none |

---

## 2 · This session's commits (12, all pushed; some reverted later)

```
29b0e7905 fix(membership): revert step 2 — QUIC re-emits NodeAdded/NodeRemoved (cold-boot leader election needs sync source)
9da5a6e4d fix(membership): restore quorum-eval legacy seed; public count stays snapshot-only
ed5d10fa0 refactor(membership): delete unused routeDisconnect; document audit progress (step 8 partial)
78c67ed66 refactor(membership): snapshot is sole source; drop legacy fallback + SWIM event listener (audit steps 4+5)
1243e3cb2 refactor(membership): membership-delta-driven QUIC eviction (audit step 3)
5fdffe967 refactor(membership): TopologyObserver owns NodeAdded/NodeRemoved + DHT NodeDown route (audit step 2)
fbed2e95b refactor(membership): add MembershipView delta publisher in TopologyObserver (audit step 1)
a030d985c fix(test-sql-connector): retarget+probe APP_ENDPOINT for cloud slice routing (RC1-4)
247ea85ef fix(test-infra): add cluster_node_count_quiesced for race-free single-shot reads (RC1-8)
d668676d3 fix(ctm): provisioned VMs tagged with cluster name, not 'unknown' (RC1-1)
a7192eec4 fix(reaper): catch aether-node-id orphans alongside aether-cluster matches
b073dabd2 fix(cleanup): env-aware credentials, fail-fast factories, exit code 4 (RC1-2)
```

**Verdicts on cloud:**

| Commit | Stays | Reverts | Notes |
|---|:-:|:-:|---|
| `b073dabd2` cleanup-401 | ✅ | | Confirmed working — no 401s on this session's cleanups |
| `a7192eec4` reaper orphans | ✅ | | Worked; default scope nuked PG once — fix usage discipline, not script |
| `d668676d3` CTM cluster-tag | ⚠️ partial | | CTM-provisioned VMs DO get `aether-cluster=cloud-test-b` ✅. **But bootstrap-time VMs DON'T** — they have `aether-node-id` only, no `aether-cluster`. **5th cluster-name path I missed.** See §4 fix #1. |
| `247ea85ef` cluster_node_count_quiesced | ✅ | | Helper added, not in active use yet |
| `a030d985c` 08-resources retarget | ✅ | | Untested on cloud — 08-resources didn't run |
| `fbed2e95b` audit Step 1 | ✅ | | Additive, harmless. Keep. |
| `5fdffe967` audit Step 2 | ❌ | **REVERT done @ 29b0e7905** | Cold-boot leader election broke (catch-22). QUIC ADD/REMOVE re-emit restored. |
| `1243e3cb2` audit Step 3 | ⚠️ | consider revert | Eviction now via consensus. Slow on cloud. Suspect cause of "SWIM detection time" 2nd-kill fail. Keep for now; revisit when CTM circuit breaker (§4 #2) is in. |
| `78c67ed66` audit Step 4+5 | ⚠️ | keep partial revert | Step 5 partial-reverted in `9da5a6e4d` for quorum-eval. Step 4 kept. |
| `ed5d10fa0` audit Step 8 partial | ✅ | | Pure dead-code deletion (`routeDisconnect`, unused import). Harmless. |
| `9da5a6e4d` Step 5 partial revert | ✅ | | Necessary for cold-boot quorum publish |

---

## 3 · Architectural diagnosis — why we're whack-a-moling

Five recurring patterns surfaced today. Each is the root of multiple individual bugs.

### Pattern 1: Cluster name as a primary key with N independent paths

`cluster_name` flows through:

1. **Bootstrap config TOML** (`[cluster] name = "..."`) — read by `ClusterBootstrapCommand`
2. **Composed runtime TOML** (`[cluster] name`) — emitted by `BootstrapOverlayGenerator.clusterSection`
3. **Composed runtime TOML** (`[cloud.discovery] cluster_name`) — emitted (today) by `BootstrapOverlayGenerator.cloudDiscoverySection`. Read by all 4 cloud factories' `applyDiscovery`.
4. **Bootstrap-time Hetzner labels** (`aether-cluster=<name>`) — set in `BootstrapPhaseProvision.java:183`. Reads from `ClusterBootstrapConfig.cluster().name()`.
5. **CTM-provisioned Hetzner labels** (`aether-cluster=<name>`) — set in `ClusterTopologyManagerRecord.buildProvisionTags()` (today), reads from `ClusterConfigValue.clusterName`.
6. **KV-Store** `ClusterConfigValue.clusterName` — written during cluster formation by `BootstrapPhaseFormation`.

**All six paths must agree.** Any one broken silently mis-tags VMs. Today's cycle:
- RC1-1 fix added paths 3 + 5
- Original 5 cluster nodes today STILL had `aether-node-id` only — path 4 (`BootstrapPhaseProvision`) is producing the right Map but it's not landing as a label. Need to investigate why.

**Architectural root:** there is no `ClusterIdentity` value object. Each consumer reads from its own source.

### Pattern 2: Bootstrap-mode vs steady-state-mode confusion

Membership state has multiple sources of truth that bootstrap at different phases:

- `nodeStatesById` (in-memory, populated at constructor from `config.coreNodes()`)
- QUIC transport observations (live from first connection)
- SWIM gossip (eventual, after first probe round)
- KV-Store snapshot (after Rabia quorum forms after consensus reaches majority)

**Cold-boot path (one-shot):** transport observations → quorum publish → leader elect → KV writes → snapshot → steady state.

**Steady-state path:** SWIM observations → leader's HealthReconciler → KV writes → snapshot → consumers.

**The audit assumed everything could go through the steady-state path.** Audit Step 2 deleted QUIC's transport-observation `NodeAdded` emission, assuming `TopologyObserver.publishMembershipDeltas` (snapshot-driven) would replace it. But the snapshot doesn't exist at cold-boot — it's published BY the consensus that needs the leader that needs `currentTopology` populated. **Catch-22.**

Today's logs confirmed: with Step 2 active, the leader-election FSM logs `Topology empty — skipping proposal, rescheduling tick` once per second forever.

**Architectural root:** no explicit `BOOTING` vs `NORMAL` mode on `TopologyObserver` reads. Code mixes the two assumptions.

### Pattern 3: CTM has no circuit breaker

Observed today on cloud (logs from `188.34.153.25`, leader after the kill):

```
21:15:39 CTM: Cluster at 4/5, provisioning 1 replacement(s)   → VM 129814031 created
21:16:49 CTM: expired 1 stalled provisioning slot(s)          → VM never joined within 70s
21:16:49 CTM: deficit=1 ... provisioning 1 more replacement(s) → VM 129814233 created
21:17:59 CTM: expired 1 stalled provisioning slot(s)          → VM never joined
21:17:59 CTM: deficit=1 ... provisioning 1 more replacement(s) → VM 129814381 created
... (5 more cycles, 7 VMs in 7 minutes, all orphans)
```

**CTM's reactive loop has no max-attempts, no backoff, no error state.** Each failure spawns the next attempt 70s later. We saw 7 cycles before the test was killed.

The "stalled provisioning slot" timeout is 70s. The new VM apparently can't join within 70s on cloud — cloud-init takes longer than that for first-boot Docker pull + container start. So every replacement times out. Forever.

**This is the orphan-leak factory.** Every failed bootstrap that runs 12-network's kill-test produces 5–10 orphans on Hetzner. The cleanup-401 fix (RC1-2) helped post-bootstrap-failure, but mid-test runaway is unaffected.

**Architectural root:** CTM treats "deficit" as a steady stimulus to react to, not as a state to manage. A real implementation needs:
- Slot-attempt counter (per logical-slot-id, not per VM)
- Exponential backoff between failed attempts
- Failed-attempt cap (e.g., 3 in 5 min) → emit `CTM_PROVISIONING_FAILED` event + write state to KV → operator alarm
- KV-Store-backed state so a leader change doesn't reset attempt counters

### Pattern 4: ProvisionSpec.tags is an untyped Map<String, String> with implicit conventions

`ProvisionSpec.tags` carries:
- `aether.peers` — 3-part PEERS list for cloud-init
- `aether.core-max` — desired core size as string
- `aether.provisioned-by` — "ctm" or "bootstrap"
- `aether-cluster` — Hetzner-spec cluster label (no dot in key — Hetzner regex)
- `aether-role` — role label
- `aether.role`, `aether.cluster`, `aether.node-id` — Docker provider's set, with dots, accepted by Docker but rejected by Hetzner

**Two label namespaces (`aether-*` Hetzner-compatible vs `aether.*` Docker-style) coexist in the same Map.** Hetzner's `mergeLabels` silently drops `aether.*`. Docker happily accepts both. No type-level enforcement.

`DockerComputeProvider` reads via `getOrDefault("aether.role", "core")` — silent if missing. The Hetzner label-filter-422 from prior session and today's runaway-provisioning-with-wrong-label both come from this asymmetry.

**Architectural root:** `ProvisionSpec.tags` should be a typed `ProvisionContext(clusterIdentity, role, peers, coreMax, provisionedBy)` record. Each provider does its own native encoding internally.

### Pattern 5: The audit's R-phase plan was asymmetric

[`aether/docs/internal/audits/membership-state-tracker-audit-2026-05-07.md`](../audits/membership-state-tracker-audit-2026-05-07.md) (the same audit doc) defined 8 steps. Today we did Steps 1–5+8.

**Step 7 ("cross-node `PeerObservationStore` quorum aggregation") is the foundation Steps 3–6 rely on.** Without it:
- HealthReconciler is a single-witness decider. Slow on cloud. Each FAULTY observation has to traverse Rabia round-trip before any visible effect.
- Audit Step 6's "phase-aware cold-boot suppression" requires `ClusterPhaseKey == NORMAL` to be observable, which depends on HealthReconciler reaching that phase, which depends on the membership being steady — which is Step 7's quorum aggregation.

**Step 7 itself requires building infrastructure that doesn't exist:** the `PeerObservationStore` reducer. Audit acknowledges this with **HIGH** risk rating but doesn't sketch the implementation.

**Doing Steps 3–6 without 7 ships the audit's intended slowness without its intended correctness.** That's what we're seeing on cloud — eviction is now consensus-mediated (slow), but aggregation is still per-leader (single-witness, not quorum). Worst of both worlds.

---

## 4 · The 5-fix plan (replaces audit Steps 3–7)

Each fix is self-contained. Build green between. Order is by leverage; can be parallelized after #1 and #2 land.

### Fix #1: `ClusterIdentity` value object (closes Pattern 1)

**Goal:** single source of truth for cluster name. No code reads `cluster_name` from raw strings; all paths read from a `ClusterIdentity` instance threaded through the entire system.

**Estimated effort:** 1 day.

**Files (likely):**
- New: `aether/aether-config/src/main/java/org/pragmatica/aether/config/cluster/ClusterIdentity.java` — value object: `record ClusterIdentity(String name) { ... factory with regex validation ^[a-z][a-z0-9-]{0,62}$ ... }`
- `aether/aether-config/src/main/java/org/pragmatica/aether/config/cluster/ClusterIdentity.java` — embed in `ClusterBootstrapConfig`
- `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/ClusterBootstrapCommand.java` — use `ClusterIdentity` from parsed config
- `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/BootstrapPhaseProvision.java:183` — use `ClusterIdentity.name()` for tags. **Investigate why today's bootstrap-time VMs are missing `aether-cluster` despite this code path. Likely the tag IS in the Map but Docker mode is in use (test-b uses Docker-style tags via DockerComputeProvider's tag-key conventions).**
- `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/BootstrapOverlayGenerator.java` — already emits `[cloud.discovery] cluster_name` (RC1-1 fix); switch source to `ClusterIdentity`
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManagerRecord.java:buildProvisionTags()` — already emits `aether-cluster` (RC1-1 fix); switch to `ClusterIdentity`
- KV-Store: `ClusterConfigValue.clusterName` field stays as String for serialization; converted to `ClusterIdentity` at read sites

**Investigation step BEFORE coding:** ssh to a freshly-provisioned cluster's bootstrap-time VM, `docker inspect aether-node`, see if `aether-cluster` IS in the docker labels. If yes, the issue is that Hetzner-API labels aren't being set (tags map flowing through DockerComputeProvider but not surfacing as Hetzner VM labels). If no, the bootstrap code path in `BootstrapPhaseProvision` isn't applying the cluster-tag for cloud sources.

**Validation:**
- Unit test: parse TOML, assert `config.clusterIdentity().name() == "test-b"`
- Integration: bootstrap a 5-node cloud cluster, run `tools/cloud-reaper.sh --cluster test-b` (no `--strict-cluster`), confirm all 5 VMs are listed under `aether-cluster=test-b`

**Risk:** LOW. Mostly mechanical. Threads through ~6 files.

---

### Fix #2: CTM circuit breaker (closes Pattern 3, the orphan-leak factory)

**Goal:** stop runaway provisioning. After N failed attempts in window W, pause CTM and surface state.

**Estimated effort:** 1 day.

**Files (likely):**
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManagerRecord.java`
  - Add `failedProvisionAttempts: ConcurrentHashMap<String, AtomicInteger>` keyed by some logical "slot ID" (e.g., the to-be-replaced `NodeId.id()` or the pool name)
  - Add `lastFailedAttemptAt: ConcurrentHashMap<String, Long>` for backoff
  - In `handleDeficit`/`handleDeficitFromConverged`: BEFORE provisioning, check counter. If `>= MAX_ATTEMPTS_PER_SLOT (e.g. 3)`, log + emit `CTM_PROVISIONING_FAILED` event + skip.
  - In `expireSlots` (called when slot timeout hits): increment the counter for that slot. Schedule next attempt with exponential backoff (`30s, 60s, 120s` then permanent failure).
- New `ScalingEvent.ProvisioningFailed` (mirrors existing `ScalingEvent.ScaledUp`/`ScaledDown` shape) — emitted to event bus + buffered in `ClusterEventAggregator`
- New `ClusterEvent.EventType.PROVISIONING_FAILED` enum
- KV-Store: optionally write a `ProvisioningFailureKey/Value` atom so leader change preserves the failure state. **Defer if scope creep.**

**Validation:**
- Unit test: simulate 3 expirations, assert 4th call no-ops + emits event
- Integration: kill a node in 12-network, deliberately misconfigure replacement so it can't join, assert CTM stops after 3 attempts within 5 min instead of forever
- Cloud: after this fix, kill-test no longer creates >3 orphan VMs

**Risk:** MEDIUM. CTM is leader-only, but state needs careful concurrent handling.

**Open question:** what counts as a "successful" provision? Today CTM logs `provisioning succeeded` when the API call returns OK, not when the VM joins. The slot is what tracks join. So the counter should reset on slot.JOIN and increment on slot.EXPIRE.

**Side benefit:** with this in place, the audit Step 3 trade-off (slower eviction) is bounded — even if SWIM detection lags, CTM won't hyper-react.

---

### Fix #3: Explicit `BOOTING` vs `NORMAL` modes for `TopologyObserver` reads (closes Pattern 2)

**Goal:** make the bootstrap-vs-steady-state distinction explicit and one-way. `BOOTING` reads from `nodeStatesById` legacy fallback. `NORMAL` reads from snapshot only. Transition is explicit and observable.

**Estimated effort:** 1.5 days.

**Files:**
- `integrations/consensus/src/main/java/org/pragmatica/consensus/topology/TopologyObserver.java`
  - Add `AtomicReference<TopologyMode> mode = new AtomicReference<>(BOOTING)` field
  - `BOOTING`: `healthyActiveNodeCount` and `readyNodeCount` use legacy `nodeStatesById` count. `healthyActivePeerCount` (private quorum) ditto.
  - `NORMAL`: all of the above are snapshot-only. Returns 0 if snapshot empty.
  - Transition `BOOTING → NORMAL` triggered by FIRST observation of `MembershipView` with `coreMemberIds().size() >= clusterSize / 2 + 1` (quorum reached in projected snapshot). One-way; no `NORMAL → BOOTING`.
  - Expose mode via API for diagnostics: `aether status --field topology.mode`
- Public API surface stays the same (`int healthyActiveNodeCount()`); behavior depends on mode
- Tests: existing `TopologyObserverSnapshotDualModeTest` already exercises both paths; add a new test asserting the BOOTING→NORMAL transition fires once

**Validation:**
- Unit: `BOOTING` mode returns legacy count; after seeding snapshot with quorum, `NORMAL` mode kicks in; subsequent empty snapshot returns 0 (no regression)
- Integration: cluster bootstrap on docker — assert mode is `NORMAL` post-quorum, not before
- Cloud: 12-network passes with no cold-boot regression

**Risk:** MEDIUM. Subtle mode transitions; tests need to cover the edge.

**Architectural ROI:** This eliminates the catch-22 we hit with audit Step 2 cleanly. The audit's Step 5 ("snapshot-only") becomes the `NORMAL`-mode behavior; legacy fallback is the `BOOTING`-mode behavior. Both are explicitly named.

---

### Fix #4: `ProvisionRequest` typed contract between CTM/bootstrap and `ComputeProvider` (closes Pattern 4)

**Goal:** replace `ProvisionSpec.tags: Map<String, String>` with a typed `ProvisionContext` record. Each provider encodes natively.

**Estimated effort:** 1.5 days.

**Files:**
- New: `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/ProvisionContext.java`
  ```java
  public record ProvisionContext(
      ClusterIdentity clusterIdentity,    // depends on Fix #1
      String role,                         // "core", "lb", "passive", etc.
      Option<String> peers,                // 3-part PEERS list, empty for first node
      int coreMax,
      String provisionedBy,                // "bootstrap" or "ctm"
      Option<String> sourceName,           // for cleanup/destroy
      Map<String, String> extraTags        // escape hatch — used cautiously
  ) { ... }
  ```
- `ProvisionSpec` — replace `Map<String, String> tags` field with `ProvisionContext context`. Provide a deprecated `tags()` accessor that derives the Map from context for transition.
- `HetznerComputeProvider.buildLabels()` — derive `Map.of("aether-cluster", clusterIdentity.name(), "aether-role", role, "aether-source", sourceName.or("manual"))`. Hetzner-spec compatible.
- `HetznerComputeProvider.buildUserData()` — derive PEERS env-var from context.peers, no longer reads from tags.
- `DockerComputeProvider` — same, but with Docker-conventional label keys. NOT IN tags.
- `AwsComputeProvider`, `GcpComputeProvider`, `AzureComputeProvider` — same pattern.
- `BootstrapPhaseProvision.java:183` — pass `ProvisionContext`, not raw Map.
- `ClusterTopologyManagerRecord.buildProvisionTags()` — rename to `buildProvisionContext()`, return typed context.

**Validation:**
- Unit: each provider's encoding tests
- Integration: bootstrap docker cluster, ssh to node, verify `aether-cluster` label set correctly
- Cloud: bootstrap 3-node cluster, verify all 3 VMs have `aether-cluster=<name>` (the bug we hit today)

**Risk:** MEDIUM. Touches all 5 providers + 2 callers. Mechanical but wide.

**Side benefit:** when audit's HetznerLabelFilter (`mergeLabels`) bails out on `aether.peers` keys (because they have dots), the context-driven path doesn't even SEND those keys as labels — they go through `userData`. The 422 bug class can't recur.

---

### Fix #5: Persist source profiles in `BootstrapState` for robust cleanup (closes Pattern 1's tail)

**Goal:** `aether cluster destroy --cluster X` from a fresh process must work without env-var conventions matching. Today the env-var fallback covers Hetzner only. The right fix is persistence.

**Estimated effort:** 0.5 day.

**Files:**
- `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/BootstrapState.java` — add `Map<String, SourceCleanupHandle> sources` field
- New: `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/SourceCleanupHandle.java`
  ```java
  public record SourceCleanupHandle(
      String provider,                  // "hetzner", "aws", etc.
      Option<String> region,
      String credentialsEnvVar          // ALWAYS env-var-name, never inline value
  ) { ... }
  ```
- `BootstrapState.fromJson` / `toJson` — backward-compat: if `sources` field is missing, populate from env-var conventions (today's behavior)
- `BootstrapCleanup.destroyVm` — look up `SourceCleanupHandle` from state, resolve credentials at cleanup time
- `BootstrapPhaseProvision` — stamp `state.withSources(...)` after provisioning

**Validation:**
- Unit: round-trip `BootstrapState.toJson` / `fromJson` with sources
- Integration: bootstrap a cluster, kill the CLI process, run `aether cluster destroy --cluster X` from a fresh shell with HCLOUD_TOKEN set — VMs should be terminated
- Backward compat: load a state file from a pre-Fix-5 run, sources field absent → cleanup uses env-var fallback

**Risk:** LOW. Additive field; back-compat read path keeps old states working.

---

### What's NOT in the plan (deferred)

- **Audit Steps 6 + 7.** Step 7 needs a `PeerObservationStore` reducer that doesn't exist. Step 6 needs Step 7 and `ClusterPhase` plumbing. Both should be RC2 work AFTER Fix #2 (CTM circuit breaker) is in production.
- **`CLEANUP_HOOK` AtomicReference → `BootstrapContext` field.** Pure ergonomics, no bug-class. Defer.
- **`bootstrap(...)` overloads → `BootstrapOptions`.** Same — ergonomics.
- **CTM decomposition (Observer/Decider/Provisioner).** RC2. Fix #2 (circuit breaker) is enough for RC1.

---

## 5 · Other ideas / considerations

### A. Test thresholds are coupled to implementation timing

The failing test `Kill_node_and_detect_drop` checks "NODE_FAILED on /api/events within 60s". This is testing an IMPLEMENTATION detail (which path emits the event), not a system property. With audit Step 4's removal of SWIM-witness emit, the path is now KV-Store-driven and slower. The test threshold doesn't allow for the new path's latency.

Better tests would say: "After kill, the cluster's `coreCount` drops within 30s OR a `NodeRemoved` event fires within 30s". One ORs the other; specific path doesn't matter. Apply during Fix #3.

### B. Reaper default-broad scope is dangerous

Today I forgot `--cluster X` and the reaper nuked the PG VM. The fix added orphan-catching makes this worse. Two options:
- Make `--cluster X` REQUIRED unless `--all` is also passed
- Add a "preserves" set: never destroy resources matching `aether-cluster=test-pg` or `aether-role=postgres` unless explicit override

Recommend the second — operator wants to scope away long-lived infra resources.

### C. Step 3 may be over-aggressive

After landing Fix #2 (CTM circuit breaker) and Fix #3 (BOOTING/NORMAL mode), Step 3's removal of synchronous QUIC eviction may still cause SWIM-detect-time test fails on second-kill scenarios. If so, revert Step 3 too. That preserves the audit's NodeAdded/NodeRemoved canonical-emit but lets QUIC evict synchronously on FAULTY for transport hygiene.

### D. The PG VM lifecycle is fragile

PG VM is needed for `aether store` integration tests. It's persistent. Today's reaper-nuke cycle cost ~5 min to reprovision. Consider:
- Tagging PG VM with a "do not reap" label that the reaper respects
- OR having `provision-test-pg.sh --idempotent` re-create it cleanly when missing

### E. The cluster bootstrap-time VMs are missing `aether-cluster` label

This is a NEW finding from today (Pattern 1's 4th path). Before Fix #1 lands, investigate:
1. Is the tag in the Hetzner API call? Add a log statement at `HetznerComputeProvider.buildCreateRequest` to log the labels Map.
2. Is `BootstrapPhaseProvision` actually using the cloud path (check `source.type() == CLOUD`) and not falling through to a different provisioning mode?
3. The `aether-node-id` label on bootstrap-time VMs IS set today. So `BootstrapPhaseProvision` IS reaching Hetzner — it's just not passing `aether-cluster` for some reason.

This is a 30-min investigation max; surfaces as a follow-on after Fix #1.

### F. The audit's `MembershipDelta` record I added in Step 1 is currently unused

`integrations/consensus/.../MembershipDelta.java` is a placeholder. Step 1 publishes via `TopologyChangeNotification.NodeAdded/NodeRemoved` directly, not via `MembershipDelta`. If we abandon the consolidation, delete this record. If we keep it, wire it into Fix #3's mode transitions (the BOOTING→NORMAL transition fires a `MembershipDelta` with all `coreMemberIds` as `added`).

### G. RC1-3 (revalidate 03-scaling) stays pending

Last session's handover flagged 03-scaling as suspicious — passed but the SPI bundle wasn't there to support CTM, so how did it pass? Until §4 fixes land and we run a clean cloud test sequence, this is unresolved.

### H. Tests that consistently use `--skip-teardown` left orphans every time the user pauses

Each session this week ended with N orphan VMs because `--skip-teardown` was used to preserve cluster for inspection but inspection didn't happen → operator pauses → orphans burn money. Two options:
- `--skip-teardown` should auto-power-off VMs after a configurable inactivity timeout
- OR a `tools/end-session.sh` script that powers off everything in test-b cluster

---

## 6 · Quick start for next session

```bash
# 1. Sanity
git log --oneline 7fbab16f5..HEAD          # 12 commits this session
git status --short                          # should be clean
git tag --points-at HEAD                    # v1.0.0-rc1-candidate

# 2. Start with a clean Hetzner inventory check
curl -s -H "Authorization: Bearer $HCLOUD_TOKEN" 'https://api.hetzner.cloud/v1/servers' | \
  jq -r '.servers[] | "\(.id)\t\(.name)\t\(.status)\t\(.labels)"'
# Expected: only PG VM (129807252) — if anything else, reap with --cluster X

# 3. Decide the strategic question:
#    OPTION A — Abandon audit, start Fix #1
#    OPTION B — Keep audit progress, start Fix #2

# 4. (If OPTION A) revert audit work:
git revert --no-commit 29b0e7905..fbed2e95b
git commit -m "revert: pause audit consolidation — pivot to fix plan §4"

# 5. (Either option) Read §3 architectural diagnosis + §4 fix plan FIRST.
#    Don't restart audit Steps 6–7.

# 6. Power on PG when you're ready to test
curl -s -X POST -H "Authorization: Bearer $HCLOUD_TOKEN" \
  'https://api.hetzner.cloud/v1/servers/129807252/actions/poweron' | jq -r '.action.status'
```

**Estimated effort to land all 5 fixes: 5-6 days** (#1 1d, #2 1d, #3 1.5d, #4 1.5d, #5 0.5d).

**Audit Steps 6+7 (cross-node aggregation):** RC2 work. Build `PeerObservationStore` reducer first, then revisit. Until then, keep the current asymmetric paths — duplicate emit is cheaper than broken cluster.

---

## 7 · Open questions for the next session

1. **Why are bootstrap-time VMs missing `aether-cluster` label?** Investigate before Fix #1.
2. **Should the audit be formally abandoned in `aether/docs/internal/audits/membership-state-tracker-audit-2026-05-07.md`?** Mark Steps 3-7 as "deferred to RC2 pending PeerObservationStore reducer".
3. **Should we add a "do not reap" tag to PG VM and have the reaper respect it?**
4. **Is the local `aether` CLI binary install path (`~/.aether/lib/aether.jar`) documented anywhere?** Today's session lost time figuring out it was stale (May 5 binary, May 7 fixes).
5. **How aggressively should Fix #2's circuit breaker pause CTM?** 3 attempts before pause is conservative. Some operators may want infinite retry for production. Make it configurable in `aether.toml`?
