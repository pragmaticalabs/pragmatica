# JBCT Single-Pass Processing

## Design Specification

**Version:** 0.1
**Status:** Draft
**Target Release:** 1.0.0-rc1
**Author:** Design team
**Last Updated:** 2026-05-08

---

## Table of Contents

1. [Motivation](#1-motivation)
2. [Current Behavior](#2-current-behavior)
3. [Target Behavior](#3-target-behavior)
4. [Architecture](#4-architecture)
5. [Mojo Surface](#5-mojo-surface)
6. [Library Refactor](#6-library-refactor)
7. [Configuration](#7-configuration)
8. [Backward Compatibility](#8-backward-compatibility)
9. [Edge Cases](#9-edge-cases)
10. [Implementation Phases](#10-implementation-phases)
11. [Open Questions](#11-open-questions)

---

## 1. Motivation

### 1.1 The Gap

The JBCT Maven plugin exposes two goals — `format` and `lint` — both of which parse every input `.java` file into a CST via `Java25Parser` and then operate on the resulting tree. Today these goals are bound side-by-side in the root pom's `pluginManagement`:

```xml
<executions>
    <execution>
        <goals>
            <goal>format</goal>
            <goal>lint</goal>
        </goals>
    </execution>
</executions>
```

Maven invokes these as **sequential, independent Mojo instances** with no shared state. Each goal's implementation parses every file independently:

- `FormatMojo` → `JbctFormatter` → `FlowFormatter.java:60-62` constructs `new Java25Parser()` per file and parses.
- `LintMojo` → `JbctLinter` → `CstLinter.java:69` reuses one `Java25Parser` instance across files (so packrat memoization helps within the lint goal) but never sees the format goal's parse trees.

Net cost: **every `.java` file is parsed at least twice in a full build** — once during format, once during lint.

### 1.2 Why This Matters

Parsing is the dominant cost in `format` and `lint`. The downstream operations (whitespace reformatting, lint-rule evaluation) are fast walks over the CST. Eliminating one of every two parses approximately halves the wall-clock cost of `mvn install` for the affected modules.

A naive cache (parse once, store CST in a map for both goals to consume) is rejected for memory reasons: CSTs for non-trivial files are large, and caching all of them across the file set is not feasible. The right approach is **single-pass orchestration** — process each file with both lint and format while its CST is in memory, then discard the CST before moving to the next file.

### 1.3 Why Now

The change is independently valuable, but two adjacent factors raise its priority:

- The 2026-05-04 migration to `peglib-maven-plugin` 0.4.2 makes the parser hot path more visible — both `Java25Parser.java` and `PgSqlParser.java` are now regenerated from grammar files and continue to be parsed by both goals during normal builds.
- An upcoming peglib performance optimization (in flight on the `java-peglib` side) will further reduce per-parse cost. The single-pass refactor compounds with that improvement: cutting two parses to one halves whatever the optimized parser cost ends up being.

---

## 2. Current Behavior

### 2.1 Format Goal — `format`

**Mojo:** `jbct/jbct-maven-plugin/src/main/java/org/pragmatica/jbct/maven/FormatMojo.java`

Iteration shape:
```java
for (var file : filesToProcess) {
    formatter.format(source);   // → FlowFormatter constructs new Java25Parser per file
}
```

Per-file cost: parser construction + parse + format text generation + write-if-changed.

Skip behavior: files exceeding 1 MB are skipped (`Skipping <file> (file size N bytes exceeds limit of 1048576 bytes)`).

### 2.2 Lint Goal — `lint`

**Mojo:** `jbct/jbct-maven-plugin/src/main/java/org/pragmatica/jbct/maven/LintMojo.java`

Iteration shape:
```java
for (var file : filesToProcess) {
    linter.lint(source);   // → CstLinter reuses a single Java25Parser; packrat cache across files
}
```

Per-file cost: parse (with packrat memoization sometimes saving work across rule positions) + rule evaluation + diagnostic emission.

Skip behavior: no per-file size limit today.

### 2.3 Net Cost in a Full Build

When the root pom binds both goals to a single execution, Maven runs `format` to completion across all files, then runs `lint` to completion across all files. Each file is parsed twice; no parse tree is reused.

---

## 3. Target Behavior

### 3.1 Single-Pass Orchestration

A new `process` goal iterates files once and, for each file:

1. Read source.
2. Apply size limit — skip both format and lint if file exceeds the configured threshold.
3. Parse to CST (one parser instance reused across files, packrat enabled).
4. If parse fails — emit parse error and continue.
5. **Run lint analysis on the CST** (read-only, collect diagnostics).
6. **Run format on the CST** (read-only, produce reformatted text).
7. Write back if the format output differs from the input.
8. Emit lint diagnostics.
9. Drop the CST (GC-eligible at next iteration).

Constant memory: one CST live at a time. One parse per file. No cache infrastructure.

### 3.2 Order: Lint Before Format

Rationale: lint diagnostics carry line/column numbers tied to source content. If format runs first and writes a new file, the user reads lint messages that reference content the user just had reformatted underneath them — confusing. Lint first means diagnostics reference the as-authored code that produced them.

This is a per-file ordering inside the loop. Both passes consume the CST read-only; no interaction risk.

### 3.3 Lint Failure vs. Format Write

**Default — permissive:** format writes regardless of lint state; lint failures still fail the build at the end. Format is idempotent on already-formatted code, so the cost of always-writing is near zero, and dev workflows benefit from auto-format even when lint is reporting errors.

**Configurable — strict:** `<failBeforeFormat>true</failBeforeFormat>` makes lint errors at fail-threshold short-circuit the loop before format writes. CI pipelines that gate on lint-clean may prefer this.

Default = permissive.

---

## 4. Architecture

### 4.1 Layering

| Layer | Module | Change |
|---|---|---|
| Mojo | `jbct/jbct-maven-plugin` | New `ProcessMojo`. Existing `FormatMojo` and `LintMojo` retained. |
| Library | `jbct/jbct-format` | `FlowFormatter` extracts `formatTree(CstNode, Source)` from `format(Source)`. |
| Library | `jbct/jbct-lint` | `CstLinter` extracts `lintTree(CstNode, Source)` from `lint(Source)`. |
| Library | `jbct/jbct-maven-plugin` (new helper) | Shared parser-acquisition + size-limit + orchestration logic in `AbstractJbctMojo`. |

No new module. No new dependency edges.

### 4.2 Per-File Inner Loop

```java
// inside ProcessMojo
for (var file : filesToProcess) {
    var source = readSource(file);
    if (source.exceedsLimit(sizeLimit)) {
        log.info("Skipping " + file + " (size " + source.size() + " bytes exceeds " + sizeLimit + ")");
        skippedCount++;
        continue;
    }
    var parseResult = parser.parseWithDiagnostics(source.content());
    if (parseResult.failed()) {
        emitParseError(file, parseResult.diagnostics());
        parseFailureCount++;
        continue;
    }
    var tree = parseResult.tree();
    var lintReport = linter.lintTree(tree, source);
    var formatted = formatter.formatTree(tree, source);
    if (failBeforeFormat && lintReport.hasErrors()) {
        emitLintDiagnostics(lintReport);
        lintFailureCount++;
        continue;   // skip format write
    }
    if (formatted.changed()) {
        writeSource(file, formatted.content());
        formattedCount++;
    }
    emitLintDiagnostics(lintReport);
    if (lintReport.hasErrors()) {
        lintFailureCount++;
    }
}
finalizeBuild(formattedCount, lintFailureCount, parseFailureCount, skippedCount);
```

### 4.3 Parser Reuse Strategy

`ProcessMojo` constructs one `Java25Parser` instance at the start of `execute()` and reuses it across all files in the goal execution (matching today's `CstLinter` pattern). Packrat memoization survives across files; the CST itself does not.

---

## 5. Mojo Surface

### 5.1 New `process` Goal

**Class:** `org.pragmatica.jbct.maven.ProcessMojo`
**Goal name:** `process`
**Default phase:** `process-sources`
**Thread-safe:** yes

Configuration parameters:

| Parameter | Type | Default | Description |
|---|---|---|---|
| `skip` | boolean | `false` | Skip the entire goal. |
| `sizeLimit` | long | `1048576` | Per-file byte size above which both format and lint are skipped. |
| `failBeforeFormat` | boolean | `false` | If true, lint errors at fail-threshold prevent the format write for that file. |
| `lintFailureLevel` | enum | `ERROR` | Lint diagnostic level that triggers build failure (consistent with current `LintMojo`). |
| `failOnWarning` | boolean | `false` | Promote warnings to build failures (consistent with current Mojos). |
| `includes` / `excludes` | path glob list | inherited from `AbstractJbctMojo` | File selection. |

### 5.2 Existing Goals Retained

`format` and `lint` continue to exist as published goals. Their internal implementations refactor to call the new tree-accepting library entry points after performing their own parsing — behavior unchanged for users invoking them directly. Some users may want only one of the two; this preserves that flexibility.

### 5.3 Root Pom Binding

Update `pragmatica-clone/pom.xml` `pluginManagement` for `jbct-maven-plugin`:

```xml
<executions>
    <execution>
        <goals>
            <goal>process</goal>
        </goals>
    </execution>
</executions>
```

(Was: `<goal>format</goal><goal>lint</goal>`.)

This is the only consumer-side change. Modules inheriting from the root pom automatically pick up single-pass behavior.

---

## 6. Library Refactor

### 6.1 `jbct-format` — `FlowFormatter`

Today (`FlowFormatter.java:60-62`):
```java
public FormatResult format(Source source) {
    var parser = new Java25Parser();
    var result = parser.parseWithDiagnostics(source.content());
    // ... format from result.tree()
}
```

Refactor to:
```java
public FormatResult format(Source source) {
    var parser = new Java25Parser();
    var result = parser.parseWithDiagnostics(source.content());
    if (result.failed()) { return FormatResult.parseFailed(result.diagnostics()); }
    return formatTree(result.tree(), source);
}

public FormatResult formatTree(CstNode tree, Source source) {
    // existing logic, lifted from format()
}
```

Backward-compatible: existing `format(Source)` callers untouched; new `formatTree(CstNode, Source)` available for orchestrators.

### 6.2 `jbct-lint` — `CstLinter`

Same pattern. `CstLinter.java:69` is inside a `lint(Source)` method that performs parse then rule evaluation. Extract `lintTree(CstNode, Source)`; have `lint(Source)` parse-then-delegate.

### 6.3 `JbctFormatter` and `JbctLinter` Facades

These wrapper classes exposed by the Mojos may need parallel signature additions. Keep the `(Source) → ...` entry points and add `(CstNode, Source) → ...` overloads. The orchestrator calls the latter; the standalone Mojos call the former.

---

## 7. Configuration

### 7.1 Plugin Defaults

The new goal inherits the existing plugin's `<configuration>` shape and adds the parameters listed in §5.1. No removals.

### 7.2 Skip Semantics

- `skip = true` → entire goal skipped.
- File-size limit hit → both format and lint skipped *for that file* (per user direction; uniform skip behavior, not per-goal).
- Parse failure → both format and lint skipped for that file; parse error emitted as a diagnostic.

### 7.3 Per-Module Override

Modules wanting different defaults override at their own pom level (standard Maven pattern). E.g., `jbct/jbct-parser` could disable the goal entirely if generated parsers cause noise (see §9.1).

---

## 8. Backward Compatibility

- `format` and `lint` goals **remain public, documented, and operational**. Users invoking `mvn jbct:format` or `mvn jbct:lint` see no behavior change.
- The root pom's plugin binding **changes** from `format,lint` to `process`. Any module inheriting that binding picks up single-pass behavior automatically.
- A module that had explicitly overridden the binding to bind only `format` or only `lint` retains its override unchanged.
- The library APIs gain new entry points; existing entry points are preserved.

No public-API breaking changes.

---

## 9. Edge Cases

### 9.1 Generated Parsers (PgSqlParser, Java25Parser)

Both regenerated parsers exceed the 1 MB size limit:
- `PgSqlParser.java`: ~4.5 MB
- `Java25Parser.java`: ~1.6 MB

Today, format skips them (size limit) and lint processes them. Under single-pass behavior with uniform size-limit skip, **both are skipped entirely**. This is the user's stated preference and matches the spirit of "generated code is exempt from JBCT discipline."

If a future enhancement wants lint coverage on generated code, a `@Generated`-marker recognition flow is the natural place to add it; orthogonal to this spec.

### 9.2 Parse Failures

A file that fails to parse cannot be linted or formatted. The new goal:

- Emits the parse diagnostic at the file:line where peglib detected the failure.
- Does not write a partial format result.
- Counts the file as a parse failure.
- If `failOnParseError = true` (new parameter, default `true`), the build fails after the loop completes.

Parse errors should be rare in normal builds; they typically indicate either a grammar bug or a syntax error in source.

### 9.3 Format-Idempotent Write Semantics

`formatted.changed()` compares the formatter output against the original source byte-for-byte. Files already in the canonical format incur no write. This makes always-writing-on-permissive-mode genuinely cheap.

### 9.4 Lint-Then-Format Ordering With Mutating Lint Rules

Some lint rules in the wider Java tooling ecosystem are auto-fixable (rewrite the AST). JBCT's lint is *not* auto-fixable today — rules are read-only. If auto-fix is added later, the order needs revisiting (auto-fix would mutate the tree, and format would need to run on the post-fix tree). For now, lint-then-format with read-only lint is correct.

### 9.5 Concurrent Builds

The Mojo is annotated `threadSafe = true` like today's siblings. The reused parser instance must be safe for sequential reuse on the same thread (already true — it's how `CstLinter` works today). Multi-threaded Maven builds will use one Mojo instance per thread, each with its own parser; no shared mutable state across threads.

---

## 10. Implementation Phases

### Phase 1 — Library Refactor

- Extract `formatTree(CstNode, Source)` in `FlowFormatter`.
- Extract `lintTree(CstNode, Source)` in `CstLinter`.
- Update `JbctFormatter` / `JbctLinter` facades to expose tree-accepting overloads.
- Tests: existing `format(Source)` and `lint(Source)` paths continue to pass; new tree-accepting paths covered by direct unit tests with hand-built CSTs.

Exit criteria: `mvn -pl jbct test` green.

### Phase 2 — `ProcessMojo`

- New `org.pragmatica.jbct.maven.ProcessMojo` class.
- File iteration, size-limit skip, parse + lint + format orchestration per §4.2.
- Configuration parameters per §5.1.
- Tests: integration test invoking the goal against a small fixture project; verifies one parse per file (instrument the parser with a counter for tests), constant memory, correct diagnostic output, format write only on change.

Exit criteria: `mvn jbct:process` works on a sample module; per-file parse count == 1.

### Phase 3 — Root Pom Binding Switch

- Update root `pom.xml` plugin binding from `format,lint` to `process`.
- Run a full reactor build; confirm no behavior regressions in any module.
- Document the change in `CHANGELOG.md` under `[1.0.0-rc1]`.

Exit criteria: `mvn install` (full reactor) green; no new lint/format diagnostics introduced; build wall-clock time measurably reduced (target: ≥ 30% reduction in `process-sources` phase wall-clock for medium-sized modules).

### Phase 4 — Documentation

- Update `jbct/README.md` to reference the new combined goal.
- Mark `format` and `lint` goals as "preserved for direct invocation; default builds use `process`."

Exit criteria: contributor docs reflect the new default behavior.

---

## 11. Open Questions

### 11.1 Goal Name

`process` is descriptive but Maven's `process-sources` and `process-resources` lifecycle phases use the same word, which can confuse log readers. Alternatives considered:

- `check` — generic, used by other ecosystems (Cargo, Spotless), implies pass/fail.
- `verify` — Maven phase already named this; conflict risk.
- `analyze` — emphasizes lint, underemphasizes format.
- `lint-and-format` — explicit but long.

**Recommendation:** `process`. Phase-name overlap is mostly cosmetic; the goal name is namespaced (`jbct:process`).

### 11.2 Parse Failure Default

Should the build fail by default on parse failure, or only when `failOnParseError = true` is set? Today's separate goals have implicit-fail behavior (an unparseable file blocks downstream work). The new goal should match.

**Recommendation:** default `failOnParseError = true`. Same as today's effective behavior.

### 11.3 Diagnostic Aggregation

When format writes succeed but lint reports errors across multiple files, how are errors aggregated? Today the goals report independently. The new goal could:

- Emit each error as it happens (interleaved with format-write log lines) — most informative.
- Buffer all errors and emit at end (cleaner output, but loses file-by-file context).

**Recommendation:** emit as they happen, per-file. Match Mojo logging conventions and avoid memory growth on large reactors.

### 11.4 Telemetry

Worth instrumenting the new Mojo with simple per-file timing (parse / lint / format / write) for one release cycle so we can quantify the wall-clock improvement empirically. Not a permanent feature — log at DEBUG level only.

**Recommendation:** add as TRACE/DEBUG logging, document in §10's Phase 3 exit criteria.

### 11.5 Future: `peglib` Migration

A peglib performance optimization is in flight. After this spec ships, migrating to the new peglib version is mechanical (version bump + regenerate parsers via `mvn -Pgenerate-parser ...`); the single-pass orchestration is independent of which parser version is in use. The two changes compose: this spec halves parses; the peglib upgrade reduces per-parse cost.

**Recommendation:** ship this spec first; migrate peglib separately.
