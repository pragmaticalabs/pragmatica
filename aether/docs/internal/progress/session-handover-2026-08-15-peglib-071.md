# Session handover — 2026-08-15: peglib 0.7.1 bump, half landed, chain + interface CST work remains

**Branch:** `feat/peglib-0.7.1` (off `release-1.0.0-rc3` at `19b76827f`) · **8 commits** · **NOT pushed** · tree clean
**Build is RED and deliberately so** — `jbct-format` (3 failures / 2 fixtures) and `jbct-lint` (13 classes)
fail. Everything else is green. Baseline before this branch was BUILD SUCCESS, so all of it is attributable.

**Resume at §9.** The bump, the grammar sync and the statement-chain refactor are done and committed;
what is left is two lambda fixtures, the linter's interface blindness, and the never-reached CLI/plugin
verification. Nothing is blocked on an outside answer — the peglib exchange is closed (§8).

**Task:** bump peglib 0.6.2 → 0.7.1 in pragmatica, integrate the upstream Java grammar changes, keep the
jbct tools working. Owner rulings: adopt the grammar in the **same pass** as the bump (not staged), and
**`aether/pg-tools` is in scope** (not started). Golden formatter tests are the contract — **adapt
`FlowPrinter`, never re-baseline the goldens.**

---

## §1 State at a glance

| Module | State |
|---|---|
| JBCT Parser | ✅ green, 32 tests |
| JBCT Core | ✅ green, 135 |
| JBCT Init / Derive | ✅ green |
| JBCT Slice Processor | ✅ green, 298 |
| **JBCT Formatter** | ❌ **3 failures**, 2 fixtures (`Lambdas`, `LambdaBlockArgs`) — §3 |
| **JBCT Linter** | ❌ **13 failing test classes** — §4 |
| JBCT CLI / Maven Plugin | ⏭ SKIPPED (never reached; blocked behind format+lint) |
| `aether/pg-tools` | ⏭ untouched — §5 |

Baseline before any change was BUILD SUCCESS across all 11 modules, so every failure is attributable
to this branch.

## §2 What landed

**`ed941ab59` — the bump and CST adaptation.**

- `peglib-runtime` + `peglib-maven-plugin` 0.6.2 → **0.7.1**; mojo goal `generate-v6` → **`generate`**
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

## §3 Formatter — chain refactor LANDED; 2 lambda fixtures remain

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

**Still failing: `Lambdas.java` and `LambdaBlockArgs.java`** (2 idempotency + 1
`FlowFormatterTest$GoldenExampleComparison`). Both are chains that should break inside a lambda body or
an assignment RHS:

```
Expected: return input.map(s -> s.trim()\n                               .toUpperCase());
Actual:   return input.map(s -> s.trim().toUpperCase());
```

These are **expression position**, whose CST shape is byte-identical to 0.6.2 — so the *shape* is not the
cause; the *break decision* is. Two candidates, both unverified:

1. **`firstPostOpHasComplexArgs` bails on `!(first instanceof Cursor.Branch)`** — the `viewAt` trap (§6).
   An argument-less `POST_OP[()]` is Leaf-viewed, so it returns false, which can let the
   `chainLinkCount == 2 && isStaticFactoryReceiver && !complexArgs` early-out keep the chain inline.
   This predates the bump, so it only explains the failure if something else changed alongside it.
2. **`Expr <- Lambda / Assignment`** hoisted `Lambda` out of `Primary`, so a lambda is no longer wrapped
   in `POSTFIX → PRIMARY`. `printNodeContent` dispatches a fixed kind set that does **not** include
   `EXPR`; worth checking what the default arm does with the lambda body now that its parent chain is
   shallower.

**Next step is bisection, not analysis.** I twice reasoned from the tree instead of measuring and was
wrong both times — this project's own "bisection-first, theorize never" rule applies. Write a throwaway
test that formats the minimal snippet
`class C { Result<String> m(Option<String> in) { return in.map(s -> s.trim().toUpperCase()); } }`
and narrow from there. **Print `node.kind()`, never the view type** (§6).


## §4 Linter — 13 failing test classes, cause identified, NOT designed yet

The signature is `Expected rule JBCT-NAM-01 but found: []` — **rules silently stop firing.**

Same root as the formatter's interface bug: the grammar split `InterfaceBody`/`InterfaceMember`/
`InterfaceFieldDecl`/`InterfaceVarDecl` out of `ClassBody`/`ClassMember`. A dozen lint rules walk
`CLASS_BODY`/`CLASS_MEMBER` (`CstInjectionRule`, `CstReturnKindRule`, `CstValueObjectFactoryRule`,
`CstZoneThreeVerbsRule`, `CstConstructorBypassRule`, `FileTypeClassifier`, `ScopeScan`, …). **JBCT's
subject matter is interfaces** — `@Slice public interface`, use cases, steps — so those rules now miss
their primary target entirely.

Tree-wide there are only **4** `INTERFACE_*` references, all added by this branch in `FlowPrinter`.

**Suggested approach (unvalidated):** add a `CstNodes` helper that yields type-body members uniformly
across `CLASS_BODY`/`INTERFACE_BODY`/`RECORD_BODY`/`ENUM_BODY`, mirroring how `parameterNodes` contained
the `Params` change, then migrate the rules to it. Contain the change in one helper rather than
patching a dozen rules independently.

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

**A peglib 0.7.2 is expected** — a grammar-*instantiation* fix, confirmed by upstream as **behaviour
unchanged**. So it does **not** move `RULE_TABLE`, the CST shape, or the generated API: `RuleKind` stays
valid and §3's merge design holds. **Do not wait for it.** Re-pinning is an independent mechanical step
(change the property, run the `generate-parser` profile, run `regen_rulekind.py`, rebuild) and cannot
invalidate the work below.

1. **Finish the formatter** (§3) — the chain refactor is DONE; what remains is the 2 lambda fixtures
   (`Lambdas`, `LambdaBlockArgs`). **Bisect, do not analyse** — §3 has the minimal snippet and two
   unverified candidates. Verify with `mvn -f jbct/pom.xml -pl jbct-format -am test`; they must go green
   **without any golden edits**.
2. **Linter interface-awareness** (§4) — larger blast radius, needs a design pass first.
3. **CLI + maven plugin** — never reached; they are the stated acceptance criterion and are still
   unverified. `jbct.jar` in `jbct-cli/target/` predates this branch.
4. **`pg-tools`** (§5) — independent, can be taken any time.
5. **Re-pin to 0.7.2** when it ships. While in that file: `jbct-parser/pom.xml` uses the property name
   `peglib.maven.plugin.version` for **both** the runtime dependency and the plugin — a misnomer that
   becomes a footgun if the two ever need to diverge. Renaming to `peglib.version` is a natural fold-in
   there; deliberately not done on this branch to avoid cosmetic churn while it is red.

**Why 1 comes before 5:** green goldens are the instrument you will want pointed at 0.7.2. A pure
CST-shape change with no new rules would slip past `regen_rulekind.py` (which only proves the RULE SET
is unchanged) — the formatter goldens are what actually detect it, and they only work as a sensor once
they pass.

Nothing is pushed and no PR is open. Per project convention this ships as a PR against
`release-1.0.0-rc3` once the tools are green.
