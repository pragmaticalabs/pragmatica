# Session handover — 2026-08-14: auto-heal had been dead for two days, and the 02w gate finally landed

**Branch:** `release-1.0.0-rc3` · **HEAD:** `e0e023828` · **NOT pushed** (21 commits) · **tree clean**

Follows `session-handover-2026-08-13-part2.md`, which handed over an uncommitted checkpoint-observability
QUAD, an unproven `02w-entity-crash` suite, and two unexplained observations. All three are resolved.
Two new issues came out of it, one of them serious.

---

## §1 READ THIS FIRST — #597: auto-heal never replaced a failed node, for two days, silently

`AutoHealConfig.DEFAULT.maxNodes()` was **`null`** instead of `Option.empty()`. Static initialisers run
in textual order; `DEFAULT` (line 35) reached the `NO_CAP` constant (line 152) through
`autoHealConfig(...)` while it was still null. Every provisioning path funnels through
`NodeLifecycleManagerRecord.capGuardedProvision`, whose first act is `maxNodes.fold(...)` — so **every
auto-heal replacement threw NPE and no failed node was ever replaced.**

**Regression window: `f2289b4cb` (2026-08-12, the #298 fleet cap) → fixed in `599811fda` (today).**

**Why nobody noticed, which matters more than the bug:** the NPE was swallowed by
`VirtualThreadScheduler.runGuarded` ("task recurrence preserved"), so

- the circuit breaker reported `consecutiveFailures: 0`, `tripped: false`;
- `/api/cluster/provisioning` reported `lastReason: NONE_PROVISIONING`, which means **"a provision is
  PERMITTED"** — it reads like health and is the opposite of what it sounds like. I misread it myself
  before checking `suppressionReason`;
- `armedForProvisioning`, `quorumSafe`, `reachedFullMembership` were all true.

Every observable signal said provisioning was working. Measured: `deficit=1` for 271s in a targeted
repro, and **~70 minutes** in a full `02w` run — which is what made `restore_cluster_baseline` declare
cluster B unrecoverable.

Fixed by reordering, plus `AutoHealConfigStaticInitTest` which pins the OBSERVABLE value rather than
declaration order (5/5, module 59/0). **Live-verified**: killing a non-leader now yields a replacement
container at **t=41s** and the cluster returns to `NO_DEFICIT`, where the same kill previously sat at
`deficit=1` indefinitely.

**Two consequences for whoever picks this up:**

1. **#597 MASKED #509.** #509 is deficit-fill firing too EAGERLY (found 2026-07-24, before the
   regression). Since 2026-08-12 nothing provisioned at all, so #509 could not reproduce. Fixing this
   un-masks it — do NOT read "#509 no longer reproduces" as evidence it was fixed. Commented on #509.
2. **Anything exercised 08-12 → 08-14 that depended on a node being replaced was silently not doing
   so.** The previous handover's `restore_cluster_baseline: only 3 core(s) reporting READY` is
   consistent with it.

**Left open on #597, deliberately** — the ordering bug was one line, the two days of silence were the
swallow: `runGuarded` should count a provisioning throw toward the circuit breaker so
`consecutiveFailures` stops being a false negative. Also flagged: `NONE_PROVISIONING`'s misleading
name, and that a null `Option` component is representable at all.

## §2 #345 I3's SIGKILL gate is CLOSED

`02w-entity-crash` passes: **56 ACKED entities survived a `docker kill` of the owner with creates in
flight, 0 missing, 0 corrupted** (suite 1 passed / 0 failed). Evidence tags upgraded from
`design intent` to `integration-verified` in CHANGELOG, feature catalog row 217, and the plan doc.

The checkpoint observability QUAD from the last session is committed and **live-proven**:
`node-1=222w/0f node-2=44w/0f node-4=143w/0f`.

**Both of the previous handover's "unexplained observations" were my own harness bugs, not product
faults** — worth internalising, because both looked like product faults:

1. **"Only 4/40 creates ACKED."** The suite drove the LB-fronted app endpoint, and the LB PINS — all 42
   failure bodies carried the SAME `"instance"` id. Every write reached one node, which owns ~1/5 of
   partitions, and the entity write path does not forward. Fixed by rotating across per-node app
   endpoints resolved via `host_port_for_container`. Ack rate **4/40 → 40/40**.
2. **"No node reported an entity keyspace."** `/api/entity/checkpoints` is a LOCAL route and `api_get`
   does NOT rotate — `_resolve_live_endpoint` only walks other nodes once the pinned one is DEAD. The
   test asked ONE node ten times. Fixed with a per-node sweep; writes must be summed CLUSTER-WIDE,
   because a node hosting the keyspace while folding no partition correctly reports zero.

