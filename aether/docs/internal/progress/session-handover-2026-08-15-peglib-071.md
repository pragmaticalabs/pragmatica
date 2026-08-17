# Session handover — 2026-08-15: peglib bumped to 0.7.2; jbct tools GREEN, pg-tools remains

**Branch:** `feat/peglib-0.7.1` (off `release-1.0.0-rc3`, since merged with it) · **pushed as PR #600**
**Build is GREEN** — all 11 modules BUILD SUCCESS, including JBCT CLI and Maven Plugin, which had never
been reached before (they were SKIPPED behind the format+lint failures).

**Resume at §9.** The formatter, the linter and the 0.7.2 re-pin are DONE and committed. What remains is
`pg-tools` (§5, untouched, independent) and one verification that is genuinely blocked (§9.3). Nothing is
blocked on an outside answer — the peglib exchange is closed (§8).

**Task:** bump peglib 0.6.2 → 0.7.1 in pragmatica, integrate the upstream Java grammar changes, keep the
jbct tools working. Owner rulings: adopt the grammar in the **same pass** as the bump (not staged), and
**`aether/pg-tools` is in scope** (not started). Golden formatter tests are the contract — **adapt
`FlowPrinter`, never re-baseline the goldens.** That contract held: the goldens are byte-unchanged.

---

## §1 State at a glance

| Module | State |
|---|---|
| JBCT Parser | ✅ green, 32 tests |
| JBCT Core | ✅ green, 135 |
| JBCT Init / Derive | ✅ green |
| JBCT Slice Processor | ✅ green, 298 |
| **JBCT Formatter** | ✅ **green, 67** — was 3 failures / 2 fixtures (§3) |
| **JBCT Linter** | ✅ **green, 703** — was 13 failing test classes (§4) |
| **JBCT CLI / Maven Plugin** | ✅ **green** — CLI also exercised over 2135 files; plugin caveat in §9.3 |
| `aether/pg-tools` | ⏭ untouched — §5 |


## §2 What landed

**`ed941ab59` — the bump and CST adaptation.**

- `peglib-runtime` + `peglib-maven-plugin` 0.6.2 → **0.7.1**, later re-pinned to **0.7.2** (§9.5); mojo goal `generate-v6` → **`generate`**
  (the only config change needed — jbct already used `lexerClassName`/`parserClassName`/`visitorClassName`;
  0.7.1 has no `className` or `errorReporting` params).
- **`java25.peg` re-synced from upstream**, 205 → 399 lines. jbct's copy was an explicit downstream sync
  two rules behind. Header records provenance; **upstream is authoritative — re-sync, do not patch locally.**
- Parser regenerated into `src/main/java` (committed generated sources, opt-in `generate-parser` profile):
  `Java25Lexer` 25k → 67k lines, `Java25ParserV6`, `Java25Visitor`.
- 8 `org.pragmatica.peg.v6.*` imports → `org.pragmatica.peg.*` across 5 hand-written files.
- **`RuleKind` regenerated** — see §6, this was a silent trap.
- **`CstNodes.parameterNodes(...)`** added; the 3 lint sites and `SuppressionExtractor` migrated to it.
- `FlowPrinter`: interface-body cases + `ORDINARY_PARAMS` routing.

**`ae4e60702`** — two format fixtures declared a constructor inside an `interface`; javac rejects it
(`error: <identifier> expected`). peglib hit the identical thing in 0.7.1 and fixed it the same way
(`interface` → `class`). This was causing a *parse* failure, not a format failure.

**`18f212252`** — `parseRecordAsTypeName` asserted a snippet javac rejects in all four positions.
Replaced with what javac agrees with, plus a pin on the remaining divergence (§7).

**`ea3677851`** — the statement-chain refactor. Details in §3.

## §3 Formatter — RESOLVED: statement chains (`ea3677851`) and lambda-body chains (`833235534`)

**Done (`ea3677851`).** The §3 design was implemented and it worked: statement-position chains break
and align again, and **`StatementChains.java` is green with no golden edits.**

What landed in `FlowPrinter`:

- `printStmtExpr` + `case STMT_EXPR` in both dispatch switches.
- `callChainLinks(...)` walks the right-recursive `CallChain` spine collecting `CHAIN_OP`/`CALL_OP`.
- `mergeDotNameWithInvocation(...)` merges `.name` + following invocation into one **logical link**, so
  `a.b().c(y)` counts 2 links as a statement exactly as it does as an expression. Without this the same
  source breaks differently by position and the goldens fail.
