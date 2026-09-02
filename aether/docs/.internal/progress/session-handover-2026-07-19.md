<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->

# Session Handover — 2026-07-19 (aether-main, day-2 continuation)

**Branch:** `release-1.0.0-rc3` @ `557bf1cb1` (pushed). **Candidate tag:** `557bf1cb1`, Release CI green (3 green candidate cycles today: e8e0c4520 → a1a143e62 → 557bf1cb1). **Working tree:** clean. **Cloud:** PG-only, verified post-smoke reap.

## TL;DR — the silent-drop arc, end to end

One day, one defect class chased through four layers: StreamFanout 5/5 red → **#467** publisher misroute + QUIC self-send silent drop (fixed `46743199f`) → **#478** cursor auto-resume contract (`a1bbb5f78`) → **#457** in-JVM RF=2 owner-kill failover sensor (`3c9378b5c`, cloud phases 1–8 hard green, 65 s, replaces paid cloud for the lossless core) → **#487** QUIC loopback at the source (`6d16238eb`, per-self FIFO on pinned event loop, honest NoPeerState, rate-limited WARN + drop metric) → the WARN immediately exposed **#491** (post-failover missing peer connections + backfill wedge, DIAGNOSED, fix needs owner ruling). Plus: remote-docker gate 15/15, Hetzner provisioning smoke PASS (153 s provision; scoped reap + firewall close verified), #484 ruled+closed, examples batch specced with rulings.

## Issues closed this session
#467 (misroute; leader-resolver audit clean; completes old-#47's unfinished half), #478 (fetchFromCommitted; docs cadence-qualified after review), #457 (failover sensor; phase 9 soft → #491), #487 (loopback; review fixed cert-rotation dangling loop + rate-limiter leak pre-landing; honest correction on the over-attributed convergence claim recorded), #484 (ruled: status-quo + lint — mapper totality via #486; Promise class doc updated by design-stream).

## Issues filed this session
#485 forward-retry parity (streaming-debt) · #486 mapper-safety lint family (design-stream, DONE — corpus 182) · #488 dangling [streams.X] registration + unwired delivery loop (CQRS example = discovery vehicle, owner doctrine) · #489 corpus burn-down (OWNER OVERRIDE → design-stream; TOT half done, ERROR restored; RET-06 143 sites next; aether-stream's 1 TOT-02 site stays aether-main) · #490 replicas-sensor delegate-routing observability gap · #491 (below) · #492 METRICS-lane missing codec for CommunityMetricsSnapshot (44×/run).

## OPEN at handover — #491 fix design (owner decision)

