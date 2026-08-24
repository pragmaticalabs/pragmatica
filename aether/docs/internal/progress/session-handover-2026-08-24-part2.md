# Session handover — 2026-08-24 part2: the deadline budget ships and 02w finally runs in 70 minutes — but production reads the budget as UNBOUNDED, and every in-JVM pin says it shouldn't

> **Stream: `aether-main` (release / integration / cloud stream). Written for the aether-main agent.**
>
> Two streams write handovers here on the shared branch — check the banner before reading one as
> your own state. This stream keeps the UNSUFFIXED name.

**Branch:** `release-1.0.0-rc3` · pushed through `5110500dc` (+ an entity-forward observability fix
in flight at session end — check `git log` before assuming). Candidate tag at `fc7d1b93a`.
Unit state at last full pass: 2,816 tests / 0 failures across core, aether-config, aether-invoke,
aether-stream, durable-entity, node. Remote host torn down clean; run5 evidence preserved in
`aether/tests/integration/failure-logs/02w-entity-crash/` (STREAMED full-run logs for every node,
including the SIGKILLed one — first time ever).

---

## §1 What landed (all pushed)

1. **Deadline budget, full seam** (`016e6aa61`, `e25d6279c`, `4f80360b4` + review fixes inside):
   core `Deadline` (TimeSpan API — owner rejected raw-millis; ScopedValue carrier; TimeSource-
   testable), `timeouts.forwarding.request_budget`/`management_request_budget` (10s/10s, owner-ruled),
   consumed by: forwarder hops (share-per-attempt incl. outer task-group attempts via `budgetShares`),
   wire `remainingMillis` on `HttpForwardRequest` + receiver drop-and-rebind on BOTH pipelines,
   entity forwards (50ms floor, refuse-before-send, outcome-unknown cause text), stream read/publish
   ack waits, `StreamForwardRetry` (captures once, re-binds per attempt, stops when backoff outlives
   budget), `ContextSnapshot` carries the deadline through `ContextPropagation`. Review: 5 MAJOR
   8 MINOR, 12 fixed 1 rebutted; mgmt receiver bind is UNPINNED (no ManagementServer test fixture —
   recorded gap).
2. **02w verdict integrity** (`5ef0f822c`, `16df34daf`): `read_amount` three-way
   (found / ABSENT-with-positive-evidence / UNREACHABLE-never-counted-as-loss), phase budgets
   (`CREATE_BUDGET`/`READBACK_BUDGET` 900s), remote connect cap, failure-log dir cleared per run,
   **capture-before-heal log streamers** (`scripts/log-streamer.sh`, self-healing re-scan; files
   survive auto-heal's `docker rm`). The streamer pkill uses `-[f]` self-exclusion — the bare
   pattern KILLED ITS OWN REMOTE SHELL in run5 (the recorded pgrep-matches-your-own-waiter class,
   second occurrence).
3. **CHANGELOG** carries the full record incl. the run5 verification block (`5110500dc`).

## §2 Run5 (the fourth attempt at the 02w durability verdict) — measured results

- **Wall clock 4,230s (~70 min) vs run4's 5.6h+ killed.** Cluster B formed 14s; failover settled 2s;
  post-crash liveness PASSED. 8p/2f.
- **Durability: verdict UNMEASURABLE, zero evidence of loss.** 14/40 creates acked in the 900s
  budget (~64s each); pre-kill readback 14/14 exact; post-kill 11 checked, 9 correct, 0 lost,
  0 corrupted, 2 UNREACHABLE, budget exhausted. The two unreachable keys hash to `orders[3]`
  (node-5-owned); node-2 promoted cleanly at the correct offset 22s post-kill — **nothing lost
  on that partition**, the holder just never answered within the sweep.
- **The one remaining defect, quantified:** every non-owner leg goes silent for EXACTLY ~30.1s
  (curl `-m 30`; gaps between successful creates are exact multiples of 30.1s). The owner leg
  answers in ~20ms. That single pathology accounts for the convergence FAIL (989s vs 480s — probe
  rounds cost 30–120s/key), the ~64s creates, and the unreachable reads.

## §3 THE OPEN QUESTION — RESOLVED SAME SESSION: there was no ScopedValue mystery, the wire pair was never registered

> **This section is superseded.** The forge repro with the §4 sensor showed `bounded=true` ~10s on
> every forward — the budget was NEVER unbounded anywhere. Run5's "exact multiples of 30.1s" were
> 3 × 10.03s doomed legs between successful creates — gap quantization, not per-leg timing. The
> real cause: `NodeCodecs` never aggregated the generated `EntityforwardCodecsNode` registry, so
> every #596 owner-forward silently vanished at the transport (the #492 "orphaned generated
> registry" class, second occurrence; the encode throw is swallowed — that silence is a recorded
> follow-up). Fixed + typed-refusal fidelity added + forge suite moved to the #596 contract:
> DurableEntityForgeTest 11/11 incl. state-survives-owner-loss, 100/100 forwards complete,
> 0 timeouts. See CHANGELOG for the full record. The in-JVM pins from the original hunt
> (AppHttpServerLocalDeadlineTest etc.) remain valid and keep their value as regression guards.

