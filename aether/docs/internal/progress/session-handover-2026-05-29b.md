<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Session Handover — 2026-05-29b — Membership-v2 finale landed + first Docker validation exposed a bug cascade → membership-unification redesign STARTED (P1+P2-consensus done)

**Branch:** `release-1.0.0-rc1`. **HEAD:** `171e7f9e4`. **Working tree clean.** **4 commits LOCAL/unpushed** (atop the pushed finale `61708f2de`).

## ⭐ START HERE

This session did three things:
1. **Finished the membership-v2 mechanical finale** (FSM + `NodeLifecycleKey/Value` atom fully deleted) — **pushed** (`61708f2de`).
2. **Ran the first-ever end-to-end Docker validation of v2 cold-start**, which exposed a **cascade of pre-existing v2 bugs** (cold-start consensus deadlock; dead route-wiring → 0 slices; LeaderReconciler provisioning death-spiral). Fixed the cold-start deadlock; root-caused the rest.
3. **Designed and STARTED a membership-unification big-bang** (the structural fix the user wants instead of tactical patches). Spec written; P1 (unified tracker) + P2-consensus done & committed.

**The single next action:** **P2-b — AetherNode wiring** (contract in §4). It puts the new `MembershipTracker` live in the consensus path; **must be followed by a Docker re-validation** (cold-start regression is the failure mode if mis-ordered). Then P3→P6.

**Local commits to consider pushing** (clean point): `2bf8e4e45`, `7eb709bb7`, `89dbe2d2a`, `171e7f9e4`.

## 1. Commit chain this session (atop pushed finale `61708f2de`)
```
171e7f9e4 refactor(consensus): P2 — TopologyObserver sources quorum/inQuorum/mode from injected MembershipView (legacy QUIC fallback); cold-start edge unchanged
89dbe2d2a fix(swim): restore COLD_BOOT FaultyObserved→UnknownObserved suppression (revert leftover THROWAWAY spike #231)
7eb709bb7 feat(membership): P1 — unified SWIM-fed MembershipTracker (sample+hysteresis, emit-once, implements consensus.MembershipView) + design-of-record spec
2bf8e4e45 fix(consensus): restore TopologyObserver cold-start ClusterStateNotification originator + idempotent handleClusterActive — fixes v2 formation deadlock (3 TopologyObserverTest)
```
The finale itself (pushed): `484a6d1b0` B6, `f997ed5c5` projector, `faffa18cf` C-2, `61708f2de` atom deletion. All build-green, unit-verified (slice 547, deployment 312, metrics 222, node 458, consensus 573, swim 93).

## 2. The bug cascade Docker exposed (and status)

Running `cd aether/tests/integration && ./run-tests.sh --env remote --suites 00,02 --skip-build` on the finale revealed, in order:

- **Bug A — cold-start consensus deadlock. FIXED (`2bf8e4e45`), Docker-proven.** 2c.0 (`a9d6229b0`) made `TopologyObserver.evaluateQuorumState()` *suppress* the `ClusterStateNotification.ACTIVE` emission, intending `RabiaEngine`/`ConsensusBridge` to own it — but the bridge only *echoes* an already-active engine, so nothing originated the first ACTIVE → every engine stuck `Stopped` → no leader → no generation → 360s timeout. Fix: restored `TopologyObserver` as the cold-start originator (route ACTIVE on `false→true` quorum edge, PASSIVE on `true→false`) + made `RabiaEngine.handleClusterActive` idempotent (ignore ACTIVE while already active/syncing — absorbs the bridge echo). The 3 `TopologyObserverTest$QuorumStatePublishing` tests are its regression guard. **Result: cluster forms 5 nodes + leader in 0s.**

- **Bug B — dead route-wiring → 0 slice instances → app 404. ROOT-CAUSED, not yet fixed (folds into P4).** In `AetherNode.assembleNode`, `allEntries.addAll(aetherEntries)` (~line 1504) runs *before* three routes are appended to `aetherEntries` (~1616 NTT-reconciler toggle, ~1619 `ClusterStateNotification`→readiness holder, ~1624 QUIC `PeerDisconnected`→evict). Those three are **never installed**. Consequence: node readiness (`NodeReportedState`) never set → CDM `allocatableNodes = activeNodes ∩ readyNodes` empty → `ClusterDeploymentState`: "No allocatable nodes" → slices never placed. (Naively moving the 3 to `allEntries` *did* fix readiness but activated Bug C — see below. The real fix is the P4 per-member-state reshape, which removes the fragile set-intersection entirely.)

