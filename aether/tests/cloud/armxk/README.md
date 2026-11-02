# Arm X + arm K cloud harness (#2512, q6 §7.4 P6)

Arm X confirms the M-1 counts (mutations by family, transactions and slots per event) on real VMs. Arm K measures the 5-core
cluster's sustained commit rate. Rulings: know `0d13d9f14c` and `4525b7b0df`, the USD 30 cap, Hetzner project "aether testing".
Test scope only: nothing here is a production artifact, and nothing here enters the Maven reactor.

## One command

```
# spends nothing: the whole run against a local multi-node cluster on one host
bash aether/tests/cloud/armxk/armxk.sh dry-run aether@<host> <checkout-on-host> [run-dir]

# SPENDS MONEY: CTO or owner only
with-hcloud env ARMXK_SPEND=yes bash aether/tests/cloud/armxk/armxk.sh cloud-run <build-host> <checkout-on-build-host> [run-dir]
```

`<checkout>` must have run `install -DskipTests -pl aether/node,aether/e2e-tests/echo-slice -am`. `cloud-run` provisions, stages,
runs, pulls the evidence after every class and tears down from an `EXIT` trap. Teardown ends only when the project's server count
is back at the count read before provisioning (`armxk.sh teardown-check <run-dir>`).

## The counting path across VMs

M-1 counts in-JVM: `M1Instrument` swaps the core KV store's router and the consensus engine's state machine by reflection. Across VMs
the same instrument runs inside each core process:

| Reading | Path |
|---|---|
| mutations by family, leader transactions accepted/refused, slots, batches, commands | `agent/M1ProbeAgent.java`, a `-javaagent` loaded into the stock `aether-node.jar`. `agent/build-agent.sh` compiles it **together with the checkout's own** `aether/ember/src/test/java/org/pragmatica/aether/ember/M1Instrument.java`, so the cloud and the in-JVM harness count with one source. Served on `127.0.0.1:<agent port>` (`/m1/snapshot`, `/m1/ops`). |
| decisions, proposals, round-1/round-2 votes, pending batches | `GET /api/v1/metrics/comprehensive` → `consensus` (`RabiaMetricsCollector`, `aether-metrics/.../RabiaMetricsCollector.java:24-145`; counters are monotonic, `snapshotAndReset` has no caller; `syncFailureCount` is never incremented, `RabiaEngine` records only successful syncs, so its 0 is not evidence) |
| load, CPU, RSS per host | `lib/node.sh sample` (`/proc/stat`, `/proc/loadavg`, `/proc/<pid>/status`) |

There is no production endpoint with per-family mutation counts: the Management API routes
(`aether-management-api/.../ManagementRoute.java`) and the metric names under `aether/*/src/main` were searched, and the only
consensus-commit metrics, `aether.consensus.commit` / `aether.consensus.batches` (`AetherMetrics.java:66-73`), have no caller. The
harness never reads them except in the dead-counter control below.

How the agent finds the node: `Main.run` keeps the `AetherNode` in a local and registers a shutdown hook whose lambda captures it
(`node/.../Main.java:143-145`, `:804-809`). The agent reads that hook from `java.lang.ApplicationShutdownHooks` (hence
`--add-opens java.base/java.lang=ALL-UNNAMED` in `lib/node.sh`) and walks it with the instrument's own graph search.

**Controls** (`control` step, before anything else is measured):
- *positive:* one `POST /api/v1/config` must count exactly one `put:ConfigKey` on **every** core, every rep; a dead path reads 0 and
  the run stops;
- *dead counter:* each core's `/metrics/prometheus` lines for `aether_consensus_commit` / `_batches` are recorded beside the live
  readings (expected 0 against non-zero live counts);
- *two independent slot readings:* the agent's `engine.slots` (state-machine proxy) and Rabia's `decisionsCount` are both recorded per
  window;
- a missing or failing agent is an error, never a zero (`Run.agent` raises).

## Admitting hand-started nodes (a fidelity choice — read before using the numbers)

A node that the cluster did not provision is never admitted: `WorkerHierarchyAssembly.workerAdmissionAllowed`
(`node/.../assembly/WorkerHierarchyAssembly.java:1043-1056`) needs an environment authorization or a `DISPATCHED`/`OBSERVED`
`CapacityReservation`, and a node with no compute provider has neither (a fresh-id core is held the same way, it stays `SYNCING`).
Ember admits its in-JVM nodes through a harness predicate (`EmberCluster.localWorkerAdmissions`). The cross-VM counterpart is the
agent's `/m1/admit`: on the leader it commits an `OBSERVED` reservation for the id the harness is about to start, by leader
transaction, always **outside** a measured window. Consequences: reservation records exist in the counted state (M-1 default mode has
none), and a restored core (S4) or replacement worker (S3) enters through a reservation, not through provisioning.

