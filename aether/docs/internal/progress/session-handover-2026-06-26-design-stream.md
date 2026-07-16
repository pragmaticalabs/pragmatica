# Design-Stream Session Handover — 2026-06-26 (#336 SWIM node-add eviction — FIXED & validated)

> Companion to the 2026-06-25 handover. This session **root-caused, fixed, and real-infra-validated**
> the SWIM node-add eviction (#336) that gated #241's community-formation Forge proof.

## ⚡ TL;DR

- **#336 SWIM node-add eviction — FIXED.** Root cause: SWIM born members as `SUSPECT` with a death
  timer armed at birth, while the first probe is gated ~10s behind `startupDelay` → live joiners
  evicted before their first probe-ack. Fix: a new **local-only `OBSERVED` birth state** +
  corrected gossip-merge. **Validated on a real Docker cluster** (scale-up 5→7 in 25s, zero
  evictions; all chaos recoveries; clean total-restart). **PR #361** (branch
  `fix/336-swim-observed-birth-state`) — **rebased onto `release-1.0.0-rc2`** (standalone; slice-3/#359
  dropped, stays its own PR; verified to compile on rc2 without it) — **aether-main reviews + merges,
  I do NOT self-merge.**
- **The single-JVM Forge harness cannot validate node-add at 6-8 nodes** (transport/probe-ack
  contention collapse under the join handshake storm) — a harness artifact, not the fix. The real
  validation is the remote Docker cluster (`run-tests.sh --env remote`).
- **S20 (self-drain-quorum-loss → full restart recovery) FAILED — but it is NOT this fix.** Proven:
  a clean total-restart re-forms perfectly. S20 is a separate harness/recovery-orchestration issue
  (see §3). Track separately.
- **#241 community sizing is now configurable** (`CommunitySizing`), but **not deployment-wired**
  and there is **no integration suite** driving FORMING→ACTIVE — so #241's loop stays unproven on
  real infra (see §4).

---

## 1. The fix — `OBSERVED` birth state (#336)

