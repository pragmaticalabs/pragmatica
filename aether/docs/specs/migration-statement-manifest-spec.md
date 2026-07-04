# Migration Statement Manifest — Design Specification

*Dialect-profile lexer + build-time split into a checksummed statement manifest.*

**Version:** 0.1.0
**Status:** Draft — writes up the direction settled in #255 (2026-06-10 design-completeness discussion)
**Date:** 2026-07-04
**Author:** design-stream
**Issue:** #255 (primary); scopes-in #408 (lint-path splitter reconcile, boundary part) and #409 (H2/SQLITE descriptors)
**Builds on:** #337 (shipped the runtime dialect-aware `sql-splitter`)
**Boundary:** `pg-persistence-spec.md` owns the compile-time PG *parser* (query validation, DDL analysis); this spec owns statement *splitting* and the manifest. The parser is a consumer here, never the splitter.

---

## 1. Problem

Three defects share one root — statement splitting is done in the wrong place, more than once, and not everywhere:

1. **Naive split still live.** H2/SQLITE migrations fall back to `AetherSchemaManager.executeStatementsNaive` (`aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/schema/AetherSchemaManager.java:573-576`): strip whole-line `--` comments, then `sql.split(";")`. A `;` inside a string literal, a `/* */` block comment, or a SQLITE `CREATE TRIGGER … BEGIN…END;` body is mis-split (#409).
2. **Split happens at apply time.** Even for the dialects #337 wired to the dialect-aware `StatementSplitter`, splitting runs on the node applying the migration — a mis-split (or a splitter bug) surfaces on the leader holding the schema lock, the worst possible place (the "stuck migration" ops scenario). `MigrationEntry` ships raw whole-file SQL plus a whole-file CRC32 (`aether/slice/src/main/java/org/pragmatica/aether/slice/blueprint/MigrationEntry.java:11`, checksum computed at `BlueprintArtifactParser.java:195-201`).
3. **Lint path and runtime path disagree** (#408). The compile-time codegen/validation stack splits with a *separate* grammar — `aether/pg-tools/pg-parser/src/main/resources/org/pragmatica/aether/pg/parser/postgres.peg` — whose `DollarString <- '$$' < (!'$$' .)* > '$$'` (`postgres.peg:494`) matches only untagged `$$…$$`, and whose `RestOfStatement <- (!';' .)*` (`postgres.peg:361`) is `;`-blind inside strings/comments/dollar bodies. What lints clean and what executes are split by two different algorithms.

Transaction wrapping is also whole-file today: a script containing `CREATE INDEX CONCURRENTLY` cannot express "this one statement must run outside a transaction" — the classification exists (`DialectSpec.isTransactional`) but is applied at apply time, per file.

## 2. Current substrate (verified)

| Piece | Where | State |
|---|---|---|
| Dialect-profile lexer, profiles as **data** | `aether/pg-tools/sql-splitter` — `DialectSpec` (string/identifier/comment/dollar-quote/boundary/copy-data rules + `classifier` + `blockStarter`), `StatementSplitter`, `Statement(text, startLine)`, `SplitError` | **Shipped** (#337) |
| Shipped profiles | `Dialects.POSTGRESQL / MYSQL / DB2 / SQLSERVER / ORACLE` | Shipped |
| Dialect wiring | `MigrationDialects.dialectFor` (`MigrationDialects.java:69`) — `H2, SQLITE -> none()` → naive fallback | Gap (#409) |
| Runtime execution | `AetherSchemaManager` splits at apply time; whole-file tx vs autocommit; per-statement resume via `statements_completed` checkpoint (`AetherSchemaManager.java:380-467`) | Split in wrong place |
| Blueprint carrier | `@Codec record MigrationEntry(String filename, String sql, long checksum)`; CRC32 over UTF-8 file bytes | No manifest |
| Lint-path splitter | `postgres.peg` top-level statement rules | Divergent (#408) |

The settled design's point 1 (lexer as data, conservative loud failure) is therefore **already built**. This spec is the remaining delta: move the split to the build boundary, make the result a first-class checksummed artifact, and make it the *only* splitter.

## 3. Design

### 3.1 One splitter, profiles as data (settled; exists)

`DialectSpec` stays the single source of lexical truth per dialect: comment markers, string/identifier quoting + escapes, dollar-quote tag matching, redefinable terminators (`DELIMITER`), batch separators, block-starter heuristic. Adding a dialect = populating a descriptor, not writing engine code. **Conservative failure** is retained: an unrecognized construct yields `SplitError` — never a silent mis-split.

### 3.2 Split at the build boundary, not at apply (settled)

The split runs **once**, where `MigrationEntry` is materialized — `BlueprintArtifactParser.addMigrationEntry` (`BlueprintArtifactParser.java:170-193`) at blueprint ingestion — and its output is stored in the entry as a **statement manifest**. A mis-split rejects the blueprint at upload/CI, before it reaches the KV store, before any node takes the schema lock.

The apply path (`AetherSchemaManager`) consumes the manifest and performs **zero parsing**: verify checksums, execute statements in manifest order, checkpoint `statements_completed` exactly as today. `executeStatementsNaive` is deleted once §3.5 lands.

### 3.3 Manifest format

```java
@Codec
public record MigrationEntry(String filename,
                             String sql,                 // verbatim source (audit/display/re-verify)
                             long checksum,              // whole-file CRC32 — identity in aether_schema_history,
                                                         // unchanged; still the R__ re-run trigger
                             StatementManifest manifest) {}

@Codec
public record StatementManifest(int formatVersion,       // MANIFEST_FORMAT_VERSION = 1
                                String dialectFamily,    // ExecutionDialect family the split ran under
                                                         // (e.g. "POSTGRESQL" covers COCKROACHDB)
                                List<ManifestStatement> statements) {}

@Codec
public record ManifestStatement(int index,               // 0-based execution order
                                String text,             // verbatim statement text, terminator stripped
                                                         // (as emitted by StatementSplitter.Statement)
                                long checksum,           // CRC32 over UTF-8 bytes of text
                                int startLine,           // 1-based source line (diagnostics)
                                boolean transactional) {} // false ⇒ must run outside a transaction
```

- **Checksum algorithm:** CRC32 (`java.util.zip.CRC32`), matching the existing whole-file checksum — one algorithm across the format. This guards *accidental* desync (hand-edited blueprint, codec bug), not tampering; blueprint upload is already an authenticated boundary. SHA-256 considered and rejected as guarantee-theater at this trust level.
- **Stability:** checksums are over the verbatim per-statement text, so reflowing whitespace/comments *between* statements does not invalidate them; editing a statement does — by design.
- **Format versioning:** `formatVersion` is checked at apply; an unknown version fails loudly (no best-effort parse). Pre-GA there is no migration shim for old entries — the blueprint format simply changes (no-backward-compat policy until GA).
- **`transactional`** is computed at build time via `DialectSpec.isTransactional` (e.g. PG `CREATE INDEX CONCURRENTLY`, `VACUUM` → `false`). Execution grouping keeps today's semantics: all-transactional file + `ddlTransactional` dialect → one transaction; any non-transactional statement → autocommit run with per-statement checkpointing. The flag moves the *classification* to build time and makes it per-statement-expressible; it does not introduce new grouping machinery.

### 3.4 Apply-time semantics

Ordered guard chain, all before the first statement executes:

1. `formatVersion` supported, else fail (`SchemaError.UnsupportedManifestVersion`).
2. `dialectFamily` matches `dialectFor(connector.config().effectiveType())`'s family, else fail (`SchemaError.DialectMismatch`) — a blueprint split for MySQL cannot run against PG.
3. Whole-file checksum matches history/checkpoint rows (existing rule — modified-after-applied detection, resume validation at `AetherSchemaManager.java:454-459`) — unchanged.
4. Every `ManifestStatement.checksum` verifies against its `text`, else fail (`SchemaError.ChecksumMismatch`, existing error) — a desynced manifest never partially executes.

Resume (`IN_PROGRESS`/`FAILED` at `statements_completed = K`) continues from statement K against the manifest — deterministic because the split is frozen in the artifact, not re-derived.

**Drift gate at ingestion:** `BlueprintArtifactParser` is the producer, so under normal flow `sql` and `manifest` cannot drift. If an externally-assembled blueprint later carries a pre-built manifest (open question Q2), ingestion re-splits `sql` and compares per-statement checksums — re-split verification at upload, zero parsing at apply.

### 3.5 H2 / SQLITE descriptors (#409)

`dialectFor` becomes total:

- **H2:** standard lexical descriptor (`;` terminator, standard strings/comments, `ddlTransactional=true`) — ≈ free, as #337's design predicted.
- **SQLITE:** same, plus the one keyword-aware primitive: `CREATE TRIGGER … BEGIN…END;` reuses the existing `blockStarter` / `BoundaryRules.blockLineTerminator` fields (already data in `DialectSpec`, exercised by Oracle) — a descriptor entry, not an engine change.

With both wired, the `Option.none()` branch and `executeStatementsNaive` are **deleted**. No naive path remains anywhere.

### 3.6 One splitter for lint and runtime (#408, boundary part)

The compile-time stack (`pg-parser` → `pg-schema` → `pg-codegen`) stops deriving statement boundaries from `postgres.peg`. The migration-lint pipeline (`LintRunner.runOnMigrations`, per `pg-persistence-spec.md`) consumes the `sql-splitter` statement list and feeds the PEG **one statement at a time**. Lint and runtime disagree-by-construction is eliminated: same lexer, same profiles, same boundaries — #408's "reconcile or note convergence" is resolved as convergence.

**Honest scope note:** #408's two token-level productions (`DollarString` untagged-only at `postgres.peg:494`, `RestOfStatement` `;`-blind at `:361`) still mis-lex constructs *inside* a single statement handed to the DDL analyzer. This spec narrows #408 to exactly those two production fixes; it does not close them.

### 3.7 pg-parser as the PG precision plugin (settled)

At the same build boundary, the PG profile gains an optional precision pass: after the lexical split, statements destined for a PG-family datasource may be parsed by `pg-parser` for (a) higher-fidelity `transactional` classification and (b) the expand/contract DDL lint hook at blueprint upload — the enforcement point for the rolling-deploy schema-compatibility contract. This is a plugin *behind* the splitter, never a replacement: non-PG dialects get no parser and need none. Scoped as a follow-up increment; the manifest format above already carries everything it needs.

### 3.8 Explicitly out of scope

- Server-side multi-statement execution (PG simple-query, MySQL `allowMultiQueries`): fallback execution mode only — coarser error attribution, implicit-transaction conflicts with `CONCURRENTLY`. Not specced.
- Undo/baseline KV-vs-`aether_schema_history` incoherence and the non-CAS `SchemaMigrationLockKey`: separate defects per #255, filed separately.

## 4. Blast radius

| Site | Change |
|---|---|
| `aether/slice` — `MigrationEntry`, `BlueprintArtifactParser` | Manifest field + build-time split + `@Codec`; blueprint format change (pre-GA, no shim) |
| `aether/aether-deployment` — `AetherSchemaManager` | Apply consumes manifest; guard chain §3.4; delete apply-time split + naive path |
| `aether/aether-deployment` — `MigrationDialects` | H2/SQLITE wired; `dialectFor` total, returns `ExecutionDialect` unconditionally |
| `aether/pg-tools/sql-splitter` | H2 + SQLITE `DialectSpec` entries; property tests extended |
| `aether/pg-tools/pg-codegen` lint path | Boundary from `sql-splitter`; PEG parses single statements |
| Issues | **#255** closed; **#409** closed; **#408** narrowed to the two PEG token productions |

**Acceptance (from #255, restated):** dialect-profile lexer property tests reuse `pg-test-corpus` for the PG profile; loud failure on unrecognized constructs; blueprint carries the pre-split checksummed manifest and the runtime performs zero parsing; `transactional=false` honored; a PL/pgSQL-function migration and a dollar-quoted seed script round-trip through integration suite 10.

## 5. Open questions

1. **Q1 — dialect binding time.** The split needs a dialect at ingestion, but the datasource's `DatabaseType` is cluster configuration. Options: (a) blueprint declares the dialect family per datasource (manifest pins it; §3.4 guard 2 catches mismatch at apply) — *proposed*; (b) split per-dialect for all families and select at apply — rejected (N× manifest bloat for a config that never legitimately varies).
2. **Q2 — external manifest producers.** Today `BlueprintArtifactParser` is the sole producer. If a client-side blueprint builder later pre-splits in CI (the ideal "fails in CI" point), does ingestion re-split-and-compare permanently, or trust + checksum-verify? Proposed: keep re-split verification — it is cheap and makes drift structurally impossible.
3. **Q3 — source spans.** `Statement` carries `startLine` only. Are char-offset spans worth a splitter extension for richer lint diagnostics, or is `startLine` enough? Proposed: `startLine` suffices for v1.
