# Artifact durability fork analysis — complete-the-DHT (A) vs durable-tier (B)

**Status:** analysis / recommendation · **Author:** background analysis agent
**Date:** 2026-07-18 · **Judged commit:** `2c4f4d330` (branch `release-1.0.0-rc3`)
**Scope:** decide C3 and the artifact-storage end-state for the #420 DHT-churn-loss class.
Read-only forensic pass; every claim carries a `file:line`. Working-tree edits and
`.claude/worktrees/**` were ignored — this judges committed HEAD only.

---

## 1. The question

#420 proved artifacts can be **permanently lost** on topology churn. Two mitigations on the
existing in-memory replicated DHT are already scoped: **C1 (#427)** departing-node chunk push
and **C2 (#428)** resolve-time alternate-target fallback + read-repair. The remaining fork
decides C3 and the terminal design:

- **Arm A — complete the DHT machinery:** add join backfill (C3) + finish C1/C2 + FULL-mode
  coverage, keep artifacts on the replicated in-memory DHT.
- **Arm B — re-home artifact storage onto a durable tier** (AHSE: `integrations/storage/` +
  `aether/aether-storage/`), retain the DHT as cache/routing only.

**Recommendation (section 7): a staged hybrid whose end-state is arm B, sequenced so rc3 ships
on the C1/C2 mitigations rather than on either large build. Do not build arm A's join
backfill.** The decisive reason: arm A, even fully completed, leaves artifacts on
`MemoryStorageEngine` and therefore *still loses everything on a full-cluster restart* — it
closes one of two loss classes. Arm B closes both, reuses a disk substrate already proven in
production for streams, and aligns with the stated direction of epic #349.

---

## 2. Current artifact data path (evidence)

**Write.** `ArtifactStore` splits content into 64 KB chunks, content-addresses each as a
SHA-256 `BlockId` (`BlockId.java:10-12,20-24`), and fans them out (bounded concurrency 8) to
the artifact block tier, then writes one metadata key
`artifacts/{g}/{a}/{v}/meta` carrying size / chunkCount / md5 / sha1 / blockIds via the raw
DHT client. Block tier = `StorageFactory.defaultArtifactStorage` =
`[MemoryTier(256MB), DhtStorageTier(dhtClient,"artifact-blocks")]`; block keys are
`artifact-blocks/{sha256hex}` (`DhtStorageTier.java:37-38,68-74`).

**Config.** The artifact DHT client is built from `config.artifactRepo()` =
**`DHTConfig.DEFAULT` = RF 3 / W 2 / R 2** (`DHTConfig.java:105`; `AetherNode.java:1064-1066,1098`).
Not FULL. Anti-entropy + rebalancer run on the same config, so blocks self-heal *while ≥1
replica survives*.

**Read.** `dht.get(metaKey)` → parse `blockIds` → `storage.get` each block → reassemble →
SHA-1 verify. The read path probes the R-set only; there is **no beyond-replica-set fallback
today** (that is C2/#428, not yet built).

**Durability floor.** `MemoryStorageEngine` is the **only** `StorageEngine` implementation
(`MemoryStorageEngine.java:33-36`, *"Data is not persisted across restarts"*) — an in-memory
`ConcurrentHashMap`. A full-cluster restart loses every DHT-resident artifact. (#383, closed as
documentation; folded into epic #349.)

**Immutability (load-bearing for the recommendation).** Artifact *blocks* are content-addressed
and therefore **immutable write-once** — a block key always maps to the same bytes. The only
mutable key is `.../meta`, which `MavenProtocolHandler` may overwrite on re-deploy / SNAPSHOT
(`MavenProtocolHandler.java:172-184,374`). Immutable content-addressed blobs are the *easiest*
data to make durable: no LWW, no epoch fence, no read-repair, no reconciliation.

**Blast radius — Maven fallback discounts almost nothing.** The shipped default repository list
is `["builtin"]` (= the DHT ArtifactStore); there is **no remote/central repository configured
by default** (`aether-default.toml:13`, `BuiltinRepository.java:26-29`). Even if an operator
adds `remote:central`, only *third-party released library deps that already live on Central* are
re-fetchable — never first-party payloads: locally-published / dev blueprints, forge-pushed
jars, user-deployed slice jars, test artifacts. Auto-heal provisioning fetches through the same
`["builtin"]` chain (`SliceStore.loadSlice` → `DependencyResolver` → `BuiltinRepository` →
`ArtifactStore.resolveWithMetadata` → `dht.get`), so a dropped artifact **wedges auto-heal
redeploy** for that blueprint version. #420's severity stands essentially undiscounted for a
default deployment.

---

## 3. Loss classes (what each arm actually fixes)

| Loss class | Trigger | Fixed by |
|---|---|---|
| **L1 graceful scale-down** | drained node was a key's only acked holder; survivors never held it | **C1 (#427) — landed** |
| **L2 stranded / crash-departure** | copies survive on non-primary nodes outside the R-set, or crash leaves remnants | C2 (#428) — *not yet built* |
| **L3 join divergence** | scale-up adds ring responsibility without moving data; later prune drops the un-migrated key | C3 join backfill (arm A) **or** durable tier (arm B) |
| **L4 full-cluster restart** | in-memory engine loses everything | **Only** a persistent engine — i.e. arm B (or arm A + a persistent `StorageEngine`, which is really arm B) |

**Arm A completes L1–L3 but cannot touch L4** without adding exactly the persistent engine that
*is* arm B. That is the crux of the whole decision.

---

## 4. C1 / C2 / C3 status at HEAD

**C1 (#427) — LANDED and wired end-to-end** (the brief called it "in flight"; it is further
along than that):

- `DHTRebalancer.pushOnDeparture` — the departing node enumerates every locally-held chunk from
  the storage engine, computes the delta target set `newSet \ existing` per key (storm-guarded
  against the reverted `d3e54717e` class), sends ack-gated pushes, waits on a bounded 10s budget,
  and reports the unacknowledged at-risk sample to a `DeparturePushObserver`
  (`DHTRebalancer.java:105-253`).
- Wired: `AetherNode.java:1852` builds the `departurePush` supplier →
  `DrainProcedure` two-condition exit gate whose quiesced fork **waits** for the push
  (`DrainProcedure.java:63-95,147-208`). Observer → `ClusterEventAggregator.onDeparturePushIncomplete`
  → `DeparturePushIncomplete` cluster event on `/api/events` (`AetherNode.java:1981-1983`,
  `ClusterEventAggregator.java:577-578`). Ring pruned at the DEPARTING edge ahead of the SWIM
  DEAD edge via `DHTTopologyListener.onNodeDeparting` (`AetherNode.java:2700`), with a symmetric
  `onNodeRecovered` re-add on drain-refute (`:2708`).
- Tests: `DHTChurnSurvivalTest` (unit — `lostWithoutPush`, `survivesViaPush`, `churn_5to7to5…`);
  `ArtifactChurnSurvival5to7to5ProbeTest` (Forge `@Tag("Heavy")` regression sensor asserting
  survival). FULL-mode `pushOnDeparture` is a deliberate no-op (every node already holds all).

**C2 (#428) — NOT built.** The resolve path has bounded retry but no beyond-R-set probe or
read-repair. Open, rc3.

**C3 join backfill — NOT built (the gap this fork decides).** `DHTTopologyListener.onNodeJoined`
only calls `node.ring().addNode(...)` — **no data migration** (`DHTTopologyListener.java:63-67`).
Scale-up silently diverges physical placement from ring responsibility (L3).

---

## 5. AHSE reuse inventory (what arm B rides vs builds)

Verified across `integrations/storage/` and `aether/aether-storage/`:

**Proven / tested (REAL-TESTED):** `DefaultContentStore`, `MemoryTier`, `LocalDiskTier`,
`RemoteTier` (real SigV4 S3 client, MinIO Testcontainers integration test, **not** `@Disabled`),
`SnapshotManager`, `Promotion`/`Demotion`/`GC` — all with green unit/integration suites.

**Proven in production TODAY — but only for streams:** the disk-backed stream tier is live and
unconditional: `StorageFactory.defaultStreamStorage` builds `[memory, disk, dht]` with
`LocalDiskTier(4GB)`, wired at `AetherNode.java:1079-1082`. Its crash-durability, however, comes
from `PartitionWal` (fsync `force(false)` before publish-ack; temp + `force(true)` + atomic
rename on recovery), **not** from `LocalDiskTier` — which does a plain
`Files.write(CREATE,TRUNCATE)` with **no fsync and no atomic rename** (`FileOps.writeBytes`).

**Two real gaps before artifacts can re-home (REAL-UNWIRED / NOOP / STUB):**

1. **Metadata persistence.** `MetadataStore` has exactly one impl, `InMemoryMetadataStore` —
   snapshot-only, no WAL. This is AHSE's Achilles heel for the general stream/tier use. *But for
   artifacts specifically it is much smaller:* artifact metadata is itself just the immutable
   `.../meta` key, already versioned via `putVersioned`; it does not need the general metadata
   store.
2. **Artifact-side wiring + fsync.** `defaultArtifactStorage` is memory+DHT with **no disk tier**
   by default (disk only if an operator adds an `"artifacts"` `StorageConfig`). And `LocalDiskTier`
   needs fsync + atomic-rename (or a `PartitionWal`-equivalent) to be genuinely crash-safe.

**Deliberately-open seams confirm the intended direction:** `StorageBackedPersistence` exists but
is **unwired** (`resolvePersistence` uses git-backed or in-memory only, `AetherNode.java:555-561`);
`DelegatedStorageAdapter.noOp()` is wired for demotion/GC (`AetherNode.java:2111`). Epic **#349
explicitly names a "persistent DHT engine (option c)" as the strategic fix that makes the DHT,
artifacts, and fenced entity-KV restart-durable** — arm B *is* a facet of #349, not a detour
around it.

**Reuse verdict: ~70% of a durable artifact tier is already built and tested; ~30% is
greenfield (artifact-side wiring + fsync/atomic-rename, and a small immutable metadata-persist
path).** The substrate is proven; durability-critical fsync + metadata remain to be engineered,
not merely re-pointed.

---

## 6. Arm-by-arm evaluation

### (1) Production-readiness under churn + full-restart
- **A:** closes L1–L3; **leaves L4 open** — a cold full-cluster restart still returns 404 for
  every first-party artifact. Not production-durable for the deploy substrate.
- **B:** closes L1–L4 for artifacts in one move; DHT front-cache misses read through to the
  durable tier. Production-durable.

### (2) Implementation cost + risk inside rc3
- **A:** join backfill + anti-entropy completion is **distributed-systems lifecycle code on the
  subsystem that has surprised this project the most** — the memory palace records the
  reconciler-under-load defect class, zero-leader edges, SWIM-latency NODE_FAILED, and a
  *reverted rebalance storm* (`DHTTopologyListener.java:132-143` documents `d3e54717e`).
  Correctness of key movement under concurrent load is precisely the hardest class here.
- **B:** wiring an existing, unit-tested engine + adding fsync/atomic-rename (small, and the
  stream WAL is a working reference) + an immutable metadata-persist path. Risk is **local and
  observable** (a tier that either has the bytes on disk or does not), not emergent-under-load.
  Its cost is that it changes the artifact data path mid-rc3 — mitigated by keeping the DHT in
  front (write-through) so the change is additive, not a cutover.

### (3) Blast radius on C1/C2 already landing
- **A:** C1/C2 are core machinery — fully retained, and A stacks C3 on top.
- **B:** C1/C2 are **retained but demoted from correctness to cache-warmth** — see section 8.
  B does not obsolete the code; it removes their load-bearing status, which is the safer posture.

### (4) Restart durability
- Only **B** (a persistent engine) addresses L4. A cannot, by construction.

### (5) Alignment with #349 / #383
- **B is the #349 "option c" direction** made concrete for artifacts. A is orthogonal to #349 and
  leaves #349/#383's restart-durability goal entirely unmet for artifacts.

### (6) Operational surface
- **A:** operators must understand ring-migration semantics, rebalance-storm risk under load, the
  DEPARTING-edge prune, and that **artifacts do not survive a full-cluster restart** (a permanent
  caveat they must design runbooks around — never take the whole cluster down cold).
- **B:** operators gain a disk-footprint + (optional) S3 story and GC/retention knobs; the
  headline caveat *disappears* — artifacts survive restart. Net simpler mental model for the
  durability guarantee, at the cost of a storage-capacity story.

---

## 7. Recommendation — staged hybrid, arm B end-state

Ship rc3 on mitigations; make the durable tier the terminal design; skip arm A's join backfill.

- **Stage 0 — DONE. Keep C1 (#427).** Closes the *observed* L1 loss; already wired + tested.
- **Stage 1 — rc3, small, fork-independent. Build C2 (#428)** resolve-time beyond-R-set probe +
  read-repair, and **harden the harness** (gate the marker seed on `await_generation_quiesced`).
  Cheap insurance for L2 that lands regardless of the fork.
- **Stage 2 — rc3 end-state (the fork decision): arm B.** Re-home artifact **blocks** onto a
  disk-backed, fsync'd, content-addressed durable tier — reuse `LocalDiskTier` + add
  fsync/atomic-rename (or a `PartitionWal`-equivalent), persist the immutable `.../meta` key
  write-once, and **retain the DHT as the write-through front cache/routing layer**. This closes
  **L1–L4** for artifacts, rides the stream disk substrate already proven in production, and
  realizes #349's stated direction. Because artifact blocks are immutable content-addressed
  blobs, the durable write is the simplest possible (write-once, fsync, done) — none of the LWW /
  epoch-fence / reconciliation machinery that makes DHT durability hard applies.
- **Do NOT build arm A's join backfill (C3).** A durable backstop makes join-migration
  unnecessary for artifacts — a joining node reads through to the durable tier and lazily warms
  its cache — so C3 *eliminates the L3 class* under B instead of adding lifecycle code to an
  in-memory store that still loses everything on restart. Building both is wasted risk.

**Net:** rc3's production-readiness rests on C1 (landed) + C2 (small) as the churn safety net,
with the durable tier as the correctness backstop that also erases the restart caveat. No large,
emergent-risk distributed-lifecycle build enters rc3.

---

## 8. C1 / C2 disposition per arm

| | Arm A | Arm B (recommended) |
|---|---|---|
| **C1 (#427)** | core L1 fix, retained, load-bearing | retained; **demoted** to cache-warmth (durable tier is the backstop) — keeps the front cache warm during scale-down so reads don't stampede disk |
| **C2 (#428)** | core L2 fix, retained, load-bearing | retained; demoted to cache-hit optimization for stranded copies |
| **C3 backfill** | **build it** (join-migration lifecycle code) | **skip it** — durable read-through eliminates L3 |

Under B, neither C1 nor C2 is obsoleted, but both stop being *correctness*-critical. That is the
intended risk reduction: the mitigations become performance, not the last line against data loss.

---

## 9. Work-breakdown sketch (sized)

**Arm A (not recommended):**
- C3 join backfill: on `onNodeJoined`, migrate keys whose responsibility moved to the joiner,
  ack-gated, storm-guarded — **L (distributed lifecycle, high emergent risk under load)**.
- FULL-mode rebalancer/backfill coverage — **M**.
- Finish C2 read-repair — **S**.
- *Still leaves L4 unsolved.* To fix L4 you must add a persistent `StorageEngine` — i.e. do arm B
  anyway — **L**.

**Arm B (recommended):**
- Add fsync + temp/atomic-rename to `LocalDiskTier` (or wrap artifact writes in a
  `PartitionWal`-equivalent) — **S–M** (stream WAL is a working reference).
- Wire `defaultArtifactStorage` → `[memory, disk, dht]` write-through; DHT becomes front cache —
  **S**.
- Persist the immutable `.../meta` key durably (write-once; blocks are already content-addressed)
  — **S**.
- Durable-read-through on cache miss + lazy cache warm on join — **M**.
- Stage 1: C2 (#428) + harness quiesce-gate — **S**.
- Integration test: seed → full-cluster cold restart → artifact still resolves; churn survival
  rides the existing Forge probe — **M**.

---

## 10. Open questions for owner

1. **SNAPSHOT / metadata mutability.** Blocks are immutable, but the `.../meta` key can be
   overwritten on re-deploy / SNAPSHOT (`MavenProtocolHandler.java:184,374`). Confirm artifact
   metadata may be persisted as last-writer-wins-versioned (already the `putVersioned` model) —
   if SNAPSHOT semantics need richer history, the metadata-persist path grows.
2. **fsync vs full WAL for artifacts.** Is fsync + atomic-rename on `LocalDiskTier` an acceptable
   durability floor for artifact blocks, or must artifacts get a `PartitionWal`-grade WAL? Blocks
   are immutable, so simple fsync-before-ack should suffice — please confirm the bar.
3. **Cold full-cluster restart in the threat model.** Does production readiness require surviving
   a simultaneous cold restart (L4)? If operators are guaranteed to always keep ≥1 node, L4 is
   moot and arm A's churn-completion *could* suffice — but the default `["builtin"]`-only config
   and auto-heal dependency argue strongly that L4 is in scope.
4. **rc3 vs GA staging.** Stage 2 (durable tier) is the larger piece — land it fully in rc3, or
   ship rc3 on C1+C2 and land the durable tier as the rc3→GA gate? #349 was filed rc3 and flags
   pulling at least "path-a" earlier.
5. **S3 remote tier in scope now?** `RemoteTier`/S3 is built and tested. Offer it as the artifact
   remote tier in this work, or local-disk-only for rc3 with S3 deferred?
6. **DHT retention as pure cache.** Once artifacts are durable, should the DHT artifact tier gain
   a bounded cache eviction policy (it is currently unbounded-ish memory+replication), reclaiming
   the memory now that disk is the source of truth?