## What can create a server

Nothing the nodes run. No node is given a cloud provider section or a provider token; the committed cluster configuration carries an
`ssh` source only because the validator demands one with a core sub-table (CL-07); its hosts are unroutable `10.255.0.x`, its key
path does not exist and its image does not resolve. Auto-heal is also disabled through the live toggle
(`POST /api/v1/cluster/topology/auto-heal/disable`) after formation and again after every leader change, because the toggle is per
leader term and `[operations.auto_heal]` in TOML is refused (PF-25). Nodes are killed as processes (`SIGKILL`), never by deleting
VMs. Only `lib/provision-hetzner.sh` calls the provider.

## Topology

- 5 cores, one per VM (`core-host-1..5`), ccx13 (2 dedicated vCPU each, 10 in all, inside the recorded limit of 12). Arm K is a
  lower bound and criterion 2 is decided only in the direction a lower bound can decide, so a small core is conservative. Every
  capacity number is reported as "at ccx13".
- 7 worker hosts, cpx62 (112 shared vCPU, inside the recorded 120), carrying N = 100 workers (15/15/14/14/14/14/14). N per class:
  arm K 0 (before any worker); S0 100; S6a 100 instances on 100 workers, S6c 5 on the cores; S3 100 at the start of each window
  (99 during it, restored after); S4 100 workers, 5 cores at the start (4 during the window).
- Arm K runs first: the driver (`driver/ArmKDriver.java`) on worker host `wh-1`, before its workers start; never on a core.
- Workers several per host on the worker hosts, round-robin, admitted as above; the community policy is committed through
  `POST /api/v1/cluster/config` (max size 25 at N ≥ 50, else 5, as M-1).
- Ports: SWIM listens on the cluster port + 100 (measured), so cluster ports take the first hundred of each 200-block from 26000
  and TCP ports (management, app HTTP, agent) a band from 30000; all below the Linux ephemeral floor (32768), so another process's
  ephemeral socket cannot hold a node's port (dry run 1 lost a core to exactly that at 47030).

## Classes and evidence

`control`, then arm K, then `s0` (idle), `s6` (`s6a`: one slice to every worker, `WORKERS_ONLY`; `s6c`: the cores-only control),
`s3` (governor killed; restored by a fresh worker id), `s4` (core leader killed; window held until a successor is committed;
restored by a fresh core id). `s4` runs last because it changes the core set: in dry run 2 a `CORE_ONLY` deploy after two `s4`
restores placed only on the three original cores and never reached 5 (the reservation-admitted cores took no instance;
[unverified: cause not traced — harness admission path or product placement]). Each window follows M-1's `measure()`: idle bracket before and after, the settle rule on non-idle commits, a
per-core delta and its spread. Placement for `s6a` is set by `POST /api/v1/scale` (a plain Put), where M-1 uses a leader transaction.

Arm K: c ∈ {1, 4, 16, 64, 256}, 15 s warm-up, 60 s measured, then a 20 s c=0 control after every step; 3 passes, pass 2 reversed.
Readings of every core bracket the measured 60 s (`armk.jsonl`: Δdecisions/Δproposals/Δvotes, Δ`put:ConfigKey` and so commits per op,
pending batches, the driver's ops/s, p50/p99 and errors). Commits per op (k) is exact: the target core's `put:ConfigKey` over the whole driver run against the
driver's `okAll`, inside the bound [`okAll`, `okAll + failedAll`] (a timed-out op may still commit); the rate ratio over the 60 s window
mixes two windows and is recorded only as `commitsPerOpRateRatio`. The summary row computes the plateau (< 5 % growth per doubling or p99 > 1 s).

Evidence goes to `<run-dir>/evidence/*.jsonl`; host logs and samples are pulled to `<run-dir>/evidence/pull-<step>/` after every
step and on failure.

## Cost

`armxk.sh estimate` prices the topology from the list prices recorded in `lib/estimate.py` (with the date and pages they were read
from) and checks it against the project limits. It never calls the API. Default: 5 ccx13 + 7 cpx62 = EUR 1.8122/h; a 3 h run
plus a 1 h margin bills 4 h = EUR 7.25. The arm K load generator is worker host `wh-1` before its workers start (q8 P6.1), so it adds
no server; a separate cpx62 load generator would add EUR 0.2091/h. `cloud-run` refuses to provision on any estimate problem, and
refuses each step whose billed hours (every started hour, per server) would cross the cap.