- `printChainWithPrimary(primary, links, nestedDotMethodCount)` is now the shared body; both
  `printPostfixWithPrimary` and `printStmtExpr` feed it. `printMethodChainAligned` and its call sites
  moved from `List<Cursor>` to `List<List<Cursor>>`; a link is identified by its head node.
- Note there are **two** POSTFIX entry points — `printPostfix` (switch-dispatched) and
  `printPostfixWithPrimary` (helper). Both had to be adapted; the first was easy to miss.

**`Lambdas.java` and `LambdaBlockArgs.java` — resolved in `833235534`.** The cause was
candidate 2, confirmed by bisection, not by reading the tree:

```
Expected: return input.map(s -> s.trim()\n                               .toUpperCase());
Actual:   return input.map(s -> s.trim().toUpperCase());
```

The lambda body's CST is byte-identical to 0.6.2 (`POSTFIX → PRIMARY[s.trim] POST_OP[()] POST_OP[.toUpperCase()]`),
so the shape was never the issue — the **dispatch path** was. `Expr <- Lambda / Assignment` hoisted `Lambda`
out of `Primary`, so an argument lambda now arrives via `POST_OP → ARGS → EXPR → LAMBDA`. `printArgs` prints
child expressions with `printNodeContent`, whose `LAMBDA` arm is `printLambdaContent` — which, unlike
`printLambda`, **never entered the tail context** that makes chains break. Under 0.6.2 the lambda sat under
`PRIMARY`, and `printPrimary` reached it through `printNode` → `printLambda` → tail context. Both entry
points now share one walker (`printLambdaWith`) that establishes the context; they still differ only in how
non-body children print. **67 formatter tests green, no golden edits.**


## §4 Linter — RESOLVED in `6be655136`; the diagnosis in this section was incomplete

The signature was `Expected rule JBCT-NAM-01 but found: []` — **rules silently stopped firing.**

The section's original framing (a dozen rules walking `CLASS_BODY`/`CLASS_MEMBER`) named a real but
**minor** part of it. Two corrections worth carrying forward:

- **Records lost the same levels as interfaces.** Pre-bump `RecordMember <- CompactConstructor /
  ClassMember`, so records nested a full `ClassMember → Member`. Now `RecordMember` holds `MethodDecl`
  directly. Records are JBCT value objects, which is why `CstMemberOrderingRuleTest$ValueObject` was
  among the failures.
- **The `MEMBER` level, not `CLASS_MEMBER`, was the dominant loss.** For a class the modifier-bearing
  wrapper (`ClassMember`) and the declaration (`Member`) are two nodes; for an interface or record they
  **collapse into one**. `CstNodes.findAllMethods`/`isMethodMember` keyed on `MEMBER` and so returned
  **empty for every interface** — 48 call sites across 27 files inherited that.

Fixed by reconciling the shapes in `CstNodes` (`isMemberDecl`, `isMemberWrapper`, `enclosingMember`,
`enclosingMethodMember`, `typeBodyMembers`, `isFieldDecl`, `memberDeclText`), mirroring how
`parameterNodes` contained the `Params` change, then migrating ~20 rule sites. **703 lint tests green.**

**Two traps the collapse sets — both were live defects, expect them again elsewhere:**

1. **An ancestor-only wrapper lookup walks straight past an interface/record member**, because there the
   member IS its own wrapper. `enclosingMember`/`enclosingMethodMember` therefore match the node itself
   (`selfOrAncestor`). Without it `CstReturnKindRule.isPrivateMethod` read `false` for every
   interface/record method and JBCT-RET-01 over-reported by 144.
2. **A wrapper's text spans annotations**, so any heuristic that regexes a declaration mis-reads it:
   `@SuppressWarnings("…")` supplies the first `(` and `)`. JBCT-NAM-01 reported
   `Factory method 'SuppressWarnings'`; JBCT-LOG-02's `indexOf("Logger ") < indexOf(")")` silently
   inverted. **`memberDeclText` is the fix** — it anchors on the `MethodDecl` and is byte-identical to
   `text(member)` for a class, so class-side behaviour cannot move.

