# Session handover — 2026-08-13 (cost guardrails, then a status surface that lied)

**Branch:** `release-1.0.0-rc3` · **HEAD:** `f8426a6e6` · pushed · tree clean
**Candidate tag:** `v1.0.0-rc3-candidate` → `f8426a6e6` (current)

Two arcs. Part A: #345 I2 grounded and closed as satisfied-by-prior-work, then the #298 cost-guardrail
family. Part B: the #508 crash-durability test, which surfaced three defects that had nothing to do
with durability — the last of them a replica view that reported healthy owners as permanently broken.

---

## §1 What landed

| Commit | What |
|---|---|
| `1c49a06f5` | #345 I2 satisfied by prior work; 7 plan-doc divergences + 5 stale citations corrected |
| `8611518be` | Stream fence gate executed green (2 tests, 36s, non-vacuous) |
| `f2289b4cb` | #298 operator fleet cap at the provisioning chokepoint |
| `d9e61fb0a` | Dead `CloudProvider` SPI + `QuotaStatus` deleted; 6 spec references corrected |
| `89aebdef4` | SWIM cross-cluster ANNOUNCE gate armed, upgrade-safe on both sides |
| `3b171bec5` | `[cluster] max_nodes` wired end to end; `Main` finally applies autoHeal config |
| `08ab6e3fb` | Cloud cost guard aborts; estimate × fleet size × instance type |
| `ceca60c2b` | #508 crash-durability test in its own suite (`02y-stream-crash`) |
| `490297d86` | #594 harness scale payload matches post-#581 contract |
| `75a0e76eb` | #593 replica view answers a node's own row from local truth |
| `f8426a6e6` | SSH roundtrip out of the chaos poll loop; wait bounded by wall clock |

## §2 #345 I2 — nothing to build

The stream-path fence **landed 2026-07-16**. The plan doc described it as MISSING and carried five
citations pointing at wrong lines; an implementer trusting it would have rebuilt a working fence.
Verified at HEAD: gate at `StreamPartitionManager:1221` (ahead of `buffer.append` and the WAL fsync),
armed on real nodes (production factory wraps the high-water `Option.some(...)`), ownership
consensus-written by a leader-gated driver. Gate `StreamOwnershipDriverFenceTest` ran green.

**Fourth time on this epic that reading code beat reading the plan.** The doc is corrected in place
with dated notes rather than silent overwrites.

**I3 is the next increment**, and it is *not* blocked by #349 — see §6.

## §3 The #298 family — four defects, one theme

The ticket asked for a quota cap. Following it literally would have shipped a gate that could never
fire: `QuotaStatus.unknown()` sets `sufficient = true` and all five providers returned exactly that,
on an SPI (`CloudProvider`) with **no production consumer at all**. There is no bulk provisioning
path — fleets grow one node per call through `ComputeProvider.provision(spec)`.

What shipped instead: a live fleet-count cap at `NodeLifecycleManager.provisionNode`, the single
chokepoint all provisioning funnels through, scoped by the `aether-cluster` tag. **Opt-in with no
default** (a default cap would silently refuse on any cluster already larger than it). **A failed
count refuses rather than provisions.**

Then the dead SPI was deleted (8 files) with its spec references corrected, and two more inert
guards were found and armed:

- **SWIM's cross-cluster ANNOUNCE gate** was fed the empty-string "disabled" sentinel on every node.
  Armed with a both-non-empty refinement that makes it upgrade-safe; mutation-checked (the original
  single-sided condition demonstrably rejects an un-upgraded peer).
- **`[operations.auto_heal]` never reaches a node** — parsed into `AutoHealSpec` and rendered
  nowhere. `max_nodes` now has a delivered path; the other three fields remain inert and are
  documented as such in `bootstrap-config.md`. **Not filed as an issue — worth doing.**

**Cost guardrails (from an external report, all verified):** `MAX_CLOUD_HOURS` printed a WARNING and
fell through — now aborts. The cost estimate ignored fleet size *and* instance type (`echo "0.071"`)
— now per-type × node count captured **before** deletion. Note the old code was wrong twice in
opposite directions, which is why nobody noticed: a 2h/100-node `cx23` run reported €0.14 against a
true ~€1.50.

## §4 Part B — #508, and the status surface that lied

The test itself was straightforward and its result never wobbled across four runs and four cluster
conditions: **80 acked, 0 missing; 40/40 publishes ACKED across a SIGKILL; all four partitions
contiguous.** It runs in **63s** in its own suite.

Getting there took ~16 runs and exposed four defects, none in durability:

1. **#594 — `restore_cluster_baseline` never rescaled.** The harness sent `{"coreCount":N}`; the
   endpoint has taken `(source, role, count, expectedVersion)` since #581. Every call 500'd behind a
   WARN reading *"cluster may already be at target — proceeding"*. **Read past ~40 times across nine
   runs.** Fixed; verified `HTTP 200` with `configVersion` incrementing.
2. **#593 — a node's own replica row never advanced.** `registerReplica` seeds every descriptor at
   `SYNCING`/`confirmedOffset = -1`; only `updateWatermark` advances it, driven by acks **from
   peers**. A node never acks itself, so **every owner reported itself permanently un-synced while
   serving complete data**. Live evidence: owner `SYNCING`/`-1` with `ownerHeadOffset: 24`, a peer
   `CAUGHT_UP` at 23, and all 24 events readable in order. Fixed; owner now reads `CAUGHT_UP` at the
   ring head.
3. **#595 — closed as fixed-by-#593** on 5/5 green against a 2-in-10 baseline. Statistics stated on
   the issue: 5 passes is consistent with a 20% failure rate about one time in three, so the close
   rests on the mechanism argument, not the count.
