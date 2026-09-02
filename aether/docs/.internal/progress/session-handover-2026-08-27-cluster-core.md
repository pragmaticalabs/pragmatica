# Session handover — 2026-08-27 — STREAM: cluster-core

**Banner: this is the cluster-core stream's handover.** Other streams (operator-surface/C, docs/E) keep
their own. Read §7 first if you are picking up work; read §2 if you are about to trust a green build.

## §1 What this stream shipped today

- **#660** — Rabia sync-adoption off-by-one fixed, gated end-to-end (self counted exactly once, self as
  FLOOR not candidate, intersection preserved, thresholds never re-derived from live counts). Five
  mutations red. Superseded part of the D9 boot detector; recorded in the spec with an operator
  recovery action rather than silently.
- **#642 / #509** — closed on live probe evidence.
- **#557 / #558 / #590** — closed with proofs, not arguments. #590's community fence measured at
  **9673–9704ms against a 10000ms window, margin 10296–10327ms, 31ms spread over six runs**.
- **#556** — `forge.sh`, the local forge gate, with the `HCLOUD_TOKEN` strip as a mechanism.
- **#232** — membership chaos cycle built and measured (decommission 5648–8147ms, heal 21267–23754ms,
  leader recovery 22279–24765ms).
- **#591** — re-scoped honestly; instrument (`coordination_slope.py`) built and live-validated. Blocked
  on credentials only.
- **#571** — stream C's `HealthSignal`/`HealthSignalSink` deletion package applied in full: 39 files,
  415 insertions against 742 deletions, one atomic commit.
- **#519** — the dead-config-accessor gate re-homed into `aether/dead-surface-gate` so its corpus
  precondition is Maven-enforced. Recovered a branch that had been red for hours.
- **#351 / #345 I4** — durable entity timers landed end to end, including the 18 deliberately-parked
  Stage A files. Measured across restart and owner handover; see the ticket.

