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
