# Pragmatica Monorepo

Global rules (git, delegation, challenge mode, ndx, consult-before-action) live in user `~/.claude/CLAUDE.md`. JBCT patterns, `jbct-coder` / `jbct-reviewer` policy, and `aether-coder` slice triggers are owned by their respective skills/agents and not repeated here. This file is for project-specific invariants only.

## Project layout

| Module | Purpose |
|--------|---------|
| `core/` | `Result`, `Option`, `Promise` |
| `integrations/` | Jackson, Micrometer, DB, HTTP, consensus, statemachine, swim, storage |
| `jbct/` | Formatting, linting, Maven plugin, slice-processor |
| `aether/` | Distributed runtime (BSL 1.1; rest of repo is Apache-2.0) |

**Version:** `1.0.0-rc4` on branch `release-1.0.0-rc4`.

**Priorities & roadmap:**
- V1 roadmap: [`aether/docs/.internal/progress/v1-roadmap.md`](aether/docs/.internal/progress/v1-roadmap.md)
- Active work items: GitHub Issues on `pragmaticalabs/pragmatica`
- RFCs: [`docs/rfc/`](docs/rfc/)
- Latest session handover: `aether/docs/.internal/progress/session-handover-*.md` (sorted by date)

## Build

```bash
mvn -pl <module> install -DskipTests -am   # focused rebuild + deps
./build.sh                                   # full build (bootstrap → format/lint → install → e2e/forge → blueprints)
mvn jbct:check -pl <module>                 # format + lint only
```

**`jbct.skip` POM hierarchy** — NEVER pass `-Djbct.skip=true` manually for aether builds; the POMs already handle it:
- Root pom: `jbct.skip=true` (core/integrations don't use the plugin)
- `aether/pom.xml`: overrides to `jbct.skip=false` (enables lint for all aether modules)
- `jbct/pom.xml`: `jbct.skip=true` (self-dogfooding cycle)

The only valid use of `-Djbct.skip=true` is building `jbct/` itself during bootstrap/release.

**`mvn verify` warning** — NEVER run `mvn verify` when `HCLOUD_TOKEN` is set in the environment. Failsafe picks up `HetznerCloudIT` and creates a real paid Hetzner server. Use `mvn -pl <module> test` for module tests and let the `build-runner` agent own any maven invocation.

**Stale-sibling trap** — a `-pl` build resolves SIBLING modules from the local repository, so after pulling another stream's cross-module change, local failures often point at code you did not touch (three instances on 2026-08-27 alone). The tell: the failure names an innocent module while CI is green on the same commit. Fix: `mvn -pl <changed sibling> install -DskipTests` first; trust CI (which builds fresh) over a local `-pl` verdict. It also bites self-inflicted: rename across two modules and install one, and the stale jar fails with the OLD symbol. **Timestamps prove nothing** — with multiple working trees, a wrong artifact can be NEWER than your correct source (another tree installed it after your build); compare artifact CONTENT against source, never mtimes. **Multi-tree rule:** EVERY stream — including the main checkout — uses an isolated `-Dmaven.repo.local=<tree>/.m2-local/repository`, appended to the TRACKED `.mvn/maven.config` and protected with `git update-index --skip-worktree .mvn/maven.config` (the file is tracked, so `.git/info/exclude` does nothing for it); add `.m2-local/` to `.git/info/exclude`, and seed it cheaply with `cp -al ~/.m2/repository .m2-local/repository` (hardlinks, no re-download). **The shared `~/.m2` is the OWNER'S space and agents never write it.** This replaces the earlier rule that assigned the shared repo to the main checkout — that arrangement is what let one stream's in-flight, half-compiled multi-module change reach every other stream's dependency space (2026-08-29: a mismatched `consensus`/`tcp` pair killed forge runs with `NoClassDefFoundError` naming a class the reader never touched — the wrong-module trap again). **Consequence the owner must know:** no agent refreshes `~/.m2` any more, so it goes stale; run `mvn install -DskipTests` from HEAD before any build where sibling freshness matters. Verify isolation by jar CONTENT, not timestamps — a new symbol must be present in `.m2-local` and ABSENT from `~/.m2`. CI on the pushed commit is the only arbiter for any locally-gated claim. **The inverted tell:** the stale-artifact family also runs backwards — a test can pass locally BECAUSE of leftover `target/` output and fail on CI's clean build (e.g. a gate scanning modules that build after its own module). A gate that only passes on a dirty tree is broken; verify gate-type tests from a clean tree (wiped `target/` or a detached worktree) so local reproduces CI's from-scratch order.

**Cross-module delete/rename discipline** — enumerate references UNTRUNCATED (no `| head` on the sweep grep) and compile every downstream module (or the full reactor `-DskipTests`; assert reactor MODULE-level SKIPPED == 0 — test-level conditional skips, e.g. token-gated Hetzner ITs, are benign and expected) before pushing. A predicted blast radius is not a verified one: on 2026-08-27 a truncated sweep hid two call sites and broke `aether/node` on CI for four commits. Run the jbct format+lint gate (`mvn jbct:check`) on every touched module too — compilation green is not gate green, and a gate that aborts mid-reactor has NOT checked the modules ordered after the failure: re-run the WHOLE gate after fixing, never resume from the failed module.

**Generated-aggregate trap** — per-package codec aggregates (e.g. `DhtCodecsInvoke`) are GENERATED from `@Codec` sources; deleting the last `@Codec` type in a package deletes the aggregate on the next clean build, but stale `target/` artifacts keep an incremental root-reactor `install` GREEN on a tree CI fails. When a deletion removes the last annotated source in any package: `mvn clean install`, never incremental, before trusting local green.

**Test naming:** `methodName_scenario_expectation()`.

## Branching

```
main (stable — latest published release only)
  └── release-X.Y.Z (active development — short-lived PR branches, reviewed, merged by the owner)
```

- **One PR branch per ticket, off the release branch, merged back within the day.** Name it
  `fix/<issue>-<slug>` (or `feat/`, `chore/`, `docs/`). Every PR gets an adversarial review before the
  owner's merge (owner ruling 2026-09-05; the reviews found blocking defects in most PRs of the first
  week, which is the reason for the step). No long-lived branches: a branch that outlives its review
  round is rebased or re-merged onto the release tip, never left to diverge. Empty `know:` commits
  recording rulings go straight onto the release branch.
