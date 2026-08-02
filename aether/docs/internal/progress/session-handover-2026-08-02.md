<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->

# Session Handover — 2026-08-02 (aether-main, continues the 2026-08-01 arc)

**Branch:** `release-1.0.0-rc3`. **HEAD:** `1e3fc291c`. Working tree clean. **All PRs merged — 0 open.**

> ⚠️ **`v1.0.0-rc3-candidate` is still STALE at `27cf20ed1`** — now 16 commits behind. Deliberately not
> moved: the tag move publishes `ghcr.io/pragmaticalabs/aether-node:1.0.0-rc3-candidate`, which the cloud
> harness pins, and #564 is unresolved. See §6.

## TL;DR

1. **#557/#558 and #559 are fixed and LIVE-PATH VERIFIED** on a 5-node remote-host cluster — not just unit
   tested. Cold-start formation from a fully dead cluster in 12s; minority self-fence firing correctly
   with writes stopped; a replica at the owner's tail reaching CAUGHT_UP with lossless failover.
2. **#557's root cause was not what the previous handover said.** It is the config boot seed at wiring
   time, not a restored snapshot — and that mis-attribution had hidden three further symptoms of the same
   root, including one that made the previously-landed fix dead code in production.
3. **Remote-host gate improved 11→12 passing, 4→3 failing.** `13-edge-cases` went from red to green.
4. **#561, #562, #553 merged.** Nothing left open.
5. **Three issues filed** (#564, #565, #566), and **two suspected defects turned out not to be defects** —
   one was my own measurement error, one a deliberate mechanism I nearly "fixed" into a regression.

## 1. What landed

| Commit | Content |
|---|---|
| `e72b92b8a` | #557 + #558 — boot-time quorum counts observed reachability, not configured membership |
| `d3b9bcd63` | #559 — backfill distinguishes an empty owner from self being at the owner's tail |
| `311a1b0d7` | #561 (merged) — interceptor config sanitize + de-collide |
| `1e3fc291c` | #562 (merged) — ARCH-04 pinning test, closes #548 as not-a-bug |
| — | #553 (merged) — violation density per KLOC, with the #533 infeasibility note added as a merge condition |

## 2. #557 — the root cause, and why the previous handover was wrong

The 2026-08-01 handover §3 attributed the `BOOTING → NORMAL` flip to "a restored snapshot". **There is no
snapshot restore.** `AetherNode.java:2566` seeds the membership FSM from `config.topology().coreNodes()` at
*wiring* time, and `MembershipFsm.seedMember` promotes each id by dispatching `UpHysteresisMet` **directly**,
bypassing the healthy-streak hysteresis. Every configured core is a strict `MEMBER` before a packet moves.

That single fact produced **four** symptoms, only one of which had been identified:

1. `PresenceGenerationSnapshotSource` latches its one-way quorum gate on the first call → `TopologyObserver`
   takes the `MembershipView` branch → quorum declared with zero connections → `RabiaEngine` broadcasts
   `SyncRequest` into an empty network. *(measured: `BOOTING -> NORMAL` and `Quorum established` in the same
   millisecond, 160 ms before the first QUIC Hello)*
2. **The BOOTING connectivity fallback landed in `dc24377a7` was unreachable in production.** It runs only
   while the membership view is absent, which the seed made false before `TopologyObserver` had started. Its
   tests pass because they configure *partial* topology. It was dead code on any cluster configured with at
   least a quorum of cores.
3. `/api/status` (`cluster.quorate`), `/api/health` and `/health/ready` all derive from
   `StatusRoutes.quorumStatus`, whose **both** paths were seed-derived — so readiness reported quorum held
   from configuration alone. The previous changelog entry claimed `/health/ready` was unaffected; that was
   false and has been corrected in place.
4. `QuorumLossDetector`'s arm-after-first-quorum latch — documented as "has this cluster ever been quorate",
   existing so a node booting into a forming cluster never self-fences — **armed on the configured set during
   construction**, spending its cold-start guard before formation began.

**The fix:** `MembershipFsm.coreObservedMembers(self)` and `strictCoreObservedMemberCount(self)` narrow the
counting projections to members with **latched** first-hand reachability evidence (completed QUIC handshake
or SWIM ALIVE), plus self. The latch is one-way *on purpose* — this gates formation, not liveness, so a
transient SUSPECT or link flap must not drop a member from the quorum numerator. Placement, heal-deficit and
role-assignment consumers keep reading `coreCountedMembers`; only the quorum numerator moved.

**Why it does not deadlock formation** (the failure mode that killed the previously-rejected fix, 0/5
activating): with the view correctly absent at boot, the connectivity fallback carries cold start, and the
same handshakes that satisfy it latch the evidence. Fresh formation cannot produce *authoritative state*
— which is what the rejected rule required — but it does produce *reachability*.

## 3. #559 — the issue's stated fix was unusable

#559 proposed discriminating on `response.toOffset() == fromOffset - 1`. **That is an identity.**
`ForwardCatchupTransport.toResponse` stamps `toOffset = fromOffset - 1` for *every* empty response, so it
carries no information about the owner's true tail. The two cases are byte-identical on the wire.

**The fix:** probe the owner on the empty-response path; treat self as caught up iff the owner reports a real
watermark (`>= 0`) that self is not behind. `ownerWatermark >= 0` preserves #445 (an empty failover owner is
never a true tail). Gated on `selfConfirmed >= 0`, so the #445 path keeps its exact previous behaviour and
costs no extra probe.

