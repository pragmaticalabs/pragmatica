# Storage identity and adoption (#1569) — design spec

| | |
|---|---|
| Ticket | pragmatica #1569 (storage identity and adoption) |
| Status | **r7.1, FINAL.** The reviewer rated r7 **SOUND**. r7.1 folds in the residuals R7-1..R7-4 and records the CTO decisions. All open questions are decided (§14). Rulings in force: divergence is **detect-and-flag**; entities follow **option A**. |
| Read point | `spec-src`, detached at rc4 tip `5f6ba462f`. Every `File.java:N` citation refers to that tree. "(Javadoc)" marks a citation of a documentation comment. |
| Depends on | P1 #1529; P2 #1546 + `RestoreCommand`; P3 AHSE `openLog`/`seal` (A1-A8, A10, A12-A14); S8 (A9, A11); P4 #1555; P5 #1532; P6 #1577; #1574 (`OperatorWarning`); #1564 |
| Out of scope | Designing `openLog`/`seal`/A11; automatic divergence reconciliation (post-GA, §9.6); the runtime divergence fix (S6); offset reuse after full retention expiry (**#1580**); total disk loss (#1570); a durable DHT (#1544); the remote tier (#249) |

**Conventions**
- `[doc]`: the claim comes from a document.
- `[unverified: …]`: the claim was not checked against code or a run.
- `[ASSUMPTION]`: a choice made without a settling input.
- `R`: the `streams` StorageInstance root.
- Tickets are **AD1…AD14** (§12). S6 and S8 are CTO streams.

---

## 0. Changelog

### 0.1 r7.1: the r7 residuals and the CTO decisions

| Item | Resolution | Sections |
|---|---|---|
| **R7-1** Records below the base compare NONE = NONE | **A defensive rule.** A copy that holds **any retained record below its own base** (`low_X < base_X`) raises `HISTORY_INCOMPLETE`. NONE is therefore only ever compared over offsets that no copy holds. The spec's own ordering (set-aside before `BASE`, detach at `open()`) already prevents this state; the rule makes the check explicit. Test T7(p), mutation M57. | §7.5.2, §7.5.3 |
| **R7-2** The CAS can livelock on repeated re-flags | **Flag transactions are idempotent.** A flag is a set-union of reasons into the record. Reasons carry only deterministic evidence (reason kind plus storage id), with no timestamps and no counters. The candidate list is sorted by StorageId. When every reason is already present, the leader writes nothing, and `recordDigest` does not change. So the same evidence always yields the same record and the same digest, and a resolution taken against a stable digest can commit. Test T12(u): a node flaps `LOCAL_MISMATCH` during an `--empty`. Mutation M56 (the flag stamps a time) causes the livelock and must turn T12(u) red. | §7.5.3 |
| **R7-3** Reseed at the base after full retention expiry | Pre-existing and inherited: when retention reclaims every segment and compacts the WAL empty, the sealed floor drops to −1 (`StreamPartitionManager.java:3848-3860`), and the ring reseeds at the base, reissuing offsets that were already consumed. This holds for base 0 today, and for base n after this spec. **Filed as #1580.** The history-based floor (A11 is never pruned) is its proposed input. Named in §1's bounds. | §1, §7.6.2 |
| **R7-4** `--empty` after a `CARRIED_OVER` re-flag | **Runbook rule, plus a CLI guard.** A partition flagged **only** with `CARRIED_OVER` (typically #1532's lag losing a RESOLVED record) is resolved with plain `accept-loss`, never with `--empty`, which would set aside copies holding acknowledged post-resolution writes. `--empty` on a `CARRIED_OVER`-only record requires `--override-carried-over` and prints the rule. | §7.6.2, AD10 |
| **OQ-16** Stream generation vs #1564 | **Decided (CTO); no clash by construction.** At the read point, the user-declared `StreamConfig` (`StreamConfig.java:20-29`) carries `replicas` and `minSyncReplicas`, which are #1564's territory, being replaced by RF/CF. `StreamMetadataValue` (`AetherValue.java:1392-1399`) carries no replication field. #1564's ReplicationPolicy field was not found in any tree searched (space: `spec-src` plus `pragmatica-stream-*` slice modules) `[unverified: #1564's final field placement]`. **The generation lives in the KV envelope `StreamConfigValue(StreamConfig config, long createdAt)` (`AetherValue.java:1950`) as a new component `String generation` (ULID), never inside `StreamConfig`.** #1564 edits `StreamConfig` and its parser; AD13 edits the envelope. The two changes touch disjoint records. | §4.2, AD13 |
| CTO-level OQs | OQ-2, 3, 5, 6, 8, 9, 10, 11, 12, 15, 16, 17, 20 and 25 are **decided (CTO)** as recommended. | §14 |

### 0.2 Earlier rounds (traceability)

| Round | What it settled |
|---|---|
| r7 | H1 completeness rule; H2 `--empty` always admissible; M-1 canonical digest; M-2 durable base; M-3 joiner ahead of owner plus widened `placement_known`; M-4 evidence-gated NO_CANDIDATE; M-5 atomic ref move; M-6 per-resolution synthetic epochs |
| r6 | R5-1 history comparison from 0; NO_CANDIDATE; exhaustive list of automatic modifications; refs-only purge; digest CAS/token; `uniqueRecords`; RBAC; joiner verification; liveness exits |
| r5 | Detect-and-flag; entities option A |
| r4 | N3-N11; the choke point; `RestoreCommand` |
| r3 / r3.1 | F1-F12, N1, N2 |
| r2 | d81a5c08e |

---

## 1. Goal and the guarantee

**Goal.** When the data volumes survive a whole-cluster cold restart, no acknowledged stream, durable-topic or durable-entity record still within retention is lost or silently diverges. Every node restarts under a fresh NodeId.

### G-COLD (per partition P)

**Preconditions:**
1. `[backup]` is configured, and P's declaration is restored at the same generation.
2. The cluster runs in durable mode (A9, C0).
3. AHSE provides A1-A14.
4. **E-INV** holds (§7.5.1). This needs P6 #1577, S8/A11 and the N13 assertion.

**Outcome.** After the restart, exactly one of these holds:
- **(W) Writable and complete.** P passes detection (§7.5.3) and becomes writable once `ready(P)` holds (§7.4). Every record acknowledged for P, within retention at the crash, and held at the crash by at least one surviving admitted copy is readable at its original offset. If at most CF − 1 of the storages that acknowledged P's records were lost (counting CONTESTED, forgotten and abandoned storages as lost), that is **every** acknowledged record within retention.
- **(F) Flagged.** P carries at least one reason (§7.5.3). It refuses writes, serves no reads, and emits an `OperatorWarning`. It waits for `pick-source` or `accept-loss`. **Every flag shape has an exit:** `accept-loss --empty` is admissible whatever the reasons, and an idempotent flag cannot livelock its CAS (R7-2).

**Never:**
- P becomes writable while a divergence among its surviving admitted copies has been detected (§7.5.2). This includes divergence below any copy's retained low, any offset whose provenance is UNDEFINED, and any record held below a copy's base.
- A copy is modified except by the automatic modifications (a)-(e) below or by an operator resolution.

**Automatic modifications (exhaustive):**
- (a) the A10 recovery cut at `open()`;
- (b) catch-up appends above the copy's own head;
- (c) normal sealing (A5) and retention by policy;
- (d) the AD13 orphan detach at `open()`;
- (e) the superseded-storage detach at a later admission;
- and applying a committed operator resolution: its detaches and its `BASE`/`UNKNOWN` entries.

Every detach preserves bytes and refs (A12).

**Mechanism:**
- The ack follows a group-commit fsync on CF storages (`PartitionWal.java:170-192, 356-369`; A6; `ReplicationReceiveHandler.java:341-346`).
- Seal never outruns durability (A5, A9).
- Identity is bound to the cluster (§4).
- Placement ranks storage ids (§6).
- Detection compares complete histories (§7.5).
- Only an operator resolves a flag (§7.6).

**Bounds, stated honestly:**
- **The loss flag is computed over `placement_known`** (§8.2). An ack set outside it is not seen. That happens through HRW churn, or through a storage minted and lost within #1532's lag and observed by no survivor. A surviving copy that holds such records is still caught by divergence detection or the joiner-ahead rule.
- **Runtime losses before the crash are not repaired** when every copy that could evidence them was also lost.
- **Within retention:** retention removes records by design. **After full retention expiry, a partition reseeds at its base and reissues offsets that were already consumed.** This is pre-existing, and it is tracked as **#1580**.
- **Before P6 (#1577) lands,** spurious `DIVERGED` flags will occur. They fail closed.
- **Logs written before A11** flag once after rollout (OQ-24, owner).

**Not covered:** DHT-only data (#1544); anything without `[backup]`; declarations inside #1532's commit lag; cursors, which are at-least-once (§10).

---

## 2. Owner rulings (fixed)

1. Terminal removal, and incarnation-dominant epochs (#1529).
2. Storage identity lives in the data directory.
3. HRW over storage ULIDs, with a committed host map.
4. First claim wins, refined at cold restart by report-then-admit plus CONTESTED (OQ-14).
5. The adoption unit is the AHSE StorageInstance.
6. Zero-loss cold restart requires `[backup]`; its absence triggers a WARN.
7. RF/CF default to 3/2.
8. Ownership comes from the committed record; only the leader judges liveness.
9. Total disk loss is out of scope.
10. `openLog`/`seal`, with a single root.
11. A9 and A11 belong to S8.
12. **Detect-and-flag.**
13. **Entities follow option A.**

---

## 3. Current state (read point 5f6ba462f)

- **NodeId in the storage path:**
  - `AetherNode.java:980-986` (data dir) and `:993-1007` (WAL base);
  - `StreamPartitionManager.java:3982` (WAL file) and `:176-179` (WAL opened at ring creation, Javadoc);
  - `StorageFactory.java:504-507, 547-548`, plus the memory+DHT fallback at `:602-615`, which S8 removes;
  - `DefaultSnapshotManager.java:456`.
- **Placement:** HRW over NodeId (`ReplicaPlacement.java:57-90`); core-only (`AetherNode.java:4792-4795`); ownership value (`StreamPartitionOwnershipWriter.java:141-203`); owner rings are unpaced and replicas are paced (`StreamPartitionManager.java:112-117`).
- **Epochs:**
  - the local stamp: `StreamPartitionManager.java:172-175`;
  - the batch epoch: `ReplicationMessage.java:29-40`; the #1577 issue is at `ReplicationBatcher.java:349-352` (via the review);
  - the high-water fence: `StreamPartitionManager.java:166-171, 423-427`;
  - the backfill fence stamp: `AetherNode.java:4500-4505`;
  - no epoch in the log frame (`PartitionWal.java:47`, Javadoc) or in `CatchupResponse` (`ReplicationMessage.java:151-157`).
- **Backfill compares heads only:** `PartitionBackfill.java:363-377, 933-938, 992-1003, 1114-1143`.
- **Quarantine is in memory:** `StreamPartitionManager.java:192, 329-330, 2039-2046`.
- **Seeding:**
  - `seedRing` makes the next append `seed + 1` (`StreamPartitionManager.java:3840-3846`);
  - `seedFor` and the head-gap WARN are at `:3848-3873`;
  - `OffHeapRingBuffer.seedHead` is at `:748`.
- **Declarations:**
  - `StreamConfig` at `slice-api/…/StreamConfig.java:20-29`;
  - the KV envelope `StreamConfigValue(config, createdAt)` at `AetherValue.java:1950`;
  - `StreamMetadataValue` at `AetherValue.java:1392-1399`.
- **KV:**
  - git-backed or in-memory (`AetherNode.java:725-734`);
  - lifecycle saves, as a binary snapshot `[doc: backup-recovery.md:11-20]`;
  - `EphemeralKeys` applies to TOML only (`:13-14`);
  - `restoreSnapshot` is node-local and fence-bypassing (`KVStore.java:255-256, 460-484`).
- **Codec:** canonical collections (`FrameworkCodecs.java:241-305`).
- **Block lifecycle:** refCount; `orphanedAt` stamped on the transition to ≤ 0 (`BlockLifecycle.java:15-19, 100-108`).
- **Management security:**
  - roles with a deny-by-default ADMIN (`ManagementRoutePermissions.java:27-35, 69-74`);
  - checks run only when `securityEnabled` (`ManagementServer.java:856`), i.e. `securityMode != NONE` (`AppHttpConfig.java:189-190`).
- **Durability:**
  - append at `PartitionWal.java:170-192, 356-369`; fail-stop at `:341-345`; the silent recovery cut at `:508-516`;
  - the replica barrier at `StreamPartitionManager.java:2133`;
  - non-durable blocks at `LocalDiskTier.java:72` and `FileOps.java:102-112`.
- **DHT** is in-memory (`MemoryStorageEngine.java:35`).
- **Entities:** `StreamEntityLogSubstrate.java:588-632`; `EntityLogSubstrate.java:40-45, 68-79`.

---

## 4. Storage identity lifecycle (item a)

### 4.1 Definition

`R/STORAGE_ID` holds a write-once ULID bound to the cluster:

```
format=2
storage-id=<ULID>
cluster-name=<ClusterName>
cluster-lineage=<#1532 lineage>
created=<ISO-8601>
```

### 4.2 Layout

```
R/  = [storage.streams] disk_path, else <artifacts disk_path sibling>/streams-storage
  STORAGE_ID, CLAIM, LOCK                  adoption-owned
  aside/<asideName>/                       detached logs: file + .epochs sidecar + ref namespace (A12)
  …                                        blocks, refs/snapshots, logs, <log>.epochs sidecars (AHSE)
```

- No NodeId appears in the layout.
- `wal_path` is refused.
- A legacy directory triggers a WARN.
- Metadata is stamped per A7.
- Only core nodes have an `R`.
- **Log names are `<stream>@<generation>/<partition>`.** The generation is a ULID stored in `StreamConfigValue.generation` (AD13, OQ-16). It is minted when the first `StreamConfigValue` for a name is written, kept across config updates, and replaced when the stream is deleted and re-created. A log of any other generation becomes an orphan aside at `open()`.

### 4.3 Minting and binding

**Phase 1, pre-flight:**
- A name mismatch refuses boot.
- A foreign or damaged directory refuses boot.
- Otherwise the node freezes `ledgerSeq`.

**Phase 2, before the report:**
- A lineage mismatch refuses boot, with no report.
- The node mints only when the gate allows it, via `writeBytesDurable` (`FileOps.java:114-158`).

**Modes:** durable mode requires A9. The non-durable opt-in uses an ephemeral id (`AetherNode.java:1009-1019`). The two modes never mix (C0).

### 4.4 Copied volumes

| Situation | Result |
|---|---|
| A copy of the live holder | Refused; the holder is not poisoned. |
| Cold restart | The highest `ledgerSeq` wins; a tie → CONTESTED. |
| A stale copy | Refused (C3). |
| A fresher copy after OPEN | CONTESTED plus a flag; the copy is untouched. |

### 4.5 Same-host double open

`tryLock` on `R/LOCK`. Failure refuses boot.

### 4.6 What adoption needs from AHSE (requirements, not a design)

| # | Requirement | Owner |
|---|---|---|
| A1 | One root and no NodeId; caller-supplied log names | P3 |
| A2 | Identify-only construction; nothing written before `STORAGE_ID` is durable | P3 |
| A3 | No mutation before `open()` | P3 |
| A4 | Per log: `(low, head, lastEpoch, historyDigest, firstStart, divergedAt?)`, plus the full history on request | P3 + S8 |
| A5 | Seal order: blocks → ref → truncation; never to a non-disk tier | P3 |
| A6 | Today's append, fail-stop and recovery semantics | P3 |
| A7 | StorageId-stamped metadata | P3 |
| A8 | Reserved names | P3 |
| **A9** | No durable tier (blocks and logs) means no instance | **S8** |
| A10 | The recovery cut is logged; it is the only self-initiated truncation | P3 |
| **A11** | `recordEpochStart`, `epochHistory()` and `truncateEpochsAbove` in the `<name>.epochs` sidecar. Never pruned from below. Synthetic epochs `BASE(d)` and `UNKNOWN(d)` order below every real epoch. | **S8** |
| A12 | **Detach moves refs, never blocks, and never passes a refCount through 0.** It is one metadata transaction that adds the aside refs before removing the live refs, or renames the ref keys. `purgeAside` drops refs only; blocks are reclaimed by GC only once unreferenced. Crash-idempotent. `[unverified: GC counts refs across the aside namespace — P3 test]` | P3 |
| A13 | Durable `markDiverged` / `clearDiverged` | P3 |
| A14 | Durable `put` for the streams instance | P3 |

---

## 5. Claim protocol (item b)

Unchanged since r4/r5. The implementation reference for AD2-AD5 is the r4/r5 text, as summarised here.

**Tags.** Take the next free slots after the highest pin at merge time (2113 at the read point; `SystemTags.java:44-50, 510-512`):

| Tag | Type |
|---|---|
| T+0/1 | claim key and value, with `admittedFromV` |
| T+2 | state enum (UNKNOWN last, treated as HELD) |
| T+3 | `ClaimEpoch` |
| T+4 | `StorageId` |
| T+5/6 | release (`LeaderAuthorized`) |
| T+7/8 | contested (`ContestFenced`) |
| T+9/10 | gate (`LeaderAuthorized`) |
| T+11 | `GateState` |
| T+12/13 | reports (ephemeral) |
| T+14/15 | inventory |
| T+16/17 | `PartitionRecovery` (`LeaderAuthorized`) |
| T+18 | recovery state |
| T+19 | `RestoreCommand` |
| T+20/21 | `StorageAside` (`LeaderAuthorized`) |
| T+22/23 | `HistoryRequest` / `HistoryResponse` |
| T+24/25 | `CheckpointBlockReplicate` / `Ack` |
| T+26 | synthetic-epoch codec, if needed |

`CatchupResponse` gains `epochSlice`.

**Fences:**
- "Gate open" means `OPEN ∧ G.inc == I`.
- `v` is the frozen `ledgerSeq`.
- `ClaimFenced`: C0 → C1 → C1b → C2c → C2' → C2 → C3 → C4/C5, default refuse.
- `ContestFenced` admits either a tie at admit time, or `admittedFromV < v < V.seq`.
- Leader transactions: release, forget, abandon, resolve-contest, mint plus gate plus digest, pick-source, accept-loss, purge-aside, flag (idempotent, §7.5.3).

**Claimant, step by step:**
1. Pre-flight.
2. Wait for a gate in I.
3. Lineage check, then report.
4. Incremental admit while SETTLING; once OPEN, retry on the same `p`.
5. Won → `open()`, which applies committed set-asides, supersessions and BASE entries.
6. Lost → contest, then marker, then FATAL.
7. Running self-fence.
8. The release wait is bounded (`QuorumLossDetector.java:118-124`) `[unverified: values]`.

**Order premise:** `[doc: rabia-instance-round-contract.md:3-4, 87-88]`, `[unverified for rc4]`.

---

## 6. Placement, routing, ownership (item c)

- `E` = storages HELD in I, with a live core host, with the instance open.
- HRW over StorageId, from one snapshot.
- Call sites: `ReplicaSetController.java:329, 392, 439`; `PartitionBackfill.java:971-973`; `EntityOwnershipReconciler.java:236`.
- **The ownership record names the node.**
- A flagged P gets no ownership write.

---

## 7. Cold-restart sequence (item d)

### 7.1 End to end

| Step | Action |
|---|---|
| 0-3 | Stop; fresh NodeIds; pre-flight; genesis. |
| 4 | `RestoreCommand`: one decided payload, installed on every replica. |
| 5 | One transaction: mint I, gate SETTLING, lineage, digest. |
| 6-7 | Lineage check, report, known set, incremental admit. |
| 8 | Winners `open()`: the A10 cut, orphan detaches, pending set-asides, supersessions and BASE entries, then inventory. |
| 9 | The gate opens. |
| 10 | Placement over E. |
| 11 | Detection runs; `PartitionRecovery` commits; each flag emits an `OperatorWarning`. |
| 12 | CONSISTENT → ownership in I, append-only catch-up, writable once `ready(P)`. FLAGGED → §7.6. |
| 13 | Assignments, cursors, entities. |
| later | Storages admitted after OPEN go through joiner verification (§7.5.4). |

### 7.2 Records naming a NodeId

As in r5-r7. Every fate is safe, restored or not, because #1529 epochs dominate. `StreamPartitionOwnershipValue` is kept in the backup **as evidence only**, used for `placement_known` (OQ-25, decided). It is never used to route and is rewritten in I.

### 7.3 Epoch fencing

Epochs minted in I dominate. Comparing histories across restarts depends on P1.

### 7.4 CAUGHT_UP choke point

`ready(P)` holds iff all of the following hold:
- the gate is open in I;
- `PartitionRecovery(P).inc == I`;
- the state is CONSISTENT or RESOLVED;
- `localVerified(P)`.

`localVerified(P)` holds iff:
- (i) every set-aside and supersession for this storage has been applied;
- (ii) any `BASE(d)` entry is durable;
- (iii) the copy is history-consistent with `sourceHistory` (or with the owner's history, for a joiner), or no copy is held.

A failure fail-stops the log, emits `STREAM_EPOCH_HISTORY_MISMATCH`, and raises an idempotent flag `LOCAL_MISMATCH(storageId)`.

**The choke point** is `ReplicaRegistry.admittedState`, called from `updateWatermark` (`:209-236`) and `rebuildSingleWatermark` (`:250-259`). It covers all 7 sites:
- `PartitionBackfill.java:817, 1088, 1244, 1456, 1540`;
- `DefaultReplicationManager.java:213-217`;
- `ReplicaRegistry.java:239-259`.

`StreamReadRouter.selfRowOverride` (`:257-265`) and the ownership writers are gated as well.

A flagged P has no CAUGHT_UP row, no owner and no reads. `[unverified: every read path requires CAUGHT_UP — AD7 test]`

### 7.5 Detection

#### 7.5.1 Provenance recording

- **Base:** 0, or the start of a first entry `BASE(d)`.
- **E-INV:** for every `o` with `base ≤ o ≤ head`, retained or not, the last entry with `start ≤ o` names the epoch under which `o` was first appended.
- **The fence token is never provenance** (`AetherNode.java:4500-4505`).

| Path | Source of `recordEpochStart` |
|---|---|
| Owner publish | The owner's stamp. Empty-history rule: the first entry must start at the base. An owner with records but no history records nothing and flags. |
| Replica batch | The batch `ownerEpoch` (P6), under the same rule. |
| Backfill | Only the source's `epochSlice`, covering the full prefix `[base, to]`, including synthetic entries. |
| Resolution apply | `BASE(d)` at `startOffset`, or `UNKNOWN(d)` at 0, durable before any append. |
| WAL recovery | Nothing. |

**N13:** the receiver's history must equal the slice's history restricted to `[base, from − 1]`. On a mismatch, apply nothing, fail-stop, set A13, and emit the event.

#### 7.5.2 The divergence rule: completeness and records below the base

For a copy X:
- `base_X` is 0, or the start of its first entry if that entry is `BASE(d)`.
- X is **complete** iff its history is non-empty, its first entry starts at `base_X`, **and `low_X ≥ base_X` (it holds no record below its base, R7-1)**.
- `prov_X(o)`, for `o ≤ head_X`:
  - `o < base_X` → **NONE**;
  - `o ≥ firstStart_X` → the epoch of the last entry with `start ≤ o`;
  - otherwise → **UNDEFINED**.

**Equality:**
- Real epochs compare as usual.
- `BASE(d)` and `UNKNOWN(d)` each equal only themselves with the same `d`.
- NONE equals NONE only for the same base.
- **UNDEFINED equals nothing.**

> **a and b diverge iff there is an o ∈ [0, min(head_a, head_b)] with prov_a(o) ≠ prov_b(o).**

Because of R7-1, NONE is only compared over offsets that neither copy holds. The walk is over history boundaries, so it is bounded by history length.

**Incompleteness flags even a lone copy.** Any candidate that is not complete, with an offset ≤ head below its first start **or** a retained record below its base, raises `HISTORY_INCOMPLETE(storageId)`.

**Worked examples:**
- **H1 path 1**, the pre-A11 log: `HISTORY_INCOMPLETE` + `DIVERGED`.
- **`--without-history`**: `UNKNOWN(d)` copies agree, and unrelated resolutions never do.
- **`--empty` at 500**: NONE below 500, then `BASE(d)`.
- **R7-1**, a copy with `BASE(d)` at 500 still holding a record at 400: `HISTORY_INCOMPLETE`.
- **R5-1**, F1, N3, S-1: `DIVERGED`.

**Equal provenance implies equal payload.** Each epoch has one writer and each offset is written once. Synthetic epochs are unique per resolution. `[unverified: no two owners share an epoch — #1230, #1529]`

**Non-divergent implies prefix**, as argued in r6/r7.

#### 7.5.3 Detection procedure and the record

**Candidates** are open storages holding P's log at the restored generation. Asides are excluded.

**Reasons**, each a `(kind, storageId?)` value in a **set**:

| Reason | Condition |
|---|---|
| `NO_CANDIDATE` | Zero candidates **and** evidence of writes: a restored cursor or entity checkpoint ≥ 0, a restored `PartitionRecovery` head ≥ 0, an aside of P, or any missing known storage. With no evidence, P is CONSISTENT and empty at base 0. |
| `HISTORY_MISSING(id)` | Records with an empty history. |
| `HISTORY_INCOMPLETE(id)` | Not complete per §7.5.2, **including a retained record below the base** (R7-1). |
| `MARKED_DIVERGED(id)` | A13. |
| `DIVERGED` | §7.5.2. |
| `LOSS_BUDGET` | ≥ CF storages of `placement_known(P)` are missing. |
| `CONTESTED(id)` | — |
| `CARRIED_OVER` | — |
| `LOCAL_MISMATCH(id)` | Raised by `localVerified`. |
| `DIVERGED_LATE_JOINER(id)` | Raised by joiner verification. |

**`PartitionRecoveryValue`:**
```
( inc, state, reasons : Set<Reason>,
  candidates : List<…> sorted by storageId,
  source?, sourceHistory?, sourceHead?,
  resolution? { kind, principal, at, setAside, supersededStorages, acceptedHead, startOffset, synthetic } )
```
Only `resolution` carries a time. Nothing else in the record is time- or counter-dependent.

**`recordDigest`** = SHA-256 of the value's encoding with `canonicalCollections()` enabled (`FrameworkCodecs.java:241-305`).

**Flag transactions are idempotent (R7-2).**
- A flag transaction computes `new = old with reasons := old.reasons ∪ added`. If `CONSISTENT` or `RESOLVED`, it sets `state := FLAGGED`.
- **If `new` equals `old`, the leader writes nothing.**
- A flapping node that re-raises `LOCAL_MISMATCH(id)` therefore leaves `recordDigest` unchanged after the first flag.
- The same evidence always yields the same record and digest. A resolution CAS'd on that digest can commit, even while the flap continues.
- A **new** reason or storage does change the digest. That is correct: the operator must see the new evidence.

**Outcome:**
- No reasons → CONSISTENT, with `source = argmax (lastEpoch, head)`.
- Otherwise → FLAGGED, plus `STREAM_PARTITION_FLAGGED` (CRITICAL for the divergence, loss, no-candidate, local-mismatch and incomplete reasons; WARNING for the rest).

#### 7.5.4 Joiner verification

A storage admitted after OPEN may not register, serve or act as a source for P until **all** of these hold:
1. supersession and set-aside have been applied;
2. its history is **complete** (§7.5.2, including R7-1);
3. it is consistent with the owner's history (`HistoryRequest`) over `[0, min(head_j, head_owner)]`;
4. `head_j ≤ head_owner`.

Any failure raises an idempotent flag `DIVERGED_LATE_JOINER` (or `HISTORY_*`). Every copy is left untouched, and writes stop (OQ-22, owner). A joiner with no log backfills as an empty receiver.

### 7.6 Operator resolution

#### 7.6.0 Authority and confirmation

- **Routes:** `pick-source`, `accept-loss` and `aside purge`, each bound explicitly to `ADMIN_ONLY` (`ManagementRoutePermissions.java:27-35, 69-74`).
- **Token:** `--expect-digest` = the canonical `recordDigest`, required in every mode. It acts as a read witness, i.e. a CAS on the whole record.
- **Under `securityMode = NONE`**, where there are no role checks (`ManagementServer.java:856`; `AppHttpConfig.java:189-190`), the token prevents accidents only. It does not stop a malicious caller who can reach the port. The runbook says so.
- **Execution:** always a leader-witnessed `LeaderTransaction` (`KVCommand.java:19-23`).

#### 7.6.1 `pick-source`

```
aether stream pick-source <s> <p> --storage <id> --expect-digest <d>
                          [--without-history --acknowledge-unknown-provenance]
                          [--start-offset <n>]
```

**Preconditions:**
- FLAGGED in I with digest `d`.
- The storage is a candidate and is not named by `LOCAL_MISMATCH` or `DIVERGED_LATE_JOINER`.
- If the storage is `HISTORY_MISSING` or `HISTORY_INCOMPLETE`, both history flags are required.

**Commits RESOLVED:**
- `source`, `sourceHistory`, `acceptedHead`, and `startOffset` (default `acceptedHead + 1`).
- **`setAside`:** every copy that diverges from the chosen one; every copy named by `LOCAL_MISMATCH` or `DIVERGED_LATE_JOINER`; and, under `--without-history`, every other candidate.
- **`supersededStorages`:** the missing and CONTESTED storages of `placement_known`.
- `StorageAside` records, and `clearDiverged` on the chosen copy.
- Under `--without-history`, the chosen copy's history becomes `[(UNKNOWN(d), 0)]` before any append. The old sidecar is copied into `aside/` `[ASSUMPTION: A11's sidecar is copyable]`.

**Effect:**
- Kept copies catch up by append.
- Set-aside copies are detached (refs only) at `open()` or immediately, then backfill as empty receivers.
- Superseded storages are detached when admitted.
- Nothing is deleted.

**Events:** `STREAM_PARTITION_SOURCE_PICKED`, plus `STORAGE_ASIDE_CREATED` for each set-aside.

**Re-flags are bounded:** at most one per candidate, each needing an operator command. When the candidates are exhausted, `--empty` remains.

#### 7.6.2 `accept-loss`

```
aether stream accept-loss <s> <p> --expect-digest <d> [--empty [--override-carried-over]] [--start-offset <n>]
```

**Without `--empty`:**
- FLAGGED with digest `d`.
- No divergence reason and no `HISTORY_*` reason.
- At least one candidate, and the candidates are pairwise non-divergent.

**With `--empty`** (H2): FLAGGED with digest `d`, **whatever the reasons**.
- **R7-4:** if the reasons are exactly `{CARRIED_OVER}`, the command also requires `--override-carried-over`, and the CLI prints: *"A CARRIED_OVER-only flag usually means #1532's lag lost a RESOLVED record; the copies may hold acknowledged writes made after that resolution. Use plain `accept-loss`, which keeps every copy."*

**Commits RESOLVED:**
- `source`, which is none under `--empty`;
- `acceptedHead`;
- `startOffset`: default `acceptedHead + 1`. Under `--empty`, the default is `1 +` the maximum committed evidence offset: cursors, entity checkpoints, restored recovery heads and set-aside heads.
- `supersededStorages`;
- under `--empty`: `setAside` = every candidate, and `synthetic = BASE(d)`.

**The durable base (M-2):**
1. The consensus commit carries `startOffset`.
2. Every host that materializes P for this resolution records `(BASE(d), startOffset)` as the first history entry, durable before any append. `localVerified` (ii) depends on this.
3. Each ring materialization with no record at or above the base calls `seedRing(startOffset − 1)` (`StreamPartitionManager.java:3840-3846`; `OffHeapRingBuffer.java:748`).
4. The head-gap WARN is suppressed at the base (`:3848-3873`).
5. If a cold restart loses the resolution to #1532's lag, the durable `BASE(d)` entries on the volumes keep the base.

*Offset reuse after full retention expiry is not covered here; it is #1580 (R7-3).*

**Events:** `STREAM_PARTITION_LOSS_ACCEPTED` (CRITICAL).

#### 7.6.3 The aside registry

**`StorageAsideValue`:**
```
(reason, stream, gen, partition, lastEpoch, head, bytes, createdInc, uniqueRecords?, purgeAuthorizedBy?)
```

`uniqueRecords` is set when the aside holds any of:
- records above `acceptedHead`;
- records whose provenance differs from the source;
- anything set aside by `--empty`;
- anything with reason `ORPHAN_GENERATION`.

**List:** shows the records, bytes, `uniqueRecords` flag and digests.

**Purge:**
- ADMIN, with the token; `--destroy-unique-records` is required when `uniqueRecords` is set.
- A `LeaderTransaction` sets `purgeAuthorizedBy`; the host then drops **refs only**, and blocks go through the refcount GC.
- A host that is down purges at `open()`.
- Emits `STORAGE_ASIDE_PURGED`.
- **Never automatic.**

**Budget:** aside bytes count against the disk budget. `STORAGE_ASIDE_BYTES_HIGH` fires above `aside_warn_fraction` [ASSUMPTION: 0.10]. Exhausting the budget refuses appends on that node; the runbook explains this.

---

## 8. Partial survival (item e)

### 8.1 Cases

| Case | Outcome |
|---|---|
| All volumes survive; ≤ CF − 1 lost, consistent | (W) |
| ≥ CF lost within `placement_known` | (F) `LOSS_BUDGET` |
| A divergent copy (including below a retained low, or an UNDEFINED region) | (F) → `pick-source` or `--empty` |
| A pre-A11 or partial history, or a record below the base | (F) `HISTORY_*` → `pick-source --without-history` or `--empty` |
| Zero candidates with evidence / without evidence | (F) `NO_CANDIDATE` / (W) empty |
| A late joiner that diverges or is ahead | (F) |
| All candidates LOCAL_MISMATCH | (F) → `--empty` |
| `CARRIED_OVER` only | (F) → plain `accept-loss` (R7-4) |
| Torn tail, CRC cut, crash mid-seal | (W) |
| Foreign volume; no durable tier | refused |
| Other-generation log | orphan aside, marked unique |

### 8.2 The loss flag

`placement_known(P)` = HRW top-RF over the known set, **plus** the storage of the restored pre-restart owner (OQ-25). `LOSS_BUDGET` fires iff ≥ CF of those storages are missing. The residual is stated in §1.

### 8.3 A fresher copy after OPEN

CONTESTED plus an idempotent re-flag. The copy is left untouched.

---

## 9. Interactions (item f)

- **9.1 AHSE.** P3 provides A1-A8, A10, A12 (atomic ref move; refs-only purge; GC namespaces), A13 and A14. S8 provides A9 and A11 (never pruned from below; synthetic epochs).
- **9.2 #1555, S6, #1577.** P6 lands before AD7 is enabled. Runtime divergence stays with S6. This spec detects divergence at cold restart and at join, and never reconciles. The §7.5.1 contract is shared with S6 (OQ-15, decided).
- **9.3 #1564.** Budget CF − 1. SYSTEM streams use RF = |E|. **The generation lives in `StreamConfigValue`, disjoint from #1564's `StreamConfig` fields** (OQ-16, decided).
- **9.4 Entities (option A):**
  - durable `put`;
  - `CheckpointBlockReplicate`;
  - the pointer is published only after CF durable copies exist;
  - blocks are republished to the DHT;
  - a flagged arc refuses folds;
  - an entity is recovered iff its arc reaches (W) and at most CF − 1 block holders are lost.
- **9.5 Stale backup.**
  - Ledger `observed`.
  - #1532 is the single source for `RestoreCommand`.
  - Orphans are marked unique.
  - Restored ownership is kept as evidence.
  - Residual: declarations inside #1532's lag.
- **9.6 Post-GA reconciliation.** A dedicated ticket (OQ-20, decided), blocked by #1577 and S6.
- **9.7 #1580.** Reuse after full retention expiry. The history floor from A11 is its input.

---

## 10. Guarantee summary (consistency lens)

| Operation | Guarantee | Mechanism | Bounds |
|---|---|---|---|
| Publish ack | fsync-durable on CF storages, owner included, in durable mode | A6; RRH:341-346; A9; C0 | None in non-durable mode |
| Seal truncation | Never past an offset whose block and ref are not both durable | A5, A9 | — |
| Divergence | Detected over complete histories from 0. UNDEFINED never matches. A record below the base, or incompleteness, is flagged on its own. Never reconciled. | §7.5.2, A11 | E-INV |
| Automatic modifications | Exactly §1 (a)-(e) plus committed resolutions. Every detach preserves bytes; no refcount passes through 0. | §1, A12 | — |
| Flag | Idempotent: the same evidence gives the same record and digest | §7.5.3 | — |
| KV restore | Identical bytes at one log position, digest-checked | `RestoreCommand` | P2 |
| Claim / contest | At most one HELD per id per incarnation; a contest only on a tie or a fresher copy | `ClaimFenced`, `ContestFenced` | order premise |
| CAUGHT_UP | Never stored unless `ready(P)` | `admittedState` | — |
| Cold restart, records | (W) or (F), per §1; every (F) has an exit | §7 | §1 bounds; #1580 |
| Late joiner | Complete, consistent and not ahead, or P is flagged | §7.5.4 | — |
| Operator resolution | Leader-witnessed CAS on the canonical whole-record digest; copies kept or detached, never deleted; a durable base; events emitted | §7.6 | NONE mode guards against accidents only |
| Space reclaim | Refs only, by explicit purge; blocks only via the refcount GC; an extra flag for unique records | §7.6.3 | GC namespace `[unverified]` |
| Cursors | At-least-once; no skip except retention expiry, and reissue per #1580 | `ClusterCursorStore.java:40-46` | — |
| Entities | Recovered iff the arc reaches (W) and ≤ CF − 1 block holders are lost | §9.4 | — |
| No `[backup]` / DHT-only data | None | — | — |

---

## 11. Test plan (item g)

Counts come from `<testcase>` elements in surefire and failsafe reports. "Untouched" is asserted by `hashTree` equality.

**Carried over:** T1-T6 and T8-T11; T7(a)-(o); T12(a)-(t); mutations M1-M5c, M9, M11-M55.

**New in r7.1:**

- **T7(p) R7-1.** Inject a copy whose first entry is `(BASE(d), 500)` and which still holds a record at 400; a second copy is clean.
  - Assert `HISTORY_INCOMPLETE`.
  - Control: the inventory reports `low = 400 < base = 500`.
  - **M57:** the completeness check omits `low ≥ base`, so the partition is CONSISTENT → red.
- **T12(u) R7-2.** A node restarts in a loop, each time failing `localVerified` and re-raising `LOCAL_MISMATCH(id)`, while the operator runs `accept-loss --empty` with the digest read after the first flag.
  - Assert that the digest is stable across at least 10 re-raises (counted), and that the resolution commits.
  - **M56:** the flag stamps a time (or appends to a list), so every command is refused → red (count of committed resolutions = 0).
- **T12(v) R7-4.** On a `CARRIED_OVER`-only record, `--empty` without `--override-carried-over` is refused, and plain `accept-loss` keeps every copy.

---

## 12. Tickets (item h)

**Prerequisites** (owned elsewhere):

| Ref | Item |
|---|---|
| P1 | #1529: the incarnation readable by the applier; mint and gate in one transaction |
| P2 | #1546 + `RestoreCommand` (chunked) |
| P3 | AHSE: A1-A8, A10, A12 (atomic ref move, refs-only purge, GC namespaces), A13, A14 |
| S8 | A9; A11 (no pruning from below; synthetic epochs) |
| P4 | #1555 |
| P5 | #1532, keeping `StreamPartitionOwnershipKey` as evidence |
| P6 | #1577, before AD7 is enabled |
| — | #1574; #1580 (independent) |

| # | Ticket | Size | Depends on |
|---|---|---|---|
| AD1 | Storage identity: `STORAGE_ID` format 2 with cluster binding, two-phase checks, frozen `ledgerSeq`, `LOCK`, `wal_path` refused, A7 consumer, legacy WARN, durable/ephemeral modes | S–M | P3, S8, P5 |
| AD2 | Claim, release and contest KV types plus tags; `ClaimFenced`; `ContestFenced`; leader transactions (release, forget, abandon, resolve-contest, idempotent flag); T3 | M | P1 |
| AD3 | Cold-restart gate: mint + gate transaction, restore digest check, reports with lineage filter, known-set folding, incremental admit, closure/opening, deadline → `OperatorWarning`; T9, T10(a-e) | L | AD2, P2, P5, #1574 |
| AD4 | Claimant state machine: `CLAIM` ledger, retries, full-disk rules, running self-fence; applies set-asides, supersessions and BASE entries at `open()` | L | AD1, AD2, P3 |
| AD5 | Release at terminal removal, with the self-fence bound | S–M | AD2 |
| AD6 | Placement over storages: `(E, host)` snapshot; HRW over StorageId; the three call sites | M | AD2 |
| AD7 | Provenance recording (empty-history rule, full-prefix `epochSlice`, N13); inventory with `firstStart`/`low`; completeness-aware detection (including R7-1); every reason; `PartitionRecovery` with canonical digest and **idempotent flags**; joiner verification (including the head rule); choke point + `localVerified`; widened `placement_known`; `/streams/recovery`; T7, T10(f-g) | M–L | AD3, AD6, S8, P3, P4, P6 |
| AD8 | `EphemeralKeys` / #1532 alignment (ConsumerAssignment ephemeral; ownership kept as evidence) | S | P5 |
| AD9 | Entities option A: `CheckpointBlockReplicate`, pointer-after-CF, republish; T5 | M | AD4, P3 |
| AD10 | Runbooks: cold restart; resolution decision guide (**CARRIED_OVER → plain accept-loss**); `securityMode = NONE` warning; aside pressure; pre-A11 rollout on fresh volumes | S | AD3, AD14 |
| AD11 | Ember harness: slot volumes, fresh-id restart, `[backup]`, volume utilities, injected histories, detach kill hook | M | — |
| AD12 | End-to-end tests T1, T2, T4-T8, T10-T12 | L | AD3-AD7, AD9-AD11, AD13, AD14 |
| AD13 | Stream generation: `generation` ULID in `StreamConfigValue` (not `StreamConfig`); log name `<stream>@<gen>/<p>`; orphans become unique asides; T11 | S | P3 |
| AD14 | Operator resolution: `pick-source` (+ `--without-history`, `UNKNOWN(d)`), `accept-loss` (+ `--empty` always admissible, `--override-carried-over`, durable base); aside list/purge (refs only, `uniqueRecords`); ADMIN binding + digest token; events; T12 | M–L | AD7, P3, #1574 |

**Critical path:** P1 → AD2 → AD3 → AD7 → AD14 → AD12. Write T7(a, c, h, l) first and record them red at the read point.

---

## 13. Rejected alternatives

| Decision | Rejected | Why |
|---|---|---|
| Records below the base flag | Trusting the sequencing | R7-1: the state is unchecked otherwise |
| Idempotent set-union flags | Time-stamped flag records | R7-2: CAS livelock |
| `--override-carried-over` | An unguarded `--empty` on `CARRIED_OVER` | R7-4: it buries acknowledged writes |
| Generation in `StreamConfigValue` | Generation in `StreamConfig` | OQ-16: disjoint from #1564 |
| *(earlier)* completeness; per-resolution synthetic epochs; `--empty` always admissible; canonical digest; durable base; joiner head rule; evidence-gated NO_CANDIDATE; atomic ref move; detect-and-flag; histories from 0; `RestoreCommand`; choke point; … | see r4-r7 | — |

---

## 14. Open questions

**Decided (owner)**, adopted as recommended (know on `docs/know-s28-rulings-5`):

| # | Question | Decision |
|---|---|---|
| 22 | A late joiner that diverges or is ahead stops writes to P (fail-closed). | **Decided (owner): keep.** The exit is one `pick-source`. |
| 1 | G-COLD as (W) or (F), with the exhaustive list of automatic modifications and the `placement_known`/#1580 bounds. | **Decided (owner): adopted.** |
| 14 | The cold-restart admit refines ruling 4. | **Decided (owner):** recorded as a know commit. |
| 13 | A fresher copy after OPEN is detected and flagged, not prevented. | **Decided (owner): accepted.** |
| 21 | A flagged partition refuses reads. | **Decided (owner): kept for rc4**; `inspect-copy` later. |
| 24 | Logs written before A11 flag once after rollout. | **Decided (owner): accepted** (pre-GA); roll A11 out onto fresh volumes. |

**Decided (CTO):**

| # | Decision |
|---|---|
| 2 | `TopicSubscriptionKey`: ephemeral only with a group-GC grace window; otherwise a leader prune. |
| 3 | No automatic gate open; the deadline only escalates. |
| 5 | #1529 acceptance: the incarnation readable by the applier; mint and gate in one transaction. |
| 6 | Backed up: claims, contested, gate, flagged/resolved `PartitionRecovery`, `StorageAside`. |
| 8 | `ConsumerAssignmentKey` is ephemeral. |
| 9 | Only cores claim storage. |
| 10 | Release is coupled to terminal removal plus the self-fence bound. |
| 11 | P3 acceptance: A12 (atomic, refs only, GC namespaces), A13, A14. |
| 12 | Lineage comes from #1532; `rebind-lineage` in AD10. |
| 15 | S6 owns the replica/backfill call sites; AD7 owns `epochSlice`, N13, detection, joiner verification and the choke point. |
| 16 | The generation lives in `StreamConfigValue`; no clash with #1564 `[unverified: #1564's final field placement; AD13 re-checks at merge]`. |
| 17 | Chunk `RestoreCommand` in P2. |
| 20 | Post-GA reconciliation gets a dedicated ticket, blocked by #1577 and S6. |
| 25 | `StreamPartitionOwnershipKey` stays in #1532's backup, as evidence only. |

**Settled:** 18, 19 and 23.

---

## References

**Decisions and review**
- Tickets: #1569 (this design), #1567 (AHSE openLog/seal), #1564 (replication policy), #1570 (AHSE unification), #1574 (OperatorWarning), #1532 (change-triggered backup)
- Rulings: know commits on `docs/know-s28-rulings-5` (including d81a5c08e, b6b714cca and 2b0f20d74)
- #1580 (R7-3)
- Adversarial review: seven rounds, SOUND at r7 (review notes are kept in the CTO records)
- Storage inventory at cb59a632f (kept in the CTO records)

**Docs**
- `rabia-instance-round-contract.md` :3-4, :45, :87-88 (normative)
- `backup-recovery.md` :10-20
- `deployment-recovery.md` §2.2, §4.5

**Code** (spec-src, 5f6ba462f)

| File | Lines |
|---|---|
| `AetherNode.java` | :519, :702-715, :725-734, :980-1041, :4500-4510, :4743-4748, :4792-4795 |
| `AetherValue.java` | :1392-1399, :1950 |
| `StreamConfig.java` (slice-api) | :20-29 |
| `StorageFactory.java` | :504-507, :547-548, :602-615 |
| `KVStore.java` | :247-264, :455-494 |
| `KVCommand.java` | :19-23 |
| `LeaderAuthorized.java` | :3-11 |
| `EpochBearing.java` | :23-47 |
| `AssignmentGuarded.java` | :3-27 |
| `FrameworkCodecs.java` | :241-305 |
| `BlockLifecycle.java` | :15-19, :100-108 |
| `OffHeapRingBuffer.java` | :748 |
| `ManagementRoutePermissions.java` | :27-35, :69-74 |
| `ManagementServer.java` | :856 |
| `AppHttpConfig.java` | :189-190 |
| `PartitionWal.java` | :47 (Javadoc), :170-192, :341-345, :356-369, :508-516 |
| `PartitionBackfill.java` | :363-377, :817, :933-938, :992-1003, :1088, :1114-1143, :1244, :1456, :1540 |
| `DefaultReplicationManager.java` | :209-246 |
| `ReplicaRegistry.java` | :209-236, :239-259 |
| `StreamReadRouter.java` | :257-265 |
| `ReplicationMessage.java` | :29-53, :151-169 |
| `ReplicationBatcher.java` | :349-352 (via the review) |
| `ReplicationReceiveHandler.java` | :341-346 |
| `StreamPartitionManager.java` | :112-117, :166-179, :192, :329-330, :423-427, :2039-2046, :2133, :3840-3873, :3982 |
| `ReplicaSetController.java` | :329, :392, :439 |
| `ReplicaPlacement.java` | :57-90 |
| `StreamPartitionOwnershipWriter.java` | :141-203 |
| `EntityOwnershipReconciler.java` | :236 |
| `StreamEntityLogSubstrate.java` | :588-632 |
| `EntityLogSubstrate.java` | :40-45, :68-79 |
| `ClusterCursorStore.java` | :40-46 |
| `EphemeralKeys.java` | :13-37 |
| `SystemTags.java` | :44-50, :510-512 |
| `FileOps.java` | :102-112, :114-158 |
| `LocalDiskTier.java` | :72 |
| `DefaultSnapshotManager.java` | :456 |
| `MemoryStorageEngine.java` | :35 |
| `QuorumLossDetector.java` | :118-124 |

**External**
- [KIP-101](https://cwiki.apache.org/confluence/display/KAFKA/KIP-101+-+Alter+Replication+Protocol+to+use+Leader+Epoch+rather+than+High+Watermark+for+Truncation)
- [ULID spec](https://github.com/ulid/spec)
- [Rendezvous hashing](https://en.wikipedia.org/wiki/Rendezvous_hashing)
- [PostgreSQL fsync errors](https://wiki.postgresql.org/wiki/Fsync_Errors)