### Root cause (proven; full analysis in `336-swim-node-add-eviction-diagnosis-2026-06-25.md`)
SWIM had no benign "known-but-unproven" state, so the 2026-06-13 failure-detector rework overloaded
`SUSPECT` (a death-bound state) for a freshly seeded/announced member and armed its death timer at
birth (`addSeedMember` → `beginSuspicion`). The first probe is gated ~10s behind `startupDelay`,
`joinGrace`(12s) ≈ `startupDelay`-max, and the join-grace suppression gated only the observation
stream, not the `onMemberFaulty` death-path. NEW regression vs the original 2026-03-09 SWIM (which
seeded joiners ALIVE, no timer). Node-type-agnostic (hits #336 core scale-up and #241 worker join
identically). Structural — violates ratified spec intent (born-OBSERVED, A1 alive-authority, #126).

### What changed (branch `fix/336-swim-observed-birth-state`)
- **`1fae84eb7` — OBSERVED birth state.** New `MemberState.OBSERVED` (appended last). `addSeedMember`
  and `introduceAnnouncedObserved` (renamed from `…Alive`) birth OBSERVED: no death timer, no gossip,
  no `everSeenHealthy`. `isProbable` includes OBSERVED. Promotion OBSERVED→ALIVE on probe-ack or
  gossip Alive. Liveness deadline: OBSERVED→SUSPECT only past `joinGrace` (no immortal limbo).
  `addMemberUpdate` wire-leak guard (OBSERVED never serialized). Removed the now-subsumed born-SUSPECT
  band-aids (join-grace defer in `expireSuspectIfOverdue`, the join-grace branch in
  `emitFaultyOrUnknown`); kept cold-boot suppression + transport-veto co-confirmation.
- **`157461755` — corrected gossip-merge** (the forge run caught the first attempt's bug):
  `statePriority(OBSERVED) = -1` (weakest) so a gossiped `Alive` **promotes** an OBSERVED member
  (propagated probe-ack evidence — the first attempt made OBSERVED "sticky" at priority 3, which left
  non-leader nodes holding healthy peers in OBSERVED and false-evicting them). `applyExistingMember`
  ignores gossiped SUSPECT/FAULTY for an OBSERVED member (own probing decides — A1/#126).
  `applyNewSuspectMember` of an UNKNOWN id now births OBSERVED too (gossip-SUSPECT-of-unknown is
  hearsay). Both `addMemberUpdate` overloads guard OBSERVED.
- **`df22bc773` — `CommunitySizing` config** (#241; see §4).
- Preserved invariants (do NOT regress): #231 tombstones, #126 co-confirmation, #94 LHM/dogpile.

### Validation (all green)
- **Unit:** `mvn -pl integrations/swim test` → **170/170**. Reviewed line-by-line (me) + a full
  `jbct-reviewer` pass (found the gossip-SUSPECT-of-unknown gap, now fixed).
- **Real-infra** (`run-tests.sh --env remote`, separate Docker containers built from this branch):
  suite **03 scale-up 5→7 converged in 25s, zero FAULTY/evictions**, scale-down 571/571 0.00% error;
  suite **02** kill-leader/node/multiple/under-load + **joining-window-kill** (replacement killed
  mid-JOIN) all PASS; suite **13** worker-join role accounting PASS.
- **Total-restart isolation** (manual, this branch): a fresh 5-node Cluster B re-forms cleanly after
  both a graceful (`compose stop/start`) and an abrupt (`kill -9`/start) total loss — leader elected,
  `LeaderReconciler reached full membership 5/5 NO_DEFICIT`.

### Key files
`integrations/swim/.../SwimProtocol.java` (`addSeedMember`, `introduceAnnouncedObserved`, `isProbable`,
`markSuspect`/`isSuspectEscalation`/`pastJoinDeadline`, `applyExistingMember`, `applyNewSuspectMember`,
`statePriority`, both `addMemberUpdate`), `SwimMember.java` (`MemberState`).

---

## 2. Forge harness limit (in-JVM node-add)

`CommunityFormationProbeTest` / `ScaleUpFiveToSevenProbeTest` (the tracked-red repros) **cannot go
green in single-JVM Forge** at 6-8 nodes: the join handshake storm starves probe-acks / drops QUIC
in one shared JVM, so healthy members are evicted as genuinely unreachable regardless of the
membership fix (43 probe-timeouts/run; the code's own `QuorumLossDetector "stuck-promotion artifact"`
suppression fires). This is a **harness resource artifact** — the real #336 is a cloud/real-hardware
bug, validated on the remote Docker cluster instead. The probes remain useful as tracked-red repros
of the harness limit; do NOT treat their red as the SWIM fix failing.

---

## 3. S20 self-drain-quorum-loss recovery — SEPARATE issue (NOT this fix)

Suite 02's S20 (survivors self-drain on quorum loss → `restart_all_nodes` → recover to 5) **failed**;
the cluster never came back, cascading the rest of 02. **Proven not to be the SWIM fix:** the clean
total-restart (§1) re-forms perfectly. Mechanism (from `/tmp/aether-deaths.log`): the chaos suite
auto-heal-replaced **all five** original compose nodes (`aether-b-node-1..5`) with **ephemeral
ULID-provisioned** `docker run` nodes; the committed membership then referenced the ULID ids; S20's
`restart_all_nodes` restarts the **compose** nodes (which the committed state no longer knows and the
ULID replacements don't persist across a `compose` restart) → no matching members to converge on. A
**harness-orchestration artifact** (restart-original-compose-nodes after auto-heal replaced them),
possibly also exposing a provisioned-node-persistence assumption. File as a separate harness/recovery
issue; not a blocker on the SWIM fix.

---

## 4. #241 community sizing — configurable, not yet deployment-wired

`df22bc773` adds `org.pragmatica.aether.config.CommunitySizing(targetSize, viabilityFloor)`
(DEFAULT 100/3), threaded `ClusterDeploymentContext` → `ClusterDeploymentManager` →
`AetherNodeConfig.DeploymentDefaults` → `AetherNode`; `nextCommunityState` is pure on `floor`. **Gaps
(needed to validate #241 on real infra):** (a) **no deployment seam** — `Main.java:88` never sets
`DeploymentDefaults`, no TOML/CLI/env binding; cheapest wiring is an `AETHER_COMMUNITY_VIABILITY_FLOOR`
/ `…_TARGET_SIZE` env read in a `resolveDeploymentDefaults` at `Main.java:88`, set in the container
env. (b) **No integration suite** drives FORMING→ACTIVE; the closest is suite 13 worker-join-accounting
(role only). Per-community worker count is driven by how many WORKER nodes are provisioned per source,
NOT `targetSize` (inert until Phase-C growth); `viabilityFloor` gates ACTIVE.

---

## 5. How to pick up (next steps)

> **★ NEXT SESSION'S MAIN TASK: fix S20 (§3).** Self-drain-quorum-loss → full-restart recovery: a
> cluster whose committed membership references auto-heal-replaced (ephemeral provisioned) nodes
> cannot recover when those nodes don't come back. Start from §3's mechanism. Confirmed NOT the SWIM
> fix (clean total-restart re-forms perfectly), so this is its own root-cause→fix→validate cycle:
> harness `restart_all_nodes` vs current membership, and/or the recovery path when committed members
> are permanently gone. Reproduce via suite 02 (or a focused self-drain-quorum-loss + restart) with
> **node logs captured live** (the run's logs are destroyed at teardown — attach `docker logs -f`
> per `aether-b-node` container to files first).

Then, in order:
1. **aether-main: review + merge PR #361** (`fix/336-swim-observed-birth-state`, the foundational SWIM
   fix) — now **rebased onto `release-1.0.0-rc2`** (standalone; slice-3/#359 dropped — stays its own
   PR; verified to compile on rc2 without it). **I never self-merge.**
2. **Wire `CommunitySizing` to a deployment seam** (`Main.java:88`, §4) + add a **#241 community
   integration suite** (provision ≥floor workers to a source → assert `CommunityKey` reaches ACTIVE)
   — the only way to validate #241's loop end-to-end on real infra.
3. **Re-enable** `CommunityFormationProbeTest` / `ScaleUpFiveToSevenProbeTest` as green gates **only**
   once the Forge harness can sustain node-add (or keep them as tracked-red repros of the harness
   limit, §2).

## Env / cost notes
- Remote: `--env remote` = Docker containers on `$TARGET_HOST` (cost-safe, builds this branch). Build
  the jar first: `mvn -pl aether/node -am package -DskipTests` (NEVER `mvn verify` — `HCLOUD_TOKEN` is
  set; failsafe `HetznerCloudIT` creates a paid VM). `--env cloud` runs the *published* image (not this
  branch) and creates Hetzner VMs — avoid for branch validation.
- Cluster B direct deploy (for isolation tests): `docker network create aether-b-network` then
  `docker compose -f ~/docker-compose-b.yml up -d`; mgmt on `5161..5165`; `down -v` to clean.

*Memory updated: `project_pre_ga_no_backward_compat.md`, `feedback_use_jbct_coder_for_coding.md`,
`project_design_stream_now_implements.md`.*