A third, self-inflicted: the convergence probe re-created the SAME keys every poll, so after the first
success every later poll got `KeyAlreadyExists` and counted it a failure — convergence was unreachable
by construction, and its 481s timeout said nothing about ownership. Fresh keys per poll now.

## §3 New issues filed

- **#596 — a durable entity is reachable ONLY on its partition owner.** No owner-forwarding for writes
  or bounded-stale reads, and no client-side re-resolution. `NotCurrentOwner` documents itself as
  "stable, the caller re-resolves", but nothing gives a caller a way to. Behind the shipped ingress 37
  of 40 creates were refused; cross-node slice code is refused for the same reason (there is no remote
  `DurableEntity` implementation — only `Fenced`/`InMemory`/`PartitionFenced`). Streams already solved
  this with `ForwardingReadRouter`; `DurableEntityError.java:95` says the entity equivalent "is a
  follow-up". The plan doc's gap list recorded only the READ half; the write half is worse.
- **#597** — above.

## §4 Other tickets worked

| Issue | State |
|---|---|
| **#539** (blocking) | FIXED `fefb88f87`. `aether cluster migrate` now pre-flights `CLUSTER_MIGRATE_PLAN` and only prompts if it succeeds, so an operator is never asked to authorise a migration the server cannot perform. Its premise was partly stale — #525 already replaced the 404 with an honest 501. Acceptance criterion 2 (a general guard) is NOT done: a static scanner would misfire on this very command, which still *references* `CLUSTER_MIGRATE` and merely cannot reach it. The property is behavioural. |
| **#432** | RESOLVED by amending the spec to the shipped surface (v0.4.0, `65e422856`). **Marked as my call** — the issue assigned it to the design stream. Decided on completeness: the pinned six-case set was INCOMPLETE, not merely misnamed (shipped surface is ten cases), so the spec had to absorb them either way. Reverting is a spec edit, no code churn. Book `part3-playbook.md` 1207-1214 still teaches the old names and needs updating. |
| **#592** | VALIDATED, NOT implemented — it flips structural. `extractZone` returns everything before the last dash of the NodeId, so `aether-b-node-2` yields zone `"aether-b-node"`; zone-awareness is INERT, not merely inaccurate. But the obvious fix (read `SwimMember.labels`) is a trap: labels are populated on the ANNOUNCE path only and **dropped on every membership update** (`applyNewMember` 1760, state-change 1878 both use the 4-arg factory), so zone would flap to default on any SUSPECT/ALIVE transition — worse than today's stable-but-wrong behaviour. Three options documented on the issue. |
| **#296** | VALIDATED, NOT implemented. Half is already fixed (`UserDataTemplate:228` emits `role.value()`). `BootstrapPhaseDeploy.buildRestartCommand` still hardcodes `-l aether-role=core`, production-reachable but only on the NON-discovery path (`discoveryAssembly` short-circuits the re-launch for the common single-core-source shape). Fix needs role threaded into `ProvisionedNode` — do NOT recover it by splitting the nodeId, that is #592's defect. Cheap to verify: `buildRestartCommand` is a pure string builder with existing unit tests. |

## §5 Traps found this session — do not re-learn these

- **`pgrep -f "build.sh"` matches the waiter process itself.** My "wait until the build exits" loop
  contained the string `build.sh`, so it matched itself and waited forever; I then misread a FINISHED
  build as RUNNING. The build-runner agent hit the same thing and returned a "will report later" that
  never came. Wait on a log MARKER (`until grep -q VERDICT log`), not on a process name that appears in
  your own command line.
- **`.claude/worktrees/` holds 11 worktrees** — a `find`/`grep` trap exactly like `.ndx/`. A bare
  `find . -name ProvisionedNode.java` returned the WORKTREE copy first and I nearly read a stale record
  definition. Scope to `aether/` or exclude the directory.
- **The integration-test shell linter greps line-wise and does not skip comments.** Rule R2 flagged a
  COMMENT of mine that contained the literal `2>/dev/null || true` while explaining why the code
  avoids it. Reword rather than baseline.
- **There are two lint baselines both at 49** — JBCT Java (error-gated, no numeric baseline) and
  `aether/tests/integration/lint-baseline.txt` (the numeric 49). A report saying "49, 0 new" may be
  about either; say which.
- **`NONE_PROVISIONING` means PERMITTED.** See §1.
- `host_port_for_container <name> <inport>` resolves a node's host-mapped port via `docker port`; use
  it rather than deriving `base + index` (host mapping differs per cluster: A→8070.., B→8080..).

## §6 The full run, and the harness defects it exposed

A full 15-suite run (`--env remote`, 13320s) landed **13 suites green, 4 with failures** — and every
failure was root-caused to something other than the day's product changes:

