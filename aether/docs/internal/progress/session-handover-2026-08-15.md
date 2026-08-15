# Session handover — 2026-08-15: codec Phase 2, #590 built and then repaired, and what 02y caught

**Branch:** `release-1.0.0-rc3` · **HEAD:** `fe335e6d9` · **PUSHED** · **tree clean** · candidate tag
re-pointed to HEAD

Nine commits across two work items from the owner-set pipeline (codec Phase 2 → #590 → 02y/02w → zone
test). Both items landed; the second one shipped a severe defect that the integration suite caught and
that is now fixed and pushed. **§4 is the part to read if you read nothing else.**

---

## §1 Codec tag space — Phase 2 LANDED

280 framework and Aether protocol types now carry hand-assigned wire tags in one registry,
`org.pragmatica.serialization.SystemTags`, consulted by `SliceCodec.deterministicTag` before it falls
through to the (renamed, unchanged) `hashedTag`. Generated codecs already called `deterministicTag` at
class-init, so pinning is a one-line edit to one file: **no regenerated code, no `@Codec` churn, no
envelope bump** (envelope stays 1000).

**Layout.** `0..20` framework primitives (unchanged) · `21..109` the 89 hot types, ONE wire byte ·
`110..127` reserved so a future hot type can still be promoted · `128..1659` the other 191, two bytes ·
`2112..16383` reserved. Every one of these was previously in the 3-byte user range.

**How the enumeration was obtained — do not redo this by grep.** Resolved from the annotation
processor's own output: the 30 `*Codecs.CODECS` registries referenced by `NodeCodecs` + `WorkerCodecs`,
then each registry's codec-class entries, then the `deterministicTag("…")` literal inside each. Two
things that a naive sweep gets wrong and did:
- Keying registry roots by SIMPLE name silently merges `org.pragmatica.cluster.metrics.MetricsCodecs`
  with `org.pragmatica.aether.worker.metrics.MetricsCodecs`, losing all 10 cluster-metrics types
  (`HealthHintWire` among them). Import-aware resolution took 270 → 280.
- The processor spells nested types `Outer.Inner`, NOT `Outer$Inner`, so keys derived from
  `Class.getName()` never match and every such type silently takes a hashed user tag instead.

**The obligation is enforced, not documented.** `SliceCodec.systemCodec()` refuses to build a system
registry containing a type that fell through to the hash, and names it. That is why the set never has
to be rediscovered: add a framework codec without a pin and the build fails telling you which.
`SystemTags` rejects a duplicate name AND a duplicate tag at class-init.