- **Never edit `CHANGELOG.md` in a PR.** Add `changelog.d/<issue>-<slug>.md` (see
  [`changelog.d/README.md`](changelog.d/README.md)); the CI check refuses a direct edit and a source
  change without a fragment.
- **"Fixes #N" never auto-closes here** — GitHub only auto-closes on the default branch, and all work merges to the release branch. Close referenced issues manually (with a PR-citing comment) as part of every merge round.
- **Closing comments name the stale surfaces.** When a fix lands, the closing comment must name (and the fix should sweep) the docstrings, comments, and docs that described the OLD behavior — stale descriptions mint wrong tickets. Evidence: 2026-08-27, four tickets in one day carried materially wrong premises because fixes landed and the describing surfaces did not move; one stale docstring propagated its obsolete model into its ticket's own diagnosis.
- Release branch merges to `main` when ready → tag `vX.Y.Z` → publish to Maven Central.
- Slash commands: `/new-release-branch`, `/pre-release-check`, `/release-check`, `/release`, `/wrap-up`.

`v<version>-candidate` is a moving tag that tracks WIP state of the current release branch — re-create on HEAD after each batch of commits.

## Triage before fixing — match the fix to the issue

Before fixing any issue, triage it: **local** (one site, one wrong line, one
missing guard) or **structural** (a symptom of a missing/wrong abstraction that
already surfaces — or will — as several symptoms)?

- **Zoom out first.** Before reaching for the edit, look at the bigger picture:
  does this symptom share a root with others? Would the same class of bug recur
  elsewhere? Triage is cheap; a misclassified fix is expensive.
