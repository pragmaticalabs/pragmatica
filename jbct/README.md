# JBCT CLI and Maven Plugin

Code formatting, linting, and violation-density reporting for Java Backend Coding Technology (JBCT).

## Overview

Provides a CLI tool and Maven plugin for enforcing JBCT coding standards. Features include source
code formatting with method chain alignment, a linter, violation-density reporting (violations per
KLOC), project scaffolding, slice project verification, and AI tooling integration.

The methodology itself is documented at [pragmatica.dev](https://pragmatica.dev). This README covers
only how to run the tools.

## Usage

### CLI

```bash
jbct format src/main/java          # Format in-place
jbct format --check src/main/java  # Check formatting (CI)
jbct lint src/main/java            # Check JBCT compliance
jbct check src/main/java           # Combined format-check + lint
jbct score src/main/java           # Report violation density (violations per KLOC)
jbct init my-project               # Scaffold a project (Aether slice by default; --no-slice for plain)
jbct verify-slice                  # Validate slice configuration
jbct upgrade                       # Self-update to latest version
```

`jbct --help` lists the full command set, including the slice scaffolding and reporting commands.

### Maven Plugin

```xml
<plugin>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>jbct-maven-plugin</artifactId>
    <version>1.0.0-rc4</version>
</plugin>
```

Code-quality goals: `jbct:format`, `jbct:format-check`, `jbct:lint`, `jbct:check`, `jbct:score`.

Slice and blueprint goals: `jbct:process`, `jbct:collect-slice-deps`, `jbct:verify-slice`,
`jbct:package-slices`, `jbct:install-slices`, `jbct:deploy-slices`, `jbct:generate-blueprint`,
`jbct:package-blueprint`.

### Violation density

`jbct score` / `jbct:score` reports **violations per 1000 non-blank source lines** — an unbounded
metric where **lower is better**. Every ratio is printed next to the raw counts it came from
(violations, ERROR/WARNING/INFO split, LOC, file count), because a small denominator turns a single
violation into a large ratio: one finding in a 90-line module is `11.1/KLOC`.

`STYLE` is an *advisory* category — formatting, logging, ordering and zone-naming rules are measured
and reported, but excluded from the total so they cannot inflate the headline number.

Gate the build with a maximum, which fails when density is **above** it:

```bash
jbct score --max-density 25 src/main/java     # CLI
mvn jbct:score -Djbct.density.maxPerKloc=25   # Maven
```

The previous 0-100 compliance score and its `--baseline` / `jbct.score.baseline` gate (which failed
*below* a threshold) are gone. Both names are rejected with migration guidance rather than
re-interpreted, since carrying a threshold across the inversion would silently assert its opposite.

### Configuration

Uses `jbct.toml` for project configuration, merged from the repository root down to the working
directory:

```toml
[project]
basePackage = "com.example"

[format]
maxLineLength = 120
indentSize = 4

[lint]
failOnWarning = false
# excludePackages = ["some.generated.**"]
```

An optional `[lint.layers]` section maps packages to layers and slice roots for the architecture
rules; omit it to rely on the naming conventions described below.

### Architecture / layering rules

The layering rules (`JBCT-ARCH-*`, `JBCT-MIX-01`) classify each file's package into a **layer**
(`domain` / `application` / `adapter` / `bootstrap`) and a **zone** (`business` / `adapter-boundary`)
from single-file facts — the file's own `package` declaration and its imports. Classification is
**convention-first**: with no `[lint.layers]` config, a package is classified by the layer keyword
among its dotted segments (`domain`; `application` / `usecase`; `adapter` / `integration` / `infra`;
`bootstrap` / `main`), deepest segment winning. Explicit `[lint.layers]` globs override the
conventions. A package that matches neither is left unclassified and produces no layering
diagnostics.

**Coverage summary.** Because unclassified packages are silent, a narrow `[lint.layers]` config can
enforce almost nothing while the run still reports clean. Whenever an explicit `[lint.layers]`
section is present, each run of `lint` / `check` / `score` prints one line —
`layering: evaluated 46 of 3458 files, 3412 unclassified` (plus `, N excluded` when
`excludePackages` skipped files) — so the enforced fraction is visible. With no explicit section the
summary is omitted, since conventions alone leave most real package names unclassified by design. On
the CLI it goes to stderr, so `--format json` / `sarif` output stays machine-parseable.

**Known limits.** These rules are **syntax-only**: they see package strings and imports, not resolved
types, and perform **no transitive-dependency analysis** — each import is judged on its own. A target
reached through a star import or (for a use case) a non-`*UseCase`-named type is not resolvable from
single-file facts; a project package whose segment happens to be a layer keyword without layering
intent may be misclassified (reclassify it via `[lint.layers]` or `@SuppressWarnings`).

## JBCT Specification

The full Java Backend Coding Technology specification is published at
[pragmatica.dev](https://pragmatica.dev).

## Dependencies

- Java 25+
- Maven 3.9+ (for plugin)
