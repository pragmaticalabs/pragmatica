# Session handover — 2026-08-20: pg-tools on peglib 0.7.2, merged; two stale PRs now unblocked

> **Stream: `pragmatica-clone` (design/implementation stream).** This is not the aether-main handover.
> Both streams write here on the shared branch — check the banner before reading one as your own
> state. aether-main's are the unsuffixed files (e.g. `session-handover-2026-08-17.md`).

**PR #618 is MERGED to `release-1.0.0-rc3`.** peglib **0.7.2 is on Maven Central** (`v0.7.2` at
`6fd769b`), so the artifact blocker that governed the last two sessions is gone.

| Item | State |
|---|---|
| **PR #618** pg-tools → peglib 0.7.2 + DML corpus + formatter fix | ✅ **MERGED** 2026-08-20 |
| peglib 0.7.2 | ✅ **published to Central**, signed |
| **PR #600** jbct on peglib 0.7.2 **+ all of #602** | ✅ rebuilt on current release, **CI green**, awaiting review |
| **PR #602** | ⛔ **CLOSED** — superseded by #600, nothing lost |
| Issue **#619** nested block comments | open — needs upstream peglib work |
| Issue **#620** BND-01 works in unit tests, not through the CLI | open — pre-existing, not a regression |

---

## §1 What landed in #618

pg-tools migrated off peglib 0.6.0. **813 tests, 0 failures** (2 errors are Testcontainers needing
Docker — the same two excluded from the old 807 baseline). Verified **clean-room**: every local
peglib artifact deleted from `~/.m2` and rebuilt, so it consumes the published artifact
(`_remote.repositories` reads `peglib-0.7.2.jar>central=`).

### The one thing to internalise before touching pg-tools

**Never identify an identifier by rule name under identifier fallback.** The same identifier reaches
a consumer three ways:

```
id      -> Token ColId          the identifier rule's own kind
public  -> Token PublicKW       a named rule spells that literal, so it owns the kind
name    -> Terminal [name]      collides with an INLINE literal -> ANONYMOUS, no kind at all
```

`findAll("ColId")` drops an arbitrary subset — **silently**, because a missing identifier reads as
"no column here" rather than as an error. Every consumer fix in #618 was converting name-based
lookups to POSITION-based ones (`CstExtractor.leadingIdentifier`, `identifierBeforeNested`,
`leafIdentifiers`). peglib documented this in their README.

Corollary, learned the same way: **do not dispatch on keyword token kinds.** Under kind unification a
literal is named after whichever rule claims it — the `NULL` of `SET NOT NULL` arrives as
`NullConstraint`, `UNIQUE` in `CREATE UNIQUE INDEX` arrives as `UniqueColConstraint`. Dispatch on the
command/constraint rules the grammar declares (`SetNotNullCmd`, `PrimaryKeyTblConstraint`), or on
leaf text.

### Four latent bugs that 0.6.0 concealed — all ours, none caused by peglib

Making the parser stricter surfaced real defects that had been invisible:

1. **`!ReservedKeyword` never fired once at 0.6.0** — `ReservedKeyword` appears **0 times** in 10,725
   lines of baseline CST, and `CURRENT_TIMESTAMP` parsed as a plain identifier. No production
   accepted SQL's reserved nullary functions. Fixed with `SpecialFuncExpr`.
2. **`IsClause` had a bare `NotKW NullKW` alternative**, so `DEFAULT true NOT NULL` parsed as
   `DEFAULT (true NOT NULL)` and the column **silently stayed nullable** — while
   `NOT NULL DEFAULT true` was fine. Order-dependent. PostgreSQL has no postfix `expr NOT NULL`.
3. **Statement counting was wrong in both directions** — 0.6.0 reported a constant 2 per file (68
   total). Truth is 148 against 150 semicolons. The `_ROOT` unwrap fixed it.
4. **`CREATE INDEX` unique/concurrently scanned the WHOLE script**, so one `CONSTRAINT … UNIQUE`
   anywhere marked every index in that file unique. This had been baked into the tracked jOOQ golden.

### Coverage: the corpus gap is closed

The old corpus was 34 DDL files. It contained 6 SELECTs and **zero** `TargetElem`, `ColLabel`,
`WindowSpec`, `JoinClause`, `CaseExpr` — structurally blind to every SELECT-side change made.
**Presence of a statement type is not coverage of it.**

Added **191 statements harvested from real SQL string literals** in `aether/` and `examples/` —
queries the codebase actually issues, so the corpus stays independently motivated and exercises
shapes nobody thought to assert.

```
TargetElem 0 -> 47    SelectStmt 6 -> 74    UpdateStmt 0 -> 10
ColLabel   0 ->  2    InsertStmt 6 -> 53    DeleteStmt 0 ->  9
```

`CorpusParseTest` holds two sensors, each matching a defect that shipped past a green suite, **both
mutation-checked**: every repo `.sql` parses (the `--`-not-trivia bug made 18 of 34 files
unparseable while every unit test stayed green), and one statement per corpus line (catches `_ROOT`).

**A byte-exact CST dump is deliberately NOT checked in.** At 2 MB it regenerates wholesale on any
CST change and would be re-baselined mechanically the first time it went red. The differential
remains a manual before/after procedure, documented in `CorpusParseTest`.

### Formatter: silent source deletion, fixed