**Verification — the differential corpus run is the instrument to reuse.** Build the CLI at the
pre-change commit in a worktree, lint the *same* tree with both binaries, diff normalized findings:

```bash
git worktree add --detach <wt> ed941ab59^ && (cd <wt> && mvn -f jbct/pom.xml package -DskipTests -Djbct.skip=true)
java -jar <wt>/jbct/jbct-cli/target/jbct.jar lint aether --format json > before.json
java -jar jbct/jbct-cli/target/jbct.jar          lint aether --format json > after.json
```

Result: the bump had silently dropped **1962 of 13274 findings**; after the fix **every rule matches its
pre-bump count exactly, and every finding matches by file, line AND column**, with two intended
exceptions — JBCT-EX-02 (§9.6) and one JBCT-ORD-01 column, where a record's `RecordStaticField` node
spans its modifiers while the old `FieldDecl` began at the type, so the anchor now covers the whole
declaration.

**Two traps when reusing this instrument:**

- The JSON emits a trailing non-JSON summary line after the array — parse with `raw_decode`, not `load`.
- **The `file` field is a BASENAME, never a path**, and 21 of 2141 aether basenames collide (2.2% of
  findings). Diff as a **multiset** over `(file, line, column, ruleId)` — a set-based diff silently
  collapses ~880 duplicate triples and can hide a change in one of two colliding files behind the other.

**An adversarial review pass found 5 defects the corpus could not**, because aether happens not to
contain the triggering shapes. All are fixed; `MemberShapeRegressionTest` (12 paired
class/interface/record cases) pins them:

1. `typeBodyMembers` looked for the body among DIRECT children, but a nested type is a bare `TypeKind`
   whose child is the `InterfaceDecl` that holds the body — so it returned `[]` for every caller passing
   a `TypeKind`, which is what `findFirstInterface` returns. UC-01's multi-method exemption was dead.
   **Pre-bump had the same shape error** (`childByRule(iface, CLASS_BODY)` on a `TypeKind`), which is why
   the corpus diff stayed clean either way — so fixing it *activates* an exemption that has never run.
   Zero effect on this corpus (UC-01 is 0 before and after), but on other code UC-01 can now correctly
   report FEWER findings than pre-bump. Like §9.6 that is a repair, not a restoration.
2. `memberDeclText` keyed on `MethodDecl`, so a **constructor** or **field** member fell back to
   annotation-spanning text — a record constructor was reported as `method 'Deprecated'`. It now takes
   the first non-`Annotation` child, which covers every member shape.
3. + 4. `CstValueObjectFactoryRule` and `CstFullyQualifiedNameRule` read raw `text(method)`: an
   annotation defeated VO-01's builder exemption, and STY-03 flagged `@java.lang.Deprecated` as a fully
   qualified name in a method body. **Nine sites doing declaration-shaped analysis were switched**;
   five that genuinely need the modifiers (`CstConstructorBypassRule:104`, `CstFactoryNamingRule:57`,
   `CstNestedRecordFactoryRule:57,63`, `FileTypeClassifier:453`) correctly keep the wrapper text.
5. Diagnostics anchored on the wrapper, so an annotated interface method reported the annotation's line
   where a class reported the signature's. `anchorOf` fixes it and is a no-op for non-members, so a
   helper shared between members and type declarations can route every anchor through it.

**Lesson worth keeping: "the corpus shows no divergence" is necessary, not sufficient.** It proves no
regression against THIS tree; it cannot prove correctness for a shape the tree does not contain. Four of
the five above were latent in exactly that gap.

**Known gap, deliberately not implemented:** JBCT-EX-02 does not flag a method *reference*
(`Optional::orElseThrow`). It never did — that is a feature, not a regression, and expanding it was out
of scope for this branch.


## §5 `aether/pg-tools` — in scope, not started

Peglib **0.6.0**, older than jbct was. Scope, from investigation:

- `PgSqlParser.java` is a **committed, self-contained 103,316-line** generated parser depending only on
  `core`. `peglib` is **test-scope** there.
- The `generate-parser` profile config is 0.5.x-era: single `<className>PgSqlParser</className>` plus
  `<errorReporting>ADVANCED</errorReporting>`. **Neither parameter exists in 0.7.1**, and the
  BASIC/ADVANCED recovery split was an intentional 0.6.0 drop.
