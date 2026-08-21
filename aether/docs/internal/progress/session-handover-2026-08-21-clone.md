# Session handover — 2026-08-21: peglib 0.7.2 landed across pg-tools and jbct; one fix in flight

> **Stream: `pragmatica-clone` (design/implementation stream).** Not the aether-main handover. Both
> streams write here on the shared branch — check the banner before reading one as your own state.
> aether-main's are the unsuffixed files.

The peglib 0.7.2 migration is **complete and merged** on both sides — pg-tools (#618) and jbct
(#600). peglib **0.7.2 is on Maven Central**. One regression from #600 is fixed and **awaiting merge
in #622**.

| Item | State |
|---|---|
| **#618** pg-tools → peglib 0.7.2, DML corpus, formatter comment fix | ✅ MERGED 2026-08-20 |
| **#600** jbct → peglib 0.7.2 **+ all of #602** | ✅ MERGED 2026-08-21 |
| **#602** | ⛔ CLOSED — superseded by #600, nothing lost |
| **#622** wildcard-in-array-creation spacing (fixes #621) | 🔵 **OPEN, mergeable, CI in flight** |
| **#619** nested block comments | open — needs upstream peglib |
| **#620** BND-01 works in unit tests, not via the CLI | open — pre-existing, not a regression |
| **#621** | open — closes when #622 merges |

---

## §1 FIRST THING: land #622

`jbct:format` rewrites `new Class<?>[0]` → `new Class< ?>[0]`. A regression from #600, cosmetic but
blocking: **`jbct:check` bundles format-check, so wiring it into CI makes the formatter's output
normative.** Until #622 lands, `release-1.0.0-rc3` carries one deliberately odd line
(`TransactionConfig.java`, accepted in `e69392abe`); #622 reverts it.

Cause: `?` is in the spaced-operator set for ternaries, and that spacing is suppressed by
`typeContextDepth` — which the **array-creation path leaves at 0**. Instrumented, not inferred:
the two `new Class<?>[…]` occurrences emit `?` at depth 0; every correct occurrence emits at depth 1.

Fix: a `?` directly after `<` is always a wildcard (`<` cannot precede a ternary's `?` in valid
Java), so the guard keys off the preceding token rather than the depth. The deeper cause — array
creation not raising `typeContextDepth` — was deliberately NOT chased: `<` and `>` are already
spaced correctly there, so `?` was the only observable symptom. Recorded in a comment at the guard.

`WildcardSpacingTest` pins the creation and declaration forms **separately** (different paths; only
one regressed) and is mutation-checked — disabling the guard turns 4 of 6 red.

---

## §2 What landed, and the one thing to internalise

**Never identify an identifier by rule name under peglib identifier fallback.** The same identifier
arrives three ways:

```
id      -> Token ColId          the identifier rule's own kind
public  -> Token PublicKW       a named rule spells that literal, so it owns the kind
name    -> Terminal [name]      collides with an INLINE literal -> ANONYMOUS, no kind at all
```

`findAll("ColId")` drops an arbitrary subset **silently** — a missing identifier reads as "no column
here", not as an error. Select by POSITION (`CstExtractor.leadingIdentifier`,
`identifierBeforeNested`, `leafIdentifiers`). Corollary: don't dispatch on keyword kinds either —
the `NULL` of `SET NOT NULL` arrives as `NullConstraint`, `UNIQUE` in `CREATE UNIQUE INDEX` as
`UniqueColConstraint`. Dispatch on the command/constraint rules the grammar declares.

**#600 restored 1962 lint findings** the 0.7.1 member-shape change had silently dropped, and fixed
three linter defects (annotation trailing-trivia disabling annotations; BND-01 simple-name matching;
STY-03 flagging qualified names inside comments and string literals).

**Four latent bugs that 0.6.0 concealed** — all ours, none caused by peglib. `!ReservedKeyword` never
fired once at 0.6.0 (0 occurrences in 10,725 baseline lines), hiding that no production accepted
`CURRENT_TIMESTAMP`; `IsClause`'s bare `NotKW NullKW` made `DEFAULT true NOT NULL` silently nullable;
`_ROOT` made statement counts a constant 2/file; `CREATE INDEX` unique/concurrently scanned the whole
script so one `UNIQUE` constraint marked every index in the file unique.

**Corpus coverage.** The old 34-file DDL corpus contained 6 SELECTs and **zero** `TargetElem`,
`ColLabel`, `WindowSpec`, `JoinClause`, `CaseExpr`. Presence of a statement type is not coverage of
it. 191 statements harvested from **real** SQL string literals now cover it
(`TargetElem` 0→47, `SelectStmt` 6→74, `UpdateStmt` 0→10, `DeleteStmt` 0→9). Harvest, don't author —
authored fixtures encode what you already suspect.

---

## §3 Build invariants

- **The `-Pgenerate-parser` profile is RETIRED** (`03d547e26`). The parser generates into `target/`
  on every build; the generated sources are no longer committed. Do NOT pass the flag — older notes
  are obsolete.
- **Always put pg-parser in the reactor with pg-schema.** `-pl aether/pg-tools/pg-schema` alone
  resolves pg-parser from `~/.m2` — a stale artifact. Two measurements were void this way.
- **`jooq-xml-showcase` is outside both reactors** and is the only consumer of the schema→XML export
  path. CI runs it; local module runs do not. It caught a real bug that local runs missed.

```bash
mvn -f aether/pg-tools/pom.xml test                              # full pg-tools (813 tests)
mvn -pl aether/pg-tools/pg-parser,aether/pg-tools/pg-schema test  # never pg-schema alone
mvn -f jbct/pom.xml test -Djbct.skip=true                         # jbct reactor (1481 tests)
```

---

## §4 Resume — after #622

1. **#620 — BND-01's origin fix does not take effect through the CLI.** Its unit test passes with
   `LintContext.defaultContext()`; the same fixture via `jbct lint` reports 2 findings. Reproduces
   on #602's branch alone, so pre-existing and not a regression. Worth auditing whether **other rule
   tests share the assumption that the default context matches what the CLI ships** — that is the
   generalisable half.
2. **JBCT-EX-02 — re-derive the number before planning a burn-down.** Older handovers say the
   repaired rule surfaces **53** violations. This build reports **4 of 57** `.orElseThrow` calls, and
   `CstOrElseThrowRule` was never modified by #600. The 53 is not reproducible — measure it.
3. **#619 nested block comments** — needs a counting scanner in peglib; not fixable in the grammar
   (nested comments are not a regular language, so a DFA-lexed rule cannot express them).
4. Design-review backlog, not started: **#604–#617**. #607 (slice-testkit cannot test the core
   programming model) and #606 (three examples teach patterns that do not run) are the cluster worth
   taking together.

