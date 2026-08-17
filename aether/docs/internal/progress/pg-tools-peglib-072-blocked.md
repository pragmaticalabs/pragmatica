# pg-tools → peglib 0.7.2: BLOCKED on two upstream generator defects

**Status:** migration implemented, **cannot build**. Both blockers are in peglib's code generator, not
in pg-tools. This branch (`feat/pg-tools-peglib-0.7.2`) holds the completed work so it can be finished
the day a fixed peglib ships. **Do not merge** — `pg-parser` does not compile.

`release-1.0.0-rc3` is untouched and pg-tools still builds there on peglib 0.6.0.

---

## Blocker 1 — generated constant names collide for case-variant literals

```
PgSqlParser.java:[579,30] variable KIND_INLINE_TIME_CI is already defined
```

The generator derives a Java constant from a token-kind name by upper-casing it, so two
case-insensitive literals spelled differently in the grammar produce one constant:

| grammar | token kind | constant |
|---|---|---|
| `postgres.peg:447` `'time'i` (TimeType) | `INLINE_time_CI` = 369 | `KIND_INLINE_TIME_CI` |
| `postgres.peg:742` `'TIME'i` (TimeKW) | `INLINE_TIME_CI` = 654 | `KIND_INLINE_TIME_CI` |

Two collisions in this grammar: `TIME` and `HASH` (`postgres.peg:150` `'hash'i` vs `:630` `'HASH'i`).
Any grammar mixing the case of a case-insensitive literal hits it.

Worth noting this is a **correctness** bug, not only a naming clash: the two kinds match *identical*
input, so even without the compile error one of the two parser sites would test the wrong kind.

**Fixable locally?** Yes — spelling both literals the same way merges them into one kind, and the
facade maps every inline literal to `Terminal("literal")` regardless of kind, so it is invisible
downstream. That was tried and works. It was **reverted**, because blocker 2 makes it pointless and
the grammar is legitimate as written — PEG case-insensitive literals may be spelled any way.

## Blocker 2 — the generated lexer exceeds the JVM constant-pool limit

```
PgSqlLexer.java:[6,14] too many constants
```

Hard limit, no workaround short of shrinking the grammar:

| | java25.peg (jbct) | postgres.peg |
|---|---|---|
| DFA states | 414 | **1288** |
| transitions (states × 256) | 105 984 | **329 728** |
| distinct int literals > 32767 | fits | **75 641** |
| JVM constant-pool limit | — | **65 535** |

`buildTransitions()` emits the table as inline integer literals; every value above the small-int range
takes its own constant-pool entry. jbct's Java grammar fits under the limit and postgres.peg does not,
which is why the 0.7.1 bump succeeded for jbct and stops here.

**A fix belongs upstream** — emit the table as a `String` constant and decode it, or as a classpath
resource, or split it across several classes.

---

## What is already done on this branch

- **poms migrated** — `peglib` → `peglib-runtime` (compile scope, since the generated API now exposes
  `CstArray`/`TokenArray`), one `peglib.version` property at 0.7.2, and the 0.5.x-era `<className>` /
  `<errorReporting>` replaced by `<lexerClassName>`/`<parserClassName>`/`<visitorClassName>`.
  `errorReporting` no longer exists — the BASIC/ADVANCED split was dropped in 0.6.0.
- **`PostgresParser` facade rewritten** over the new CST, keeping its public `CstNode` / `SourceSpan`
  API **byte-identical** so none of the 22 consumers change. The substance:
  - 0.7.x keeps only RULE nodes in the CST and holds tokens in a separate `TokenArray`, where the old
    generator inlined tokens as tree nodes. The converter re-interleaves them: per rule node it walks
    that node's token range and emits every token not covered by a child, in source order, skipping
    trivia.
  - `Terminal` vs `Token` is preserved by keying on the `INLINE_` prefix: an anonymous inline literal
    becomes `Terminal("literal")`, a named token rule becomes `Token(ruleName)`. Derived from the
    baseline, where 1479 of 1485 `Terminal` nodes are named `literal`.
  - Spans need line/column at both ends, which the token array does not carry, so the facade builds a
    line map over the input.

## The sensor, ready to use

`ZCstDumpTest` (uncommitted, in `pg-parser/src/test`) serialises the whole facade tree for every `.sql`
file in the repo. The **0.6.0 baseline is captured**: 34 files, 10 725 lines, 6439 NonTerminal / 2733
Token / 1485 Terminal / 0 Error. Re-run it after the migration builds and diff against
`cst-060.txt` — that proves the facade is preserved, which no unit test can do on its own.

Baseline for the rest: pg-tools is green at 0.6.0 — 7 modules, **807 tests**, excluding two
Testcontainers tests that need Docker (`StatementSplitter*DiffTest`).

## Resume

1. File both defects upstream; blocker 2 is the one that must land.
2. Re-pin, regenerate, build.
3. Run `ZCstDumpTest`, diff against the 0.6.0 baseline, and account for every difference.
4. Then the full pg-tools suite.
