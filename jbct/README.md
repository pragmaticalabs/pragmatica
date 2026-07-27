# JBCT CLI and Maven Plugin

Code formatting, linting, and violation-density reporting for Java Backend Coding Technology (JBCT).

## Overview

Provides a CLI tool and Maven plugin for enforcing JBCT coding standards. Features include source code formatting with method chain alignment, a linter with 40 rules across 14 categories, violation-density reporting (violations per KLOC), project scaffolding, slice project verification, and AI tooling integration.

## Usage

### CLI

```bash
jbct format src/main/java          # Format in-place
jbct format --check src/main/java  # Check formatting (CI)
jbct lint src/main/java            # Check JBCT compliance
jbct check src/main/java           # Combined format-check + lint
jbct score src/main/java           # Report violation density (violations per KLOC)
jbct init my-project               # Create new JBCT project
jbct init --slice my-service       # Create Aether slice project
jbct upgrade                       # Self-update to latest version
jbct verify-slice                  # Validate slice configuration
```

### Maven Plugin

```xml
<plugin>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>jbct-maven-plugin</artifactId>
    <version>1.0.0-rc3</version>
</plugin>
```

Goals: `jbct:format`, `jbct:format-check`, `jbct:lint`, `jbct:check`, `jbct:score`, `jbct:collect-slice-deps`, `jbct:verify-slice`.

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

Uses `jbct.toml` for project configuration:

```toml
[format]
maxLineLength = 120
indentSize = 4

[lint]
failOnWarning = false
# excludePackages = ["some.generated.**"]

# Optional — package classification for the architecture / layering rules (JBCT-ARCH-*).
# Omit entirely to rely on the book-layout conventions below.
# [lint.layers]
# domain      = ["com.example.core.**"]
# application = ["com.example.app.**"]
# adapter     = ["com.example.adapter.**", "com.example.integration.**"]
# bootstrap   = ["com.example.boot.**"]
# slices      = ["com.example.usecase.*"]
```

### Lint Rules (40 total)

Categories: Return Kinds, Value Objects, Exceptions, Naming, Lambda/Composition, Patterns, Style, Logging, Architecture/Layering, Static Imports, Utilities, Nesting, Zones, Sealed Types, Acronyms.

#### Architecture / Layering (JBCT-ARCH-*, JBCT-MIX-01)

The layering rules classify each file's package into a **layer** (`domain` / `application` /
`adapter` / `bootstrap`) and a **zone** (`business` / `adapter-boundary`) from single-file facts —
the file's own `package` declaration and its imports. Classification is **convention-first**: with no
`[lint.layers]` config, a package is classified by the layer keyword among its dotted segments
(`domain`; `application` / `usecase`; `adapter` / `integration` / `infra`; `bootstrap` / `main`),
deepest segment winning. Explicit `[lint.layers]` globs override the conventions. A package that
matches neither is left unclassified and produces no layering diagnostics.

- **JBCT-ARCH-01** — dependency direction: imports point up only
  (`domain <- application <- adapter <- bootstrap`); a domain file may not import framework packages.
- **JBCT-ARCH-02** — `lift(...)` (foreign-exception→`Cause` conversion) is confined to the
  adapter-boundary zone.
- **JBCT-ARCH-03** — a use case (`*UseCase` name, or an interface extending the `UseCase` marker)
  must not reference another `*UseCase`; extract a shared step instead.
- **JBCT-ARCH-04** — a file must not import another slice's internals. Slice roots come from
  `[lint.layers] slices` globs or, by convention, the immediate child of a `usecase` segment
  (`com.example.usecase.registeruser`); anything strictly beneath a root is that slice's internal.
- **JBCT-MIX-01** — the focused JDK-I/O specialization: no `java.io` / `java.nio` / ... imports in a
  domain package.

**Known limits.** These rules are **syntax-only**: they see package strings and imports, not resolved
types, and perform **no transitive-dependency analysis** — each import is judged on its own. A target
reached through a star import or (for a use case) a non-`*UseCase`-named type is not resolvable from
single-file facts; a project package whose segment happens to be a layer keyword without layering
intent may be misclassified (reclassify it via `[lint.layers]` or `@SuppressWarnings`).


## JBCT Specification

The full Java Backend Coding Technology specification — including structural patterns (Leaf, Sequencer, Fork-Join, Condition, Iteration), zone architecture, and BPMN integration guide — lives in the [coding-technology](https://github.com/siy/coding-technology) repository.

Key references:
- **Structural patterns** → BPMN correspondence: Leaf↔Task, Sequencer↔Sequence Flow, Fork-Join↔Parallel Gateway, Condition↔Exclusive Gateway, Iteration↔Multi-Instance Activity
- **BPMN Integration Guide** → `coding-technology/BPMN-INTEGRATION-GUIDE.md`

## Dependencies

- Java 25+
- Maven 3.9+ (for plugin)
