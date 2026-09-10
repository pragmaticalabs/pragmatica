### Fixed (2026-09-10 — #865: forge.sh could pass or fail against bytecode that was not the tree's)

- **The acceptance gate ran with `-pl aether/forge/forge-tests` and no `-am`, so every sibling module
  resolved from the local Maven repository.** The suite therefore executed whatever happened to be
  installed there, which may be arbitrarily older than the working tree, and nothing in the run said
  so. During #858 this produced a false NEGATIVE — 15 of 16 classes "failed" against a `node` jar
  built the previous evening, before the fix commits. The symmetric case is worse and silent: a stale
  GREEN certifies a fix that was never executed. The module scope stays hard-coded and without `-am`,
  because that scope is what keeps `HetznerCloudIT` (which provisions a real paid server) out of the
  reactor; the staleness is addressed instead of the scope.
- **`forge.sh` now refuses to start against a stale runtime**, naming every stale module with the
  installed artifact's timestamp beside the newest source file that outpaces it, and telling the
  operator to run `./build.sh`. The check runs before Maven, so it is a gate rather than advice.
  `FORGE_SKIP_FRESHNESS=1` overrides it and prints that the run is *not* evidence about this tree.
- **The check is itself an instrument, and it is built so it cannot report success without having
  looked.** `tools/forge-freshness.py` prints the number of modules it examined — that count is the
  evidence, not the exit status — and it exits 2, never 0, when it examined nothing. Three silences
  that used to be indistinguishable are now separated: a checker that DID NOT RUN (the script is
  missing) refuses exactly as a stale tree does; a checker that ran on ZERO modules says its result
  means nothing and why; and an artifact that is NOT INSTALLED is counted and named separately from a
  fresh one, because an absent jar is not evidence of freshness.
- **Scope is forge-tests' intra-repo dependency closure, computed from the poms**, not every module
  in the tree — those are the artifacts the no-`-am` run actually resolves. The closure size is
  printed so a reader can see the space that was searched rather than infer it from a verdict.
  Measured on this tree: 160 poms scanned, closure of 70 jar modules. Sweeping all 160 would refuse
  for edits the gate never executes, and a gate that refuses constantly is a gate that gets
  overridden by habit.
- Run output now states the artifact timestamps the run exercises (oldest and newest, each with its
  module), which is the ticket's option (c) — a reader can see what was executed instead of inferring
  it. The opt-in `--rebuild` of option (b) was deliberately not added: the refusal already names the
  remedy, and a gate that silently rebuilds hides the same staleness it exists to reveal.
- **The new `script-gate` module gates itself**, which the repository default does not do.
  `jbct.includeTests` is false repo-wide, so every test-only module — including the modules that
  exist to enforce gates — is examined by nothing, and the goal exits green having looked at zero
  files while #740's WARNING says so out loud. A gate module exempt from the gate is the same defect
  this module was built to catch, so `jbct.skip=false` and `jbct.includeTests=true` are set on
  `script-gate` alone (NOT at the root — that is #938, held behind #974). Turning it on cost a
  rewrite rather than a flag: 43 lint errors and 6 format issues, almost all `JBCT-EX-01` (`throws`
  on a JUnit method). The harness now returns `Result<T>` from every IO operation and no test method
  declares a checked exception, so `ScriptRunner` and `SyntheticRepo` read like production code —
  under this property they are held to production rules.
- [verified: `script-gate` — `ForgeFreshnessGateTest` 8/8 (fresh; touched source; touched *resource*;
  absent artifact named and never counted as fresh; no artifact at all → exit 2; missing local
  repository → exit 2; absent forge module → exit 2; and a stale module OUTSIDE the closure that must
  NOT refuse, which is what makes the closure claim checkable), `ForgeGateRefusalTest` 5/5, driving
  the real `forge.sh` with `mvn` stubbed on PATH so a refusal is only credited when the stub's marker
  file is ABSENT; `ScriptGateFixtureTest` 4/4 — 24/24 total.
  Mutation matrix, each reverted and the revert confirmed by an empty `git diff HEAD`: removing the
  staleness comparison reddens 3 named tests; making examined-zero return FRESH reddens
  `noArtifactAtAll_isIndeterminateRatherThanGreen`; making `forge.sh` never invoke the checker reddens
  3 tests in `ForgeGateRefusalTest` while all 8 `ForgeFreshnessGateTest` tests stay GREEN, which is
  the independence of the two classes stated as what goes red.
  Live: on this tree (jars 2026-09-08, sources checked out today) the checker reports
  `examined 70 module(s): 51 fresh, 19 stale, 0 with no installed artifact` and `forge.sh smoke`
  exits 1 with Maven never invoked.
  The full matrix was re-run after the JBCT rewrite of the test sources and produced IDENTICAL red
  sets, so the rewrite did not weaken the instruments.
  `mvn jbct:check -pl aether/forge/forge-core` — 2 Java files, 0 format issues, 0 lint errors;
  `-pl script-gate` — **7 Java files**, 0 format issues, 0 lint errors, `JBCT check passed` (the
  count is the point: it was 0 files before `jbct.includeTests=true`).
  Root `mvn clean install`, run under the shared suite lock — BUILD SUCCESS, 145 modules, 0 SKIPPED,
  13,445 tests, 0 failures/errors, 19 skipped = **10 surefire + 9 failsafe**; the 9 are
  `HetznerCloudIT`, gated on an unset `HETZNER_CLOUD_TESTS` and invisible to a surefire-only count.
  All 31 tests this branch adds ran; none skipped, and none carries `@Disabled` or an assumption]
- **This gate is a LOCAL developer gate and is NOT enforced by CI.** `forge.sh` is invoked nowhere
  under `.github/` — searched `.github/`, `*.sh` and `docs/`, with `grep -rln mvn .github/workflows/`
  returning `ci.yml` and `release.yml` as the positive control; only `build.sh`'s advisory `echo`
  mentions it. So nothing stops a stale-runtime forge run in CI, because CI never runs forge.sh at
  all. Stated because "we added a freshness gate" reads as stronger than it is.
- **What is NOT covered:** the freshness check is a MTIME comparison, so it cannot see a jar whose
  content differs from its source at the same timestamp, and a `git checkout` that rewrites source
  mtimes marks a tree stale even when the bytecode matches — deliberately fail-closed, since the
  alternative is the silent stale run. It compares against the artifact `-pl` would resolve, not
  against what Maven ultimately puts on the classpath, so a dependency pulled from a remote
  repository rather than the local one is outside its reach. The forge suite itself was not executed
  as part of this change.
