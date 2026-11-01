# M-1 / M-2 measurement harness (counting arm), #2512

`M1MeasureTest` + `M1Instrument` count, per key family, the KV mutations the core commits for each node event, plus leader transactions and consensus slots. They are the harness behind the M-1 baseline (`oss/internal/rc5-planning/wave3/m1-measure-report.md`, measured on rc5 `3bfeddbc34`) and are meant to be run again unchanged for M-2.

**They are not CI tests.** `M1MeasureTest` carries `@Tag("Benchmark")`: the root pom's `surefire.excludedGroups` (`Infinite, Slow, Benchmark, S3`) keeps surefire from running it, and `tools/ember-shard.py` skips a top-level `@Tag("Benchmark")` class (`EXCLUDED_CLASS_TAG`), so no Ember shard schedules or expects it. Run it only explicitly, by clearing the exclusion and naming the class.

## How the counts are taken

Test-only, by reflection on final fields of objects the node built; nothing in `src/main` is instrumented.
1. Notification reading: `KVStore.router` is replaced by a counting router (every `ValuePut`/`ValueRemove`, never during a snapshot replay; family = key class).
2. Engine reading: `RabiaEngine.stateMachine` is replaced by a `Proxy` counting batches, commands by kind, leader transactions accepted/refused, and slots.
The two must agree (`tx.accepted.mutations + cmd.Put + cmd.Remove == put.total + remove.total`); the `control*` classes plant a known mutation and must move the count by exactly one.

## Reproduce (on a quiet host; one Ember run at a time)

```
mvn test -pl aether/ember -Dtest=M1MeasureTest -Dgroups=Benchmark -Dsurefire.excludedGroups= \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dm1.n=<workers> -Dm1.classes=<comma list> -Dm1.reps=3 -Dm1.label=<run> -Dm1.idleMs=30000 \
  -Dm1.out=<file>.jsonl \
  '-Dvthread.argLine=-Djdk.virtualThreadScheduler.parallelism=8 -Xmx7g'
```
(on the shared build hosts: through `bb-build`, one ticket per situation, each under ~20 min.)

| property | meaning | default |
|---|---|---|
| `m1.n` | workers (5 cores are always added) | 10 |
| `m1.classes` | `control, control-refused, idle, join, leave, crash, kill-k, kill-community, kill-governor, kill-leader, core-join-leave, policy-change, replace-worker, scale, crash-replaced` | the unmanaged set |
| `m1.reps` | repetitions per class | 3 |
| `m1.source` | `default` = manual workers; `eu` = CTM-managed workers (required for `scale`, `crash-replaced`, `replace-worker`) | `default` |
| `m1.raisedSwim` | `true` = `withRaisedSwimTimeouts()` | false |
| `m1.communitySize` | community `max_size` | 5 below N=50, 25 below 200, else 50 |
| `m1.idleMs` | idle bracket before and after each event | 45000 |
| `m1.base` | first port (QUIC; SWIM +100, mgmt +1000, app +2000, stride 3) | 36000 |
| `m1.label`, `m1.out` | run label, JSON-lines output | `run`, `/tmp/m1-<label>-n<N>.jsonl` |

The M-1 report's situations map as: idle `idle`; one worker killed `crash`; 10 % or a community killed `kill-k`, `kill-community`; governor killed `kill-governor`; core leader killed `kill-leader`; 10 % scale-up `scale` (with `-Dm1.source=eu`). **One slice deployed to every community is not implemented** (Ember has no slice-artifact path).

Examples used for the baseline:
```
-Dm1.n=50  -Dm1.classes=control,control-refused,idle,join,leave
-Dm1.n=50  -Dm1.classes=crash,kill-k,kill-community,kill-governor
-Dm1.n=100 -Dm1.raisedSwim=true -Dm1.source=eu -Dm1.classes=scale,crash-replaced,replace-worker
```
Memory: about 90 MB resident per node (N=200 needed 18.7 GB RSS with a 7 GB heap and was not a steady state on the shared host), so size N to the host.

## Analysis

`tools/m1-measure/` turns the JSON lines into the report tables: `python3 tools/m1-measure/summary.py data/*.jsonl` (class x N), `top3.py`, `mkreport.py` (full per-class tables and slopes), `runs.py`. Idle-netting pools the idle brackets of the clean classes per N; read raw counts where the idle rate is large.

## Validity rules learned in M-1

- Record the host load (each sample carries `loadStart`/`loadEnd`) and the SWIM mode: with default SWIM timeouts on a loaded host, N >= 100 flaps and the counts are dominated by announcement commits.
- A crash is only an event once the leader has committed the departure; the harness holds the window until then.
- `N` is confounded with community shape; state communities x size next to every N.
- `EmberCluster.withPortStride(3)` is required beyond 100 slots (SWIM port = cluster port + 100); `withAdditionalNodeSlots` now allows up to 256.
