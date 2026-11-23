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
| `RabiaSlot`, `RabiaEpoch`, `RabiaAdopt` | Rabia: slot sequence, in-log reconfiguration, state adoption (#3164) | `integrations/consensus/.../rabia/package-info.java` | `RabiaEngine.java`, `PhaseData.java` |
| `Fsm2Leader` | FSM2 leader: open-warning set, leader fence, `Rebuild` inherit and rule O, publish after commit (#2763, #2834, #3085, #3274) | `aether/aether-deployment/.../cluster/fsm2/package-info.java` | `Lifecycle.java`, `Warnings.java`, `Reconcile.java`, `Failures.java`, `Outbox.java`, `ClusterFsm.java` |
| `DHTDepartureDrain` | DHT departure drain: plan, retarget, halt, receiver acceptance (#3049) | `integrations/dht/.../dht/package-info.java` | `DeparturePushRun.java`, `DHTAntiEntropy.java`, `DHTTopologyListener.java` |

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
  A fix name added to a model later is OFF in every existing known-bad's `Fixed` unless that row's defect needs it ON:
  the config models the code the defect lived in, so a later fix cannot mask it (#3580). The exception: a row that pins a
  residual of the current code, not a fixed defect (DHTDepartureDrain `abandon`, `wavesset`, `flap`, `allnamedflap`,
  `nohaltfair`), keeps the new fixes ON, as code residuals and controls.
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
- an action fragment that does not define its own name, or that `tail` never references;
- a reference no fragment of the module defines (#3311): an operator application `Name(`, or a capitalised bare name,
  that is not defined (`==`), declared (`CONSTANT`, `VARIABLE`, `EXTENDS`), bound (a parameter, `\A`, `\E`, `CHOOSE`,
  a set or function constructor) or a keyword or standard-module operator. Every such reference is named with its
  Java line. The check is lexical, not scoped: a name bound anywhere in the module counts as bound everywhere, so it
  can miss a reference but never reports a defined one; a lowercase bare name is left to SANY.

Other syntax and semantic errors are SANY's and TLC's. `check.sh` annotates every `line N, col M ... of module <Module>`
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

## Pinned state counts: `pinned-counts.tsv`

One row per pinned `pass` row: module, variant, profile (`small` or `full`), the distinct-state count TLC must find,
and why. `run.sh` at that profile is red when the row passes with any other count. Use it where a spec extends a
model by a mode switch: the old mode must reach exactly its old states, and an edit that leaks into it can REMOVE
states, which no verdict shows (#3443: a LATE exclusion read at STAMP = "off" removed the traces the two CAS
controls rest on, so they passed; the pins hold the Fsm2Leader off-mode `LATE = FALSE` pass rows at their base counts).

## Running

```
tools/tla/run.sh [small|heavy|full|sim] [module-regex] [variant-regex]   # the configs with their expected verdicts
tools/tla/runselftest.sh                              # run.sh's own verdict logic, on a fixture with wrong expectations
tools/tla/check.sh <Module> <Variant> <source roots...>  # one config
tools/tla/selftest.sh <Module> <source root>           # the extractor's own checks, on a copy of the root
tools/tla/selftest.sh Alpha tools/tla/fixtures/two-modules   # same-name operators of two modules in one file: probes must edit only the target
# e.g.
tools/tla/run.sh small SharedPools
tools/tla/check.sh SharedPools leak aether/resource/api/src/main/java
```

`small` applies the row's overrides (about two hours for the whole table run sequentially; CI shards it by model) and does
not run a row marked `heavy` (see below); `heavy` runs only the rows marked `heavy`, as written; `full` runs every config as written (hours: liveness at the
written bounds is the long pole). `full` is not expected to finish `DHTDepartureDrain` `code`: its config as written (N=5 RF=3 MaxDrain=2
MaxStale=1) did not finish in 150 minutes even before #3225 added state; the row records that bounded result. `sim` runs every config as written under TLC's random simulation
(`-simulate num=<SIM_TRACES> -depth <SIM_DEPTH>`, stopped after `SIM_SECONDS` per config by
`-Dtlc2.TLC.stopAfter`): each row prints its wall time, the trace count and the mean trace length. **Simulation is
sampling, not a proof**: a `pass` row means no violation among the sampled traces; its gate is that every known-bad
row still FAILS (a bug the sampler cannot find in the budget turns that row red). Every row of every profile prints its
wall time, which is the reference for a timing budget.

### Heavy rows (`heavy` in the `small` column of `models.tsv`)

A row that does not finish inside the small profile's per-config budget (`TLC_TIMEOUT` 1200 s; measured on cloudbb-5 at
`a35b0a006a`: `RabiaSlot/live` and `Fsm2Leader/code_goals`, both exit 124) is marked `heavy`. The small profile does not run it
and never counts it as a pass: `run.sh` prints `SKIPPED-HEAVY <module>/<variant>` and appends an evidence row with
`actual=skipped`, `verdict=SKIPPED-HEAVY`, and `results-check.py` names every such row (`NOTE SKIPPED-HEAVY ...`) and tallies
the verdicts, so a skipped row is never folded into a pass count. The `heavy` profile runs only these rows as written (the
`full` pinned counts apply to it); the schedule and a manual dispatch (`profile=heavy`) run it. A known-red row is recorded as
it is: `RabiaAdopt/ballotlossD5` is RED (Agreement violated, #3592) and stays RED in the rows until the defect is fixed.

### Fragment-touched check (pull requests)

Code inside a region documented by a `tla` fragment can change while the fragment stays as it was; in three weeks on
rc6 four PRs did, and one left the model silent on a new ordering (Rabia announce order, #3455).
`tools/tla/touched.py --base <rev> [--head <rev>]` (the `TLA+ PR checks` workflow, on a pull request) fails when a changed
line that holds code (not a blank line, not a comment) falls inside such a region and that region's fragment (same
`Module:Part`, same file's base version) is unchanged. The output names each region as `file:line` with the changed lines.

- **Region** = the declaration the doc comment documents, from its first line to its end: a method, a field with its
  initialiser, a `package` statement. A class, interface, enum or record documents its header only (the skeleton
  fragments sit there; a class body is not one atomic step). The end is found by a brace and parenthesis depth scan
  with comments, string literals, text blocks and character literals blanked, so a brace in a string cannot end a
  region early or late.
- **Excuse**: a line `tla-unchanged: <Module>: <reason>` in the PR body (the reason is required; the marker covers every
  fragment of that module in the PR). Use it when the model still describes the code, e.g. metrics-only edits.
- The base is reduced to `merge-base(base, head)`; a rename is followed (`git diff -M`, explicit so a `diff.renames=false` config cannot hide it; a move below git's similarity threshold is matched through the deleted file's fragments by Module:Part); a deletion of code that follows a fragment's doc comment counts as a change of its region; a file new in the PR has no fragment to keep. Deleting a fragment together with its code is not checked (a removed model has no region left to compare).
- Not a required check (a ruleset edit, the owner's). Tests: `python3 -m unittest discover -s tools/tla -p test_touched.py`
  (fixture repositories for every case, plus the real rc6 commits of #3455 and #3278 as positive controls, and the live case of
  2026-11-22: #3576 over the not-yet-landed #3580 fragments of `DHTDepartureDrain` is RED for Plan, Retarget and Halt, while
  the same #3576 on rc6 is green because no fragment was there; that case needs the #3580 tip commit in the clone and skips without it).

### Evidence rows (`results.tsv`)

Evidence that a model was checked used to live in PR prose. `run.sh` now appends one row per config that ran to
`$TLA_RESULTS` (default `<TLA_OUT>/results.tsv`), one TSV with a header: `commit dirty date profile module variant expect
actual violated distinct tlc verdict constants`. `actual` is TLC's own outcome (`pass`, `fail`, `error` from its exit code),
`expect` the `models.tsv` verdict, `verdict` (`OK`, `RED`, `ERROR`) what run.sh concluded from both plus the violated
property and the pinned count. A results file with another header is refused (exit 2), never appended to.

- **Commit and dirty.** `commit` is HEAD of the repository `run.sh` was started in; `dirty` is `dirty` when a tracked file
  differed from HEAD, so the commit does not name the code that ran. Commit the code, then run, then commit the rows: the rows
  name the code commit, and a row can never name the commit that contains it.
- **A PR that touches a modelled region commits its run's rows** by appending the run's `results.tsv` rows to
  `tools/tla/results.tsv`.
- **`results-check.py [--base REV] [--head REV]`** (the `TLA+ PR checks` workflow, job `evidence-rows`) checks the rows
  the PR adds (the diff from the merge base; a squash merge rewrites commits, so rows already merged are history): a header
  other than `run.sh`'s, a malformed row or value, a commit that is not in the repository or is not an ancestor of the head,
  a dirty row, and a commit recorded as `none` are RED. Not a required check (a ruleset edit, the owner's). Tests:
  `python3 -m unittest discover -s tools/tla -p test_results_check.py` (fixture repositories; the three ancestry cases go red
  when the ancestry test is removed) and the `rows-*` cases of `runselftest.sh`.
- **The scheduled and pushed `TLA+ models` run uploads its rows** as the `tla-results-*` artifact (90 days).
- **What a row does not say.** It records that TLC explored a model extracted from the code at that commit, within the
  profile's bounds; it does not say the code still matches the model after later commits (that is `touched.py`'s job).

### Making the PR checks required (owner)

`fragment-touched` and `fragment-paths` are fit to be required contexts of the merge-queue ruleset (`rc6-merge-queue`, id
27494155; add them next to `fragment`, GitHub Actions as the integration). What makes them fit, and what each decision rests on:

- **They report on the merge queue.** The workflow also triggers on `merge_group`; a required context that never reports there
  holds the queue forever. A merge_group run takes the sha concurrency group and is never cancelled by another.
- **The merge group is re-judged, per queued squash commit.** `fragment-touched` has no PR body on a merge group, and the tree
  of a PR queued behind another is not the tree its pull_request run judged, so passing with a line would be an unearned claim.
  It walks the first-parent commits of `base_sha..head` and runs `touched.py` on each `commit^1..commit`, with the excuses read
  (`gh api`) from the body of the PR that commit's subject names (`(#N)`, which a squash adds; a commit that names none is
  judged without excuse). Judging each commit on its own patch, not the group's sum, matters: a later PR that changes the
  fragment would otherwise excuse an earlier one that left it alone. `fragment-paths` reads no body and no diff (the tree
  against itself), so it runs unchanged on the queue's commit, which is the tree that will be merged. `evidence-rows` states
  that a squash drops the PR commits the ancestry of its rows is checked against, and passes with that line (it is not a
  proposed required check).
- **The instrument is the base's.** Each job reads `tools/tla` from the base revision (`git archive`) into a temp dir and runs
  the scripts and their self-tests from there against the PR's tree, so a branch older than the instrument is judged
  (#3605 failed with `NO TESTS RAN`: its own checkout had no `test_touched.py`) and a PR cannot weaken the judge that judges it.
  When the change edits `tools/tla/*.py` or its fixtures, the head's copy and tests also run. An instrument the base lacks is
  one `INSTRUMENT ABSENT:` line (exit 1), never exit 5 or a pass. `test_workflow.py` is the exception: it reads the head's
  workflows, README and scripts against each other, so it is the head's, and a branch too old to carry it is told to merge the base.
- **Not closed by this:** a PR can still edit the workflow file itself (it comes from the PR's merge commit). A ruleset
  `workflows` rule or CODEOWNERS on `.github/workflows/` closes that and is the owner's.
- **Tests:** `test_workflow.py` classes `QueueAndInstrument` (the wiring), `MergeGroupDryRun` (the merge_group step's own shell
  executed over a scratch queue history with a `gh` shim) and `InstrumentFromBase` (the #3605 shape: a branch whose
  `tools/tla` lacks `test_touched.py`).

### In CI

The `TLA+ models` workflow (`.github/workflows/tla-models.yml`) runs on demand, on a push to `release-1.0.0-rc6` that
touches `tools/tla/**` or a Java file carrying a fragment, and on a schedule (daily: small for every model, heavy for the models with a heavy row). It
never runs TLC on a pull request and is not a required check; a red run is a flake-priority issue. (The cheap checks that need no
TLC run on a pull request in `TLA+ PR checks`: `fragment-touched`, `evidence-rows` and `fragment-paths`.)

- **One job per model.** `tools/tla/plan-matrix.py --event E --profile P --modules REGEX` reads `models.tsv` and prints the
  GitHub matrix; the workflow's `plan` job feeds it to the `model-check` job (`fail-fast: false`, so a red model hides no
  other verdict). A push runs profile small for every model; the schedule adds profile heavy for each model that has a heavy
  row; a dispatch runs the chosen profile (heavy: only models with a heavy row). A regex that matches no model is exit 2, never
  an empty green run. The `selftests` job runs the python tests and `runselftest.sh` once per run. Tests:
  `python3 -m unittest discover -s tools/tla -p test_plan_matrix.py`.
- **The `paths:` list is generated.** `tools/tla/fragment-paths.py --sync` rewrites the block between its BEGIN and END
  markers from `extract.py`'s own discovery (every Java file with a fragment, outside `tools/tla/`, which the glob
  covers). `fragment-paths.py --check` names every fragment file missing from the list and every stale entry; the
  workflow runs it, and `test_fragment_paths.py` (a fragment file missing turns it red) before the models. A PR that
  adds a fragment to a new file runs `--sync`. The same `--check` runs on every pull request (job `fragment-paths` of
  `TLA+ PR checks`, no TLC), so a PR that skipped `--sync` is red at PR time, not after the merge. `--check` also reads
  `models.tsv`: a model module that has fragments in a Java file but no row there is RED and named (it would never be
  model-checked), and `test_workflow.py` pins this wiring (the jar pin, the PR job, one list of profiles: `small`, `full`,
  `sim`, `heavy`, which `run.sh`, `results-check.py`, `plan-matrix.py`, the dispatch input and this README must agree on).
- **The schedule starts when the workflow reaches the default branch.** GitHub fires `schedule:` only from the default
  branch's copy of the workflow. The default branch is `release-1.0.0-rc6` (`gh repo view --json defaultBranchRef`) and
  its copy has the dispatch trigger only, so the schedule is dormant until this file is merged there; a push trigger on the
  same branch fires from the pushed commit's own copy. A scheduled run checks out `RELEASE_BRANCH` (the `env:` of the
  workflow), not the default branch; when the default branch moves, `RELEASE_BRANCH` and the `push:` branch move with it.

```
gh workflow run tla-models.yml -f profile=small -f modules='.*'
gh workflow run tla-models.yml --ref <branch> -f profile=full -f modules=SharedPools
gh run watch                                   # then download the tla-models-* artifact for the TLC logs
```

Each shard runs `selftest.sh` for its module, then `run.sh` at its profile for that module, and uploads `target/tla/`
(every config's TLC log with Java positions) and its evidence rows.

**Where the jar comes from.** Every job downloads `tla2tools.jar` from one asset of this repository: the release
`tla2tools-2026.11.06` of `pragmaticalabs/pragmatica` (`env.TLA_JAR_URL` of the workflow), approved by the owner, and
verifies it against `env.TLA_SHA256` (`e1f11b75c4b5bc4478ba0ac5cb6ae28e40a01b0016bc33e0aa97da22dfdbcd7f`, the jar every
build host carries) in a step that runs on a cache hit too. The cache key is the sha, so a new pin never reads an old
cache. The TLA+ project's own release page is not used: its `v1.8.0` asset has another sha256, so the pin there never matched.
**To move the pin:** upload the new jar as an asset of a new release (a new tag, never a replaced asset), then change
`TLA_JAR_URL` and `TLA_SHA256` together in the workflow and the two lines above in this README; `test_workflow.py` fails
while any of the three disagrees or a job downloads from anywhere else. Re-run the build hosts' jars too (`sha256sum`).

Environment: `TLA_JAR` (default `~/tools/tla/tla2tools.jar`), `TLC_WORKERS`, `TLC_OPTS`, `TLA_OUT` (default
`target/tla/<Module>` for `check.sh`, `target/tla` for `run.sh`), `TLC_CONST` (`check.sh`: space-separated
`Name=value` overrides of the config's constants, recorded in the log), `TLC_TIMEOUT` (`run.sh`: seconds per config), `TLC_JAVA_OPTS` (`check.sh`: extra JVM options; TLC always runs with `-XX:+UseCompactObjectHeaders`, JDK 25+, measured on DHTDepartureDrain `strand`: heap after GC -21% at peak, RSS -8%, state counts unchanged), `TLA_DISK_MAX`, `TLA_MEM_MIN_MB`, `TLA_GUARD_SECS` (`check.sh`: the resource guard, below), `SIM_SECONDS`, `SIM_TRACES`, `SIM_DEPTH` (`run.sh sim`), `MODELS_TSV` (`run.sh`: a different table; the self-test uses it), `PINS_TSV` (`run.sh`: a different pinned-counts table). The deadlock check is off: bounded models end in terminal states by design. The full TLC log
is `<out>/<Module>_<Variant>.log`; the console gets the verdict, the counterexample as actions with their Java
positions, and the state counts.

**Resource guard** (`check.sh`, so every run has it): TLC runs in the background and is checked every `TLA_GUARD_SECS` (10)
seconds. It is killed (TERM, then KILL after 10 s; only the PID `check.sh` started) when the filesystem holding the output dir is
at or above `TLA_DISK_MAX` percent (default 90) or `MemAvailable` is below `TLA_MEM_MIN_MB` (default 2048; Linux `/proc/meminfo`
only). `check.sh` prints `RESOURCE-GUARD: killed TLC: disk N%` (or `MemAvailable M MB`), removes the run's states dir and exits 75;
`run.sh` records the row as verdict `RESOURCE` (actual `error`, red): a guard kill is never a pass or a fail, and `results-check.py`
accepts and names it. Re-run on a freer host; never read it as a model result.

Run TLC on a build host, not on a laptop: a model with two keys, two scopes and four acquires has tens of millions
of states.