4. **Poll-loop cost (`f8426a6e6`)** — `topology_events_since` did an SSH roundtrip per iteration of a
   1-second loop, inside a step whose every branch is a `log_warn`. Turned a 240s budget into 3176s.

**#593 is the important one.** It produced, in sequence, an apparent "position cliff", a phantom
cross-test interaction, a suspected replacement-node bug class, and a suspected identity bug. All
four were my framings; all four were killed by evidence. One unadvanced row caused every symptom —
the "cliff" correlated with chaos volume only because more kills mean more freshly-assigned owners,
and every fresh owner displayed the stale row.

## §5 Process findings worth carrying

**Assert on what the system did, not on what it says about itself.** #508 passed 11/0 through all of
this because it asserts on acks, event presence, and offset contiguity. `test-stream-replica-failover`
failed repeatedly on healthy clusters because it gates on `servedByOwner` and convergence fields.
Same cluster, same moment, opposite verdicts.

**A test that fabricates state the system cannot produce is not testing the system.** The
pre-existing `StreamReadRouterReplicaSnapshotTest` called `updateWatermark(..., SELF, ...)` by hand —
a call production never makes — and passed continuously against the broken behaviour. Mutation-checked:
disabling the fix turns only the new production-realistic test red; the fabricating one stays green.

**My own test had a vacuous pass in its core assertion.** With a failed deploy it reported
*"Every ACKED event survived the crash (0 acked, 0 missing)"* as PASS. Now gated on a non-zero count.
It surfaced only because a deploy failure handed it an empty input.

**Warnings that assert a cause they never checked hide the failure.** Two instances: #594's
*"may already be at target"* and the drain-poll's *"within 60s"* after 3176s. Both cost real
investigation time. A message stating a budget should state what actually happened.

**Verify what produced a reading.** Three probe errors: `pgrep -f` matching its own shell; a monitor
timing out on a build that had already succeeded; and — the costly one — reading `servedByOwner` off
the **delegate-routed** `/api/streams/replicas/{name}/{p}` while believing I had queried the owner.
#490 added the `/local/` variant for exactly that trap. That error reached a filed issue as a claimed
product defect before it was caught.

**`RUN7_EXIT=0` was a false green** — `--suites 02,02y` silently matched only `02`, because
`CLUSTER_B_SUITES` is a hardcoded prefix list. An exit code from a suite that never ran.

## §6 Corrections to prior documents

- **#349's "current durability" table is substantially stale** (comment posted). Three of four cited
  breaks are fixed at HEAD: sealing is wired (`AetherNode:3054`), `rebuildFromRefs` is called
  (`:3040`), `LocalDiskTier` is in the stream tier chain (`StorageFactory:138`). Only the
  `MemoryStorageEngine` row still holds. **Consequence: #345's I3 does not wait on #349 — I3 is what
  closes its last row.**
- **#593/#595 framings** corrected on the issues rather than edited away.
- **`cluster-bootstrap-spec.md`** (5 refs) and **`harness-resilience-spec.md`** (which pointed at a
  module `aether/aether-cloud` that does not exist) corrected alongside the SPI deletion.

## §7 Open items

1. **#345 I3** — the rc3 blocker. Two grounding questions answered this session: the fold-to-snapshot
   /tail machinery **does not exist** anywhere (the `durable-entity` module has no persistence code;
   `integrations/storage`'s `SnapshotManager` checkpoints storage *metadata*, not application state),
   and #349 is not a prerequisite. I3 moves entity state onto the already-disk-backed,
   already-fenced stream substrate. Its gate is free: the main property proves in Forge, the residual
   crash-mid-fsync boundary is #508's tier on the LAN host.
2. **`[operations.auto_heal]` remains inert** for `retry_interval` / `startup_cooldown` / `enabled`.
   Decide: give them a `node_config` path, or delete the section as was done for `CloudProvider`.
3. **QUAD invariant should extend to the integration harness.** #581 updated REST, CLI, docs and
   dashboard — the harness is a fifth consumer of the same contract and was missed, which is #594.
4. **`aether/tests/cloud/` pins `cx22`**, deprecated in Hetzner's June 2026 change (superseded by
   `cx23`). Provisioning-failure risk on a paid run. Owner has pre-authorised `cx23` + `max_nodes = 8`.
5. **Hetzner budget ($20) unspent.** The LAN host answered everything. Spend it on what the free tier
   genuinely cannot: real cross-host network faults, or #365's multi-node performance numbers.

## §8 Standing hazards (carried forward)

- `test-pg` unprovisioned. Before ANY cloud run: `tools/provision-test-pg.sh --print-only` and grep
  the harness teardown for destructive calls.
- #250 storage GC — DO NOT WIRE naively; the node-local refcount view authorises deletes on the
  shared DHT tier.
- `HCLOUD_TOKEN` is set on the dev machine. `mvn verify` / `mvn install` reach failsafe and
  `HetznerCloudIT`. Safe spellings: `-DskipITs`, or `mvn -f <module>/pom.xml` to stay out of the
  reactor.
- The LAN build host (`$TARGET_HOST`) now carries Maven 3.9.12 user-local at `~/apache-maven-3.9.12`
  and a full checkout at `~/pragmatica`; `run-build.sh` / `run-fence.sh` are reusable launchers.
  Its `aether` CLI on PATH is **rc2** and fails the version-parity preflight — pin `AETHER_BIN` to a
  freshly-built rc3 CLI.
- `.ndx/` is 144 GB and must be excluded from every repo-wide sweep and transfer.
