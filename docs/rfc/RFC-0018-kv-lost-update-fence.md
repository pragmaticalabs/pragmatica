# RFC-0018 — Lost-update fence for KV read-modify-write (#570)

**Status:** ACCEPTED 2026-08-08 (owner) — implemented on `release-1.0.0-rc3`.
**Scope:** `integrations/cluster` `KVStore` fence family; first consumer is `ClusterConfigKey.CURRENT`.
**Related:** #570, #581 (RFC-0017 stage 3), #582 (codec tag space), #379 / #345 (the existing fences).

## Decisions (owner, 2026-08-08)

- **O1 — ship ungated, like the two existing fences.** The mixed-version divergence is real but
  bounded (replicas re-converge at the next successor write; notification streams differ during the
  window), and rc-line releases do not support mixed-version co-application anyway — the KV
  serializer format already diverges between rc2 and rc3 (`desiredTopology` strings vs a bare core
  scalar), so old and new appliers never legitimately co-apply a log. The H4 leader fence and the
  epoch fence shipped under exactly this posture, with no gating machinery in `KVStore`. **The GA
  rolling-upgrade contract must version-gate ALL applier-semantics changes together** — this fence
  joins that list; it does not create it. Recorded for `ga-envelope.md`.
- **O2 — opt-in marker interface** (`VersionFenced`), mirroring `EpochBearing`.
- **O3 — split retry policy.** CTM path (auto-heal wants self-recovery): bounded retry recomputing
  from the fresh committed value, 3 attempts, then a typed failure. REST paths (an operator wants to
  be told): no retry — a lost write surfaces as HTTP 409 `VersionConflict`.

## Discovery during implementation — batch merging kills result attribution

§4's option (b) and the "previous-vs-current value" discriminator are BOTH unworkable, for the same
reason: `RabiaEngine.commitChanges` resolves every merged submitter's promise with the **full merged
batch's result list** (`promise.succeed(results)` per correlation id). Two racing writers whose
batches merge each receive both `Option<ClusterConfigValue>` results and cannot attribute either.
Chosen mechanism: **read-after-apply semantic confirm** — the engine runs the local `process` before
resolving, so a local re-read is authoritative for the batch, and the caller checks *the change it
asked for* (`desiredCountFor(source, role) == count`; `tomlContent` equality on apply; `version`
equality on upgrade) rather than version arithmetic, which cannot distinguish my write from a
competitor's at the same version.

---

## 1. The defect

`ClusterTopologyManagerRecord.writeDesiredCount` is an unguarded read-modify-write:

```java
var existing = clusterConfigReader.get();                              // READ
var updated  = existing.unwrap().withDesiredCount(sourceName, …);      // MODIFY  (configVersion + 1)
commandApplier.apply(List.of(new KVCommand.Put<>(CURRENT, updated)));  // WRITE
```

Two writers that both read version 5 both compute version 6. Both `Put`s commit in log order; the
second silently overwrites the first. No error is raised at any layer.

RFC-0017 stage 2 made this materially worse in one specific way. Under the former cluster-wide core
scalar, concurrent scales were necessarily contending for the same number, so "last writer wins" was
merely crude. Now `--source eu --role core` and `--source us --role worker` are **independent,
simultaneously-valid** edits to different entries of `desiredTopology` — and the lost update destroys
one of them outright. Making topology expressible created a class of concurrent write that *should*
succeed and currently does not.

**Real exposure is a scale racing an auto-heal, not two CLI scales.** The REST path already carries
optimistic concurrency (`ScaleRequest.expectedVersion`, checked in `ClusterConfigRoutes`). The CTM
path — auto-heal, reconciler, applier — bypasses it entirely.

---

## 2. Are conditional commands safe under consensus?

**Yes, and this codebase already depends on it in three places.** The applier says so itself
(`KVStore.java:96-97`):

> "Both arms are pure functions of the committed storage content and the incoming value alone, so
> every replica decides identically inside the consensus applier."

`staleLeaderWrite` is described in-source as "compare-and-put". Existing conditional applies:

| fence | condition | site |
|---|---|---|
| H4 leader | `incoming.viewSequence() > stored.viewSequence()` | `staleLeaderWrite` |
| ownership epoch | `incoming.fenceEpoch() >= stored.fenceEpoch()` | `staleEpochWrite` |
| delete witness | witness present, matching kind, current | `staleRemove` |