**Also landed:** the slice-processor now reports a within-slice tag collision at COMPILE time
(`CodecTagSpace`, a deliberate 6-line copy of the hash — consumers put only `slice-processor` on
`annotationProcessorPaths`, so reaching `SliceCodec` would drag serialization-api and Netty onto every
application's processor path; both copies pin the same probe value so drift fails a build).

`[verified: SystemCodecPinningTest builds BOTH production system registries the way AetherNode does;
SystemTagsTest 6/0; CodecTagSpaceTest 3/0; 852→868/0 aether/node, 301/0 slice-processor, 40/0
serialization-api; ./build.sh green, 0 new lint]`

### Three judgement calls, flagged as such
1. **`SystemTags` lives in Apache-2.0 `integrations/serialization/api` and names BSL Aether classes as
   strings.** Forced by the module graph — `integrations/consensus` calls `deterministicTag` and cannot
   depend on `aether/`. A global namespace registry arguably belongs in the module owning the
   namespace, but it is a layering call, not a derivation.
2. **89 hot types, not the ~46 the previous handover estimated.** Value objects (nested per-field
   inside protocol messages), worker protocol and stream protocol were counted hot. 18 one-byte slots
   remain. "Never renumber" means disagreeing later is expensive.
3. **`@Codec(tag=…)` remains dead surface** — declared, never read by the generator. The table makes it
   redundant rather than needed; deleting it is a separate call.

## §2 A latent bug the pinning test found

`WorkerCodecs.workerCodecs()` **threw on every call.** `SwimConfig` carries `TimeSpan` fields so
`SwimCodecs.REQUIRED_TYPES` demands a `TimeSpan` codec; `NodeCodecs` registers one manually and
`WorkerCodecs` never did. It went unnoticed because the registry has **no production caller** — nothing
constructs it yet, so no test and no node ever ran the code. Fixed (`a22706089`).

This matters beyond the fix: the worker-community tier has a lot of built-but-unwired surface. Also
found, all with **zero callers**: `FollowerHeartbeat` + `FollowerHealthTracker` (and
`HeartbeatCodecsNode` registers a codec for a message nothing sends);
`SpokesmanPingLoop.currentReports()`; and **nothing ever writes a `SpokesmanValue` with status
`ASSIGNED`**, so the spokesman role may never be assigned and `SpokesmanPingLoop` may never activate.

## §3 #590 — it was TWO bugs, not one

The ticket was accurate as written (verified at HEAD, 35 commits after it was filed — unlike the
07-30/31 batch). But it recorded only half the problem.

- **Community side (in the ticket).** `writeDissolved()` had exactly two callers, both gated on the
  community shrinking to zero members — a membership-shrink mechanism, not a partition response. SWIM
  is intra-community, so a cut-off community still sees all its own members alive and keeps serving.
- **Core side (NOT in the ticket).** The per-community FSM's "observed live membership" read
  `GovernorAnnouncementValue.memberCount` — a field the community writes about ITSELF. Under partition
  the governor cannot rewrite it, so it does not expire, it FREEZES. The core kept the community
  `ACTIVE` and kept placing work on nodes it could not reach. `GovernorAnnouncementKey` is never
  removed by anything, and the one receipt-based signal the core collected
  (`SpokesmanPingLoop.currentReports()`) had no consumer.

**This is the third instance of the same pattern** (#593's `SYNCING` seed, #508's status field): a
self-reported field read as observed liveness. Worth a sweep for others — the failure mode is always
that everything looks healthy right up until it matters.

**Built as ONE mechanism** off the leader's existing cluster-wide `ClusterSyncPing`/`Pong` exchange — no
new wire type, no new endpoint, no new KV key. Worker fences locally at `timeouts.cluster.core_absence`
(10s) via `DrainProcedure.initiate(CORE_ABSENCE)`, needing no consensus write; the core stops counting a
member silent beyond `community_absence` (20s) and re-places its slices. `core_absence <
community_absence` is refused at config load (in `ConfigValidator`, so it joins every other config error
in one report rather than aborting the parse).

Observability: `coreAbsence` on `GET /api/cluster/membership` — deliberately on that LOCAL endpoint
beside the core tier's own quorum-loss fence, because a node losing the core is exactly the one a
leader-forwarded read cannot reach.

## §4 ⚠️ THE DEFECT I SHIPPED, AND WHY THE TESTS MISSED IT

**#590 first shipped with `CoreAbsenceDetector` wired on EVERY node. That was cluster-killing.**

`ClusterSyncPing` dispatch is **leader-only** (`ClusterSyncState:142` — "a non-leader tick is a no-op")
and `QuicClusterNetwork.broadcastPayload` iterates `peers`, which never includes self. Therefore:

- a node that **wins an election** receives no pings ever again → drains 10s later;
- when a **leader dies**, every survivor receives none until re-election → all counting down.

Both are entirely ordinary events. Fixed in `fe335e6d9` with a **fail-safe gate** sampled at firing
time: only a node positively known NOT to be a core member may fence; an unresolved core view reads as
suppress; an unwired gate leaves the detector inert. Core liveness was always `QuorumLossDetector`'s
job.

**Why the verification did not catch it — the transferable lesson.** The unit tests were correct and
discriminating; both halves were mutation-checked and the mutations behaved exactly as claimed. But
every test fed `recordCorePing()` BY HAND, so the signal's provenance was assumed, never exercised.
Mutation testing proves the tests detect a broken implementation OF THE DESIGN; it cannot detect that
the design rests on a false premise about where the signal comes from. The question never asked was not
"who receives this signal" — that was checked — but **"who never receives it?"** The answer was written
in `AetherNode`'s own javadoc ("the leader never receives its own pings"), read earlier in the same
session while investigating something else.

`[verified: 16/16 CoreAbsenceDetectorTest; mutation-checked — disabling the gate turns exactly the 3
FenceSuppression tests red, other 13 green; ./build.sh green, node 868/0]`

## §5 02y / 02w — results, and one open question

Run: `./run-tests.sh --env remote --suites 02y,02w`, **against the pre-fix build**.

- **02w-entity-crash: 10/10 PASS** — "all 65 ACKED entities survived the crash with their exact
  values". Entities verified on the new tags.
- **02y-stream-crash: 9 pass / 2 fail** — (a) deploy "all instances ACTIVE" timed out at 240s; (b) **34
  of 80 acked events missing** after the crash.

**No `No codec registered` anywhere**, and the stream data path worked throughout (40 publishes acked,
4/4 partitions non-empty, contiguous ordered replay, clean failover in 5s). So the codec change is not
the obvious culprit.

**OPEN: causation for 02y's two failures is unresolved.** The shape fits a node draining mid-flight
(i.e. the §4 defect), but 02w passed 10/10 and the cluster reconverged to WHOLE. `run-tests.sh:81-84`
also documents a **pre-existing** known-failing test in 02y (#593, #594 for the probable cause).
**Re-run 02y against `fe335e6d9`** — if the failures clear, the fence was drawing blood; if they
persist, they are the known issue. Do not assert either way before that run.

## §6 Owner decisions pending

- **Dashboard for #590** — postponed by the owner "until rest lands". The quad needs either a panel or
  a recorded dormant-slot decision on #494; this is the one thing keeping feature-catalog row 590 at
  *Partial*. Recommendation: dormant-slot for the per-node countdown (structurally unfetchable during
  the incident it describes), with the leader-side "which communities the core has stopped counting" as
  the real future panel.
- Nothing has been posted to #494 or #590.

## §7 Doc corrections made

`known-limitations.md` described dissolve-on-core-isolation as awaiting *proof*, implying a built
mechanism waiting on a test run. There was no mechanism — neither side of the detection existed. That
page is the designated single source other docs reference, so the wording had propagated as "wired".
Corrected, with the ordering-under-real-partition claim explicitly tagged `[design intent —
unverified]`: Forge is single-JVM and cannot sever the cluster network.

Also corrected: feature-catalog #66 ("deterministic hash-based tags" — now the split space), a
`ClusterTimeouts` javadoc crediting the spokesman for a signal the leader broadcasts, and a
`CoreAbsenceDetector` javadoc describing an `armed` field that `AtomicHolder` had made redundant.

## §8 Traps hit this session

- 🔥 **`mvn -pl X test` does NOT install.** A node run reported 865/0 while compiled against the
  PREVIOUS `aether-config`/`aether-deployment` jars — it passed only because nothing in the suite
  constructs a full `AetherNode`. The tell was a later compile error reading `symbol: method
  coreAbsence()`, which is a stale-dependency signature, not a code error. `install -DskipITs` the
  upstream modules first, then verify by CONTENT (`javap` the installed class), not by mtime.
- **zsh does not word-split unquoted expansions.** A generated-source directory list built with
  `$(find …)` matched nothing and reported "NO SOURCE" for all 263 codec classes — a plausible-looking
  total failure that was purely a shell artifact. Run harness-shaped shell under `bash -c`.
- **JBCT lint caught two rule violations in new code** — `[JBCT-EX-01]` (throw forbidden; return
  `Result.failure()`) and `[JBCT-RET-01]` (void needs `@Contract`). Both because I wrote to the shape of
  surrounding OLDER code. The `EX-01` fix was a genuine improvement: `ConfigValidator` collects errors,
  so an operator with two config problems now sees both.
- **`.claude/worktrees/` (11 copies) is a `find`/`grep` trap like `.ndx/`** — a bare find returns
  worktree copies of `ConfigValidator.java` etc. first.
- **A monitor filter that matches only success is silence-on-failure.** Verified new tests actually RAN
  by grepping the log for the class name rather than inferring it from an absent failure line.

## §9 Standing hazards (unchanged)

- `HCLOUD_TOKEN` is set. `mvn verify`/`install` reach `HetznerCloudIT` and create a REAL PAID server.
  Safe spellings: `./build.sh`, `mvn -pl <module> test -DskipITs`, `install -DskipITs`.
- The `aether` CLI on PATH is rc2 and aborts the harness at version-parity preflight. Pin `AETHER_BIN`
  to a launcher that execs `aether/cli/target/aether.jar`.
- ✅ **CORRECTED:** `run-tests.sh` teardown is now fully name-scoped — verified at HEAD, no bare reap
  anywhere in `run-tests.sh`/`lib/`/`scripts/`, `PROTECTED_CLUSTERS` guards `test-pg`. The #572 hazard
  is FIXED; earlier notes describing it in the present tense are stale.
- `.ndx/` is 144 GB — exclude from every repo-wide sweep.
- #250 storage GC — DO NOT WIRE naively.

## §10 Next

1. **Re-run 02y** against `fe335e6d9` — closes §5's open question.
2. **#599 zone test + #592.** Write the zone test to FAIL against HEAD FIRST (it would otherwise pass
   for the wrong reason: communities form by SIZE and zone never enters), then land #592 (fix the
   SOURCE — `SwimMember` label propagation, option 2 per owner ruling), then confirm it flips green.
   Then update #367 to cover the zone split alongside the size split. No chaos in v1.
3. **#596** — the owner asked to look at it after the pipeline. Design proposed in the 2026-08-14
   part-2 handover §5, **awaiting sign-off, not approved**.