| Suite | Cause |
|---|---|
| `06-deployment` 2p/3f | **#598** — parallel cluster-A suites race for the cluster-global `database` datasource; the loser gets 409 and its tests fail four steps later with an unrelated signature |
| `03-scaling` 2p/1f | Known baseline, documented verbatim in earlier handovers: stalls at 6, 0.00% error rate, data intact, self-resolves |
| `02-chaos` 6p/1f | CTM now provisions a replacement that **never joins membership** — the next link in the chain, visible only because #597 made provisioning work at all |
| `02y` / `02w` | Harness pinned app traffic to a killed node |

**The run's real value was finding three defects in work landed EARLIER THE SAME DAY**, none of which a
standalone green run could reveal:

1. `02w`'s endpoint resolver enumerated by NAME PATTERN (`aether-b-node-{1..5}`), missing CTM's
   ULID-named replacements. Those DO publish app ports — on ephemeral high ports (36647), not the
   compose-fixed 8080..8084 range. `first_seed_host_app_port`'s comment claiming they "carry no
   host-mapped app port at all" is outdated.
2. Endpoints were resolved ONCE. After the suite's own kill every call burned `_api_call`'s 30s
   timeout against a dead port, turning a 360s budget into 1254s and a 480s budget into 4990s.
3. The readiness probe still used the PINNED endpoint — the actual reason `02w` failed. The slice was
   healthy; the probe could not reach it.

Fixed by `node_app_endpoints` (lib/cluster.sh), which enumerates running containers dynamically and
resolves each one's host-mapped app port. **Both suites are now green against it:**

- `02w-entity-crash` **1p/0f in 220s** (was 6524s): 5 endpoints, 40/40 creates ACKED, **59 ACKED
  entities survived SIGKILL with exact values**, checkpoints on 4 nodes.
- `02y-stream-crash` **1p/0f**: 40/40 pre-kill publishes, **79 ACKED events survived, 0 missing**, all
  4 partitions contiguous and ordered.

**The two suites needed OPPOSITE fixes, and this is the part to carry forward.** `02w` needed
ROTATION — entities are owner-pinned with no forwarding (#596), so the client must find the owner.
`02y` needed STICKINESS with failover — a keyless publish round-robins from the publisher instance's
own counter, so driving every publish through ONE port is what spreads events deterministically across
partitions (`StreamSlice`'s header says so). Rotating publishes there would have silently broken the
"events spread across partitions" and contiguity assertions that ARE #508's evidence. Only endpoint
DISCOVERY is shared; the calling policy is per-suite and must be derived from what the suite asserts.

A first `02y` run reported 39/40 publishes with no explanation, because the version I wrote wrapped
both attempts in `2>/dev/null` — reintroducing, hours later, the exact silent-stderr trap I had spent
the morning removing from `02w`. With the diagnostic restored the re-run came back **40/40**, so that
one was environmental. The lesson is the suppression, not the flake.

## §7 In flight at handover

Nothing running. The remote host is clean (0 containers, 0 networks).

Next, in the order I would take them:
1. **#596** — blocked on a DESIGN call, not effort: entity `update` takes `Fn1<S,S>` and a lambda
   cannot cross nodes, so forwarding is either caller-routing or a command-shaped API. Options and a
   recommendation are on the issue.
2. **#598 item 3** — `publish_blueprint_or_fail` got a 409 and its test still PASSED. Both it and
   `publish_blueprint` look correct on inspection, so something between the 409 and the return value
   swallows it. A helper named `_or_fail` that does not fail is a truthfulness bug in the harness.
3. **#509** — un-masked by #597; re-test deliberately rather than trusting "does not reproduce".
4. **02-chaos's replacement-never-joins failure** — the next link the auto-heal fix exposed.

`02w-entity-crash` restarts the node it killed in `cleanup` (`start_node`), because cluster B is
`restart: "no"` and `restore_cluster_baseline` only escalates to a full restart when NO leader is
reachable — a single-node kill leaves the leader healthy, so it waits out its budget on a node that can
never return. **Other destructive suites do not do this**: `02-chaos` (6 kills), `13-edge-cases` (3),
`02y-stream-crash` (1) all kill without restarting; only `12-network` is symmetric.

## §7 Standing hazards (carried forward)

- `HCLOUD_TOKEN` is set. `mvn verify`/`install` reach `HetznerCloudIT`. Safe spellings: `./build.sh`, or
  `mvn -pl <module> test -DskipITs`.
- The `aether` CLI on PATH is rc2 and aborts the harness at version-parity preflight. Pin `AETHER_BIN`
  to a launcher that execs `aether/cli/target/aether.jar`.
- `.ndx/` is 144 GB — exclude from every repo-wide sweep. Now joined by `.claude/worktrees/`.
- #250 storage GC — DO NOT WIRE naively.
- 16 commits unpushed; nothing pushed this session (no request to).