- **Bug C — LeaderReconciler provisioning death-spiral on static clusters. ROOT-CAUSED, fixed structurally by the redesign (D2) + P5 identity rule.** Once the NTT-reconciler toggle was wired, a configured static peer that hadn't joined (node-4 stuck at QUIC `INIT`) made NTT membership read 3–4 < 5 → reconciler provisioned phantom `DockerComputeProvider` replacements → 24+ `aether-a-node-3EOl…` containers → host OOM → real nodes `Exited (137)` → cascade. The reconciler must never replace a *configured* core peer that simply hasn't joined yet.

## 3. The redesign — membership unification (the structural answer)

**Spec (design of record): `aether/docs/specs/membership-unification-spec.md`** — read it; it has the full model, module placement, component-fate table, tracker mechanism, decisions, and phased plan.

**Decisions (settled with user):**
- **D1 = A:** ONE liveness signal (SWIM; QUIC connect/disconnect are *hints into* SWIM). ONE membership tracker, consumed by both consensus (quorum) and deployment (reconcile/place) via the `consensus.topology.MembershipView` interface.
- **D2:** live membership **never mutates the consensus voting group** (deliberate via config/`SetClusterSize`/provisioning) — only gates quorum + drives deployment. (Structural guard against Bug C.)
- **D3:** generation snapshot = consensus-replicated *distribution* of membership+state+epoch; leader-side CDM/reconciler read the tracker directly.
- **D4:** per-member state (`JOINING/READY/DRAINING`, from the pong) stays a deployment concern, annotating tracker members; consensus never sees it.
- **D5:** big-bang, committed incrementally directly on `release-1.0.0-rc1`; RC1.

**Module DAG (grounds placement):** `core/messaging < consensus < swim < aether-deployment < aether-node` (swim depends on consensus; consensus does NOT depend on swim). So: the `MembershipView` *interface* lives in `consensus` (low); the unified tracker *impl* lives in `swim` (it can implement the consensus interface); deployment reads it via the interface. **NTT relocates deployment→swim.**

**The core simplification:** today membership is computed in ~4 places (NTT, snapshot `coreMembers`, `TopologyObserver` view, deployment `MembershipView`), quorum in 2 (TopologyObserver QUIC-count vs LocalQuorumWatcher), phase in 2 (TopologyMode vs ClusterPhaseView), from 3 liveness signals (SWIM, QUIC, pong). Collapse to: **NTT = the one membership source; pong = per-member state attribute (looked up, NOT a set to intersect); `allocatable = members.filter(state==READY)`.**

