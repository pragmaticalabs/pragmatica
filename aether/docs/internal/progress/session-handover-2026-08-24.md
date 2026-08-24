# Session handover — 2026-08-23/24: the durable-entity structural batch, and the suite's wall-clock disease traced to the layers ABOVE the entity

> **Stream: `aether-main` (release / integration / cloud stream). Written for the aether-main agent.**
>
> Two streams write handovers here on the shared branch — check the banner before reading one as your
> own state. This stream keeps the UNSUFFIXED name.

**Branch:** `release-1.0.0-rc3` · **HEAD:** `b7aa5a081` (UNPUSHED, 1 ahead) · `./build.sh` green ·
durable-entity 151/0, node 881/0, aether-stream 680/0.

---

## §1 What landed (all unit+mutation verified; commits `7b1bf51f6`..`b7aa5a081`)

1. **#631 CLOSED** — genesis livelock; fix live-verified 3× (`02y` 1p/0f 94s, `03-scaling` 3p/0f 248s,
   plus `02w` logs showing the genesis promote firing fail-safe). The issue's filed mechanism was wrong;
   corrected on the record. Full detail in the issue and CHANGELOG.
2. **#596 write half COMPLETE** — `create`/`delete` now forward (were update-only; #596 was filed on
   creates). 6 mutations/6 kills. Two tests found passing-for-the-wrong-reason (bare `isFailure()`), fixed.
3. **Durable-entity structural batch (S1–S6)** — from a full spec-vs-implementation review:
   - **S1**: `EntityFold` froze at rebuild (replica staleness unbounded; a PROMOTED ex-replica mutated the
     frozen view = lost updates). Now catch-up-to-head on every access. 4 pins, 3 mutations killed.
   - **S6**: forward `Sender` now returns `WriteOutcome`; refused sends fail typed in ms, not 30s.
   - **S4**: `ensureLog` refuses a shape mismatch (was silently accepting changed `partition_count`).
   - **S2**: `ReplicationBarrierUnmet` no longer advises "retrying is safe" (false for update).
   - **S5**: spec's BOUNDED_STALE + RF text reconciled to the post-I3 world.
   - **S3 OPEN**: spec's `(key,n)` idempotency counter unimplemented; caller-retry dedup needs an
     API-level token — owner decision, recorded in CHANGELOG.
4. **Harness: `remote_exec` no longer slurps stdin** (`ssh -n`). A `while read` loop calling it ran ONCE
   — 02w's "1/2 lost" durability verdict was measured over 2 of 40 keys. Proven 1/10→10/10. The same
   trap was fixed at ONE call site on 08-14 and bit again 9 days later: fix shared mechanisms AT the
   mechanism.

## §1a Second batch, same night (commits `890b47b2a`..): retry classification + #634 items 1/2/6

- **`Cause.isTerminal()` + `Causes.terminal` + `Retry` stops on terminal** (core); the QUIC removed-peer
  verdict is classified. The 4,160-retry storm class is dead at the mechanism.
- **#634-1**: replicated records fsync-before-ack — chained WAL writes (file ORDER is load-bearing;
  unchained async appends raced it), `syncReplicated` barrier, ack WITHHELD on failed fsync.
- **#634-2**: unwritable WAL dir refuses boot via `Main`'s verify→abortBoot chain (JBCT lint rejected the
  first throw-based version and thereby improved it); direct constructions (Forge/tests) keep the degrade
  deliberately. Opt-in: `aether.allowNonDurableStreams` / `AETHER_ALLOW_NON_DURABLE_STREAMS`.
- **#634-6**: AHSE catalog row → Partial with delta; MetadataStore CHANGELOG claim corrected; stale
  `EvictionListener.NOOP` notes fixed.
- **Two proposal items DISSOLVED by evidence — do not re-implement:** liveness-filtered HTTP candidates
  (already wired: `membershipFsm.reachableMembers` at AetherNode:2328, pinned by
  HttpForwarderAccessibilityTest) and genesis pacing (run4 logs: all 8 partitions promoted inside a
  30-second window; the minutes-apart lines were post-churn RE-promotions on the deliberate re-verify
  cadence).
