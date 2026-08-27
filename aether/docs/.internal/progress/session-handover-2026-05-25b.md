<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Session Handover — 2026-05-25b (scheduler substrate fix LANDED; #230 slot-convergence implemented but flooding on Docker)

**Branch:** `release-1.0.0-rc1`. **origin/rc1 HEAD = `75519d174`** (pushed, good). **Local HEAD = `b613cee53`** — 4 unpushed slot-conv commits on top. **Do NOT push the slot-conv commits — they still flood on Docker (see §4).**

## 0. TL;DR
This session landed two structural fixes to rc1 (pushed, validated) and built a third (#230 slot-convergence) that is implemented + unit-green + locally committed but **NOT landable** — Docker re-validation shows CTM still over-provisions to a runaway flood. An investigator is pinning the residual mechanism.

## 1. LANDED on rc1 (pushed to origin, validated) — DONE
1. **VirtualThreadScheduler substrate fix** `804e2e243` — `SharedScheduler` now delegates to a new `core/.../VirtualThreadScheduler.java` (one platform timer thread + deadline-ordered `DelayQueue` + virtual-thread-per-task dispatch). **Root cause of the under-load detection stall was shared-`ScheduledThreadPoolExecutor` thread starvation** (~40 subsystems shared one fixed 8-thread pool; blocking task bodies starved the SWIM/ClusterSync/aggregator detection ticks). Microbench: detection dispatch-lag **7.3s → 6ms** under starving load. Docker confirmed: kill-under-load detection `No NODE_FAILED within 60s` → `Departure observed under load`. Concurrency model verified (volatile-cancel double-check, non-overlap guard, deadline-only-while-dequeued, runGuarded, unkillable timer loop). `core` VirtualThreadSchedulerTest 4/4.
2. **Leader-gate promotion-only** `37e93b52d` — `MembershipFsm.applyLifecycleCommand` leader-gates ONLY `ForceOnDuty`/`RecordJoining` (the re-projection vectors); `ForceDecommission`/`ForceDrain`/`RequestReJoin` propose unconditionally so churn-window decommissions aren't dropped. Docker kill-node 2/2→5/0, no regression. Ingress test 6/6.
3. **#2 kill-under-load test fix** `0c687e842` (+ baseline bump `62dbfb308`) — test pointed `APP_ENDPOINT` at the deleted LB port 9090; added `retarget_app_endpoint_to_active_slice` (8080). Docker-confirmed: error rate 0.00% (`success=194/0`).
4. **Slot-convergence SPEC** `75519d174` — `aether/docs/specs/slot-based-membership-convergence-spec.md` (Reviewed, implementation-ready; 6 OQs resolved in §9).
- **Broad de-risk pass** (suites 00,01,02,03,12): scheduler swap = **no regression** (00-smoke 2/0; no new timing failures). Every remaining suite red was the #230 over-count (got 9/12/13, compounding cross-suite) — the thing #230 targets.
- **#234** filed: graceful-migration atomic-supersede follow-up (RC1).

## 2. #230 spec — resolved decisions (the implementation contract)
`aether/docs/specs/slot-based-membership-convergence-spec.md`. Invariant: **exactly `clusterSize` durable slots, ≤1 ON_DUTY occupant each; CTM converges occupants to one healthy per slot.** Decisions: D1 slot-based headcount; D2 dead-node fast-path (detected-dead→reducer STOPPED, CTM does NOT graceful-drain failures); D3 failure=remove-then-add (atomic supersede deferred to #234); D4 `occupantEpoch` slot fence. OQ1 integer slot ids `0..S-1`; OQ2 FILLING dedup via `spawnedAtMs`/`deadlineMs`; OQ3 fence at wiring layer (`MembershipFsm.resolveLifecycleWrites`, reducer stays pure); OQ4 wipe-and-reseed on activation, seniority by `observedCoreEpoch` (NOT NodeId/KSUID-sort — test env mixes ordinal+KSUID ids); OQ5 **no envelope bump** (KV atom via `KVStoreSerializer`, backward-compat field add); OQ6 reducer owns dead→STOPPED, CTM only frees slot (no drain ack, no STOPPED write).

## 3. #230 implementation — 4 LOCAL commits (NOT pushed)
- `23d1a0b3c` S1: `ProvisioningSlotValue` +`occupantEpoch`+`supersededNodeId` + `KVStoreSerializer` 3↔5-field backward-compat + codec test. (behavior-neutral)
- `392ba260e` S2: CTM `ClusterTopologyManagerRecord` slot-occupancy convergence — integer durable slots, occupancy HEALTHY/FILLING/DEAD/EMPTY, provision-to-fill-empty + reap-excess, D2 dead-fast-free (no drain), reseed-by-seniority, resize folded into reconcile loop, CTM-provisioned safety filter (option B: never auto-reap MANUAL/UNKNOWN occupants). CTM 61/61.
- `d697acda3` S3: slot-derived `coreCount` (`ClusterTopologyRoutes.slotDerivedCoreCount`, capped at clusterSize) + wiring-layer `occupantEpoch` fence in `MembershipFsm.resolveLifecycleWrites` (`slotBoundEpoch` map; rejects superseded-occupant ON_DUTY promotion). MembershipFsm 81/81.
- `b613cee53` S2-fix: (a) `classifyOccupied` now keys on `lifecycleReader` (DECIDE plane: STOPPED→DEAD/ON_DUTY→HEALTHY/JOINING|DRAINING→FILLING) — dropped the SWIM `occupantHealthy()` gate; (b) activation no longer clobbers bindings (`activate` sets reconciler state first; `seedOrReseedSlots` chains the activation reconcile onto the reseed **commit** so `maintainSlotSetSize` reads a committed map); (c) `summarizeOccupancy` log DEBUG→INFO. CTM 63/63.

Stage 2 forks resolved (all confirmed): 1A keep reconciler state machine + gates; same-tick free-then-fill; resize folded into reconcile loop; activation-only full reseed; occupancy-aware reseed-surplus reap; strict-STOPPED DEAD; option-B safety filter.

## 4. THE BLOCKER — residual flood (current state)
Two Docker suite-02 runs of the slot model:
- **Run 1** (JAR 19:39, S1-S3): flooded to `current=18`, formation=4, "unrecoverable". Diagnosed 2 bugs: **BUG1** `classifyOccupied` keyed HEALTHY on the SWIM `occupantHealthy()` gate (but **structurally INERT** — bound slots are never refilled, `settleConverged` ignores `countHealthy`); **BUG2** activation double-seed clobber (`seedOrReseedSlots` binds 5 → `maintainSlotSetSize` re-reads a STALE pre-commit map → re-seeds 0-4 EMPTY → clobber → EMPTY → flood). BUG2 = the confirmed flood cause.
- Fixed both (`b613cee53`), CTM 63/63 (BUG2 test fails-before/passes-after stash-verified; BUG1 = honest pass-after consistency guard, no fail-before because it's inert). In-process spike passed.
- **Run 2** (JAR 22:08, +fix): clobber gone, but **STILL FLOODS — `current=22` at first auto-heal.** So there is a SECOND, independent flood source. Stopped early (monitor caught it; not 2h).

**Leading hypothesis (investigator confirming): FILLING-marker deadline < real Docker provision→boot→join latency.** `provisionIntoSlot` stamps FILLING (`deadlineMs=now+X`); the Docker container takes longer than X to reach ON_DUTY; `classifyEmptyOrFilling` (`deadlineMs>=now && spawnedAtMs>0 ? FILLING : EMPTY`) reclassifies the slot EMPTY when the deadline expires → `selectEmptySlotsToFill` re-provisions a NEW container → repeat → flood. The synchronous in-process harness cannot reproduce this (provisioning is instant) — same substrate gap throughout.

**CRITICAL LESSON:** the in-process Ember harness is **synchronous**, so it structurally cannot reproduce async/cross-plane bugs (clobber stale-read, FILLING-deadline-vs-boot). Unit tests + spike are insufficient for the slot model's async behavior; **Docker is the only arbiter** → ~20 min/iteration. Candidate to speed up: an async test-store mode, or a minimal targeted Docker scenario (flagged to user, not yet decided).

## 5. NEXT STEPS (ordered)
1. **Read the investigator's residual-flood report** (agent `ae939f154914988b1`, output `/private/tmp/claude-501/.../tasks/ae939f154914988b1.output` — DO NOT shell-read the transcript; it completes on its own). Confirm/refute the FILLING-deadline-vs-boot hypothesis.
2. **Fix the residual flood** per the confirmed mechanism. If deadline-vs-boot: the FILLING deadline must cover the real provision timeout (`autoHealConfig.provisioningTimeout()`), OR — better — track an explicit in-flight-provision set so a slot with an active provision is never reclassified EMPTY regardless of deadline (the deadline then only bounds genuinely-failed provisions). Delegate to jbct-coder agent **`a955e3322705a52cf`** (full slot-model context).
3. **Reset cluster-B** before re-running (it holds ~22 flood containers from run 2; runner `down -v` removes compose nodes but NOT ksuid CTM floods — remove those by explicit name or label `aether.provisioned-by=ctm`; the blanket `--filter name=aether-` wildcard gets denied by the auto-mode classifier).
4. **Re-run Docker suite 02** with a monitor on `current=[0-9]|got '[0-9]|quiesce|unrecoverable|PASSED:|FAILED:`. Decisive: `Initial 5 ON_DUTY: got '5'` + `quiesced` + auto-heal `current=5` (was 18/22).
5. **Only then push** the 4 slot-conv commits to rc1. Then: broader suite re-validation, CHANGELOG #230 entry, close #230.

## 6. Env / artifacts
- Remote: `TARGET_HOST=192.168.0.71`, `AETHER_SSH_USER=aether`, `AETHER_SSH_KEY` (export in next session). cluster-B: compose nodes torn down (`docker compose down -v`), so the live flood is STOPPED (no leader → CTM idle), but **~39 orphaned CTM-flood containers remain** (idle, not growing). The auto-mode classifier blocks agent bulk-rm — clear manually before next run: `docker rm -f $(docker ps -aq --filter label=aether.provisioned-by=ctm)` on the host (or `ssh $AETHER_SSH_USER@$TARGET_HOST`). `HCLOUD_TOKEN` set → NEVER `mvn verify`/reactor-wide test; node JAR rebuild = `mvn -pl aether/node install -DskipTests -am`; run = `cd aether/tests/integration && ./run-tests.sh --env remote --suites 02 --skip-build`.
- Logs: run 1 `/tmp/docker-s02-slotconv.log`; run 2 `/tmp/docker-s02-slotfix.log`; broad pass `/tmp/docker-broadpass.log`.
- Live agents (resumable via SendMessage): jbct-coder `a955e3322705a52cf` (slot impl), investigator `ae939f154914988b1` (residual flood).
- Spec: `aether/docs/specs/slot-based-membership-convergence-spec.md`. Tickets: #230 (this), #234 (graceful-migration follow-up).
</content>
