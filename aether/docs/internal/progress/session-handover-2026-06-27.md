# Session Handover — 2026-06-27 (streaming persistence: Phase A durable + Phase A-WAL crash-durable; A6 blocked on S20-class)

**Branch `release-1.0.0-rc2` · HEAD `eb1ac7fe1` · tree clean · NOT pushed.** 16 commits this session (`cedf64418`→`eb1ac7fe1`). Continues `session-handover-2026-06-26.md`.

## ⚡ TL;DR
User directive: **working end-to-end streaming persistence, no gaps, no unsupported doc claims** → chose the most complete durability target (**crash-durable, every acked event survives `kill -9`**). Delivered the entire stack **except the final end-to-end proof**, which is blocked by a **separate pre-existing recovery bug** (the S20-class owner-promotion race). The WAL crash-durability is **proven at the mechanism level**. **aether-clone is fixing the S20-class recovery path; once that lands, re-enable `StreamCrashDurabilityTest` and verify green — that's the only remaining step.**

## What landed (all committed, all green: aether-stream 523/523, fan-out 5/5, node compiles)
**Phase A — durable streaming (same-node-restart):**
- `04d312b23` A1+A3 — `streamStorage` promoted to a disk-backed, snapshot-capable `StorageFactory` "streams" StorageSetup (memory→LocalDisk→DHT, snapshot MetadataStore, restore-at-boot, per-node data dir, degrades to memory+DHT with loud WARN when not writable).
- `e07538107` A2 — replaced `EvictionListener.NOOP` with `SegmentSealer(StorageSegmentSink)` → segments seal to durable tier on eviction (the keystone).
- `3c9543b4d` A3b — scheduled `maybeSnapshot()` over all storage setups (closes the AHSE auto-snapshot gap too) + `SegmentIndex.rebuildFromRefs()` at boot.
- `ac4a4efa4` A4+A5 — durable consumer cursors + tiered reads wired into `StreamAccessFactory` via an additive `PartitionedStreamAccess` overload (the cursor arg-order swap — `checkpoint(stream,group,…)`→`commit(group,stream,…)` — is verified, kept inside PartitionedStreamAccess).
- `a5b8d4e9b` A0 — `StreamFanoutConsumerTest` + dedicated no-Postgres `test-stream` blueprint ported to rc2 (the regression net; 5/5 green with WAL OFF).

**Phase A-WAL — crash-durable (`e14a279d9` design):**
- `011ade7e1` W1 — `OffHeapRingBuffer.seedHead(base)` (replay positioning; head=base, **tail=base+1** so reads ≤base cleanly miss → served by segments).
- `56ecfc867` W2 — `PartitionWal` (append-only, record `[len][offset][ts][crc32][payload]` BIG_ENDIAN, **group-commit fsync** — append resolves only post-fsync, torn-write + CRC-safe recovery, threshold-lazy truncate). 11 tests incl. reopen-without-close durability + torn-write + CRC.
- `45a13773b`+`0148dbc9b` W3 — append+fsync **before ack** in `StreamPartitionManager.publishLocal` (ack gated on the group-commit; **WAL-first ordering**: durable→replicate→ack). `appendRecovered` (replica receive) untouched.
- `5f9f1d2de` W4 — replay WAL tail (`offset > lastSealedOffset`, via new `LastSealedOffsetSource` + `SegmentIndex.lastSealedOffset`) into the ring on partition (re)build, using `seedHead`.
- `062dc82e8` W5 — periodic (30s) WAL truncate to the durable `lastSealedOffset` (safe; decoupled from the void eviction listener).
- `3f8140939` W6 — `AetherNode.resolveStreamWalDir` activates the WAL only when the stream data dir is writable (Forge `/data` read-only → WAL OFF → fan-out test unchanged; a writable dir → WAL ON).
- `eb1ac7fe1` Harness + A6 repro — `EmberCluster.withDataBaseDir(@TempDir)` (writable, restart-stable per-node dirs; no stop/start change needed) + `StreamCrashDurabilityTest` (**@Disabled tracked-red**, port band 13500/13600/13700).

