# Session Handover — 2026-07-06

**Branch:** `release-1.0.0-rc2` · **HEAD:** `958f1ea7e` · **State:** clean, pushed, in sync with origin.

## TL;DR

**rc2 build work is COMPLETE.** Every milestone code item is shipped, reconciled, and green. What remains for the release: **(1) the Phase-1 Hetzner cloud gate** (validation campaign, user-gated — paid infra), **(2) the release cut** (`/pre-release-check` → `/release`, which auto-closes #403). Externally pending: PR #359 (author rebase after my request-changes), the Editor's `aether-gap` feed (loop is GO, no issues filed yet).

## Shipped across this session arc (2026-07-03 → 07-06)

| Item | Outcome | Commits / refs |
|---|---|---|
| **#337/#338/#333** migration+stream cluster | Closed as already-implemented (reconciled + 766 tests proven) | follow-ups #408/#409 (rc3) |
| **#383** KV/DHT persistence | Closed (doc disposition); coordinated-restore design constraint deposited on #349 | `df54c715a` |
| **#392/#396** | Closed (jar-inspection + compile-test proof) | — |
| **PR #391** (#336 SWIM reachability) | Reviewed + merged | `ee5b36b50` |
| **PR #410** (#262 two-knob replication) | Reviewed + merged; §10.5 honesty-scoped; follow-up #411 filed; stream freeze lifted | `f6ffa3c96`, `3fd33cc8f` |
| **PR #359** (#241 slice 3) | Request-changes: payload sound, base 97-commits stale w/ superseded #336 — rebase mandated | awaiting author |
| **#277** runtime observability | Closed — 6 increments: AtomicStrategy cells at both dispatch seams, scope hierarchy, **fleet layer absorbed** (one engine, two policies; interceptor + depth registry deleted, consumers preserved), triad, bench, dead codegen Aspect seam deleted | `c1224d720`…`fd1649ad0`; PR #356 closed superseded; #413 (rc3) |
| **#265** placement-aware hydration | Closed — 7 increments: hydration triad, placement gate (O(streams×partitions×nodes) dead), budget reject/defer, caps, reshuffle lifecycle (catch-up-gated release @ effective RF, flap debounce, system-first pacing), batched ownership writes (one consensus Batch/reconcile) | `09cadbdcf`…`d3914eee7` |
| **#414** FORM_URLENCODED silent JSON-parse | Found during #339 verification, fixed (compile-time reject) | `113cfd930` |
| **#339** media types | Closed as shipped (spec = as-landed codification) | — |
| **#198** API versioning | Closed — spec complete (issue body), implementation fully shipped incl. `[app-http] api_version_header` | — |
| **#403** | Release-anchor disposition: publish set verified complete, nothing actionable pre-cut | closes at release |
| **#345 1d-iii** | Verified DONE (`9842e1ea2`, pre-#410); fence LIVE; guarantees hedge retired | `baaffd280` |
| **#345 1e** | **True linearizable reads**: no-op consensus round (`KVCommand.Noop` barrier) + post-round fence decision point + shared owner-serve pipeline (fixed forward-guard asymmetry defect); entity `ReadConsistency` surface (#382 resolved); typed path threaded | `c4041aed4`, `7922b3f90`, `586ef5f4d` |
| **#345 1f** | Ownership triad completed: `highWater` + `fenced` deposed-window indicator per domain | `958f1ea7e` |
| Batch gate | Full `./build.sh` green mid-arc; formatter chore | `f26adc69c` |

## Owner decisions on record (do not relitigate)

1. **Envelope no-bump** for factory-shape changes under rebuild-together (memory: `feedback_277_envelope_no_bump`); envelope stays **1007**.
2. **#277 mechanism**: dispatch-seam strategy cells (user's option B + `AtomicStrategy`), NOT codegen weave; then **variant C** — fleet layer absorbed into baseline posture ("off means baseline, not blind").
3. **System-stream budget exemption** (diverges from hydration spec §6 as written; spec re-tagged).
4. **Ownership writes batched per reconcile pass** (spec was silent; recorded on #265).
5. **LINEARIZABLE = owner-route + no-op consensus round** (spec §8.1's mechanism, implemented; `lease` parse-rejected until its chaos gate).

## ⭐ Next session — first steps

1. **Phase-1 cloud gate** (user green-light required — paid Hetzner): assert via `GET /api/cluster/ownership` (deposed owner `fenced=true` on handover; steady state `highWater==epoch`); composed kill→reseat→epoch-advance→fence proof; #265 reshuffle-under-load + 100-stream memory + owner-kill history preservation; linearizable-read-under-load; forge fan-out on real infra (locally env-blocked: `/data` read-only appeared mid-session — likely colima mount; inc-2 ran it green before). Cloud discipline: `--skip-teardown`, cluster-scoped reap, preserve test-PG (memory: `project_cloud_acceptance_reaper_discipline`).
2. **Release cut** when gate is green: `/pre-release-check` → `/release`; #403 closes with it. Suggested checklist addition: run the external ticketing-posterchild 23-slice blueprint against the built archive (#392's relocated clause).
3. **PR #359**: re-review on author push (my request-changes checklist is on the PR).
4. **Mailbox watch**: re-arm the mtime Monitor on `aether/docs/internal/coordination/MAILBOX.md` (untracked transport, git-excluded; protocol + merge grant in memory: `project_gap_drain_loop_mailbox`). Triage `aether-gap` issues as they arrive (queue = rc2 ∪ label, risk-first).

## Working method (kept paying off — keep doing it)

- **Verify-before-building**: 9+ tickets this arc were already-shipped-but-stale-open (#337/#338/#333/#383/#392/#396/#339/#198/half-of-1e/half-of-1f). Read-only scoping before any build, always.
- **Increment ladders** (#277×6, #265×7, 1e×3): one coder run per increment, lead reviews + commits, every increment individually green; batch gate every ~8 merges.
- **One code track at a time; worktrees are a trap** (stale origin/main base); read-only scouts parallel freely.
- **Consistency-lens at every guarantee**: produced the §10.5 scoping, the G-6 restatement, the LINEARIZABLE mechanism decision, and the honest-degrade audit on 1e-b.

## Memory files written this arc

`project_migration_stream_cluster_closed`, `project_gap_drain_loop_mailbox`, `project_277_observability_arc_closed`, `project_265_hydration_arc_closed`, `feedback_277_envelope_no_bump`, `feedback_worktree_baseref_stale_on_release_branch` (earlier).
