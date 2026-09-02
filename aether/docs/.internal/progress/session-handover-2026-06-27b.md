# Session Handover — 2026-06-27b (A6 crash-durability: root cause CORRECTED → read-routing, not backfill; fix decided, not yet implemented)

**Branch `release-1.0.0-rc2` · HEAD `cec38e6ac` · NOT pushed.** No commits this session — this was an **investigation + decision** session. Continues `session-handover-2026-06-27.md` (the streaming-persistence Phase A / A-WAL session).

## ⚡ TL;DR
The A6 gate (`StreamCrashDurabilityTest`, full-cluster-restart WAL crash-durability proof) was tracked-red and the prior handover blamed a `PartitionBackfill` "owner self-promotes CAUGHT_UP at a low watermark, exempt from redrive" race, said to be unblocked by aether-clone's S20 work. **That diagnosis is wrong on every count.** Ground-truthing the code + a live run produced the real root cause:

- **WAL durability WORKS.** On restart the HRW owner replays its WAL to **ring head 49** and self-promotes `CAUGHT_UP@49` (confirmed in a live run). Events are not lost.
- **Reads are NOT watermark-gated.** A stream read is served straight from the local ring (`OffHeapRingBuffer.readChecked` — consults only head/tail). The promotion watermark is irrelevant to what a read returns.
- **`test-events` is RF = 1.** APP-stream RF = `clamp(spec.minSyncReplicas(), 1, clusterSize)`; `test-events` omits `minSyncReplicas` → `clamp(0,1,5) = 1`. **Only the HRW owner is a replica of partition 0; the other 4 nodes are non-replicas by design** (confirmed: only the owner ever logged any `test-events` activity).
- **The slice reads `ReadPreference.GOVERNOR`** (hardcoded in all `PartitionedStreamAccess` factory overloads), and **GOVERNOR never forwards** (`ForwardingReadRouter.route` → `case GOVERNOR -> localReader.read(...)`). The test drains from an arbitrary `appPort()`; when that lands on a non-replica node the read returns **0**, no fallback. Intermittent (~3/6) = which node `appPort()` hits.

**Decided fix (user-approved): wire app-stream reads as `NEAREST` instead of `GOVERNOR`** so a read on a non-replica node forwards to the HRW owner (which holds the WAL-recovered data). **Not yet implemented.**

## Why this is a real product fix, not a test hack
The owner-forward machinery **already exists and is already wired** — the GOVERNOR arm just ignores it:
- `ForwardingReadRouter` (class doc, steps 3 & 5): `ANY_REPLICA`/`NEAREST` → forward to a locally-known CAUGHT_UP remote replica, else **forward to the deterministic HRW owner** (every node computes the same owner; owner self-promotes first at cold-start), failing soft to a local read.
- `PartitionedStreamAccess` already holds `forwardClient` + `ownerResolver` (the publish path uses them so a non-owner producer write-forwards to the owner). The read path with GOVERNOR is the asymmetry.
- The code's own SYSTEM-stream comment (`ReplicaPlacement.systemReplicationFactor`) states the design intent explicitly: APP non-replicas **"must forward-read."** GOVERNOR-local-only as the app default means **every** app-stream consumer deployed on a non-replica node silently reads empty — a real footgun for an essential subsystem, not just this test.
- `NEAREST` keeps owner/replica-local reads local (zero overhead for the common case); it forwards **only** on a local miss.

## The fix — exact change (next session implements)
1. **`aether/aether-stream/.../PartitionedStreamAccess.java`** — the APP-stream factory overloads hardcode `ReadPreference.GOVERNOR` at lines **124, 159, 201, 294, 321, 349**. Flip the **app-read-facing** ones to `ReadPreference.NEAREST`. ⚠ Verify each site is an app-stream read overload wired by `StreamAccessFactory` (not an internal/other path) before flipping — don't blindly sed all 6. The A6 durable overloads (the `A4+A5` owner-routed + tiered ones, ~line 169–211) and the A6 app overload (~134–167) and the base (~124) are the consumer-read ones.
2. Confirm `StreamAccessFactory` passes through (or stops forcing GOVERNOR). The `forwardClient`/`ownerResolver`/`replicaRegistry` are already wired — NEAREST will use them.
3. **Do NOT touch `ForwardingReadRouter` internals** — aether-clone is actively rewriting its `LINEARIZABLE` arm for #277/#345 (their `+178`-line change). The `GOVERNOR`/`NEAREST`/`ANY_REPLICA` arms are "byte-for-byte identical" per their note. Collision check done: **clone has NOT touched `PartitionedStreamAccess` or `StreamAccessFactory`** → this fix is collision-free.
4. Optional consistency follow-up: the management `StreamRoutes.readEvents` (`StreamRoutes.java:279`) also defaults to GOVERNOR but allows a `?readPreference=` override — leave as-is or align separately; not required for A6.