**Docs:** `91e1c4aa3` truth-pass — demoted ~10 over-claimed catalog entries to Partial/Planned with why+plan, added durable-entity (#217) + `/api/storage` docs, fixed counts/footer. `cedf64418`+`e14a279d9` plan docs (`streaming-persistence-implementation-plan.md`).

## 🚧 The ONE blocker — A6 (re-enable after S20)
`StreamCrashDurabilityTest` is `@Disabled` (tracked-red). The WAL is proven: append+fsync-before-ack works, WAL bytes **byte-identical across restart** (1690→1690), replay-into-ring serves all 50 events when the owner promotes correctly (one run did). Intermittently red (3/6 recover 0) on a **pre-existing, code-acknowledged** bug: a `PartitionBackfill` **owner-promotion-under-empty-view race** after full-cluster restart — the HRW owner self-promotes `CAUGHT_UP` at a low watermark before its member view settles and is **exempt from the redrive** (`ReplicaRegistry.java:79-81`, `PartitionBackfill.redriveCandidates`/`staleCaughtUpNonOwner`), serving 0 permanently. A test-side mesh-wait did NOT fix it → it's the product recovery path = **the S20-class full-cluster-restart recovery that aether-clone is fixing**. **Next step: once S20 lands, remove `@Disabled` from `StreamCrashDurabilityTest` and run `env -u HCLOUD_TOKEN mvn -q -Pwith-e2e -pl aether/forge/forge-tests integration-test -Dit.test=StreamCrashDurabilityTest -Dfailsafe.failIfNoSpecifiedTests=false` to verify green.** Likely fix in S20: re-arm the owner's redrive when local ring head > promoted offset, or defer first backfill until the member view is quorate.

## Deferred (documented, with plan)
- **Phase B = #265** placement-aware hydration → failover-to-other-node durability (`issue-265-implementation-plan.md`).
- **Phase C** = PG-backed segments + `PgTransactionalCursorCommit` → exactly-once + cold-full-cluster.
- **W7 cursor fsync** — cursor commit durability not separately hardened; at-least-once on the last un-fsync'd cursor is acceptable (events still survive via WAL). `test-stream` has no server-side cursor, so A6 proves events-survive (the required gate).
- The AHSE-engine compression/encryption write-path gap (#142/#143) — separate, documented in catalog.

## Discipline / notes
- Local **rc2 jbct binaries refreshed** this session (`mvn install -DskipTests -Djbct.skip=true -pl jbct/jbct-maven-plugin,jbct/slice-processor,aether/pg-tools/pg-codegen -am`) — earlier worktree agents had rebuilt the rc1 toolchain (harmless, different version coordinate).
- **Worktree-base trap bit a 4th time** — `isolation:"worktree"` branches from `origin/main` (180 commits behind rc2). Mitigation used: ran all coding agents NON-isolated on the rc2 tree. See `feedback_worktree_isolation_pattern`.
- `mvn install` fires HetznerCloudIT with HCLOUD_TOKEN set → always `env -u HCLOUD_TOKEN`; forge via `integration-test -Dit.test=…`, never `verify`; `-am` re-tests dep modules (a flaky `LeaderManagerTest` crash) → omit `-am` when deps already installed. aether/** = BSL-1.1; single-line commits, no trailers.

## jbct toolchain fixes (same session, after the streaming work — all pushed)
Root cause: the jbct scaffolding referenced the **original personal GitHub repos** (`siy/*`) which moved to the `pragmaticalabs` org, so live version/release lookups silently failed and fell back to a hardcoded stale `0.20.0`. Fixed + extended (6 commits `abefd624c`→`223c4fddf`, all on `origin/release-1.0.0-rc2`):
- `abefd624c` — `GitHubVersionResolver` (jbct scaffolding versions): `siy/pragmatica` → `pragmaticalabs/pragmatica`, fallback `0.20.0` → `1.0.0-rc1`. (`ProjectInitializer` already resolved live — both regular + slice paths; the bug was purely the wrong repo + stale fallback.)
- `3cd7813f3` — `GitHubReleaseChecker` (`jbct upgrade` CLI self-update): `siy/jbct-cli` → `pragmaticalabs/pragmatica` (its release carries the `jbct.jar` asset).
- `6510e6287` — `PersistenceAdder` now generates the `[database]` + `[database.pool_config]` config in `resources.toml` (was missing → scaffolded `@PgSql` persistence had no datasource). Imports/coordinates/`V###__name.sql` schema naming were already correct vs `comprehensive-persistence`.
- `f736b9889` — **new `jbct migrate [--version X]`** command (`MigrateCommand` + `VersionMigrator`): rewrites the project pom's `pragmatica-lite`/`aether`/`jbct`/`platform` version properties to latest (default) or a specific version. There was no such command before.
- `858d66e35` — AI-tools install (`AiToolsInstaller` init + `AiToolsUpdater` update): **skip per-project install of any skill/agent already present in global `~/.claude/`**; and the installer now SHA-compares the source repo each run (was serving a stale cache indefinitely). Repo `siy/coding-technology` is correct (a real repo) — left unchanged.
- `223c4fddf` — invariant comment on `DEFAULT_VERSION`: must track the latest published release; bump-on-discrepancy.
Local rc2 jbct binaries rebuilt + reinstalled with all of the above (`MigrateCommand` confirmed in the installed jar).