The 30s silences mean `EntityForwardService.dispatch` armed its full `ENTITY_FORWARD_TIMEOUT`
(30s, hardcoded `AetherNode:757`) — i.e. `Deadline.current()` was UNBOUNDED there. But:

- The investigator's mechanism claim (`transport().async()` = thread hop) is **DISPROVEN**:
  `Result.async()` = `Promise.resolved(...)`, and `PromiseImpl.fold/onResult` run continuations
  **inline** on resolved promises. No hop.
- `EntityOwnerForwardTest` pins entity-API → transport carrying a bound budget (green).
- **`AppHttpServerLocalDeadlineTest` (NEW, green)** pins the full real path in-JVM: real Netty
  server → security validate → mint at `dispatchAuthenticated` → dispatchToRoute → router.handle
  observes a BOUNDED ~10s deadline over a real HTTP request.
- Slice classloader child-first can't split the `Deadline` class: core crosses the boundary as
  Promise/Result constantly, so core is parent-shared; both the binder (AppHttpServer) and reader
  (PartitionFencedDurableEntity) are node-loaded.
- Generated route glue uses only `Result/Option.async()` (resolved, inline). Netty calls the
  handler inline on the event loop. No executor found anywhere on the chain.

So: every link is individually proven, the composed production system still measured unbounded.
Do NOT re-litigate the pinned links. The discrepancy lives in whatever the real assembly adds that
no pin covers (real slice jar + real provisioning + real multi-node). **Next probe is an
Ember/forge multi-node entity test** — in-JVM gate before any cloud/remote spend, per the standing
sequencing rule — now made decisive by the observability fix below.

## §4 The sensor that makes the next run self-diagnosing (landed at session end)

`EntityForwardService` now logs per forward: DEBUG `waits {effectiveTimeout} (budget bounded={})`
at dispatch, and WARN on a fired timeout naming the wait it enforced. Run5's 48 minutes of chronic
forward timeouts produced ZERO log lines — "bounded" vs "unbounded" was undecidable post-hoc.
The next 02w run (or Ember repro) answers §3 by grep alone: `bounded=false` at dispatch = the
binding dies above the entity; `bounded=true` + 10s waits = the budget works and the 30s theory
is wrong somewhere else.

## §5 Traps / calibration from this session

- **Agent final reports do not reach this session by default.** Three agents completed and went
  idle silently; each needed an explicit "send via SendMessage" nudge. Put the delivery
  instruction IN the initial brief.
- The `pkill -f` self-match trap bit AGAIN despite being in memory — grep any remote kill-pattern
  for its own literal before shipping; the `-[f]` bracket idiom is the mechanical guard.
- The log streamers attach twice (daemon re-scan race) — DEDUPE streamed logs before measuring.
- `wait_for` already had its wall-clock bound (#441) — the prior handover's "unbounded per-call"
  attribution was wrong; the unbounded loops were the suite's own `while read` sweeps (now
  budgeted). Similarly the 08-24 morning handover's `InvocationTimeouts ×3 re-drive` claim was
  wrong (those knobs are parsed but unwired) — the re-driver was harness sweeps × forwarder hops
  × the 30s entity constant.
- `probe_partition_spread` needs 12/12 creates in ONE round: at 30–120s/key a round outruns any
  budget — when the forward stall is fixed, convergence should collapse to seconds.

## §6 Next (priority order)

1. **Ember/forge multi-node repro of §3** with the §4 sensor — find where the binding dies in the
   real assembly. Then fix at that mechanism, not at a symptom site.
2. **Re-run 02w** (now cheap: ~70 min) — with the stall fixed expect 40/40 creates in minutes, full
   readback, and the actual durability verdict (also live-validates #634-1 replica fsync-before-ack).
3. **#634-3+4** tri-floor operator surface (management-API quad) — design settled on the ticket.
4. Generation-counter churn (1:4254 in one suite) has NO log line — observability gap flagged by
   the investigator; fold into #634-3's surface or file separately.
5. Spontaneous pre-kill node deaths (run2 node-5, run4 node-3): run5 had none, but streamers now
   guarantee evidence when it recurs.
6. #634-7 remainder, #634-5 (owner rulings), S3 idempotency, #598, #628.