- **Still open from this arc:** deadline propagation — REDESIGNED by a scoping read before implementation:
  HttpForwarder is ALREADY bounded (~20.6s worst: ≤4 hops × 5s appTimeout + retryDelay re-queries), so a
  budget inside it adds little. The measured 30s+ burns are the STACK: `InvocationTimeouts` (15s/20s, ×3
  retries) re-drives the forwarder's whole hunt per retry ≈ 60s+ against a 30s client. Stage 1 therefore
  belongs at the INVOKE→FORWARD seam — the invoke layer mints the budget, the forwarder consumes
  `remaining` (per-hop = min(appTimeout, remaining/attemptsLeft)) — and stage 2 puts remaining-millis on
  the wire (`HttpForwardRequest`) so a receiver drops work the sender already abandoned. Read the invoke
  layer's forward call sites FIRST; this changes request-level semantics and needs owner eyes on the
  budget defaults;
  #634-4 (retention invariant check at the reclaim site — read RetentionEnforcer's apply-site first);
  #634-3 (WAL under storage config/budget/metrics — carries the management-API quad);
  #634-5 (double-gated: DD-8-1 AND the BSL→Apache license boundary — needs owner approval);
  #634-7 remainder (fsync-failure injection, crash-mid-compaction).

## §2 The 02w durability verdict is STILL UNOBTAINED — and why

Three runs, none delivered it:
- run2 (pre-fix): verdict invalid (harness measured 2 of 40).
- run3 (pre-fix, fixed harness): killed after 3h22m — creates took 8977s, readback ground to silence.
- run4 (S1+S6 build, content-verified `caughtUp`+`failFastOnRefusedSend` in the deployed jar): creates
  40/40 in 3887s, then **readback ran 20295s (5.6h) without completing 40 local reads**; killed.

One ambiguous line before the final silence: `pre-kill readback mismatch for ENTDUR-00032-Z: expected
227, got ''` — but `read_amount` returns `''` for BOTH "no node has it" and "every attempt timed out",
so on a cluster where reads burn 30s/hop this is NOT evidence of loss. The harness read helper has the
same absent-vs-unreachable compression the entity layer had.

## §3 Where the wall-clock actually goes (measured, run4 logs in scratchpad `run4-nodelogs/`)

**The entity layer is fast: create p50=12ms p90=16ms at the slice (n=55).** The suite cost lives above it:

| Layer | Evidence | Cost |
|---|---|---|
| Genesis pacing (stream) | convergence 1641s (budget 480s, FAILED); partitions self-promote minutes apart | ~27 min/run |
| App-HTTP invoke routing (`HttpForwarder`) | 19 forwards timed out at 5s/hop, serial hunt, all in the 6 min after node-3 died; stale instance registry | dominates creates + readback |
| Consensus-lane retry storm | **25,691** "backpressured or inactive" + **4,160** "peer is REMOVED (terminal)" retries in 65 min — retrying a condition its own message calls TERMINAL | 7.6 warns/sec, whole window |
| Spontaneous node death | node-5 died pre-kill in run2, **node-3 died pre-kill in run4** (17:35, SUSPECT→evict→auto-heal); dead container removed WITH its logs | trigger for the churn |
| Harness | `wait_for` still unbounded per-call (readback 20295s vs any budget); `read_amount` '' ambiguity; `-m 30` with no connect cap on `--env remote` | multiplies everything |

**Framing for the tickets: client-visible operations with no deadline budget shared across layers** —
each layer's timeout multiplies the one above (5s hops × 30s curls × 2-pass rotation × 480s waits).

## §4 Traps / corrections this session

- The 02w step names LIE: "Ownership_converged" is 12 creates succeeding; "1/2 lost" was a truncated
  population. Diagnose from node logs, never from step names.
- Auto-heal DESTROYS the dead node's logs (container rm'd). The failure-log capture fires only on suite
  FAILURE and doesn't clear stale files from previous runs (run2's node-5.log sat beside run3's capture).
- My own wrong turns, kept for calibration: predicted create collapse to minutes (wrong — the cost was
  in HTTP routing, not entity forwards); read convergence 1641s as a pass (it FAILED at budget 480s).

## §5 Next (priority order)

1. **The consensus-lane retry-to-REMOVED-peer storm** — file + fix; the message itself proves the defect.
2. **App-HTTP invoke routing under stale instance views** — needs its selection made observable (debug→
   operator surface), then hedged/deadline-budgeted forwards. This gates ANY timely 02w run.
3. **Genesis pacing** — fresh-stream partitions should promote in one redrive pass, not minutes apart.
4. **Why do cluster-B nodes keep dying pre-kill?** (node-5 run2, node-3 run4) — capture-before-heal for
   the dying node's logs is part of the fix.
5. **Harness**: `wait_for` per-call bound (owed since #441); `read_amount` absent-vs-unreachable split;
   connect-timeout for `--env remote`; failure-log dir cleared per run.
6. Then the 02w durability verdict on the S1 build — the run that finally answers it.
7. S3 idempotency (owner ruling), #598 datasource pick, #628.