**Five wrong ticket premises were caught before they cost implementation work** (#591, #232, #557,
#590, and #644's site count). That is the single most load-bearing habit in this stream: validate the
premise before building what the ticket asks for. Today's run rate was roughly three-in-four tickets
carrying a materially wrong premise.

## §2 Trap catalog — read this before trusting any local green

Most are now in `CLAUDE.md`; these are the ones with the reasoning attached.

1. **Stale-artifact family, both directions.** The familiar form: a `-pl` build resolves siblings from
   `~/.m2`, so a failure names a module you never touched. The **inverted** form bit twice today: a
   test passes locally BECAUSE of leftover `target/` output and fails on CI's clean build. A gate that
   only passes on a dirty tree is broken. **Timestamps prove nothing** — with several working trees a
   wrong artifact can be NEWER than your correct source. Compare artifact CONTENT against source.
2. **A gate that aborts mid-reactor has NOT checked the modules after it.** Maven's `-rf :module`
   resume hint invites exactly the wrong recovery. Re-run the WHOLE gate. This bit three times, the
   last time on the author of the convention entry, and the aborted run had left unchecked precisely
   the module the change had touched.
3. **Compile green is not gate green.** A fully green 143-module `clean install` sat on two files that
   failed `jbct:check`.
4. **The instrument-illusion family — four instances today, and the reason to distrust a green test:**
   - a probe whose control swallowed its own failed trigger, so a rejected request read as a
     successful one;
   - a fence probe polling a surface the mechanism DELETES on firing — success looked exactly like
     silence, and the poll interval was the difference between a result and a false negative;
   - `isArmed()` read as a precondition when it means only "a ping was once accepted" — asserting a
     proxy that sounds like the real input nearly shipped "the fence is broken";
   - a quiet period implemented as a bare `LockSupport.parkNanos`, which `Promise.await()`'s residual
     permit can collapse to zero. **Measured: 5 residual-permit hits per 20,000 awaits; 0ms versus
     503ms.** Three exactly-once gates were silently degenerate until fixed.
   The generalisation: **validate the instrument against its own failure mode before trusting its
   output.** A test that cannot fail is worse than no test, because it is counted.
5. **Vacuous-assertion shapes**, all found in review here: a list asserted empty that was never wired
   to the object under test; a fake that echoes back what production sent, so an identity assertion
   holds even when identity was lost; an assertion comparing a constant with itself.
6. **A change falsifies its neighbouring prose faster than prose rots on its own.** The I4 batch
   created or exposed **fourteen-plus** stale docstrings, clustering around exactly the decision the
   batch was about — including the batch documenting its own central design decision BOTH ways.
7. **Wire tags are strings; a retired tag stays pinned.** Deleting a pin frees the number for silent
   reuse and a wire disagreement. Retire in place with a marker.
8. **Generated per-package codec aggregates** vanish when the last `@Codec` source in a package is
   deleted — and stale `target/` keeps the incremental build green on a tree CI fails.

## §3 Queue for the successor, in order

**1. #644 — assembly-vs-start task arming. START HERE.** The full partition, the two real hazards, and
a proposed fix shape are posted **on the ticket** — work from there, not from the ticket body, whose
site count is stale (62 repo-wide, not ~13). Headlines:
- `entityOwnershipReconciler::tick` can issue **KV REMOVEs into consensus from a never-started node**:
  the removal half has no leader gate, and it is inert today only by coincidence of construction
  order. This may deserve its own ticket ahead of the arming work.
- `retentionInvariantWatch::tick` raises **operator-visible alerts** from a non-participating node.
- Three assembly-armed sites (`SliceInvoker`, `AdaptiveSampler`, `RabiaEngine`) sit OUTSIDE
  `periodicTasks` and are never reached by `cancelArmedWork` — a guard failure leaves them armed
  forever.
- Two UNKNOWNs are recorded as unknown with what would settle each. Do not guess them.
- The `presenceMemberSupplier` seam extraction rides this ticket — you will be inside that assembly
  method anyway.

**2. #694 — `EmberComputeProvider.toInstanceInfo` stamps no instance tags**, so in-JVM worker reconcile
reads `actual=0` forever. A latent test-trap: the symptom looks like a CTM product bug, so the next
investigation starts in the wrong module. Needs the same guard-test + mutation discipline as the
#590 role-label fix, because it changes what `listInstances` returns for every Ember consumer. Design
note on the ticket: tags must be built at provision time and stored per node, since `toInstanceInfo`
is also reached from `listInstances`/`instanceStatus`, which hold no request.

**3. #692 — stale-surface sweep**, good filler. Includes 24 comment references to `HealthReconciler`,
a type that does not exist anywhere in the repo.

**Credential-gated, unchanged.** All three need `TARGET_HOST` / `AETHER_SSH_KEY` / `AETHER_SSH_USER`,
which sit with the owner:
- **#591** is **one command ready to run** — `coordination_slope.py` at 4→8→12 workers with
  `--node-ids`, already live-validated. Run it at the next natural boundary when the trio lands.
- **#367** inherits the community-tier validation: gap (1) is CLOSED in-JVM by #590, so it carries two
  gaps, not three. Its scenario must **assert the advertised role BEFORE asserting the fence**, or a
  mislabelled node passes it vacuously.
- **#628** needs a live gate-fire on cluster B (deliberately fail a restore), or an owner decision to
  accept mechanism-armed evidence.

## §4 Open tickets this stream filed today

#667, #673, #674, #678 (closed), #682, #689, #690, #691, #692, #694, #696, #700, #701.

Two worth knowing about because they are live product defects rather than debt:
- **#696** — Jackson's `OptionDeserializer` skips contextualization for `Option<T>`, so values arrive
  as raw `Object`. `RefundRequest.partialAmount()` in the ecommerce example throws
  `ClassCastException` at first use. **Practical rule until fixed: an optional non-String field on a
  request record must be a boxed type.** `Option<T>` looks idiomatic and is the one that breaks.
- **#700** — checkpoint writes are not monotonic across nodes; a lower honest claim can overwrite a
  higher one whose log has already been reclaimed. Needs a conditional substrate write.

## §5 Standing constraints

- `mvn verify` with `HCLOUD_TOKEN` set provisions a real paid Hetzner server. `forge.sh` strips it as a
  mechanism; keep it that way.
- `-Djbct.skip=true` is permitted ONLY for a mutation probe (a deliberately-invalid artifact that lint
  would reject before the test runs) and for bootstrapping `jbct/` itself. **No build that verifies
  delivered code may skip it.**
- The candidate tag advances only to **CI-verified-green** commits, newest-green, self-serve by any
  stream. Never past an unverified commit.
- Single-line conventional commits. No bodies, no trailers.
- `aether/tests/integration/failure-logs/` is deliberately untracked. Leave it.

## §6 Review method worth reusing

The whole-diff review that caught the I4 batch's real defects used **six focused reviewers with
docstring-truth as a NAMED focus area**. That one assignment surfaced fourteen-plus false claims that a
general reviewer would have filed as nits — including the batch documenting its own central design
decision both correctly and incorrectly in different files. Use this shape for any large batch.

## §7 The closing observation

The verification culture only scales if the level below will tell the level above it is wrong, **with
evidence**. Three real instances today:

- a reviewer contradicted the reviewer's own earlier recommendation on #678, with code, and was right
  — my "explicitly NOT option 2" advice rested on a wrong reading of a compound predicate;
- a coding agent **traced my instruction instead of complying with it**, found my claim about
  `settleAppendedFire` was false, had a second agent confirm, and kept the docstring I had told it to
  change;
- that same agent caught and reversed **its own** overclaim mid-task, having written a docstring
  asserting replication that the test could not actually distinguish from forwarding.

Each correction was real, and each happened below the level where I could have caught it myself. That
is the property to preserve: not deference, and not contrarianism, but evidence beating rank in both
directions. Two of today's most valuable findings were corrections to my own work, and one was a
retraction of an evidence claim I had already reported upward.