**Deliberately scoped to the owner-pull path.** The cold-start contest in `decidePromotion` keeps its
lowest-NodeId tie-break, because there the HRW-ranked node is a *candidate*, not an authority. My first
implementation keyed on "an owner exists among the probed peers" and would have promoted the loser of a
legitimate cold-start tie-break — caught by an existing test, not by review.

**One invariant changed:** `backfill_selfIsNonOwner_noSource_staysSyncingUntilBound` previously asserted the
owner is never probed on that path. That invariant is incompatible with any fix for #559 (the owner's
watermark is the only discriminator). All of its behavioural assertions are unchanged, and it still fails
when the #445 guard is removed — verified by mutation.

## 4. Verification — what is now genuinely `[verified]`

Full remote-host suite, 5 nodes in containers on `$TARGET_HOST`: **12 passed / 3 failed / 0 unrecoverable**
(baseline 2026-07-31: 11 / 4). `13-edge-cases` went red → green.

Live-path evidence, multi-node with failure injection:

| Claim | Evidence |
|---|---|
| Cold-start formation is not gated behind consensus | S20 killed all 5 nodes; recovery to 5 in **12s**, leader in **1s**, fresh generation `1:4`, blueprint re-pushed, 3 instances ACTIVE — total 30s |
| Minority self-fence works under the new numerator | 3 of 5 killed; both survivors exited in **20s** (budget 45s) with **exit code 2** (`Runtime.halt(2)`), and **no KV writes after the drain trigger** on either |
| Quorum survives real node loss | `Cluster has quorum after leader kill (4 nodes)`; leader re-elected in 6s / 19s end-to-end |
| A replica at the owner's tail reaches CAUGHT_UP | `A CAUGHT_UP replica other than owner exists (promotable)` — the exact assertion that failed in baseline |
| Owner failover is lossless | New owner in **1s**, **all 20 pre-kill markers** served, RF restored, replacement replica CAUGHT_UP |
| Data plane holds through disruption | Scale-down under load: 589 requests / **0 failures**. Kill-under-load: 199 / **0 failures** |

Unit evidence: 10 tests on the FSM projections, 3 on the backfill path, **each design decision
mutation-checked red** (vacuous reachability filter → boot-seed regression fails; non-latching flag → exactly
the two flap tests fail; `#445` guard removed → two tests fail; tail check disabled → exactly the two new
acceptance tests fail). 1588 + 647 module tests green, counts verified from surefire XML rather than agent
summaries — an agent misreported one tally during this session.

## 5. Issues filed

- **#564 (rc4)** — post-failover offset discontinuity: all 25 events present, 5 not at their expected
  offsets. **Includes a caveat that the assertion silently skips unmatched offsets** and has a documented
  history of parse bugs, so confirm the measurement before chasing the mechanism. Probably *exposed* by
  fixing #559 (baseline aborted earlier), not caused by it — unconfirmed.
- **#565 (rc4)** — `SELF_DRAIN_INITIATED` never reaches `/api/events`; reproduced on both survivors. The
  fence works and tells nobody. Consensus publish cannot be relied on at the moment quorum is lost, so a
  node-local durable record is the strongest option.
- **#566 (rc4)** — datasource migration ownership is keyed on a directory-derived name that **both over- and
  under-approximates** the real invariant. See §7.

## 6. THE lesson from this arc

**Two of the six defects I reported turned out not to be defects**, and both corrections came from reading
the code *around* the thing rather than the thing itself:

- The `PASSED: 0 / FAILED: 0` suite was **my own measurement error** — those suites report `SKIPPED: 1`, and
  my monitor's grep matched `PASSED:`/`FAILED:` but not `SKIPPED:`. A filter that drops a field manufactures
  defects as readily as it hides them.
