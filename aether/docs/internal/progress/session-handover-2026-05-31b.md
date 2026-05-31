<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Session Handover — 2026-05-31b — #34 closed; forwarding + cluster-identity hardened; silent-death detection fixed; membership-convergence cluster remains

## ⚡ START HERE / TL;DR

This session started from #34 (provider-minted replacements never reach READY in the leader's view) and peeled a long, disciplined stack. **Many real fixes shipped and are committed**; the remaining work is a **structural membership-convergence cluster** (replacement-core-classification, multi-kill/under-load departure, sub-quorum self-drain safety, CTM provisioning churn) that wants a deliberate scoped effort — not more tactical patching.

- **Branch:** `release-1.0.0-rc1`. **HEAD `d42a86ebe`.** Working tree clean. **NOT pushed** (RC1 not green).
- **Original forwarding goal is implemented + committed but its error-rate validation is still BLOCKED** by the multi-kill/under-load departure issue (the test aborts on the departure precondition before reaching the error-rate assertion).
- **Authoritative oracle is Docker** (`run-tests.sh --env remote --suites 02,12`). The in-process Ember spike misled us twice this session — trust Docker.

## 1. What shipped (committed this session, oldest→newest)

- `63d653ff0` fix(membership): replacements reach READY in leader view (#34) — SWIM announce-until-acknowledged + ClusterSync ping-set unions connected peers.
- `826affd1f` fix(reconciler): re-provision in-flight replacement killed during joining window (LeaderReconciler self-rescheduling sweep).
- `bd9cbbd8a` fix(docker): propagate AETHER_CLUSTER_NAME to provisioned replacements.
- `30409fd1a` fix(provisioning): propagate cluster-identity env across **all** providers (shared `ClusterIdentityEnv` allow-list) + fail-loud boot guard on missing cluster name + refuse dev-mode when operator TLS configured + Azure customData + drop "default".
- `0a65b5895` fix(forwarding): filter forward targets by NTT live membership (`NodeTopologyTracker.keepOnlyAccessible` + `AccessibilityFilter` SAM wired via deferred `nttRef`) — the **error-rate fix**, unit-validated, integration-validation still blocked (see §3).
- `9fee81491` docs: cluster-identity hardening + boot guards + dev-mode TLS guard + NTT-filtered forwarding (CHANGELOG, feature-catalog, tls-certificates, configuration, management-api/cli precondition notes).
- `551f97f12` fix(membership): silent-death detection — drop channel-open revival + **eviction marks snapshot dirty** for prompt NODE_FAILED + SWIM transport blackhole injection hook.
- `288986417` test(forge): faithful both-plane black-hole spike (absence-is-terminal contract).
- `7db18df4b` fix(test-harness): coerce cluster member-count to single int (stop `wait_for` predicate word-splitting → 600s wedge).
- `d42a86ebe` fix(swim): anti-resurrection — clear death-memory on FAULTY removal + re-add peers as SUSPECT (probe-ack sole ALIVE authority).

## 2. The diagnostic stack we peeled (so the next session doesn't re-walk dead ends)

1. **Forwarding error-rate (47%)** root-caused: HttpForwarder filtered targets by QUIC `connectedPeers()`, which on silent death stays CONNECTED → routes/retries to a dead node until `forwardTimeout` → client-deadline failures. Fixed by intersecting with NTT live set (`keepOnlyAccessible`). `0a65b5895`.
2. **Departure events (NODE_FAILED) intermittent/slow** → not transport-gated per se; the chain `evict → currentMembers drops → snapshot reproject → coreMemberIds diff → NodeRemoved → NODE_FAILED` had a **missing edge**: `ntt.evict` never marked the snapshot dirty. Fixed (`markDirty` wired onto the NTT reconcile trigger). `551f97f12`.
3. **φ-accrual was a DEAD END** — prior-session memory said φ-accrual was "computed but unwired"; it does **not exist on this branch** (removed in the membership-v2 collapse). Don't chase it.
4. **The in-process spike's "~62s SUSPECT onset" was a HARNESS ARTIFACT** — the black-hole only silenced QUIC, leaving SWIM's separate UDP plane alive (the victim kept acking). Fixed the fault injection (`NettySwimTransport` blackhole gate, `AetherNode.blackhole` toggles both planes). With both planes silenced SWIM detects FAULTY in ~8s correctly.
5. **SWIM resurrection (#231)**: a removed node was re-created at ALIVE/incarnation-0 and `everSeenHealthy` (permanent) made it instantly HEALTHY → NTT re-admitted → oscillation. Minimal fix shipped (`d42a86ebe`): clear `everSeenHealthy` on removal + re-add as SUSPECT.
6. **Death tombstone — TRIED AND REVERTED**: a tombstone gating re-admission caused a **Docker cold-boot formation regression** (never-HEALTHY incarnation-0 seeds got tombstoned during formation → quorum never forms → self-drain). Reverted. If re-attempted, it MUST be gated on `everSeenHealthy` (only tombstone something that actually lived) and is unproven-necessary — validate need on Docker first.

## 3. THE REMAINING CLUSTER (next session START HERE) — structural, interrelated

Latest Docker `02,12` (HEAD `d42a86ebe`): formation clean, single-kill detection fast (2s), cluster-identity correct, kill-leader survives, no resurrection — **but NOT green**:

- **(A) Replacements not classified as cores — LIKELY SHARED ROOT.** `pick_non_leader: only 1/2 candidates` — healthy ULID replacements aren't counted as core-eligible by the leader/harness. Cascades into Kill_2_nodes, partition-quorum-gate (S05), quic-connectivity (quorum-window). Start here — fixing classification may cascade-fix several.
- **(B) Multi-kill / under-load departures don't fire in budget.** `Kill_2_nodes` and `Kill_node_during_active_load` time out waiting for NODE_FAILED. **This blocks the forwarding error-rate validation** (`Kill_node_during_active_load` gates on the departure precondition, never reaches `assert_error_rate_below`). Single quiescent kill departs in 2-15s; multi/under-load does not. Likely tied to (A) and/or leader-busy snapshot re-projection under churn.
- **(C) Sub-quorum self-drain BROKEN (SAFETY-CRITICAL, pre-existing).** Killing 3/5, survivors stayed Up 12 min, never self-drained, exit 0 not 2 (`SELF_DRAIN_INITIATED` never observed). Violates "sub-quorum must dissolve" (split-brain safety). Failed identically in this session's FIRST Docker run, so it predates the SWIM changes.
- **(D) CTM over-provisioning / churn tail.** 6+ `aether-b` containers churning (continuous fresh ULID spawns) after the run, surviving teardown. Likely downstream of (A) — if replacements aren't counted as cores, CTM keeps provisioning.

These sit in the membership-v2 / NTT / CDM / CTM convergence area the prior sessions also wrestled with (#230/#231/#232, cluster-B collapse). Treat as a deliberate structural pass, not tactical patches.

## 4. Reusable infrastructure built this session

- **Faithful both-plane black-hole spike:** `aether/forge/forge-tests/.../MembershipBlackHoleSpikeTest.java` — in-process (~2-3 min), silences QUIC **and** SWIM UDP, asserts absence-is-terminal (victim absent from membership + NODE_FAILED in `/api/events`). Run: `mvn -pl aether/forge/forge-tests test -Dtest=MembershipBlackHoleSpikeTest -Dsurefire.failIfNoSpecifiedTests=false`. **Caveat: it diverged into Ember-specific auto-heal/provisioning behavior (5→10 growth) and gave inconsistent evidence (54× vs 0× re-seed) — Docker is authoritative for membership-convergence.**
- Temporary BHTIMELINE instrumentation pattern (reverted) — re-add at the hops (`onSwimFaulty`, `onLivenessGone`, `evictIfConfirmedDead`, `emitPingTimeoutIfExceeded`, `GenerationSnapshotPublisher.runApply`, `ClusterEventAggregator` NODE_FAILED) when timing diagnosis is needed.

## 5. Process notes (hard-won)

- **Docker is the authoritative oracle.** The in-process Ember spike misled twice (incomplete fault injection; provisioning-storm divergence). Use it as a fast dev loop but confirm on Docker.
- **Early-boot STOP discipline** caught the tombstone formation regression before a wasted 360s run — keep it.
- **Verify subagent claims** repeatedly paid off (the φ-accrual dead end; the 62s artifact; the tombstone necessity). Demand live evidence.
- Java → jbct-coder; maven → build-runner with focused `mvn -pl <m> install -DskipTests -am` (NEVER verify/format/`build.sh`; NEVER with HCLOUD_TOKEN). Shell harness → general-purpose.
- Hetzner 15/15 is DEFERRED (paid). Cloud env-propagation (Hetzner/AWS/GCP/Azure) ships on unit tests + the Docker cloud-init pattern only this session.
- Pre-existing, separate: `LeaderReconcilerTest$ProvisioningArmLatch#deficitFromStart_…` fails at clean HEAD; gossip-encryption TLS-handshake heuristic.
