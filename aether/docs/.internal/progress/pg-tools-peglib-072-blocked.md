# pg-tools → peglib 0.7.x: BLOCKED on case-insensitive identifier fallback

**Status:** migration implemented and generating; **parsing does not work**. The three defects that
blocked the build are fixed upstream and verified here. What remains is not a bug in the migration —
it is that a SQL-shaped grammar cannot express "identifier that is not a reserved keyword" under
peglib's lex-then-parse model today.

This branch (`feat/pg-tools-peglib-0.7.2`) holds the completed work. **Do not merge** — `pg-parser`
compiles but 230 of 307 tests fail. `release-1.0.0-rc3` is untouched and pg-tools builds on 0.6.0.

---

## Fixed upstream and verified here (peglib 0.7.2, jars of 2026-08-17 23:29/23:31)

| defect | before | after |
|---|---|---|
| constant-name collision (`KIND_INLINE_TIME_CI` ×2) | uncompilable | gone; case-variants merge to one kind |
| lexer constant-pool overflow | `too many constants`; 75 641 int literals > 32767 | **0** literals; `TRANSITIONS_DATA` Base64; 1.82 MB → 897 KB; compiles |
| `'SET'` inline-literal lookup | `DFA build inconsistent` | generation completes |
| stale-output skip | silently reused old output | files carry `// peglib-generator: 0.7.2`; a stamp mismatch forces regeneration |

**A trap that cost a wrong bug report:** before the stamp existed, `peglib:generate` decided staleness
from grammar-vs-output mtime alone. After a generator upgrade the outputs are silently stale, so
"I regenerated and nothing changed" meant "the mojo skipped", not "the fix is absent". If a future
generator behaves oddly, check for `skipped (up-to-date)` in the log **first**.

## The remaining blocker

`ColId`, `ColLabel` and `ReservedKeyword` are allocated lexer kinds that the DFA can never emit:

| kind | index | reachable in `ACCEPT_KIND` |
|---|---|---|
| `ColId` | 67 | **false** |
| `ColLabel` | 68 | **false** |
| `ReservedKeyword` | 80 | **false** |
| `UnquotedIdentifier` | 69 | true |

`users` lexes as `UnquotedIdentifier`; the parser tests for `ColId`; the check can never pass, so every
statement fails with `expected=ColId, found=…`. The three share one trait — a syntactic predicate over
a rule reference, which a DFA lexer cannot honour:

```peg
469: ColId <- !ReservedKeyword (QuotedIdentifier / UnicodeIdentifier / UnquotedIdentifier)
```

Under 0.6.0's single-phase PEG this worked naturally. It does not survive lex-then-parse.

**peglib has exactly the right mechanism — identifier fallback — but TWO independent gates block it**,
both confirmed by peglib after measuring:

1. `DfaBuilder.buildIdentifierFallbacks` skips any inline literal whose key does not end `/cs`, and
   `inlineLiteralKey` gives case-insensitive literals `/i`. Every SQL keyword is `'SELECT'i`. Fixing
   this also needs case-folding on the `hardKeywords` containment test, since those are extracted as
   written (uppercase). **Necessary but not sufficient** — with it applied, the fallback set was still
   empty.
2. `RuleClassifier.detectSkipPrefixRules` requires the skip-prefix body to be pure-lexical with **no
   rule references** (`!bodyProps.usesOnlyLexicalConstructs() || bodyProps.referencesAnyRule()`).
   `ColId`'s body is three rule references, so `keywordSkip()` is empty and the fallback loop never
   runs at all.

**And the obvious grammar-side repair is blocked by a third rule.** Inlining the identifier body to
satisfy gate 2 is not acceptable here: lines 469/470 are the ONLY references to `QuotedIdentifier`
and `UnicodeIdentifier` in the whole grammar, so inlining drops quoted-identifier support outright —
`CREATE TABLE "my table"`, `COLLATE "en_US"` (already covered by a test), and every case-preserving or
reserved-word identifier. Splitting it back out does not work either, because a rule whose body
references only lexer rules is demoted to LEXER:

```peg
ColIdRaw <- !ReservedKeyword < [a-zA-Z_] [a-zA-Z0-9_$]* >   # lexer, satisfies the skip-prefix gate
ColId    <- ColIdRaw / QuotedIdentifier / UnicodeIdentifier  # only lexer refs -> demoted to LEXER
```

So the guard and the alternatives cannot live in the same place. Resolving this needs an exemption in
one of those two classifier rules, not just the `/cs` filter — it is a peglib design decision, not a
grammar tidy-up.

**Hand-expanding the guard inline is not viable**, and was priced rather than assumed: the verified
idiom requires the lookahead spelled inline in each parser rule with no intermediate rule, and
`ReservedKeyword` has **78** alternatives against **69** `ColId` references plus 4 `ColLabel`. That is
a 78-way negative lookahead duplicated at 73 sites, re-edited on every PostgreSQL keyword change.

## What is done on this branch

- **poms** — `peglib` → `peglib-runtime` (compile scope), one `peglib.version`, 0.5.x-era
  `<className>`/`<errorReporting>` replaced by the three class-name parameters.
- **`PostgresParser` facade** rewritten over `CstArray`/`TokenArray`, public `CstNode`/`SourceSpan` API
  byte-identical so none of the 22 consumers change. Re-interleaves tokens (0.7.x keeps only rule nodes
  in the CST), preserves `Terminal` vs `Token` via the `INLINE_` prefix, builds a line map for spans.
- **`GrammarTestBase`** re-pointed from the interpreted parser to the generated one: 0.7.x has no
  rule-specific entry on `PegParser.fromGrammar`, and the generated parser gained `parseRuleFrom` +
  `ruleKinds()`. 92 rule tests now exercise the artifact that ships.

## The sensor, ready to use

`ZCstDumpTest` (deleted from the branch; recreate from git history or rewrite — ~100 lines) serialises
the facade tree for every `.sql` file in the repo. **0.6.0 baseline captured**: 34 files, 10 725 lines,
6439 NonTerminal / 2733 Token / 1485 Terminal / 0 Error, at
`scratchpad/cst-060.txt`. Re-run after parsing works and diff — that proves the facade is preserved,
which no unit test does on its own.

Baseline for the rest: pg-tools green at 0.6.0 — 7 modules, **807 tests**, excluding two
Testcontainers tests needing Docker (`StatementSplitter*DiffTest`).

## Resume

1. Wait on peglib. Two code changes are needed (CI filter with folding, plus an exemption in either the
   skip-prefix gate or the LEXER demotion rule), and peglib has it written up with postgres.peg as a
   checked-in test case. No commitment or date — do not poll.
2. Regenerate, build, run the pg-parser suite — expect the 230 failures to clear.
3. Run the CST differential against the 0.6.0 baseline and account for every difference.
4. Then the full pg-tools suite, then PR.