**The safety invariant, stated exactly:** a conditional command is divergence-free iff its predicate
is a pure function of *(committed state at that log position, command payload)* — and nothing else.
Every replica reaches that position with identical state by induction, so identical inputs yield
identical decisions. This is the canonical conditional primitive in replicated state machines (etcd
`txn`, ZooKeeper versioned `setData`, Consul `cas`).

### 2.1 What would break it

- **Any unreplicated input** to the predicate or the stored value: wall clock, `Math.random`, local
  node identity, `HashMap` iteration order, external I/O.
- **The live trap for this change.** `ClusterConfigValue.withDesiredCount` stamps
  `updatedAt = System.currentTimeMillis()`. That is safe *today* only because the leader computes the
  whole value once and ships it as a literal `Put` payload, so every replica stores identical bytes.
  It becomes genuine divergence the moment the command becomes a **delta** ("increment this count",
  "set this field") applied per-replica, because each replica would stamp its own clock.
  → **Design rule: the fence compares a version and stores a PRE-COMPUTED LITERAL value. Never ship a
  mutation to be evaluated per-replica.**
- **Snapshot restore.** `restoreSnapshot` deliberately bypasses both existing fences
  (`KVStore.java:98-99`) — correct, since a restored snapshot is authoritative committed state, not a
  competing write. A new fence MUST bypass identically, or log replay diverges from live apply.
- **Rolling upgrade / codec decode.** This is the real operational hazard, not SMR theory. A node that
  cannot decode a command or value variant cannot apply it: skipping diverges, throwing costs
  availability. This is what makes new wire types expensive here, and why §3 avoids one.

---

## 3. Proposed design — the successor fence

Add a third arm to the existing `staleWrite` family. For values that opt in, reject a `Put` unless the
incoming version is the **immediate successor** of the committed one:

```
reject iff  stored is present
        AND incoming.fenceVersion() != stored.fenceVersion() + 1
```

Opt-in via a marker interface alongside `EpochBearing`, e.g.:

```java
/// A value whose writes are ordered by a strictly-incrementing version, fenced against lost updates.
public interface VersionFenced {
    long fenceVersion();
}
```

`ClusterConfigValue.fenceVersion()` returns the existing `configVersion`. **No new field.**

### 3.1 Why this is correct

| scenario | stored | incoming | outcome |
|---|---|---|---|
| A and B both read v5 — A commits first | 5 | 6 | accept → 6 |
| …then B's write lands | 6 | 6 | **reject** (6 ≠ 7) — lost update prevented |
| ordinary sequential write | 6 | 7 | accept |
| first write / bootstrap seed | absent | any | accept (no fence) |
| snapshot restore | — | — | bypasses fence, as the existing arms do |

The predicate reads only committed storage and the incoming payload → deterministic → no divergence.

### 3.2 Cost: zero wire change

This is the design's main argument. The condition is a property of the **value**, exactly like the two
existing fences — not of a new command. So:

- no new `KVCommand` variant (no sealed-switch exhaustiveness break on older nodes, no new codec tag,
  no intersection with #582's tag-space work);
- no added field on `Put`, whose wire shape is universal;
- no added field on `ClusterConfigValue` — `configVersion` already exists and is already maintained.

The only change is applier logic plus a marker interface. A node running the old applier simply does
not enforce the fence; it never fails to decode. That degrades safety during a mixed-version window
without breaking liveness or determinism — and, importantly, an old node's *acceptance* of a write a
new node would reject is a divergence risk **only if the two disagree while both applying the same
log**. This needs explicit review (see §6, open question O1) — it is the one place where "no wire
change" is not automatically free.

### 3.3 Precondition — verified

Every current writer of `ClusterConfigKey.CURRENT` is already a strict successor:

| site | version |
|---|---|
| `ClusterTopologyManagerRecord:238` | `withDesiredCount` → `configVersion + 1` |
| `ClusterConfigRoutes:617` (`storeScaledConfig`) | inherits `withDesiredCount` |
| `ClusterConfigRoutes:455` (`storeUpdatedConfig`) | called with `stored.configVersion() + 1` (:426) |
| `ClusterConfigRoutes:654` | `stored.configVersion() + 1` |
| `ClusterConfigRoutes:357` (`storeInitialConfig`) | initial — no stored value, no fence |
| `BootstrapModule:296` | seed — no stored value, no fence |

So the fence would reject nothing that legitimately happens today. **[verified: read of all six write
sites]**

---

## 4. Rejection must be visible to the caller

A fence whose rejection is invisible is **worse than the current race**: it converts a silent lost
update into a silent lost update *reported to the operator as success*. That is the same failure shape
as the lying cleanup stub (#574 arc) and the inert firewall — this session has already paid for that
lesson twice.

Today `handlePut` cannot express rejection:

```java
if (staleWrite(put.key(), put.value())) {
    return Option.option(storage.get(put.key()));   // current value
}
var oldValue = Option.option(storage.put(...));     // previous value
```

Structurally indistinguishable, and a rejected put emits **no** notification.

**Two options.** Recommended is (a):

**(a) Read-after-apply verification at the caller.** After the apply resolves, re-read and confirm
`configVersion == intended`. If not, the CAS lost: retry from the fresh value with a bounded attempt
count, or surface a typed conflict. Needs no change to `KVStore` or the `StateMachine` contract.
Correct because a rejected write leaves committed state unchanged, so the intended version is absent.

**(b) Widen the per-command result.** `StateMachine.process` is already `<R> List<R>` — generic and
unconstrained, cast unchecked inside `KVStore` — so returning a richer sealed outcome needs **no
consensus-contract change**. Blast radius is whoever interprets results: in aether the applier is
typed `Function<List<KVCommand<AetherKey>>, Promise<List<Object>>>`, i.e. already opaque, and no site
was found destructuring it. Cleaner long-term; larger and riskier now.

(a) unblocks #570; (b) is the better eventual shape and can follow.

---

## 5. Rejected alternatives

- **Reuse the existing `EpochBearing` fence.** Does not work, and is the tempting cheap option.
  `incomingEpochIsStale` rejects only a **strictly older** epoch; equal is accepted by design
  (governor reannouncement, same-epoch ownership takeover). Two writers both at v6 → equal → accepted
  → lost update survives. It is a **regression fence, not a lost-update fence**. Verified by reading
  `incomingEpochIsStale`.
- **New `PutIfVersion` command variant.** Correct but pays the full rolling-upgrade and codec-tag cost
  for a condition that the value can carry instead.
- **`expectedVersion` field on `Put`.** Changes the wire shape of the most universal command in the
  system.
- **Local mutex / single-threaded executor on the leader.** Cheapest, and insufficient:
  `clusterConfigReader.get()` reads local state that may lag the commit, so a serialized writer can
  still build on a stale read; and a leader handoff mid-flight loses the guarantee entirely. A
  mitigation, not a fix.

---

## 6. Open questions for the owner

- **O1 — mixed-version enforcement asymmetry (blocking).** During a rolling upgrade, new nodes enforce
  the fence and old nodes do not. Both apply the same committed log, so does an old node accept a
  write a new node rejects, and can that diverge state? My reading is that it CAN — the fence changes
  the applier's effect, and the applier must be identical across replicas. If so, this needs the same
  treatment other applier changes get (version-gated activation), and "zero wire change" does not mean
  "zero rollout care". **This is the question I am least sure of and it should be settled before any
  code.**
- **O2** — should the fence be opt-in per value type (marker interface) or default for anything
  carrying a version? Opt-in is safer; default is harder to forget.
- **O3** — retry policy for (a): bounded attempts then typed conflict, or immediate conflict and let
  the operator re-issue? Auto-heal wants retry; an operator scale probably wants to be told.

---

## 7. What this does NOT fix

- #578 — `ClusterConfigApplier` no-ops 8/10 `DiffAction`s. Unrelated and still open.
- Cross-key transactions. This fences one key per command; there is no multi-key atomic CAS.
- The lost-update class on every OTHER read-modify-write in the codebase, until those value types opt
  in.

---

## 8. Test plan

- Applier unit tests mirroring `KVStoreRemoveFenceTest`: successor accepted; equal rejected; older
  rejected; first-write accepted; snapshot restore bypasses.
- **Determinism test:** apply the same batch to two independently-constructed stores and assert byte
  equality of `makeSnapshot()` — this is what would catch a non-deterministic predicate or a clock
  stamp leaking into a per-replica computation.
- Concurrency test at the CTM level: two interleaved `setDesiredCount` calls on different
  `(source, role)` pairs; assert BOTH survive after retry, which is the behaviour stage 2 created the
  need for.
- Mutation check: revert the fence and confirm the concurrency test goes red. A test that passes
  against the unfenced applier proves nothing.
