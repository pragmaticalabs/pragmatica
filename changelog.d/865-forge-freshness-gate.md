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