Full diagnosis + probe on the issue. Mechanism (BOTH hypotheses): graceful leader-kill → PASSIVE window → liveness sweep falsely evicts LIVE survivor↔survivor links (14 evictions; ping is leader-only) → strict single-dialer higher-id 60 s grace delays re-form (`ConnectionDirection.java:31`, `RECONCILE_BACKOFF_CAP_MS` `QuicClusterNetwork.java:184`) → catch-up path is connectivity-blind (`PartitionBackfill.java:659-679` → `StreamForwardClient.readRemote` fire-and-forget, ignores `WriteOutcome.NoPeerState`, blind 5 s re-poll) → **150 s probe: replica STILL `SYNCING@-1` after the grace elapsed — product fix REQUIRED, not just transport tuning**; owner side healthy. Fix menu (multi-layer): (a) backfill reacts to NoPeerState with eager-dial + await + session re-establishment; (b) HRW-divergence placement fix (empty node self-elects owner); (c) hardening: sweep gating on consensus-active / leader-independent keepalive / SWIM false-removal suppression (#94 class). StreamOwnerFailoverTest phase 9 hardens when #491 closes.

## Rest of queue
Streaming-debt: #431 crash-durability re-enable, #411 serializer (option 3), #485; #429/#430 earmarked as CQRS-example load profiles. aether-stream TOT-02 site. Examples batch (spec `aether/docs/internal/arch-examples-spec-draft.md`; RULED: examples/ + Apache-2.0; D3 sequencing = after streaming-debt, recommended not ruled; saga/workflow examples example-first with #345 facades §6/§7). #488/#490/#492 unscheduled. Design-stream: mid-#489 RET-06, then #451–#453/#448, #443; will signal before taking #462/autoscaler.

## Operational gotchas (this session)
- **Teammate report drops are CHRONIC** (6+ tonight): idle-ping-without-report → ping for the verdict (worked 5×); when pings ALSO vanish (investigator-491, 3× lost), **file-based handoff works**: have the agent write to scratchpad and Monitor the path. Check the task board + tree/artifacts before re-instructing — work is usually DONE, only delivery failed.
- Same-checkout coder agents: pull-before-work; design-stream pushes frequently → non-fast-forward pushes routine (rebase; beware dirty-tree refusals; `--amend` targets HEAD — regroup with `reset --soft` if the wrong commit got amended).
- MAILBOX.md (repo root, COMMITTED) = inter-stream coordination log, newest-on-top, file claims + notices. The Editor gap-drain transport (`aether/docs/internal/coordination/MAILBOX.md`) remains UNTRACKED — distinct files; a rebase collision there needs backup/restore.
- `/api/streams/replicas` over HTTP is delegate-routed (#490) — for owner-authoritative views in forge tests read `AetherNode.streamReadRouter().replicaSnapshot` in-JVM.
- test-pg env recovery + Hetzner reap selector + pg-firewall discipline: see memory (`project_test_pg_env_recovery`, `feedback_hetzner_standing_grant`).

## ADDENDUM — 2026-07-19 evening (aether-main, #491 F2'+F4 batch saga)

**Branch:** local `3d3e228b8` (F1 landed, CI green); origin moved ahead (`0cc32eaa3`: design-stream #448 phase 1 + #443 phase A + mailbox) — PULL FIRST. **Working tree: the ENTIRE #491 F2'+F4 batch, UNCOMMITTED** (~7 files: PartitionBackfill+Test, AetherNode, QuicClusterNetwork+ReconcilerTest, StreamOwnerFailoverTest hardened, StreamAccess untouched). **Candidate:** `3d3e228b8`, CI green.

### The #491 batch — five evidence-locked mechanism layers (owner-ruled: ALL lands in rc3)
1. **F1** (LANDED `3d3e228b8`): higher-id dial grace scoped to never-connected peers; REMOVED-peer connect gate.
2. **F4** (in tree): PartitionBackfill promotion gated on committed ownership none-or-self (permissive; epoch-aligned) — local-HRW self-election divergence closed. `committedOwnerIsOther` mirrors AetherNode:856.
3. **m2** (in tree): SAFETY not latency — cold-start self-promotion gated when a committed owner exists (stay SYNCING, 5s re-pulls already existed); #445 distrust-empty gate untouched.
4. **m3(i)** (in tree): reconciler skip-branches eagerly create INIT PeerStates (desired ungated / topology membership-gated) — null-vs-EVICTED is the hard-drop-vs-buffer boundary.
5. **m3(ii)a** (in tree): probe-first re-verify for stranded CAUGHT_UP non-owners (lastReverifyMs stamped at dispatch AND promote; owner-ahead ⇒ pull, else pure no-op); write-idle strand self-heals. **FINAL LEG** (in tree, verifying): **unicast dispatchPayload/WithOutcome hard-dropped null-peer sends while BROADCAST buffers** — now: null + authoritative member ⇒ getOrCreatePeer + offline-buffer Queued (drain-on-attach); non-member ⇒ drop+WARN+metric unchanged. This asymmetry was the true root under everything — reconciler-side creation alone lost the send-time race (gate 0/3 proved it; forensics in scratchpad/gate-forensics.md + on #491).

### Corrections trail (all on #491 — do not re-litigate)
Force-dial-dead-code REFUTED (the 150 "NOT dialing" = dead sof-3 correctly gated; 1-arg connect driver retry-forever was the real small bug, fixed in F1). Zombie-scheduler REFUTED (stop() clears peers; no post-kill activity). F4-over-gating REFUTED (survivor self-promoted fine). REMOVED-readmit already exists (:2333/:2409) — never extend F1 to REMOVED. sendOutcome stream-reaction CONSCIOUSLY SKIPPED. "Session recovery" de-scoped (starvation not wedge). Structural alt (receiver-stamped confirmedOffset) PARKED on #491 with evidence bar.

### Acceptance state
Unit: consensus 696/0 + new buffering tests (WriteOutcome import fixed by lead), stream 628/0 (WriteIdleResidualReverify 5 arms), PartitionBackfillTest 44/0. Gate: **0/3 pre-buffering-fix** (empty auto-heal replacement HRW-churned into ownership, catch-up unicast dropped); **post-buffering 3× gate RUNNING at handover** — criterion: 3 consecutive converged. Fan-out 5/5 throughout. On green: review (transport diff!), land as one batch, close #491, harden-comment already cites resolved-by, candidate re-point. Ember auto-heal stressor STAYS (harsher than cloud — it exposed layers 2-5).

### Also this session
- **#489 CLOSED** (design-stream): corpus clean, TOT-01/02+RET-06 back at ERROR; #451 shipped (8 rules); **#493** filed (residual ~160 WARNING); #448 phase 1 + #443 phase A pushed. **BND-01 disposition: FIXED by aether-main** (`3471f172a` AwsLoadBalancerProvider Optional→resolved-unit promises, module 65/0) — BND-01 ERROR restore unblocked.
- **Promise API consult** (owner Q): instance .all() ALREADY forwards to static (verified 14/14 arities); allOrCancel/allOfOrCancel EXIST (:2394/:2832); cancel() = chain-cancellation not operation-abort (honest-guarantees nuance). Ruled: keep all/allOrCancel distinct. OPEN: owner hasn't answered the offered javadoc cross-reference pass (all↔allOrCancel discoverability).
- **Operational:** coder-478 ZOMBIED (repeated silent drops) → replaced mid-batch by coder-491 (worked well; per-leg scratchpad logging is now the standing protocol). Lead directly fixed two compile blockers (PartitionBackfillTest factory call sites +CommittedStreamOwnerSource.none(); WriteOutcome import). Helper agents have no SendMessage — their replies relay through the lead; expect it.

### Next-session queue
1. **#491 gate verdict** → review → land → close → candidate re-point (if gate red: STOP-rule — fresh finalView forensics, no fix-stacking).
2. Streaming-debt tail: #431, #411 (option 3), #485; then #488/#490/#492 scheduling.
3. Examples batch per spec (D1/D2 ruled; D3 = after streaming-debt); #420 stage-2; #386 D1-D5; W7-AWS/W8 last.
4. Design-stream continues #448 phases/#443/#452/#453; will signal on #462/autoscaler.

## ADDENDUM 2 — SESSION STOP POINT (2026-07-19 ~17:10): #491 terminal finding, OWNER DECISION PENDING

**RESUME HERE.** Branch local = `45dddb06c` (handover addendum; origin may be ahead — design-stream active on #448/#443: PULL FIRST). **Working tree: 6 uncommitted files = the complete F2'+F4+m3 batch** (PartitionBackfill.java+Test, StreamOwnerFailoverTest.java [phase 9 HARD], AetherNode.java, QuicClusterNetwork.java+ReconcilerTest.java). NOTHING committed of the batch. Unit state: consensus 699/0, stream 628/0, PartitionBackfillTest 44/0, fan-out 5/5.

**Post-buffering gate run 1 (the terminal evidence, full detail on #491):** drops = ZERO (unicast member-buffering CLOSED the transport-loss class — durable win), but phase 9 timed out: the promoted-owner↔survivor connection NEVER FORMS because **SWIM has live survivors stuck DEAD** (`swimDeadStuck=[sof-1, sof-2]` while countedMembers=5 suppresses the false quorum-loss). Dial layer consumes raw SWIM state → never dials SWIM-dead peers → buffered catch-up never drains → 5 s RPC attempts time out forever. Addressing fine (0 Unknown-NodeId), mesh broadly forms (20 attaches). This is the CONSCIOUSLY-DEFERRED C-layer (#94-class SWIM false-removal under churn) — every layer above is fixed and proven.

**THE PENDING DECISION (owner):** (1) extend batch into SWIM stuck-death recovery now; (2) land the four proven fixes + re-soften phase 9 at a new precisely-scoped SWIM issue; (3) = 2 + membership-pinned test variant proving 3× convergence in batch scope (unpinned stays as SWIM sensor). **Recommendation: 2+3.** On ruling: if 2/3 → review the 6-file diff (transport product code — full reviewer round), land as `fix: ... (#491)` batch, close-or-scope #491, file the SWIM issue with the evidence chain from the #491 comments, candidate re-point, THEN resume rest-of-queue (handover above). Gate-run artifacts: /tmp/gate-run-1.log, forensics scratchpad/gate-forensics.md (session scratchpad, may age out — the ISSUE comments carry everything durable).

**Agents at stop:** coder-478 zombied (abandoned). coder-491 idle-available, has full batch context, per-leg log in its session scratchpad 491-f2-impl.md. Investigators done. Editor MAILBOX (coordination path) untouched all session; root MAILBOX.md current.