- Regenerating on 0.7.1 produces **three** tokens-first classes requiring `peglib-runtime` at compile
  scope — a different artifact shape entirely, not a version bump.
- `PostgresParser.java` (107-line facade) exports `CstNode` / `SourceSpan` / `parseCst` / `parseScript`
  and is consumed by **21 files** across `pg-parser`, `pg-schema`, `pg-codegen`. Keeping that facade
  stable over `CstArray` is the whole job — the same problem jbct solved with `Cursor`.
- Also: `GrammarTestBase.java` imports `org.pragmatica.peg.parser.Parser` → `org.pragmatica.peg.Parser`.

It is fully independent of the jbct work and can be taken in parallel.

## §6 Traps found — do not re-learn these

- **`RuleKind` is a hand-maintained enum mirroring `Java25ParserV6.RULE_TABLE` *by index*.** A grammar
  change silently desynchronises it — `ROOT` resolved to `UNKNOWN` and only one test noticed. It went
  108 → **140** constants; exactly **2** disappeared (`PARAM`, `LAMBDA_PARAM`). Regenerate it whenever the
  grammar moves — the script is committed at **`jbct/jbct-parser/regen_rulekind.py`**: run it with the
  generated parser and the enum as arguments to see the added/removed delta, add `--write` to apply.
  A zero delta proves the RULE SET is unchanged; it does **not** prove the tree shape is (see §9).
- **`viewAt` returns `Leaf` for any node with no child nodes — including genuine rule nodes.** A probe
  that labels nodes by `instanceof Cursor.Branch` prints `leaf` and hides the kind. This cost most of a
  session: I concluded operator nodes weren't being materialised when they were. **Always print
  `node.kind()`, never the view type.** `Cursor.kind()` is a default method on the sealed interface, so
  it works on `Leaf` too, and `childrenByRule` finds Leaf-viewed nodes fine.
- **`mvn -pl <module> test` WITHOUT `-am` silently resolves a stale artifact from the shared `~/.m2`.**
  A CST dump taken that way showed the *old* `PARAMS → PARAM` shape and I nearly reasoned from it. This
  is the shared-`~/.m2` hazard from CLAUDE.md, arriving via the test path rather than an install.
  **Every verification run in this repo needs `-am`.**
- **A green test suite does not prove a rule still fires.** The bump dropped 1962 corpus findings while
  every suite outside `jbct-lint` stayed green, and 13 lint classes failed only because they happened to
  assert on interfaces. Behaviour that is "does this rule see this construct at all" needs a corpus
  differential (§4), not unit tests.
- **Params went flat → nested**: `Params <- ReceiverParam? OrdinaryParams`, `OrdinaryParams <-
  (PlainParam ',')* LastParam`. `CstNodes.parameterNodes` flattens it and **excludes `ReceiverParam`** —
  the explicit `this` receiver declares no variable, so it is not a parameter for naming, reassignment or
  nullability analysis.
- **`printBrokenParams` split on commas that are now inside `ORDINARY_PARAMS`**, not direct tokens of
  `Params`. Fixed by routing the wrapper through `printParams`; the same wrapper-transparency trap will
  recur anywhere a rule gained an intermediate node.

## §7 Known divergence, deliberately pinned