---

## §5 Corrections — do not act on older text

- **`aether-deployment` is NOT broken.** An earlier handover claimed a `SourceName cannot be
  converted to String` compile failure. It compiles cleanly. That came from an `-amd` build while a
  branch was behind release, mid-way through someone else's refactor.
- **The 0.6.0 "807 tests" and "68 statements" baselines were never trustworthy** — the statement
  count was a constant 2 per file.
- **`ZCstDumpTest` was never deleted**, despite `pg-tools-peglib-072-blocked.md` saying to recreate
  it from git history.
- The 2026-08-18 `-clone` handover describes pg-tools as blocked upstream with "do not poll".
  **Resolved.**

## §6 Method notes

Five measurement errors occurred across this work; all were caught by re-measuring, none by arguing.

- **A measurement taken in a broken state stays wrong until someone re-runs it.** "The corpus is 100%
  DDL" was measured while 18 of 34 files failed to parse, and was quoted for hours.
- **Assert the property, not the symptom.** Three regression tests passed against the very bugs they
  were written for. The invariant that finally worked was one number:
  `idFallbackKinds(R) ∩ aliasKinds(guard) == 0`.
- **A consumer's tests can be green because the bug and the test agree with each other** — and #620
  shows the variant where the test agrees with a *configuration* production does not use.
- **Verify through the shipped entry point**, not only the API. The jOOQ golden and #620 were both
  found that way; neither was reachable from the module test suites.
- Making a parser stricter surfaces latent bugs in consumer grammars — **budget for it**, and expect
  a correctness fix to look like a regression until the latent bug it exposed is fixed.