- **Fix at the matching level.** Structural issue → structural fix (eliminate
  the class). Local issue → local fix (don't manufacture an overhaul).
- **Both mismatches fail.** Patching a structural problem locally → whack-a-mole
  across symptoms. Over-engineering a local problem → speculative complexity.
- When triage flips an issue to structural mid-task, surface it with the
  trade-offs before committing to the big fix.

## Spec implementation is "done" only when reconciled

A spec/plan implementation is not done until all three hold — partial is failure:

1. **End-to-end** — every spec section has working code through the full path
   (e.g. CLI → API → KV → runtime), not just the entry point.
2. **Verified** — tested (unit + integration as the change warrants) and
   actually run. Show the evidence; don't assert it.
3. **Reconciled** — walk spec → code section by section and tag each item
   DONE / MISSING / STUB / SHORTCUT / OMISSION / SIMPLIFICATION. Commit only when
   MISSING = STUB = SHORTCUT = OMISSION = SIMPLIFICATION = 0.

Disqualifying: placeholder `Option.empty()`, `TODO`/`FIXME`, thrown
"not implemented", or any "simplified for now" left in the path.

## Sequencing: risk-first, observability-first hardening

Stabilize the foundation before stacking features; make complex subsystems observable
before hardening them. **Big-bang (implement everything, then test/fix once) is rejected** —
failures across interacting changes can't be localized, and a degraded core masks
downstream failures (one under-load reconciler bug has dragged multiple unrelated suites red).

- **Order by risk, not by milestone or difficulty.** Rank work by
  interaction-risk × blast-radius × observability-gap. A 10-line change that everything
  leader-pinned funnels through outranks a large self-contained feature. Foundational
  topology / provisioning / consensus / reconciler-under-load items go FIRST; features
  build on the stabilized core.
- **Observability-first — you can't harden what you can't see.** For ANY complex
  topology / provisioning / consensus / reconciler / membership issue, adding an
  operator-visible observability surface is PART of the fix — not optional, not a
  test-only hook. It pays three times: unblocks the current diagnosis, becomes a
  regression sensor, and ships as production ops / LLM-ops telemetry. Build it as a
  first-class, versioned Management-API surface (snapshot reads, no hot-path cost —
  additive capture of values the code already computes), never log-scraping. Observe
  enough to localize, then fix — don't let instrumentation become a yak-shave that
  defers the actual hardening.
- **Tickets are hypotheses, not specs.** A ticket's framing is often wrong until
  build→observe→reframe corrects it (e.g. "provisionNode fails 49×" was logic-sound +
  environmental; the "obvious" fix was documented-wrong and would have reintroduced a
  known over-provisioning death-spiral). Don't implement N tickets as-written and test
  once — you bake in N wrong framings and can't tell which one broke.
- **Incremental, with a validation gate between foundational changes.** Land each core
  fix behind a gate: a fast in-JVM (Forge/Ember) proof first, the expensive cloud sweep
  as the FINAL gate, never the primary debug surface. Strong in-JVM coverage — not
  stacking unverified changes — is how cloud cost is amortized.
- **Batch only the genuinely independent.** Docs, quick-wins, and additive features with
  no shared state can be batched freely; they don't mis-frame and don't interact.

## Project-specific invariants

### 1. REST API → CLI → Docs → Dashboard quad
Adding a Management API endpoint requires updating ALL four layers or the feature is incomplete:
1. **REST routes** in `aether/node` (`*Routes.java` + `ManagementServer` wiring + `ManagementRoute` enum entry)
2. **CLI command** in `aether/cli/AetherCli.java` (subcommand + registration)
3. **Docs** in `aether/docs/reference/management-api.md` + `aether/docs/reference/cli.md`
4. **Dashboard surface** — a panel/field per the #495 spec, or an explicit dormant-slot decision recorded on #494 (owner ruling 2026-07-20: dashboard is ontology-shaped and functionally complete; dormant dimensions show true degenerate values, never fabricated ones)

### 2. Feature Catalog + Changelog
- [`aether/docs/reference/feature-catalog.md`](aether/docs/reference/feature-catalog.md) — Aether capability inventory with Complete/Partial/Planned status. Update when features are added, completed, or a gap is discovered.
- `CHANGELOG.md` — per-release Keep-a-Changelog. Update for every significant change.

### 3. Envelope version bump
Changes to `slice-processor` code generation (`FactoryGenerator`, `ManifestGenerator` output structure) require bumping `ENVELOPE_FORMAT_VERSION` in `ManifestGenerator.java`. See [`aether/docs/contributors/envelope-versioning.md`](aether/docs/contributors/envelope-versioning.md).

### 4. BSL license headers
Files under `aether/**`, `jbct/slice-processor/`, `jbct/slice-processor-tests/` carry the SPDX `BUSL-1.1` header template at `docs/legal/bsl-header.txt`. Bulk applicator: `tools/license/apply-bsl.sh`. Don't re-license or drop these headers without explicit approval.

### 5. Integration test environment
- Remote host reachable as `$TARGET_HOST`, SSH key at `$AETHER_SSH_KEY`, user `$AETHER_SSH_USER`. Never inline these values — reference by name only.
- Cluster A (non-destructive, parallel): `aether/tests/integration/docker-compose-a.yml`.
- Cluster B (destructive, sequential): `aether/tests/integration/docker-compose-b.yml` — `restart: "no"` policy (destructive tests require `docker kill` to be authoritative).
- Run: `cd aether/tests/integration && ./run-tests.sh --env remote [--skip-build] [--suites N,M]`.
- `AETHER_INSECURE_DEV_MODE=true` is set inside compose env for all cluster containers (C2 security gate).

### 6. Claim discipline — evidence tags
Applies to every guarantee stated anywhere user-visible: docs, `CHANGELOG.md`, feature catalog,
issue-closing comments, release material. Complements the consistency-lens rule in the global
`~/.claude/CLAUDE.md` (which bans one-bit labels); this governs *what backs* a claim.

Each guarantee carries exactly one of:

- **`[verified: <test path>]`** — exercised **end-to-end on the live path**. A unit test standing in
  for the operation does NOT qualify. The bar is the feature catalog's *Integration-verified*:
  multi-node with failure injection. Evidence this bar is necessary: `./build.sh` stayed green with
  declarative consumers entirely disabled; a mapping-level test passed while the route returned 500;
  2915 unit tests were green while forge hung for 30 minutes.
- **`[mechanism: <one line>]`** — follows from a design property (quorum arithmetic, Rabia's
  `weak_mvc.ivy` formal spec).
- **`[design intent — unverified]`** — believed, not demonstrated. **This is the DEFAULT for
  failure-mode claims.** Design intent is regularly wrong in ways that read as correct: the backfill
  tie-break was logically sound (lowest NodeId wins) and still livelocked, because the designated
  winner sat on a different code path and never participated.

If a claim fits none of the three, it does not ship. Between two candidate phrasings, choose the
weaker one.

**A guarantee statement is incomplete without the operator's recovery action** — what clears the
state, not only what happens. Example: a FAILED schema migration holds its blueprint's slices and
clears via `aether schema retry` / `baseline` or a redeploy.

Once `aether/docs/reference/ga-envelope.md` exists it is the **single source of truth** for guarantee
claims; every other surface links to or quotes it and never paraphrases it.