- The `common.sh:816` predicate error is a **deliberate mechanism** (#441 item 3a), documented in the comment
  directly above the line. The shell error is how a *failed read* is distinguished from a *false predicate*.
  My proposed fix would have collapsed rc 2 into rc 1, making transport failures silent — undoing a fix made
  because that exact collapse caused a forensic failure (a healthy 5-node cluster measured as 0 under API
  contention).

Combined with #557's mis-attributed root cause, #559's unusable stated fix, and the previous arc's four
wrong tickets: **the running count is now roughly 7 of 7 issue framings corrected by reading code.** Treat
every ticket — including handover text and including your own prior findings — as a hypothesis.

Corollary that cost real time this session: I proposed a fix for `06-deployment` before reading the log, and
the previous handover recorded that failure as "fixture missing from the artifact store". It was not. The
artifacts uploaded fine; the *publish* was rejected 409 and the later deploy's 500 masked it.

## 7. #566 — the datasource identity decision (owner-agreed 2026-08-02)

`BlueprintArtifactParser.addMigrationEntry:174-182` derives the datasource name from the JAR path, with
`schema/` → the hardcoded literal `"database"`. `aether_schema_history` is a fixed, unqualified table — one
per **physical** database. So #550's invariant is genuinely *one migrating blueprint per physical database*.

The derived key misses that invariant in both directions:
- **Over-rejects** — two blueprints using the documented default layout both claim `database` and collide
  even when pointing at different physical databases. `url-shortener` (the getting-started example) is one of
  the two that collide today.
- **Under-rejects** — `[database.a]` and `[database.b]` resolving to the *same* physical database are treated
  as distinct owners and **both permitted to migrate it** into one shared history table. A safety hole.

**Rejected:** namespacing the derived name per blueprint. It fixes the over-rejection, makes the
under-rejection worse, and needs a three-way coordinated change (derivation + `[database.x]` section +
`@PgSql(config=...)`), coupling slice code to blueprint identity.

**Agreed:** record the owning blueprint in `aether_schema_history_meta` in the physical database and check it
when the migration runner connects. Identity becomes the physical database by construction — no derivation,
no data path to build, non-breaking, survives config drift. Keep the name-based check as a cheap pre-flight.

**Contrast with #545** (the stream analogue): there the fix *is* to add the owner to the key, because two
artifacts sharing a consumer group is *undesigned*, not *unsafe*. Datasources need a **truer** key (the
physical resource), not a **finer** one. The consistent principle across both: *identity must name the thing
the invariant is about*.

## 8. Next session

**Decisions parked (owner):**

1. **#564 first, before the tag move.** It is the only finding that could conceivably implicate #559. Start
   by confirming whether the 5 events are genuinely at wrong offsets or merely unmatched by the assertion
   regex — that determines whether there is a bug at all.
2. **Move the candidate tag?** 16 commits behind. Nothing else blocks it once #564 is understood.
3. **Hetzner** — sequence is tag → Release CI publishes images + dist assets → container sweep → JVM sweep
   (needs `jar_url` wired; `aether-cloud.toml` only has container config today).
4. **#509 / `03-scaling`** — NOT triaged this session. 6→5 convergence misses the 180s budget, matches
   baseline exactly, **zero data-plane impact** (589 requests / 0 failures, marker SHA-256 intact), and
   self-resolves on the next scale request. It is a convergence-latency defect, not a wedge.

**Ready to implement, no decision needed:** #517, #519, #524, #543, #545, #547 (all rc4).

**Verification debt:** attribution across the whole gate run is **by inspection, not baseline comparison** —
there is no pre-#557 run of this suite on this host. Also: one `02-chaos` test ran against a dirty 6-node
topology left by `03-scaling`'s stall (Cluster B is destructive and sequential), which explains its two
failures; `restore_cluster_baseline` cleaned it for every subsequent test.

## 9. Gotchas confirmed this arc

- **The stale rc2 CLI at `~/.aether/bin/aether` still shadows on PATH.** The #440 version-parity preflight
  catches it and aborts before bootstrap. Fix: `AETHER_BIN` pointing at a wrapper over
  `aether/cli/target/aether.jar`. A working wrapper pattern is in the session scratchpad.
- **`HCLOUD_TOKEN` is set in this environment.** Every ad-hoc maven invocation must be prefixed
  `env -u HCLOUD_TOKEN` — the hazard is the test/IT phase running with it set, not `verify` specifically.
- **build-runner correctly refused to edit source** when asked to apply a mutation, citing its contract. That
  refusal is the same discipline that prevents a stray `mvn verify`. Do not push narrow agents past their
  contracts; apply mutations yourself.
- **A monitor filter matching a suite name matches every line of that suite** and will be auto-stopped for
  output volume. Filter on terminal signals (`FAIL`, tallies, gate end), not on suite names.
- **Host resource checks: read container stats, not `free -g`.** On a ZFS host, `free` reports ARC cache as
  "used" — I misdiagnosed memory pressure from `26/31 GB used` when actual container usage was ~6.6 GiB
  across 11 containers on an idle host (load 0.10).
- **One transient ~900–1100s convergence episode** occurred mid-run on an idle host and self-recovered.
  Unexplained; not reproduced. Noted in case it recurs.