**The tracker mechanism (replaces NTT's leaky per-node timers):** today NTT updates its set instantaneously on every SWIM edge and a per-node timer debounces only ONE of five reconcile-trigger paths (leaky). New design = **periodic sample tick + per-node hysteresis** (in after K-up samples, out after K-down), emit set-delta **once** per stable transition. Smooth the SET (identity-preserving), not a scalar count; count-smoothing belongs one layer up in the reconciler's scale decision. QUIC connect/disconnect bias the sample (and fast-evict per-member state) without bypassing hysteresis.

## 3a. DONE so far in the redesign
- **P1 (`7eb709bb7`):** `org.pragmatica.swim.membership.MembershipTracker` in `integrations/swim` — SWIM-fed, sample+hysteresis, emit-once, implements `consensus.MembershipView` **as-is (no interface change)**, exposes `members()/memberCount()/hasQuorum()/phase()` + `onQuicReconnect/Disconnect/onSwimObservation` + deterministic `sample()` test hook. `MembershipChange(joined,left,members)` + `MembershipListener` + `MembershipPhase{COLD_BOOT,NORMAL,RECOVERING}` + `MembershipTrackerConfig(sampleInterval,upHysteresis,downHysteresis; fromDepartureTimeout(...))`. Quorum from `IntSupplier coreSize`→majority. `onDutyMemberIds()==members()` until P4 attaches pong-state. **13 unit tests green.**
- **Swim fix (`89dbe2d2a`):** the 4 pre-existing swim failures were a REAL defect (a leftover `THROWAWAY` spike `8ad603b8f` gutted COLD_BOOT `FaultyObserved→UnknownObserved` suppression). Reverted → swim 93/0/0.
- **P2-consensus (`171e7f9e4`):** `TopologyObserver.evaluateQuorumState()` now derives `haveQuorum` from the injected `MembershipView` (`view.healthyOnDutyCount() >= quorumSize()`) when present, else legacy QUIC-count fallback. Cold-start ACTIVE/PASSIVE edge + `quorumEstablished` CAS **byte-for-byte unchanged**. `inQuorum()`/`TopologyMode` transitively view-sourced. **573 consensus tests green, cold-start guard intact.** No consensus interface change; transport-registry/discovery/voting-group/`MembershipDecision` all retained (migrate later).

## 4. NEXT — P2-b: AetherNode wiring (THE immediate next step; AetherNode = direct edits, truncation magnet)

Make the `MembershipTracker` the live `MembershipView` the (already-rewired) `TopologyObserver` reads. Contract:
1. **Construct** `swim.MembershipTracker` in `AetherNode` (self, `MembershipTrackerConfig`, `Supplier<HealthSnapshot>` from `swimHealthDetector`, `IntSupplier` core-size = `config.topology().coreNodes().size()`, a `MembershipListener`).
2. **Feed it:** `swimHealthDetector.addObservationListener(tracker::onSwimObservation)`; **start its sample tick** (`tracker.start()`); wire QUIC connect/disconnect → `tracker.onQuicReconnect/onQuicDisconnect` (reuse the existing QUIC connectivity taps — see `nttConnectTap`/`nttDisconnectTap` and `attachQuicConnectivityReporter`).
3. **Inject as the view:** the `GenerationSnapshotSource` passed into `RabiaNode`→`TopologyObserver` must return `Option.some(tracker)` from `currentMembershipView()` — **but only once the tracker has a real view; return `Option.none()` until then** (else quorum gates on `healthyOnDutyCount=0`). Preserve `observedRabiaTerm()` (used by `publishMembershipDeltas`).
4. **Ordering:** the tracker needs `swimHealthDetector` (constructed late in `assembleNode`, ~line 1528) but the `snapshotSource` is built early (~line 382 `KvBackedGenerationSnapshotSource`). Use a forward-ref (mirror the existing `cdmReadyNodesRef`/`publisherRef`/`cdmSnapshotSupplierRef` late-binding pattern) so `currentMembershipView()` resolves the tracker lazily.
5. **Tracker quorum semantics to honor:** `healthyOnDutyCount()` counts self when ON_DUTY and only SWIM-healthy core members; `coreMemberIds().size() >= quorumSize()` drives the one-way BOOTING→NORMAL flip.

**Then immediately Docker-re-validate** (`./run-tests.sh --env remote --suites 00 --skip-build --skip-teardown`) that the cluster still forms cold (5 nodes + leader). NTT (deployment) still exists in parallel at this point — that's fine; it's removed in P3.

## 5. Remaining phases (per spec §7)
- **P3** — `LeaderReconciler` + CDM + `GenerationSnapshotPublisher` read the tracker; **delete `LocalQuorumWatcher` + `ReachabilityAggregator`**; `ClusterPhaseView` phase ← tracker.
- **P4** — per-member state → `Map<NodeId,NodeState>` keyed by tracker membership; `allocatable = members.filter(state==READY)`, `draining = ==DRAINING`; QUIC-evict cleans state; **wire all routes into the live router (fixes Bug B's `aetherEntries`-after-merge defect as a side effect)**.
- **P5** — reconciler correctness: **identity-aware (never replace a configured core peer not-yet-joined)** + count-smoothing for scale decisions (fixes Bug C).
- **P6** — delete dead code (NTT, LocalQuorumWatcher, ReachabilityAggregator, etc.); full build green; **Docker `--suites 00,02` green end-to-end** (cold-start → slice placement, allocatable>0, app 200 → 02-chaos). First fully-green v2 00-smoke gate.

Task list (in the task tool): #9 P2 (consensus done; P2-b remains), #10 P3, #11 P4/P5, #12 P6.

## 6. Open items / traps
- **Remote `$TARGET_HOST` has leftover phantom `aether-a-node-3EOl…` containers** from the death-spiral run (classifier blocked my broad sweep on shared infra). The next integration run's `deploy_docker` label-scoped zombie cleanup (cluster a) removes them, or run a label-scoped `docker rm` (`label=aether.cluster=a` minus the 5 static names).
- **4 unpushed local commits** (`2bf8e4e45`..`171e7f9e4`).
- **AetherNode is a ~3500-line single `assembleNode` method** — do P2-b/P4 wiring via targeted Read+Edit, NOT delegated (truncation magnet). The KV-router fluent chain has brutal indentation; use the landmark-slice technique (see this session's Python-slice edits) for surgical removals.
- Verify builds with `mvn -pl aether/node -am install -Dmaven.test.skip=true` (NOT `-DskipTests`; NEVER `mvn verify` — HCLOUD failsafe). Integration runs: `--skip-build` reuses the JAR but still pushes it + rebuilds the remote image (gated by `--skip-image-push`, not `--skip-build`).
- Integration API key = `aether-integration-test-key`; cluster-A mgmt `http://$TARGET_HOST:5151`.

## 7. References
- Spec: `aether/docs/specs/membership-unification-spec.md`
- Prior finale handover: `aether/docs/internal/progress/session-handover-2026-05-29.md`
- Memory: `[[project_membership_v2_redesign]]`
