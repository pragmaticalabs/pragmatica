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
jbct skeleton [--fields] [--json] <file>...  # Types and members with exact row/column ranges
jbct init my-project               # Scaffold a project (Aether slice by default; --no-slice for plain)
jbct verify-slice                  # Validate slice configuration
jbct upgrade                       # Self-update to latest version
```

`jbct --help` lists the full command set, including the slice scaffolding and reporting commands. To install the CLI, run [`install.sh`](install.sh); it installs to `$JBCT_HOME` (default `~/.jbct`) and requires JDK 25+.

**Exit codes** are the same for `lint`, `check`, `score` and `format --check`, and they separate the
two things a gate needs to tell apart:

| Code | Meaning |
|------|---------|
| 0 | Everything collected was analysed, and nothing was found |
| 1 | Everything collected was analysed, and rule violations (or, with `--fail-on-warning`, warnings) were found |
| 2 | Some collected file could not be read or parsed, so the run cannot speak for it — and, for `lint` only, the run found no Java files at all (#1100) |

**Coverage is stated, never assumed.** A run that could not read every file it collected reports
`checked 1 of 2 file(s), 1 UNPARSEABLE` in its summary line and never renders as a pass, however
clean the files it did read were. The same clause appears on the Maven goals' `Lint results (...)` /
`Check results (...)` lines, and `score` renders it in its box header
(`JBCT DENSITY — 5 LOC, 1 of 2 files, 1 UNPARSEABLE`) alongside a `filesUnanalyzed` field in
`--format json`. Zero findings over a file the tool never analysed is silence, not compliance (#977).

**Machine formats carry their own coverage (#1100).** Most CI SARIF uploaders never read the exit status,
so `jbct lint --format json|sarif` states in the document itself what the run examined:

- `--format json` emits one object: `collected` (files found), `examined` (files analysed), `skipped`
  (`{file, reason}` for each file that could not be read or parsed) and `diagnostics`. A clean scan is
  `jq -e '.examined > 0 and (.skipped | length) == 0 and (.diagnostics | length) == 0'`.
- `--format sarif` adds `runs[0].invocations[0]`: `executionSuccessful` is `false` when any collected file
  could not be analysed or when no Java files were found, with one `error` notification per skipped file
  or one saying that nothing was examined. A clean scan is
  `jq -e '.runs[0] | .invocations[0].executionSuccessful and (.invocations[0].toolExecutionNotifications | length) == 0 and (.results | length) == 0'`.

A `lint` run that found no Java files exits `2`: examining nothing is a coverage gap, never a pass. `check`,
`score` and `format --check` still exit `0` in that case.

`shape-census` and `obligations` are reports rather than gates: they still exit 0 over a partial run,
but each states the gap rather than omitting it silently.

### `jbct skeleton`

Prints the types and members of Java files with the exact range each occupies, so that a reader (a person, a
script, a coding agent working inside a context budget) can open the one method it needs instead of reading a
5 000-line file. It is read-only and uses the same parse tree as `format` and `lint`.

```
jbct skeleton [--fields] [--json] <file-or-directory>...
```

One line per declaration, two spaces of indentation per nesting level:

```
<kind> <name><signature> <startRow>:<startCol>-<endRow>:<endCol>[ doc@<row>][ @Annotation ...]
```

An excerpt of the output for a small file:

```
class Docs 6:1-29:1 doc@3
  method three(): void 13:5-13:19 doc@10
  method annotated(): void 24:5-25:23 doc@21 @Deprecated
  record R 28:5-28:22 doc@27
```

- Kinds: `class`, `interface`, `record`, `enum`, `annotation`, `method`, `constructor` (a compact constructor is
  `Name(compact)`), `initializer`; with `--fields` also `field` and `enum-constant`. A declaration of several
  variables (`int a, b;`) is one range and one line per variable. Anonymous classes, local classes and lambdas are
  not listed: they lie inside the range of the member that holds them.
- Rows and columns are 1-based and the end is INCLUSIVE: `endRow:endCol` is the closing brace or the semicolon
  itself. A tab counts one column. Columns are UTF-16 code units (an emoji is 2), and a leading BOM counts as one
  column on row 1. A lone CR, a CRLF (one) and an LF each end a line, as the Java compiler counts them. The range
  starts at the first annotation or modifier and ends at the last token of the declaration, so trailing blank lines
  and comments are outside it.
- The doc comment (`///` lines or `/** */`) is outside the range; `doc@` is the first row of the NEAREST preceding
  doc comment (a run of consecutive `///` lines counts as one comment; blank lines between the comment and the
  declaration do not detach it).
- The signature is the source as written, whitespace collapsed. Legacy array forms (`int f()[]`, `int a[]`) are
  reported as written before the name: the brackets after the name are not in the signature, so `h(int a[])` reads
  `h(int)`. Annotations are listed by name wherever they sit among the modifiers; their arguments are not printed. An annotation directly before the type of a field (`final @A int f;`) belongs to the
  type for the parser and is not listed.
- `--json` prints one JSON array on stdout, always valid: one `{file, declarations}` object per file, each
  declaration carrying `kind`, `name`, `signature`, `startRow`, `startColumn`, `endRow`, `endColumn`, `docRow` (or
  `null`), `annotations` and `children`. A file that could not be read or parsed is an entry `{file, error}` with
  `error` holding `message`, `row` and `column` (both `null` when the failure has no position); no Java files at all
  is `[]`.
- A file that does not parse gets `<path>:<row>:<column>: <message>` on stderr, a file that cannot be read gets one
  line `<path>: <reason>`; neither gets a skeleton, never a partial one, and the other files still print.
- Exit codes: `0` every file printed; `2` no Java files found, or at least one file could not be read or parsed (as
  for `lint`, `check` and `score`).

### Maven Plugin

```xml
<plugin>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>jbct-maven-plugin</artifactId>
    <version>1.0.0-rc5</version>
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

An optional `[lint.layers]` section maps packages to layers and slice roots for the layering rules; omit it to
rely on the naming conventions described below. An optional `[lint.tree]` section holds `exclude` globs of
packages the package-tree rules do not judge (see below).

A `[lint.rules]` key must be the ID of a rule of the registry: an unknown key (a typo) refuses the load naming the
key, and a retired rule (`JBCT-ARCH-04`, `JBCT-SEAL-02`) names the rule that replaced it. `generate-blueprint`
reads the same configuration and stops on such a key too.

### Package tree rules

`[project] basePackage` is the root `R` of the package tree of a source root. In `business` mode, while a rule that
reads it is enabled, the key is required: a source root without it, with an `io` or `shared` segment after its first
segment, or with no file under it is refused (`jbct lint` exits 2, the Maven goal fails) while the code rules still
run and report. `infrastructure`, `format-only` and `disabled` roots never read the key.

- `JBCT-TREE-01` (WARNING): no sideways import inside the tree. A file in package `P` may import from `Q` when `Q` is
  outside `R`, an ancestor of `P`, a descendant of `P`, or sits under an `io` or `shared` segment whose parent is an
  ancestor of `P`. A workflow importing its sibling workflow is sideways.
- `JBCT-TREE-02` (WARNING): a main file is declared under `R`. Test roots are judged by no tree rule.

Both rules print a coverage line per run, `JBCT-TREE-01: judged K of N file(s)` (`0 of N` included).

### Architecture / layering rules

The layering rules (`JBCT-ARCH-01` to `JBCT-ARCH-03`, `JBCT-MIX-01`) classify each file's package into a **layer**
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
