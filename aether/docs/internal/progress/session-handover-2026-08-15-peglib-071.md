# Session handover — 2026-08-15: peglib 0.7.1 bump, half landed, chain + interface CST work remains

**Branch:** `feat/peglib-0.7.1` (off `release-1.0.0-rc3` at `19b76827f`) · **3 commits** · **NOT pushed** · tree clean
**Build is RED and deliberately so** — `jbct-format` and `jbct-lint` fail. Everything else is green.

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
| **JBCT Formatter** | ❌ **4 failures** — §3 |
| **JBCT Linter** | ❌ **13 failing test classes** — §4 |
| JBCT CLI / Maven Plugin | ⏭ SKIPPED (never reached; blocked behind format+lint) |
| `aether/pg-tools` | ⏭ untouched — §5 |

Baseline before any change was BUILD SUCCESS across all 11 modules, so every failure is attributable
to this branch.

## §2 What landed (3 commits)

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

## §3 Formatter — 4 failures, cause fully diagnosed, design settled

Fixtures: **`Lambdas.java`, `LambdaBlockArgs.java`, `StatementChains.java`** (idempotency) + 1
`FlowFormatterTest$GoldenExampleComparison`. **No content is lost — every diff is a missing line break:**

```
Expected: entries.stream()\n                             .anyMatch(spec -> …)
Actual:   entries.stream().anyMatch(spec -> …)
```

**Cause — statement-position chains no longer use `Postfix` at all** (peglib 0.7.1, JLS 14.8 rework,
deliberate and confirmed upstream):

```
a.b().c();                        return a.b().c();
STMT_EXPR(B)                      POSTFIX(B)              ← unchanged from 0.6.2
  PRIMARY(B) [a.b]                  PRIMARY(B) [a.b]
  CALL_CHAIN(B)                     POST_OP(L) [()]
    CHAIN_OP(L) [()]                POST_OP(L) [.c()]
    CALL_CHAIN(B)
      CHAIN_OP(L) [.c]
      CALL_CHAIN(B) → CALL_OP(L) [()]
```

`FlowPrinter` has no case for `STMT_EXPR`/`CALL_CHAIN`/`CHAIN_OP`/`CALL_OP`, and its whole chain
apparatus keys on `childrenByRule(postfix, POST_OP)` — which finds zero, so `chainLinkCount < 2` and the
chain never breaks. **Expression position is byte-identical to 0.6.2 and already works.**

**The design (agreed, not yet implemented):** walk the right-recursive `CALL_CHAIN` spine collecting
operator nodes, then **merge a dot-name `CHAIN_OP` with its following invocation op into one logical
link**. This matters — peglib flagged that the shapes *group operators differently*: `.c()` is **one**
`PostOp` but **two** nodes (`CHAIN_OP[.c]` + `CALL_OP[()]`), so `a.b().c()` naively scores 2 links as an
expression and **3** as a statement, and the same source would break differently by position. Merging
makes the counts identical and every existing break/align decision carries over untouched.

Mechanically: `printMethodChainAligned` and `countDotMethodChainLinks` move from `List<Cursor>` to
`List<List<Cursor>>` (a link = its node group); the `POSTFIX` call site wraps each `POST_OP` as a
singleton. The aligner's internals — anchors, broken-args handling, leading comments — stay as-is; only
the iteration unit changes.

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
  grammar moves. Script used: `scratchpad/regen_rulekind.py` (parses `RULE_TABLE`, CamelCase →
  UPPER_SNAKE, `_ROOT` → `ROOT`, `--write` to apply).
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

1. **Formatter chain refactor** (§3) — design is settled, it's the delicate part, do it fresh. Verify with
   `mvn -f jbct/pom.xml -pl jbct-format -am test` and expect the 4 fixtures to go green **without any
   golden edits**.
2. **Linter interface-awareness** (§4) — larger blast radius, needs a design pass first.
3. **CLI + maven plugin** — never reached; they are the stated acceptance criterion and are still
   unverified. `jbct.jar` in `jbct-cli/target/` predates this branch.
4. **`pg-tools`** (§5) — independent, can be taken any time.
5. **Re-pin to 0.7.2** when it ships. While in that file: `jbct-parser/pom.xml` uses the property name
   `peglib.maven.plugin.version` for **both** the runtime dependency and the plugin — a misnomer that
   becomes a footgun if the two ever need to diverge. Renaming to `peglib.version` is a natural fold-in
   there; deliberately not done on this branch to avoid cosmetic churn while it is red.

Nothing is pushed and no PR is open. Per project convention this ships as a PR against
`release-1.0.0-rc3` once the tools are green.
