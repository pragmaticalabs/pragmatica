# Session handover — 2026-08-13 (part 2): #345 I3 landed; its SIGKILL gate did not

**Branch:** `release-1.0.0-rc3` · **HEAD:** `73f07d0dc` · **NOT pushed** · **tree NOT clean** (see §3)

Second arc of 2026-08-13, following `session-handover-2026-08-13.md` (cost guardrails + #593). That
handover named **#345 I3 as the rc3 blocker**. I3 is now built and live-verified for FAILOVER. Its
SIGKILL tier is written, run once, and **not established** — the run exposed defects in my own suite plus
two unexplained product observations.

---

## §1 What landed (committed, verified)

Four commits, ordered so each compiles standalone:

| Commit | What |
|---|---|
| `889cb1b0d` | `chore:` jbct formatter on sources #298 left unformatted (reflow only, zero semantic change) |
| `5567c7970` | `fix:` retention floor — entity segments no longer deleted by the node-wide age policy |
| `888379916` | `feat:` durable entity state on a fenced replicated log; survives losing its owner |
| `73f07d0dc` | `docs:` I3 landing, plan corrections, known gaps |

**Verification behind them:** `build.sh` green (lint 49, all baseline, 0 new) · `DurableEntityForgeTest`
**11/11 on a live 5-node Ember cluster** · slice 731/0, stream 660/0, entity 130/0, node 848/0, cli 652/0.

**Why the Forge gate is strong:** it replaced a test that asserted the OPPOSITE — the pre-existing
`state_isUnrecoverable_afterTheOnlyNodeHoldingItStops` ("one graceful stop destroyed it permanently").
The assertion is discriminating by construction, not by a mutation I chose.

Three properties are additionally **mutation-proven** (disable the code, the test goes red, verified):
gap refusal, contiguous checkpoint watermark, retention floor.

## §2 The design, in one paragraph

Entity keyspaces are real streams named `entity:<keyspace>` — the SAME coordinate the write fence,
linearizable reads and I1's ownership records already key on, so narrow-C's records needed no migration.
A write appends through the fenced, fsync-durable, replicated `publishLocal` path and does not ack until
`minSyncReplicas` hold it. In-memory state is a FOLD of that log. Checkpoint BLOCKS go to stream storage
(DHT-backed, cluster-reachable); the checkpoint POINTER goes to consensus KV, because stream storage's
refs and `SegmentIndex` are node-local and a ref would strand the checkpoint on its writer.
`RetentionEnforcer` gained a recovery floor so the tier cannot delete what is not yet folded.

**Authority for this epic remains `issue-345-implementation-plan.md`**, whose I3 section I rewrote with
what building it corrected — including that "governor owns the fold" (spec §4.4) was NOT followed
(owner-driven; the owner already holds the state) and that the registry did NOT need `replicationFactor`.

## §3 UNCOMMITTED — decide before doing anything else

The tree is dirty. Two coherent bodies of work, both building and linting clean, **neither committed**:

**(a) Checkpoint observability, QUAD-complete.** `EntityCheckpointDriver` records successful writes and
per-partition checkpointed offsets; `GET /api/entity/checkpoints` (LOCAL); `aether entity checkpoints`;
docs in `management-api.md` + `cli.md`; dashboard recorded as an explicit **dormant slot** (summing
`writes` across nodes answers no operator question — revisit if a cluster-wide "stalled checkpointing"
alert is wanted, which IS aggregatable).
Files: `ManagementRoute.java`, `ManagementApiResponses.java`, `ManagementServer.java`, `AetherNode.java`,
`EntityCheckpointDriver.java`, `EntityCheckpointRoutes.java` (new), both doc files.

**(b) The `02w-entity-crash` suite** + its registration in `CLUSTER_B_SUITES` (`run-tests.sh:85`).

I asked whether to commit these flagged-unproven and did not get an answer. **My recommendation: commit
(a) — it is verified and independently useful. Hold (b) until it produces a real result**, or commit it
with an explicit "unproven" note, because a suite that has never passed is not evidence of anything.

## §4 The SIGKILL run — what actually happened

`./run-tests.sh --env remote --suites 02w` → `02w-entity-crash 0p/1f (527s)`. Correctly failed. But:

**MY TEST HAD A FALSE PASS, and it is the important finding.** `pick_non_leader` REQUIRES an observed
leader (its own docstring: callers must `wait_for_leader` first). I passed none → fail-fast → no node
identified → **the SIGKILL never ran** → and then:

- `Failover_completed` → PASS *"cluster settled after SIGKILL"*
- `Every_ACKED_entity_survives_the_crash` → PASS *"all 4 ACKED entities survived the crash"*

There was no crash. I had built #508's non-vacuity gate against an empty ACK SET and missed the larger
precondition — that the crash happened at all. **Fixed** (uncommitted): a `KILL_CONFIRMED` flag now gates
both assertions, and the leader is observed before picking. Lint clean, helpers verified to exist.

**Two unexplained observations — diagnose these FIRST next session:**

1. **Only 4 of 40 creates ACKED; ownership never converged in 480s.** In Forge, twelve probe keys
   converged in ~100s. Candidates: the `minSyncReplicas=2` barrier not satisfiable on the docker cluster,
   reconcile latency, or my `create_entity` retrying only 10 round-robin attempts. **Unknown which.**
2. **No node reported an entity keyspace** on `/api/entity/checkpoints`, though registration happens at
   provisioning on every node hosting the slice (it logged on all 5 in Forge). Either the surface is not
   reachable in the docker image or registration did not happen.

**A third defect in my suite blocks diagnosing (1):** its helpers swallow response bodies with
`2>/dev/null`, so the test cannot explain its own failure. **Remove that before re-running** — otherwise
the next run produces the same uninformative log.

## §5 Exact next steps

```bash
# 1. Pin a fresh rc3 CLI — a stale ~/.aether/bin/aether (rc2) aborts the run at preflight.
#    A launcher that execs aether/cli/target/aether.jar is enough; verify `aether --version` says rc3.
AETHER_BIN=<path-to-rc3-aether> ./run-tests.sh --env remote --suites 02w
```

Before that run: delete the `2>/dev/null` in `create_entity` / `read_amount` and log the failing body, or
(1) stays undiagnosable. The teardown is SAFE for `--env remote` — the bare cloud reap is gated on
`CLOUD_RESOURCES_PROVISIONED`, which remote-docker never sets, and the reaper default-protects test-pg.

## §6 Known gaps in what SHIPPED (already recorded in the plan doc and feature catalog)

1. **SIGKILL crash durability for entities — NOT established.** Forge proves failover; it structurally
   cannot prove crash durability (`stop()` always closes the WAL, which is why #508's evidence lives in
   docker). The catalog and plan doc both say so; nothing in the repo claims otherwise.
2. **`BOUNDED_STALE` reads are not forwarded.** A node outside a partition's replica set refuses with
   `PartitionNotHeld` rather than lying with `absent`. The LINEARIZABLE path already routes to the
   committed owner; bounded-stale does not — so read availability is the replica set, not the cluster.
3. **Simultaneous restart of the owner AND every replica** can leave the post-checkpoint tail recoverable
   only on the original owner; ownership landing elsewhere refuses loudly rather than losing data quietly.

## §7 Traps found while building — do not re-learn these

- **`RetentionPolicy.maxCount` is the RING CAPACITY.** `buildRing` passes it straight to
  `OffHeapRingBuffer`; `floorBytes = HEADER + 24*capacity + firstSegment`. `Long.MAX_VALUE` OVERFLOWS
  into a ~40-byte control segment and throws on the first index write. It is not "retain everything", it
  is "cannot allocate". Also: the 100k default × 64 partitions ≈ 154 MB of index per keyspace.
- **Codec tags collide.** `(fqcn.hashCode() & 0x7FFFFFFF) % 16256 + 128`. `EntityCheckpointValue` hit
  tag 7612, already `HealthHintWire`'s, and poisoned `NodeCodecs` static init (48 unrelated test errors)
  — **invisible to the owning module's own 731-test build**, because only the full node assembly
  registers both. Renamed `EntityFoldCheckpointValue`. `@Codec.tag()` exists but is **dead surface**.
- **`mvn -f <module> test` does NOT install.** Downstream (forge, integration) keeps resolving the
  previous jar and fails with a believable message about the new feature. Cost one wasted Forge run.
  Verify by `javap` on the CONSUMER's bytecode — and pick a probe that DISCRIMINATES (checking for a new
  CLASS proves nothing when only its WIRING is new; I made that error and nearly "confirmed" a correct
  conclusion for the wrong reason).
- **`ReplicaSetController.reconcile()` is event-driven only** — the entity tick's minting half was NOT
  retired as narrow-C's expiry note implied. Retiring it strands a keyspace deployed into a steady-state
  cluster with writes refusing forever.
- **BSD sed has no `\b`** — a rename "succeeds", exits 0, and changes nothing. Verify by grepping for the
  OLD name expecting empty.

## §8 Standing hazards (carried forward, still true)

- `HCLOUD_TOKEN` is set. `mvn verify`/`install` reach `HetznerCloudIT`. Safe spelling: `-DskipITs`.
- The local `aether` CLI on PATH is **rc2** and aborts the harness at version-parity preflight. Pin
  `AETHER_BIN`. (The harness catches this cleanly BEFORE bootstrap — it cost seconds, not a cycle.)
- `test-pg` unprovisioned; `.ndx/` is 144 GB and must be excluded from every repo-wide sweep.
- #250 storage GC — DO NOT WIRE naively.