`jbct:format` **deleted any comment between an annotation and the member it annotates** — all four
comment styles, no warning, build green, and it rewrites files in place on every build. The parser
attaches such a comment inside the annotation's own span, so it is no node's leading trivia, is not a
same-line trailing comment, and for a bare `@Override` sits inside a **leaf** span the token walk
jumps over. Three mechanisms each correctly declined it and nothing owned it.

Fixed with `flushInSpanOwnLineComments` — the own-line counterpart to the existing
`flushInSpanTrailingComment`. Mutation-checked. jbct reactor **1452 tests, 0 failures**.

**Note for archaeology:** any comment that was in that position has ALREADY been destroyed
repo-wide — a sweep of 1520 annotated files finds zero losses with the fix *disabled*, because there
is nothing left to lose. Recover from git history if a design note goes missing.

---

## §2 Build invariants

**The `-Pgenerate-parser` trap is RETIRED.** Immediately after #618 merged, the main stream landed
`03d547e26` — the parser is now generated into `target/` on every build instead of being committed
into `src/main/java`, and the opt-in profile is gone. This removes the worst trap of the migration:
previously, without `-Pgenerate-parser` the peglib plugin **silently never ran** (no "skipped"
message) and you measured a stale parser. Verified after pulling: `mvn -f aether/pg-tools/pom.xml
test` with NO profile gives 813 tests, 0 failures. Do not add the flag back; older notes mentioning
it are obsolete.

**Still live: always put pg-parser in the reactor with pg-schema.** `-pl aether/pg-tools/pg-schema`
alone resolves pg-parser from `~/.m2` — a stale artifact. Two measurements were void this way.

```bash
mvn -f aether/pg-tools/pom.xml test                                  # full pg-tools
mvn -pl aether/pg-tools/pg-parser,aether/pg-tools/pg-schema test     # both, never pg-schema alone
mvn -f jbct/pom.xml test -Djbct.skip=true                            # jbct reactor
```

**`jooq-xml-showcase` is outside both reactors** and is the only consumer exercising the schema→XML
export path. It is what caught bug (4) above. CI runs it; local module runs do not.

---

## §3 Resume — recommended order

1. **Get PR #600 reviewed and merged.** It now carries **both** #600 and #602, rebuilt on top of
   release-with-#618. **CI green** (build-and-test + forge-tests). It restores **1962 lint findings**
   that the peglib 0.7.1 member-shape change silently dropped, and fixes three linter defects.
   Merging it also unblocks the **peglib project itself**, which is pinned to jbct rc2 because
   `JBCT-BND-01` flagged its own `Expression.Optional`.

   Two conflicts were resolved keeping BOTH sides — re-check these first if anything looks wrong:
   `CstFullyQualifiedNameRule` → `MapperSafety.blankNonCode(memberDeclText(method))`, and
   `jbct/CHANGELOG.md`. Merged (not rebased) deliberately: #600 rewrites `FlowPrinter` (+211/−64),
   the same file #618 fixed, and replaying 22 commits over 53 risked silently dropping #618.

2. **#620 — BND-01's origin fix does not take effect through the CLI.** Its unit test passes with
   `LintContext.defaultContext()`; the same fixture via `jbct lint` reports 2 findings. Reproduces on
   #602's branch alone, so pre-existing, and not a regression (simple-name matching flagged it too).
   Worth auditing whether other rule tests share the assumption that the default context matches what
   the CLI ships.

3. **JBCT-EX-02 — re-derive the number before planning a burn-down.** Earlier handovers say #600's
   repaired rule surfaces **53** violations. This build reports **4 of 57** `.orElseThrow` calls, and
   `CstOrElseThrowRule` was never modified by #600 — the "53" is not reproducible and should be
   measured, not assumed.

4. **#619 nested block comments** — needs a counting scanner in peglib; not fixable in the grammar.

Not started, from the design-review triage: **#604–#617**. #607 (slice-testkit cannot test the core
programming model) and #606 (three examples teach patterns that do not run) remain the cluster worth
taking together.

---

## §4 Corrections to earlier handovers — do not act on the old text

- **`aether-deployment` is NOT broken.** I reported a compile failure (`SourceName cannot be
  converted to String`) twice. It compiles cleanly on current release (0 errors). The failure came
  from an `-amd` build while my branch was behind release, mid-way through someone else's
  `SourceName` refactor. Disregard.
- **The 0.6.0 "807 tests" and "68 statements" baselines were not trustworthy.** The statement count
  was a constant 2 per file.
- **`ZCstDumpTest` was never deleted** — `pg-tools-peglib-072-blocked.md` says to recreate it from
  git history. It is present and working.
- The `-clone` handover for 2026-08-18 describes pg-tools as blocked upstream with "do not poll".
  **That is resolved.**

## §5 Method notes worth keeping

Four measurement errors occurred across the two collaborating sessions, and all four were caught by
re-measuring rather than by arguing. The recurring shapes:

- **A measurement taken in a broken state stays wrong until someone re-runs it.** The "corpus is 100%
  DDL" figure was measured while 18 of 34 files failed to parse; it was quoted for hours and nearly
  shipped in peglib's handover.
- **Assert the property, not the symptom.** Three regression tests (peglib's) passed against the very
  bugs they were written for. The invariant that finally worked was a single number:
  `idFallbackKinds(R) ∩ aliasKinds(guard) == 0`.
- **A consumer's tests can be green because the bug and the test agree with each other.**
- Making a parser stricter surfaces latent bugs in consumer grammars — **budget for it**, and expect a
  correctness fix to look like a regression until the latent bug it exposed is fixed.
