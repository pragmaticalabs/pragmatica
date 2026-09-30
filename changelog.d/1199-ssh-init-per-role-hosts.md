### Fixed (2026-09-29 — #1199: cluster init --target ssh emits role 'count', which PF-10 refuses)
- **`aether cluster init --target ssh` wrote a file `aether cluster bootstrap` then refused**: each role
  carried `count`, which PF-10 rejects for SSH sources, and the host list sat at the source level, where
  the parser never reads it.
- Each role now carries its own `hosts`: the first `--core-nodes` hosts form the core tier and the
  remainder the worker tier.
  `[verified: aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/ClusterInitSizingRoundTripTest.java]`
- `init` now parses and validates what it is about to write with the same parser and validator
  `bootstrap` runs, and refuses (writing nothing) when bootstrap would reject it — e.g. a host listed
  twice in `--hosts`, which lands in both tiers (PF-09).
  `[verified: aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/ClusterInitSizingRoundTripTest.java]`
