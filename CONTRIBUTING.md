# Contributing to Pragmatica

This is a monorepo: **Pragmatica Core** (functional-programming library — `Result<T>`/`Option<T>`/
`Promise<T>`), **JBCT** (the coding-standard tooling that lints/formats this codebase), and
**Aether** (the distributed application runtime). They have different licenses (see below) and
different maturity levels, but share one build and one contribution process.

For anything beyond a small fix, please open an issue first to discuss the approach before
investing in a PR — this is a fast-moving pre-GA codebase and some things you'd expect to be
stable (APIs under `aether/`, in particular) are still in flux.

**Found a security issue?** Do not open a public issue — see [SECURITY.md](SECURITY.md) for
private reporting.

## License implications of contributing — read this first

This repository is dual-licensed by directory; **your contribution takes the license of the
directory it lands in.** From [`LICENSE`](LICENSE):

| Path | License |
|---|---|
| `core/`, `integrations/`, `examples/`, `jbct/` (except the two paths below) | Apache-2.0 |
| `aether/`, `jbct/slice-processor/`, `jbct/slice-processor-tests/` | Business Source License 1.1 |

**BSL-1.1 is not an OSI-approved open-source license.** If your PR touches `aether/` or the
slice-processor, you are contributing to a source-available codebase whose Change Date is
2030-01-01 (after which that code relicenses to Apache-2.0), and whose Additional Use Grant
excludes Managed Service / SaaS offerings — the Licensor (Pragmatica Labs) retains that
commercial right in the meantime. Full terms: [`aether/LICENSE`](aether/LICENSE). By submitting a
PR you agree your contribution is offered under the license terms of the module it's submitted
to — there is no separate CLA in this repository as of this writing.

New source files need the SPDX header matching their module:

- BSL-1.1 files carry the exact block in [`docs/legal/bsl-header.txt`](docs/legal/bsl-header.txt):
  ```
  // SPDX-License-Identifier: BUSL-1.1
  // Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
  // Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
  // See LICENSE in the repository root for full terms.
  ```
- Apache-2.0 files may carry a shorter header or none; the module's own `LICENSE` file governs
  either way (per `LICENSE` at the repo root).

## Development setup

