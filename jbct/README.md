# JBCT CLI and Maven Plugin

Code formatting, linting, and compliance scoring for Java Backend Coding Technology (JBCT).

## Overview

Provides a CLI tool and Maven plugin for enforcing JBCT coding standards. Features include source code formatting with method chain alignment, a linter with 40 rules across 14 categories, compliance scoring (0-100), project scaffolding, slice project verification, and AI tooling integration.

## Usage

### CLI

```bash
jbct format src/main/java          # Format in-place
jbct format --check src/main/java  # Check formatting (CI)
jbct lint src/main/java            # Check JBCT compliance
jbct check src/main/java           # Combined format-check + lint
jbct score src/main/java           # Calculate compliance score (0-100)
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

**Coverage summary.** Because unclassified packages are silent, a narrow `[lint.layers]` config can
enforce almost nothing while the run still reports clean. Whenever an explicit `[lint.layers]`
section is present, each run of `lint` / `check` / `score` prints one line —
`layering: evaluated 46 of 3458 files, 3412 unclassified` (plus `, N excluded` when
`excludePackages` skipped files) — so the enforced fraction is visible. With no explicit section the
summary is omitted, since conventions alone leave most real package names unclassified by design. On
the CLI it goes to stderr, so `--format json` / `badge` / `sarif` output stays machine-parseable.

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