## Verify (the gate)
- Re-enable: remove `@Disabled` (and the now-restored `import org.junit.jupiter.api.Disabled;`) from `StreamCrashDurabilityTest`.
- Run repeatedly — it was intermittent, so need ~5–6 consecutive green:
  `env -u HCLOUD_TOKEN mvn -Pwith-e2e -pl aether/forge/forge-tests integration-test -Dit.test=StreamCrashDurabilityTest -Dfailsafe.failIfNoSpecifiedTests=false`
  (`integration-test` alone does NOT fail the build on a test failure — verdict from the failsafe "Tests run: 1, Failures: 0, Errors: 0" line, not BUILD SUCCESS.)
- ⏱ **A FAILING run takes ~20–24 min** (it drains the full 240s recovery timeout, plus 240s ready awaits, ×restart). A PASSING run should be much faster (events found immediately once forwarding works). Budget accordingly; a loop helper was used this session (in scratchpad, ephemeral).
- Regression net: `aether-stream` unit suite (523/523) + `StreamFanoutConsumerTest` (5/5, WAL-off) must stay green. NEAREST must not change behavior when self IS a caught-up replica (the fan-out test's owner-local reads).
- Then reconcile §A6 in `streaming-persistence-implementation-plan.md`, update `feature-catalog.md` + `CHANGELOG.md`, run `./build.sh`, commit single-line (BSL-1.1 module). Update memory `project_streaming_persistence_built_but_unwired`.

## ❗ Corrections to the prior handover / cross-agent reconciliation
- `session-handover-2026-06-27.md` §"The ONE blocker — A6" is **wrong**: A6 is NOT blocked on the S20-class `PartitionBackfill` owner-promotion race, and **NOT** dependent on aether-clone. The owner promotes correctly (wm 49); the bug is read-routing.
- aether-clone's **#336 "S20"** work (in `../pragmatica-clone`, commits up to `19c562204`) is a **test-harness** quorum-restore fix (`lib/cluster.sh restart_all_nodes` — cloud/remote 02-chaos), validation pending (GitHub issue #362, cloud torn down — must re-provision PG before any cloud run). It has **zero** bearing on the in-JVM A6 read path. The two share only the "S20" label.
- Clone open PRs: **#356** (#277 PR1 observability seam), **#359** (#241 slice 3). Clone also did **#345 P2** (per-partition entity fence, lock-free per-key serial executor). None overlaps the A6 read-preference fix.

## ⚠️ Unexpected working-tree change (NOT mine — investigate before committing)
`git status` shows `aether/docs/specs/durable-entity-primitive-spec.md` **modified (+592/−110)**, mtime **23:38 (during this session)**, unstaged. **I did not touch it** — session start reported a clean tree. It's a large #345 durable-entity spec expansion (added §4.4/4.6/5.x/6.x/7.x), which is the clone's / a parallel session's domain. **Likely a concurrent session editing this same working tree** (multi-agent collab). Do NOT bundle it into the A6 commit; confirm ownership first. The only change I made this session is the corrected `@Disabled` annotation on `StreamCrashDurabilityTest.java`.

## State of `StreamCrashDurabilityTest.java` (my only edit)
Restored to `@Disabled` with a **corrected** annotation (the previous one documented the wrong root cause). It now states: WAL durability proven (owner→ring head 49); reads not watermark-gated; RF=1 so only owner is a replica; GOVERNOR never forwards → non-replica reads return 0; fix = NEAREST app-read. Tree is otherwise clean re: my work.

## Files to know
- `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/PartitionedStreamAccess.java` — **the fix site** (GOVERNOR→NEAREST).
- `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/StreamAccessFactory.java` — app-read wiring.
- `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/ForwardingReadRouter.java` — owner-forward algorithm (DO NOT edit; clone-contested).
- `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/replication/{PartitionBackfill,ReplicaRegistry,ReplicaPlacement}.java` — backfill/redrive/placement (the path the prior handover wrongly blamed; left untouched).
- `aether/forge/forge-tests/.../StreamCrashDurabilityTest.java` — the A6 gate (re-enable after fix).
- `aether/tests/blueprints/test-stream/` — the `StreamSlice` + `resources.toml` (`test-events`: partitions=1, no minSyncReplicas → RF 1).