Prerequisites: Java 25+, Maven 3.9+, Docker (Aether's integration tests use it).

The authoritative local build gate is `./build.sh` at the repo root
[mechanism: `build.sh`]. It bootstraps the annotation processors, then runs JBCT's format+lint
gate (auto-fixing formatting, failing on lint errors), then installs every module, compiles the
e2e/Forge test sources, builds the test blueprints, and lints the integration-test infra. Run it
before opening a PR.

**Why run `build.sh` and not just wait for CI:** CI's JBCT step runs the *non-mutating* `check`
goal — it fails on formatting drift but does not fix it for you [mechanism:
`.github/workflows/ci.yml`, "JBCT format + lint gate" step]. `build.sh` runs the mutating
`process` goal, which reformats in place. Running `build.sh` locally first means you fix
formatting issues once, locally, instead of round-tripping through a CI failure.

**What the JBCT gate does and does not cover:** it examines `src/main/java` only. Test sources are
excluded from every JBCT goal by default (`jbct.includeTests=false`), so a **test-only** module —
`aether/forge/forge-tests`, for instance — is not format- or lint-checked at all, and running
`jbct:check` there is a no-op. That is deliberate policy, not an oversight (#624, #740); whether the
gate should extend to test trees is an open question, not a settled yes. The goals now say so out
loud rather than reporting a bare success: when files exist but were excluded, they warn that the
module was **not** examined and name the reason, so a green result is never mistaken for coverage.
Pass `-Djbct.includeTests=true` to check a test tree by hand.

For quick iteration on a single module: `mvn test -pl <module>`. The full matrix CI actually runs
is in [`.github/workflows/ci.yml`](.github/workflows/ci.yml) — format+lint check, `mvn install
-pl '!examples'`, the postgres-async integration suite, an end-to-end `mvn verify` over
`examples/`, and a separate Forge-tests job (`-Pwith-e2e`) [mechanism:
`.github/workflows/ci.yml`]. There is no separate staging environment; passing this matrix is the
bar for merge.

**`build.sh` does not run a cluster — run `./forge.sh` before pushing.** Steps 4-5 of `build.sh`
*compile* the e2e and Forge tests; nothing local executes them [mechanism: `build.sh` step 4 runs
`compile test-compile`]. Forge is the only gate that starts a real multi-node cluster — in-JVM,
3-7 nodes, real consensus, real streams, real deployment FSM — and it catches the one class of
defect unit tests structurally cannot: a change that compiles, lints, passes every unit suite, and
then hangs or livelocks a live cluster. That is not hypothetical; a deployment-FSM and KV-codec
change once passed `build.sh` and 2915 unit tests with zero failures, then wedged Forge for 30
minutes with zero failing assertions.

```bash
./forge.sh          # smoke set: formation + deployment/invocation + one stream path
./forge.sh ci       # what CI runs (everything except @Tag("Heavy"))
./forge.sh full     # everything, Heavy probes included
```

The smoke set is the pre-push expectation, and it is **required** for changes touching
`aether/aether-deployment`, `aether/aether-stream`, `aether/slice/**/kvstore/**`,
`integrations/consensus`, or node runtime wiring. The cost argument is the whole point: the smoke
set takes a couple of minutes, while the same defect found in CI costs a 30-minute job, a red
branch, and the diagnosis.

## Branches, commits, and pull requests

- Fork the repository and branch off `main`.
- This project's history uses `<type>/<issue-number>-<short-slug>` branch names (e.g.
  `fix/613-javax-parent-first`, `feat/619-nest-directive`, `docs/608-forge-debug`) — follow it
  when you have a tracking issue, but it isn't enforced.
- Commit subjects use conventional prefixes — `feat:`, `fix:`, `refactor:`, `chore:`, `docs:`,
  `test:` — which is the convention used throughout this project's history. A body explaining
  non-obvious "why" is welcome for external contributions (the maintainers' own internal-stream
  convention of single-line, no-body commits is specific to their release workflow and isn't
  expected of contributors).
- Open the PR against `main`. A maintainer will review, request changes if needed, and choose the
  merge strategy at merge time.
- Do not edit `CHANGELOG.md`. Add your entry as `changelog.d/<issue-number>-<slug>.md` in the format
  described in [`changelog.d/README.md`](changelog.d/README.md); the file is assembled at release
  prep, and a CI check refuses a direct edit. Documentation-only PRs need no fragment.

### Sign-off

There is no automated DCO or CLA check gating PRs in this repository today [mechanism: `.github/`
contains only `ci.yml` and `release.yml` — no DCO/CLA bot is configured]. We nonetheless ask that
you certify the provenance of your contribution by adding a `Signed-off-by` trailer (`git commit
-s`), per the [Developer Certificate of Origin](https://developercertificate.org/). This is a
request, not (yet) an enforced gate — expect it to become one before GA, given the dual-license
surface above.

## Code of conduct

Be respectful, assume good faith, and keep disagreement about code, not people. Harassment,
personal attacks, and discriminatory language aren't tolerated. Maintainers may edit or remove
comments, close discussions, or block repeat offenders at their discretion. If you experience or
witness behavior that violates this, report it via the same private channel as a security issue
(see [SECURITY.md](SECURITY.md)) rather than in a public thread.

## Reference material

- [`README.md`](README.md) — module map and quick start.
- [`SECURITY.md`](SECURITY.md) — vulnerability reporting and Aether's trust model.
- [`LICENSE`](LICENSE) — the license map; [`aether/LICENSE`](aether/LICENSE) for the full BSL-1.1
  text; [`LICENSE-APACHE-2.0`](LICENSE-APACHE-2.0) for the full Apache-2.0 text.
- [`build.sh`](build.sh) and [`.github/workflows/ci.yml`](.github/workflows/ci.yml) — the build
  and CI gate, respectively.