`RestrictedTypeName <- < ('var' / 'yield') ![a-zA-Z0-9_$] >` covers only `var` and `yield`, so
`class record {}` parses even though javac rejects it ("as of release 14, 'record' is a restricted type
name"). This sits in peglib's documented "21 wrongly accepted" bucket, not in jbct.
`parse_recordAsTypeName_stillAcceptedDespiteJavacRejectingIt` pins it so it turns **red if peglib
tightens the grammar** — that red is a signal to re-sync, not a regression.

## §8 Upstream exchange — settled, no action pending

peglib was asked whether operator-node materialisation was a defect. Answer: **no.** Nodes are always
materialised; `viewAt` was the confusion (§6). The `Postfix` vs `StmtExpr`/`CallChain` split **is**
deliberate, new in 0.7.1, stable, and will not be reverted — it was priced at ~6 files of javac agreement
plus a throughput regression, and reverting would re-admit `(a);` and `a.b;` as statements. They added a
`Changed — CST shape for method/field chains` section to the 0.7.1 CHANGELOG covering both shapes, the
differing link grouping, and the `viewAt` caveat.

They asked whether the **plugin** path agreed with `PegParser.fromGrammar`, since they only tested the
latter. **It does** — dumps from the plugin-generated parser matched their findings exactly (link counts
2/2/3/3, `CHAIN_OP[.c]` + `CALL_OP[()]` splitting). Nothing for them to chase.

Separately handed to the peglib agent: **`docs/GRAMMAR-DSL.md` documents a `PegParser.fromGrammar` API
that does not exist** — two dead overloads, `Actions`/`ParserConfig` (both deleted), and
`import org.pragmatica.peg.parser.ParserConfig` (package gone). `README.md:158` calls that file "the full
reference", so its grammar-imports example is the first thing a new user copies and it cannot compile.

## §9 Suggested order for the next session

**peglib 0.7.2 is now pinned** (item 5 below). Upstream described it as behaviour-unchanged for our
usage — `%import` composition, plugin import resolution, and a single-flight parser cache — and that
was **verified rather than trusted**; see item 5 for how, and repeat it for 0.7.3.

1. ~~Finish the formatter~~ — **DONE** (`833235534`), §3. 67 tests, no golden edits.
2. ~~Linter interface-awareness~~ — **DONE** (`6be655136`), §4. 691 tests; corpus-verified against the
   pre-bump binary.
3. **CLI + Maven Plugin** — the CLI is now genuinely exercised: `jbct lint` ran over 2135 aether files
   repeatedly as the verification instrument, from a jar packaged at HEAD. **The plugin is only
   unit-verified.** Exercising `mvn jbct:check -pl <module>` end-to-end against *this branch's* rules
   would require `mvn install`, which the shared `~/.m2` forbids (it would swap the other stream's
   toolchain silently). That verification is therefore **blocked pending coordination**, not forgotten —
   raise it with the main stream rather than working around it.
4. **`pg-tools`** (§5) — independent, untouched, can be taken any time. Still the largest remaining item.
5. ~~Re-pin to 0.7.2~~ — **DONE** (`85c4c595d`). Upstream's "behaviour unchanged" was verified, not
   trusted, and the verification is worth repeating for 0.7.3:
   - `Java25ParserV6` and `Java25Visitor` regenerate **byte-identical**.
   - `Java25Lexer` differs by 104 lines that are a **pure permutation** of the 54 keyword-map
     entries — sorted `r0.put(...)` sets are identical and **no non-`r0.put` line changed at all**.
     Prove it that way; a line count alone looks alarming.
   - `regen_rulekind.py` delta zero, and regeneration is **deterministic** (checked by regenerating
     twice — worth re-checking, since 0.7.2 made generation fork-join and jbct commits generated
     sources).
   - Both sensors clean: goldens green, and the §4 corpus differential **identical** — 14236
     findings, 0 lost, 0 new, no per-rule change.
   - **Trap:** the first corpus run showed 392 lost / 420 new. That was NOT 0.7.2 — the baseline
     predated the merge from `release-1.0.0-rc3`, so the aether tree itself had grown. Always
     rebuild BOTH sides against the SAME tree (a worktree at the pre-change commit).

   The property was renamed `peglib.maven.plugin.version` → **`peglib.version`** at the same time;
   it always governed both the runtime dependency and the plugin, and the old name said otherwise.
6. **JBCT-EX-02 burn-down** (`c3a16c26b`) — the repaired rule surfaces **53 previously-invisible
   `error`-severity violations**: 49 in tests, 4 in production (`AbstractMultiPartitionStream` ×2,
   `AbstractStreamOwnerFailover`, `AetherUp`). Landed as its own commit precisely so this burn-down can
   be scheduled separately. Anything gating on a clean lint run will go red until it is done.

**The goldens are now the sensor for 0.7.2.** A pure CST-shape change with no new rules slips past
`regen_rulekind.py` (which only proves the RULE SET is unchanged); the formatter goldens detect it, and
they only work as a sensor now that they pass. The **differential corpus run in §4 is the second
sensor** — it is what caught 1962 silently-dropped findings that every test suite was blind to, and it
should be re-run across the 0.7.2 re-pin.

Nothing is pushed and no PR is open. Per project convention this ships as a PR against
`release-1.0.0-rc3`.

