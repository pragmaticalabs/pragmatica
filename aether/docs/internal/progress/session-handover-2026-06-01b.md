<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Session Handover — 2026-06-01b — auto-heal provisioning FIXED + Docker-validated; membership/placement split cutover steps 1-2 landed; remaining = membership-convergence cluster (churn + sporadic node death)

## ⚡ START HERE / TL;DR

This session **fixed the auto-heal provisioning failure** that has blocked the destructive suite for many sessions, and Docker-validated it. It also landed steps 1-2 of the membership/placement split. **The root of the auto-heal failure was NOT consensus** — correcting the morning handover (`session-handover-2026-06-01.md` §4b), which concluded the blocker was a Rabia consensus wedge. That conclusion came from a **dual-run-contaminated** investigation; on a clean single-instance run the real root was reconciler-side grace/latch logic.

- **Branch `release-1.0.0-rc1`. HEAD `f0f0caea1`. +36 commits unpushed (DO NOT push — RC1 not green). Working tree clean.**
- **Docker oracle: single-instance only, clean slate** (see `feedback_check_orphan_runs_before_docker` — a contaminated dual run produced a false consensus-wedge root-cause earlier).

## 1. What shipped + Docker-validated this session

**Commits (this session, on top of the morning's `ff2b63dec`):**
- `f0f0caea1` fix(membership): presence-backed membership source (split cutover 1-2, cold-start quorum latch) + auto-heal provisioning via NTT high-water latch and debounce re-eval + reconciler/NTT INFO observability.
- (earlier today, already in tree: `e69f57a4b` two-plane liveness, `7e1b21932` doc cleanup, `ff2b63dec` split design spec + handover.)

**Validated on Docker (suite 02, single-instance, clean slate):**
- ✅ Cold-start formation: 5 nodes in 8s (repeatedly).
- ✅ **Auto-heal S01 block: 5/0** (was 3p/2f every prior run) — real leader-minted replacement provisions, reaches SYNCING, JOINING-window lifecycle works.
- ✅ Baseline restore to 5: **0–18s** (was timing out 600s+1200s).
- ✅ Kill-leader + re-elect: 5/0.

## 2. The auto-heal fix — the diagnosis chain (so it isn't re-litigated)

The label `aether.node-id` is set at **container creation**, before any join/consensus — so "no new label in 90s" meant **no provision was ever dispatched**, purely reconciler-side. Instrumentation (now committed at INFO) peeled it:
1. Cold-start regression from split step-2: `PresenceGenerationSnapshotSource` returned `some({self})` from boot (NTT seeds `{self}`, never empty), so `TopologyObserver` quorum stopped using the legacy `nodeStatesById` bootstrap path → formation deadlock. **Fix: one-way quorum latch** — return `none()` until NTT first reaches quorum, then `some()` (`PresenceGenerationSnapshotSource`).
2. Auto-heal grace was **anchored to the first reconcile pass**, which — because the reconciler is **departure-triggered** — first runs at the kill (count=4), so the 22.5s cold-start grace started at the departure and suppressed the very auto-heal it should allow (`reason=WITHIN_GRACE` every pass).
3. Replaced the timer-grace with a `reachedFullMembership` **fact-latch**, but sourced it from a reconcile-pass observation of `clusterMembershipCount >= configuredCoreCount` — which never happens (no pass at full membership). **Final fix: latch off `NodeTopologyTracker.peakMembershipCount()`** (NTT high-water; NTT observed 5 at formation independent of reconcile timing). Plus a **debounce re-eval tick** (reconciler is event-driven; a suppressed persistent deficit now reschedules a pass so it acts when the gate clears) and a **re-election pre-latch** (term>1 → cluster already formed). Deficit-debounce + drain hard-floor untouched. 75 unit tests pass incl. a trace-faithful test.

**Key code:** `LeaderReconciler.java` (latch off `ntt.peakMembershipCount()`, re-eval tick, INFO `logProvisioningDecision`), `NodeTopologyTracker.java` (`peakMembershipCount` high-water + INFO on membership-change/evict), `PresenceGenerationSnapshotSource.java` (quorum latch), `PresenceMembershipView.java`, `AetherNode.java` (wiring + `leaderTerm::get`).

## 3. Membership/placement split — status (spec: `aether/docs/specs/membership-placement-split-spec.md`)
- **Step 1 DONE:** `PresenceMembershipView` (presence-derived `MembershipView`).
- **Step 2 DONE:** `PresenceGenerationSnapshotSource` injected into `TopologyObserver`/CTM (membership + quorum now read NTT presence, decoupled from consensus commits), with the cold-start quorum latch.
- **Steps 3-6 REMAIN:** (3) re-source `ClusterDeploymentState.activeNodes()` + `BootstrapModule` membership reads to NTT; (4) arm the `LeaderReconciler` grace on leader-gain (the re-election pre-latch partly covers this); (5) slim `ClusterGenerationSnapshot` to placement-only (remove `coreMembers`/`nodesWithoutSlices`, drop `SnapshotMembershipView`, `@Codec` regen, de-wire publisher membership trigger); (6) full-suite Docker. Decision log D1-D4 in the spec.

## 4. REMAINING — membership-convergence cluster (now cleanly isolated, all reachable post-fix)

Suite-02 final: **3 test-files pass / 3 fail.** The 3 failures, deduped:

- **(D) "generation did not quiesce within 90s" ×3 — DOMINANT.** CTM churn between blocks; the cluster eroded to **1 running core container** by the late S19 block. **Hypothesis: driven by the sporadic node death below** (die → re-provision → generation never settles → erosion). **Likely the next root.**
- **Sporadic node death** — a seed exits **137** ~22s after formation, **not** the test's kill and **not** the harness (`kill_node` only kills the named victim; `cleanup_cluster_zombies` allowlists seeds). `OOMKilled=false`, host shows free RAM *after* the kill, node log is clean then abrupt SIGKILL. **cgroup-OOM and harness ruled out; host-level OOM-killer NOT yet confirmed** (dmesg check was inconclusive — likely needs sudo). This is the prime suspect for the churn.
- **(A) `pick_non_leader: only 1/2 candidates`** — ULID auto-heal replacements aren't classified as `pick_non_leader`-eligible (helper queries ON_DUTY core members; replacements not listed). `Kill_2_nodes`.
- **Forwarding error-rate 49.75%** (`Kill_node_during_active_load`, threshold 10%) — the original forwarding-filter validation, now UNBLOCKED (the departure path works) but failing: requests still route to the dying node in the kill window. `NodeTopologyTracker.keepOnlyAccessible` filter ineffective in that window.
- **S19 sub-quorum self-drain** — couldn't be evaluated (cluster already eroded to 1 by that block; `Survivor exit code 2` etc. all empty).

## 5. OPEN CAVEATS (verify before trusting)
- **Does the new re-eval tick contribute to the churn (D)?** It reschedules a reconcile after the debounce on a persistent deficit — verify it isn't over-scheduling/over-provisioning. The handover-05-31b flagged D as pre-existing, but confirm the tick isn't amplifying it. Check `LeaderReconciler.scheduleDebounceReEvalIfNeeded`/`runDebounceReEval` dedup.
- **Host-OOM for the sporadic death is UNCONFIRMED.** Next run: capture the dying node's full `docker logs` + `docker inspect` BEFORE teardown, and `sudo dmesg -T | grep -i "killed process\|oom"` on the host (the kernel OOM-killer would give exit 137 + `OOMKilled=false` + free RAM after, and explain random victims under 10 JVMs/host). If host-OOM, it's infra (JVM `-Xmx` caps / fewer nodes / more RAM), not code.

## 6. Remaining subtasks (carried, not started)
- ◻ **Generalize node-info preparation into provider-owned modules** (bootstrap + auto-heal share one path) — task #6.
- ◻ **Leader seeds `ClusterConfigKey.CURRENT` from config when KV empty** (non-bootstrap path) — task #7.
- ◻ **Update 5 stale SWIM unit tests** to membership-v2 behavior — task #11.
- ◻ **Rework D: document the restart-disabled invariant** across guides/spec/cloud-init/compose/providers; fix `aether/forge/docker-compose.yml:26` `restart: unless-stopped`→`"no"` — task #16.

## 7. Process notes
- The layer-by-layer **instrumentation → observe → fix** loop worked: each single-instance Docker run peeled one layer (formation latch → grace anchor → NTT-peak latch). The INFO traces (`logProvisioningDecision`, NTT `emitIfChanged`/`evict`) are committed and were essential — keep them (the missing observability was itself a defect); tune volume later if needed.
- **Single-instance + clean-slate Docker is mandatory** — the morning's false "consensus wedge" root came from an orphaned concurrent run. Guard: `pgrep -fl run-tests.sh` → kill → `docker rm -f` → confirm `count=1` after launch.
- Suite 02 is the right fast loop for this work (formation + S01 + kill-leader land in the first few minutes; grab leader logs then, abort before the slow late-block timeouts).
- Java → jbct-coder; read-only root-cause → aether-investigator; git → chore-runner; focused `mvn -pl <m> install -DskipTests -am` (NEVER verify/build.sh; HCLOUD_TOKEN-safe for test/install).
