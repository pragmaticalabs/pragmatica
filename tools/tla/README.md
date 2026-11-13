# Embedded TLA+ models

TLA+ models of critical concurrency paths live in the Java source, as fragments inside `///` doc comments.
`extract.py` assembles them into a module and its TLC configs; `check.sh` extracts and runs TLC on one config;
`run.sh` runs every config listed in `models.tsv` and compares each verdict with the expected one.

**Results are bounds, not proofs.** TLC explores every state reachable within a config's constants (keys, scopes,
acquires, environment steps). A passing config says no violation exists within those constants and nothing about
larger ones; a model that abstracts the code says nothing about what it leaves out. State a result with its config
and constants, e.g. "SharedPools `code`, 2 keys, 2 scopes, MaxAcquires 3: no violation (876,713 distinct states)".

## Models

| Module | Protocol | Skeleton | Actions |
|---|---|---|---|
| `SharedPools` | node-shared resource pools: acquire, release, `closeAll` (#2978) | `aether/resource/api/.../resource/package-info.java` | `SharedPools.java`, `SpiResourceProvider.java` |
| `Stalled` | owner catch-up all-stalled warning, level-triggered (#2967) | `aether/aether-stream/.../replication/package-info.java` | `PartitionBackfill.java` |
| `Fsm2Leader` | FSM2 leader: open-warning set, leader fence, `Rebuild` inherit, publish after commit (#2763, #2834, #3085; finding #3160) | `aether/aether-deployment/.../cluster/fsm2/package-info.java` | `Lifecycle.java`, `Warnings.java`, `Reconcile.java`, `Failures.java`, `Outbox.java`, `ClusterFsm.java` |

Scope (owner ruling 2026-11-13): protocols with a written race or ordering ruling (consensus, FSM2 leader and
reconcile, stream ownership, catch-up and ISR, SharedPools, quorum re-admission). Not mandated elsewhere.

## Convention

- A fragment is a Markdown code block inside a `///` doc comment, tagged `tla <Module>:<Part>`:

  ```java
  /// ```tla SharedPools:Acq_Admit
  /// Acq_Admit(t) ==
  ///     /\ ...
  /// ```
  private SharedEntry admit(SharedEntry current, String scope, String label, Admission admission) {
  ```

- **An action fragment sits on the atomic region that implements it**: the `compute` mapping function, the CAS,
  the method whose body is one step. Not on the whole public method when that method spans several steps. Its
  `Part` is the action name, it must define an operator of that name at column 0, and the same name is the trace
  event emitted for that step.
- **The skeleton** (`Part` = `head` and `tail`) carries what is module-wide: `EXTENDS`, `CONSTANTS`, `VARIABLES`,
  helpers shared by several actions, library semantics that are not our code (e.g. ConcurrentHashMap's count
  publication), `Init`, `Next`, fairness, invariants and properties. It sits in `package-info.java`, or in the
  protocol class header.
- **Trace event names are action names.** A test-only trace hook (e.g. `SharedPools.traceTo`) emits one event per
  atomic region, named after the action that models it, so `trace.py` can check a recorded run against the spec.
  The hook must not change production behaviour: with no sink installed the region runs its body and nothing else.
- **Every filed concurrency defect in a listed protocol adds a known-bad config**: a config that models the code
  before the fix (the model's `Fixed` constant drops the fix's name) and must FAIL. It is listed in `models.tsv` with
  `expect` = `fail`. A known-bad config that passes means the model no longer reaches the code it pins.
- **Configs** are blocks tagged `tlacfg <Module>:<Variant>`, written to `<Module>_<Variant>.cfg`. Variant `common`
  is prepended to every other variant. One variant per property keeps each counterexample attributable.
- Assembly order is deterministic: `head`, then the action fragments ordered by (source path, line), then `tail`.
  The extractor writes the `MODULE` line and the closing `====`.
- The skeleton may only reference actions that exist, and every action must be referenced by `tail` (in `Next` or
  fairness). An action that is defined but never reachable is an error, not a warning.

## Checks the extractor enforces (exit 2, `file:line: message`)

- a fence that is not closed before the doc comment or the file ends;
- a tag that is not `<Module>:<Part>`;
- a duplicate `Part` or config variant (both positions are named);
- a missing `head` or `tail`, or no config;
- an action fragment that does not define its own name, or that `tail` never references.

Syntax and semantic errors are SANY's and TLC's. `check.sh` annotates every `line N, col M ... of module <Module>`
position in TLC's output with the Java source line it came from (`<Module>.map.tsv`), so a counterexample reads as
a sequence of Java locations.

## Expected verdicts: `models.tsv`

One row per config: module, source root, variant, `expect` (`pass` or `fail`), `property`, the constant overrides of the
small profile, and what the row pins. `property` is the property a `fail` row must see violated (a comma-separated
list: any one of them; `-` for a `pass` row). `run.sh` is red when:
- a config the extractor finds has no row, or a row names a config that does not exist;
- a `pass` row finds a violation;
- a `fail` row (known-bad) finds none (TLC exit 0): the negative control did not fire;
- a `fail` row ends in a violation of a property other than the ones it names;
- TLC ends any other way (parse, config or semantic error, timeout): the row reads `ERROR`, never `fail`.

An expected failure (`expect` = `fail`) must cite an open issue or be a known-bad variant of a fixed defect; when the issue is fixed, flip the row to `pass`.

## Running

```
tools/tla/run.sh [small|full|sim] [module-regex]      # every config with its expected verdict
tools/tla/runselftest.sh                              # run.sh's own verdict logic, on a fixture with wrong expectations
tools/tla/check.sh <Module> <Variant> <source roots...>  # one config
tools/tla/selftest.sh <Module> <source root>           # the extractor's own checks, on a copy of the root
# e.g.
tools/tla/run.sh small SharedPools
tools/tla/check.sh SharedPools leak aether/resource/api/src/main/java
```

`small` applies the row's overrides (minutes in total); `full` runs every config as written (hours: liveness at the
written bounds is the long pole). `sim` runs every config as written under TLC's random simulation
(`-simulate num=<SIM_TRACES> -depth <SIM_DEPTH>`, stopped after `SIM_SECONDS` per config by
`-Dtlc2.TLC.stopAfter`): each row prints its wall time, the trace count and the mean trace length. **Simulation is
sampling, not a proof**: a `pass` row means no violation among the sampled traces; its gate is that every known-bad
row still FAILS (a bug the sampler cannot find in the budget turns that row red). Every row of every profile prints its
wall time, which is the reference for a timing budget.

### On demand in CI

The `TLA+ models` workflow (`.github/workflows/tla-models.yml`) runs only when triggered, never on a pull request,
a push or a schedule, and it is not a required check:

```
gh workflow run tla-models.yml -f profile=small -f modules='.*'
gh workflow run tla-models.yml --ref <branch> -f profile=full -f modules=SharedPools
gh run watch                                   # then download the tla-models-* artifact for the TLC logs
```

It pins `tla2tools.jar` (version and sha256 in the workflow), runs `selftest.sh` for every selected module, then
`run.sh` at the chosen profile, and uploads `target/tla/` (every config's TLC log with Java positions).

Environment: `TLA_JAR` (default `~/tools/tla/tla2tools.jar`), `TLC_WORKERS`, `TLC_OPTS`, `TLA_OUT` (default
`target/tla/<Module>` for `check.sh`, `target/tla` for `run.sh`), `TLC_CONST` (`check.sh`: space-separated
`Name=value` overrides of the config's constants, recorded in the log), `TLC_TIMEOUT` (`run.sh`: seconds per config), `TLC_JAVA_OPTS` (`check.sh`: extra JVM options), `SIM_SECONDS`, `SIM_TRACES`, `SIM_DEPTH` (`run.sh sim`), `MODELS_TSV` (`run.sh`: a different table; the self-test uses it). The deadlock check is off: bounded models end in terminal states by design. The full TLC log
is `<out>/<Module>_<Variant>.log`; the console gets the verdict, the counterexample as actions with their Java
positions, and the state counts.

Run TLC on a build host, not on a laptop: a model with two keys, two scopes and four acquires has tens of millions
of states.
