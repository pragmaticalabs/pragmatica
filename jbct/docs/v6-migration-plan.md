# JBCT v6 Parser Migration Plan

**Branch:** `spike/jbct-v6-migration`
**Created:** 2026-05-13
**Status:** Stage 0 (planning) — execution begins after plan approval

## Goal

Migrate jbct-format, jbct-lint, and jbct-maven-plugin from the legacy `peglib:generate` parser (40K-line `Java25Parser` with `CstNode`/`RuleId` tree types) to the v6 emit (`peglib:generate-v6` → `Java25Lexer` + `Java25ParserV6` + `Java25Visitor` + `peglib-runtime` `CstArray`/`TokenArray`/`ParseResult`).

The point is to **actually adopt** v6's data model — flat `int[]` CST, integer rule-kind constants, inline trivia tokens — and reap its speed + memory wins. A pure compatibility-wrapper does not justify the work; this plan rewrites consumers to use the v6 abstractions natively.

## Non-goals

- Migrating `aether/pg-tools` (uses a separate Postgres grammar) — out of scope for this branch
- Changing user-facing public APIs (`JbctFormatter.format(SourceFile)`, `JbctLinter.lint(SourceFile)`, maven goals) — these are stable
- Performance benchmarking — verify correctness first; benchmark after migration if interesting
- Removing the `Cursor` indirection (we may keep it long-term as the navigation primitive even after migration)

## Architecture

### Core types (new, in `jbct-parser`)

```java
// org.pragmatica.jbct.parser.Cursor
public record Cursor(CstArray cst, int idx) {
    public int kind();
    public boolean isLeaf();
    public boolean isError();
    public boolean isRoot();
    public Optional<Cursor> firstChild();
    public Optional<Cursor> nextSibling();
    public Optional<Cursor> parent();
    public Stream<Cursor> children();
    public Stream<Cursor> descendants();
    public CharSequence text();
    public int spanStart();      // source offset
    public int spanEnd();        // source offset
    public int startLine();      // computed via LineIndex
    public int startColumn();    // computed via LineIndex
    public int firstTokenIdx();
    public int lastTokenIdx();
    public IntStream leadingTriviaTokens();
    public IntStream trailingTriviaTokens();
    public boolean kindIs(int k);
    public boolean kindIsAny(int... ks);
}
```

Implementation: thin wrapper over `(CstArray, int)`. ~16 bytes per cursor. No tree materialization. Line/column computed lazily via a shared `LineIndex` (`int[] lineStarts`) precomputed once per parse.

```java
// org.pragmatica.jbct.parser.Kinds
public final class Kinds {
    public static final int COMPILATION_UNIT = Java25Visitor.RULE_CompilationUnit_KIND;
    public static final int CLASS_DECL = Java25Visitor.RULE_ClassDecl_KIND;
    // … all ~100 rule kinds as constants

    public static boolean isClassDecl(Cursor c)   { return c.kindIs(CLASS_DECL); }
    public static boolean isMethodDecl(Cursor c)  { return c.kindIs(METHOD_DECL); }
    // … one predicate per rule

    public static boolean isTokenLike(Cursor c);  // Identifier / *KW / Modifier / NumLit / StringLit / CharLit
    public static boolean isLiteral(Cursor c);    // grammar-literal leaf (e.g., `{`, `;`, `.`)
}
```

```java
// org.pragmatica.jbct.parser.TriviaToken
public record TriviaToken(TokenArray tokens, int idx) {
    public int kind();             // TokenArray.KIND_WHITESPACE / KIND_LINE_COMMENT / etc.
    public boolean isWhitespace();
    public boolean isLineComment();
    public boolean isBlockComment();
    public boolean isDocComment();
    public CharSequence text();
    public int start();
    public int end();
}
```

```java
// org.pragmatica.jbct.parser.Java25Parser (new, slim)
public final class Java25Parser {
    public Result<Cursor> parse(String input);   // lex + parse + return root Cursor
}
```

```java
// org.pragmatica.jbct.parser.LineIndex
public final class LineIndex {
    public LineIndex(String source);
    public int lineAt(int offset);     // 1-based
    public int columnAt(int offset);   // 1-based
}
```

### Public consumer API (UNCHANGED)

- `org.pragmatica.jbct.format.JbctFormatter.format(SourceFile) → Result<FormattedFile>`
- `org.pragmatica.jbct.lint.JbctLinter.lint(SourceFile) → Result<LintReport>`
- Maven goals: `jbct:process`, `jbct:format`, `jbct:lint`
- These call internals that use `Cursor` — the public signature change is invisible.

### Internal SPI (CHANGES)

- `CstLintRule.check(CstNode)` → `CstLintRule.check(Cursor, LinterContext)`
- `CstNodes.findAll(CstNode, Class<? extends RuleId>)` → `CstNodes.findAll(Cursor, int kind)`
- `FlowPrinter.print(CstNode)` → `FlowPrinter.print(Cursor)`
- All sealed-pattern matching against `CstNode.Terminal/Token/NonTerminal/Error` → kind-based dispatch on `Cursor`

### What gets DELETED

- `Java25Parser.java` legacy emit (40K lines)
- All nested types currently inside `Java25Parser`: `RuleId` sealed (~100 records), `CstNode` sealed (Terminal/Token/NonTerminal/Error), `Trivia` sealed (LineComment/BlockComment/Whitespace), `SourceLocation`, `SourceSpan`, `IdGenerator`, `StringSpan`, `AstNode`, `ParseError`, `Severity`, `DiagnosticLabel`, `Diagnostic`, `ParseResultWithDiagnostics`, `PartialParse`, `CstParseResult`
- `peglib:generate` execution in `jbct/jbct-parser/pom.xml`

### What MOVES

- `jbct/jbct-parser/src/test/java/.../v6/Java25Lexer.java` → `.../src/main/java/.../v6/`
- Same for `Java25ParserV6.java` and `Java25Visitor.java`
- `peglib-runtime` dep promoted from `<scope>test</scope>` → `<scope>compile</scope>`

---

## Stage 1 — Parser foundation

**Goal:** establish the new types in `jbct-parser`; legacy parser deleted; jbct-parser tests pass under v6.

**Files added:**
- `jbct/jbct-parser/src/main/java/org/pragmatica/jbct/parser/Cursor.java`
- `jbct/jbct-parser/src/main/java/org/pragmatica/jbct/parser/Kinds.java`
- `jbct/jbct-parser/src/main/java/org/pragmatica/jbct/parser/TriviaToken.java`
- `jbct/jbct-parser/src/main/java/org/pragmatica/jbct/parser/LineIndex.java`
- `jbct/jbct-parser/src/main/java/org/pragmatica/jbct/parser/Java25Parser.java` (replacement, slim)

**Files moved:**
- `jbct/jbct-parser/src/test/java/.../v6/Java25Lexer.java` → `jbct/jbct-parser/src/main/java/org/pragmatica/jbct/parser/Java25Lexer.java`
- Same for `Java25ParserV6.java`, `Java25Visitor.java`

**Files deleted:**
- `jbct/jbct-parser/src/main/java/org/pragmatica/jbct/parser/Java25Parser.java` (the old 40K-line version)
- `jbct/jbct-parser/src/main/java/org/pragmatica/jbct/parser/CstNodes.java` (will be re-created in Stage 2 against `Cursor`)

**Files modified:**
- `jbct/jbct-parser/pom.xml` — drop `peglib:generate` execution; move `peglib:generate-v6` to `generate-sources` phase writing to main; promote `peglib-runtime` dep to compile scope
- `jbct/jbct-parser/src/test/java/.../v6/V6SmokeTest.java` → move to `jbct/jbct-parser/src/test/java/.../V6SmokeTest.java` (root test package) since v6 sources are no longer "v6/" subpackage
- `jbct/jbct-parser/src/test/java/.../Java25ParserTest.java` — adapt to new `parse() → Result<Cursor>` shape
- `jbct/jbct-parser/src/test/java/.../KeywordBoundaryTest.java` — same

**Design decisions to make during Stage 1:**

1. **Cursor equality**: structural (same `cst` and same `idx`) — required for IdentityHashMap-style usage in formatter
2. **Terminal vs Token discrimination**: in `Kinds`, add `isTokenLike(Cursor)` returning true for rules where the source matched a `< … >` token-boundary or named-token rule (Identifier, ClassKW, IfKW, …, Modifier, NumLit, StringLit, CharLit, PrimType, Keyword) — false for grammar literals (kind 0 "_ROOT" and the special "Literal" kind)
3. **Error nodes**: detected via `cst.isError(idx)`; expose as `Cursor.isError()`. Error text via `cst.textAt(idx)` (whole skipped range)
4. **Trailing trivia**: rarely used by consumers; provide `trailingTriviaTokens()` but expect it mostly empty
5. **`Cursor.equals` and `hashCode`**: by `(System.identityHashCode(cst), idx)` — record-default equals works since CstArray equality is identity

**Stage 1 acceptance:**
- `mvn -pl jbct/jbct-parser test` → all tests pass
- `V6SmokeTest.v6Lexer_classifiesCommentsAsDistinctKinds` still shows kind-1/kind-3 counts > 0
- `git grep -l "Java25Parser\.CstNode\|Java25Parser\.RuleId\|Java25Parser\.Trivia"` in `jbct-parser/` → only the test files (consumers in jbct-format/jbct-lint still broken — fixed in later stages)

---

## Stage 2 — CstNodes navigation utility

**Goal:** rewrite the 30-method `CstNodes` utility against `Cursor`. This is the leverage point — once done, most lint rules become near-mechanical to migrate.

**File:** `jbct/jbct-parser/src/main/java/org/pragmatica/jbct/parser/CstNodes.java` (re-created)

**Method-by-method mapping:**

| Legacy signature | New signature |
|---|---|
| `List<CstNode> children(CstNode)` | `List<Cursor> children(Cursor)` (delegates to `cursor.children().toList()`) |
| `String text(CstNode, String source)` | `String text(Cursor)` (uses `cursor.text().toString()`) |
| `boolean isRule(CstNode, Class<? extends RuleId>)` | `boolean isRule(Cursor, int kind)` |
| `List<CstNode> findAll(CstNode, Class<? extends RuleId>)` | `List<Cursor> findAll(Cursor, int kind)` |
| `List<CstNode> findAll(CstNode, Predicate<CstNode>)` | `List<Cursor> findAll(Cursor, Predicate<Cursor>)` |
| `Option<CstNode> findFirst(CstNode, Class<? extends RuleId>)` | `Option<Cursor> findFirst(Cursor, int kind)` |
| `Option<CstNode> findAncestor(...)` | `Option<Cursor> findAncestor(...)` |
| `void walk(CstNode, Consumer<CstNode>)` | `void walk(Cursor, Consumer<Cursor>)` |
| `Stream<CstNode> stream(CstNode)` | `Stream<Cursor> stream(Cursor)` |
| `Option<CstNode> child(CstNode, int)` | `Option<Cursor> child(Cursor, int)` |
| `Option<CstNode> childByRule(CstNode, Class<? extends RuleId>)` | `Option<Cursor> childByRule(Cursor, int kind)` |
| `List<CstNode> childrenByRule(CstNode, Class<? extends RuleId>)` | `List<Cursor> childrenByRule(Cursor, int kind)` |
| `boolean contains(CstNode, Class<? extends RuleId>)` | `boolean contains(Cursor, int kind)` |
| `boolean isLiteral(CstNode, String)` | `boolean isLiteral(Cursor, String)` (compares `cursor.text()` for leaf nodes) |
| `Option<String> terminalText(CstNode)` | `Option<String> terminalText(Cursor)` |
| `int count(CstNode, Class<? extends RuleId>)` | `int count(Cursor, int kind)` |
| `int startLine(CstNode)` | `int startLine(Cursor)` |
| `int startColumn(CstNode)` | `int startColumn(Cursor)` |
| `String packageName(CstNode, String source)` | `String packageName(Cursor)` |
| `boolean hasChildOfRule(CstNode, Class<? extends RuleId>)` | `boolean hasChildOfRule(Cursor, int kind)` |
| `boolean isMethodMember(CstNode)` | `boolean isMethodMember(Cursor)` |
| `List<CstNode> findAllMethods(CstNode)` | `List<Cursor> findAllMethods(Cursor)` |
| `Option<CstNode> findFirstMethod(CstNode)` | `Option<Cursor> findFirstMethod(Cursor)` |
| `boolean containsMethod(CstNode)` | `boolean containsMethod(Cursor)` |
| `int countMethods(CstNode)` | `int countMethods(Cursor)` |
| `List<CstNode> findAllClasses(CstNode)` | `List<Cursor> findAllClasses(Cursor)` |
| `Option<CstNode> findFirstClass(CstNode)` | `Option<Cursor> findFirstClass(Cursor)` |
| `boolean containsClass(CstNode)` | `boolean containsClass(Cursor)` |
| `List<CstNode> findAllInterfaces(CstNode)` | `List<Cursor> findAllInterfaces(Cursor)` |
| `Option<CstNode> findFirstInterface(CstNode)` | `Option<Cursor> findFirstInterface(Cursor)` |
| `boolean containsInterface(CstNode)` | `boolean containsInterface(Cursor)` |
| `List<CstNode> findAllRecords(CstNode)` | `List<Cursor> findAllRecords(Cursor)` |
| `Option<CstNode> findFirstRecord(CstNode)` | `Option<Cursor> findFirstRecord(Cursor)` |
| `List<CstNode> findAllEnums(CstNode)` | `List<Cursor> findAllEnums(Cursor)` |
| `List<CstNode> findAllStatements(CstNode)` | `List<Cursor> findAllStatements(Cursor)` |
| `boolean isLambdaPrimary(CstNode)` | `boolean isLambdaPrimary(Cursor)` |
| `List<CstNode> findAllLambdas(CstNode)` | `List<Cursor> findAllLambdas(Cursor)` |

**Notes:**
- The `source` parameter on `text()` becomes unnecessary — Cursor carries `cst.input()` internally
- All `Class<? extends RuleId>` parameters become `int kind` (with `Kinds.*` constants)
- Predicates change from `Predicate<CstNode>` to `Predicate<Cursor>` — callers update with mechanical search-replace

**Stage 2 acceptance:**
- `CstNodes.java` compiles standalone (no consumer migrated yet)
- Unit tests for `CstNodes` itself if any exist — pass

---

## Stage 3 — Linter migration

**Goal:** rewrite `JbctLinter` and 49 `CstLintRule` implementations against `Cursor`.

### 3a — Core linter framework

**Files:**
- `jbct/jbct-lint/src/main/java/org/pragmatica/jbct/lint/JbctLinter.java`
- `jbct/jbct-lint/src/main/java/org/pragmatica/jbct/lint/cst/CstLintRule.java` (SPI interface)
- `jbct/jbct-lint/src/main/java/org/pragmatica/jbct/lint/cst/CstLinter.java`
- `jbct/jbct-lint/src/main/java/org/pragmatica/jbct/lint/cst/SuppressionExtractor.java`

`CstLintRule` SPI change:
```java
// before
public interface CstLintRule {
    List<LintViolation> check(CstNode root, LinterContext ctx);
}
// after
public interface CstLintRule {
    List<LintViolation> check(Cursor root, LinterContext ctx);
}
```

`SuppressionExtractor` change: walks trivia to find `@SuppressWarnings`-style comments. Now uses `cursor.leadingTriviaTokens()` → maps to `TriviaToken` → checks text.

### 3b — Rule sweep (49 rules)

**Path:** `jbct/jbct-lint/src/main/java/org/pragmatica/jbct/lint/cst/rules/`

**Files:** all 49 `Cst*Rule.java` files

**Migration pattern (mechanical, per rule):**

1. Change method signature: `check(CstNode root, LinterContext ctx)` → `check(Cursor root, LinterContext ctx)`
2. Replace `RuleId.X.class` with `Kinds.X` (or `Kinds.isX(c)`):
   - `RuleId.X.class.isInstance(node.rule())` → `Kinds.isX(cursor)`
   - `node.rule() instanceof RuleId.X` → `cursor.kindIs(Kinds.X)`
   - `findAll(root, RuleId.X.class)` → `findAll(root, Kinds.X)`
3. Replace sealed `CstNode.Terminal/Token/NonTerminal/Error` switches:
   - `case CstNode.Terminal t -> t.text()` → `if (cursor.isLeaf() && Kinds.isLiteral(cursor)) { var text = cursor.text(); … }`
   - `case CstNode.Token tok -> tok.text()` → `if (cursor.isLeaf() && Kinds.isTokenLike(cursor)) { var text = cursor.text(); … }`
   - The cleanest pattern is a helper: `cursor.text()` works for any leaf
4. Replace `node.children()` → `cursor.children().toList()` (or `cursor.children()` directly as `Stream<Cursor>` for chaining)
5. Replace `node.span().startLine()` → `cursor.startLine()`

**Subagent strategy:** group rules into batches of ~10. Each batch is one subagent invocation with a self-contained brief: "Migrate these 10 rules from CstNode to Cursor; run `mvn -pl jbct/jbct-lint test -Dtest=Cst*RuleTest` after; report any rule that doesn't pass."

**Test files to update:**
- `jbct/jbct-lint/src/test/java/.../rules/CstAwaitRuleTest.java` and ~50 sibling test files — if tests use `CstNode.NonTerminal` directly, update; mostly tests should work because they exercise via `JbctLinter.lint(SourceFile)` which is the unchanged top-level API

**Stage 3 acceptance:**
- `mvn -pl jbct/jbct-lint test` → all ~174 lint tests pass
- `mvn jbct:lint` reactor-wide produces same diagnostic count as pre-migration

---

## Stage 4 — Formatter migration

**Goal:** rewrite `JbctFormatter` and the formatter internals against `Cursor`. The biggest unknown — the formatter is stateful and the trivia handling is intricate. This is also where B1-B4 fixes need re-implementation.

### Files to rewrite (in dependency order):

1. **`jbct/jbct-format/src/main/java/org/pragmatica/jbct/format/cst/AlignmentContext.java`** — already cursor-agnostic; check for `CstNode` references and remove if any
2. **`jbct/jbct-format/src/main/java/org/pragmatica/jbct/format/flow/BlankLineRules.java`** — rule-kind based; mechanical migration
3. **`jbct/jbct-format/src/main/java/org/pragmatica/jbct/format/flow/FlowFormatter.java`** — the parse-entry + flattenNonTerminal trivia-forwarding wrapper. **The flatten trick goes away under v6** because trivia is per-node via `cst.leadingTriviaTokens(idx)` with correct attribution (after our %whitespace grammar fix).
4. **`jbct/jbct-format/src/main/java/org/pragmatica/jbct/format/flow/FlowPrinter.java`** — the big one. ~1800 lines of imperative tree-walk with state.
5. **`jbct/jbct-format/src/main/java/org/pragmatica/jbct/format/cst/CstFormatter.java`** + **`CstPrinter.java`** — older path, still active for some test fixtures
6. **`jbct/jbct-format/src/main/java/org/pragmatica/jbct/format/JbctFormatter.java`** — entry point

### Key changes in `FlowFormatter` / `FlowPrinter`:

**Drop `flattenZomWrappers` entirely** — that whole pass existed to flatten OUTER/INNER same-rule wrappers produced by the legacy emit. v6's CST shape may not have this nesting (verify by probe in Stage 1).

**Drop `printStmt` brace-detection** — that was added in Stage 2 of this session to handle the legacy `Stmt[T<{>, BlockStmt*, T<}>]` and `Stmt[T<{>, Block[BlockStmt*], T<}>]` shapes. v6 emit may have a cleaner Stmt shape; verify by probing the v6 CST for `if (a > 0) { … }`.

**Rewrite trivia emission:**
- Legacy: `emitLeadingComments(node)` iterates `node.leadingTrivia()` (a `List<Trivia>`) and emits each item
- New: iterate `cursor.leadingTriviaTokens()` (an `IntStream` of token indices), classify each token by `tokens.kindAt(i)`:
  - `KIND_WHITESPACE` (0) → ignored (flow formatter controls all whitespace)
  - `KIND_LINE_COMMENT` (1) and `KIND_DOC_LINE_COMMENT` (3) → emit as line comment
  - `KIND_BLOCK_COMMENT` (2) and `KIND_DOC_BLOCK_COMMENT` (4) → emit as block comment

**B1-B4 re-verification:**
- B1 (`///` on first member): now trivial — first member's `cursor.leadingTriviaTokens()` returns the doc tokens directly
- B2 (`//` before first statement): same
- B3 (lambda chain alignment): preserved via `AlignmentContext` — should require zero changes structurally, just adapt to Cursor types
- B4 (multi-line if/for/while body): probably needs the same Stmt-brace detection but on the v6 CST shape; verify by probe

**Test files to update:**
- `jbct/jbct-format/src/test/java/.../GoldenFormatterTest.java` — calls JbctFormatter; unchanged signature, should work
- `jbct/jbct-format/src/test/java/.../JbctFormatterTest.java` — same
- `jbct/jbct-format/src/test/java/.../cst/CstFormatterTest.java` — may directly use `CstNode` types; update
- `jbct/jbct-format/src/test/java/.../flow/FlowFormatterTest*.java` — same audit

### Stage 4 acceptance:
- `mvn -pl jbct/jbct-format test` → all 51 tests pass (including `CommentsExtended.java` for B1/B2 and the multi-statement variants for B4)
- `mvn jbct:process` reactor-wide → idempotent (zero diff on second run)
- Spot-check 5 of the previously-reformatted files (those touched in commit `f61d7f68f`) — confirm no semantic change (only formatting)

---

## Stage 5 — Cleanup + verification

**Files modified:**
- `jbct/jbct-maven-plugin/src/main/java/org/pragmatica/jbct/maven/ProcessMojo.java` — update `Java25Parser` usage to new `Result<Cursor>` shape
- `jbct/jbct-maven-plugin/src/main/java/org/pragmatica/jbct/maven/LintMojo.java` (if exists separately) — same
- `jbct/jbct-maven-plugin/src/main/java/org/pragmatica/jbct/maven/FormatMojo.java` (if exists separately) — same

**Files deleted:**
- `jbct/jbct-format/src/test/java/.../FormatProbe.java` (if leftover from earlier sessions)
- Any other parser probe files

**Cleanup:**
- Remove any unused imports across `jbct-format`/`jbct-lint`
- Update `CHANGELOG.md` with the v6 migration entry
- Update `jbct/docs/` references where they mention `CstNode`/`RuleId` types

**Final verification gate (run all):**

1. `mvn -pl jbct/jbct-parser,jbct/jbct-format,jbct/jbct-lint,jbct/jbct-maven-plugin -am test` — all jbct module tests pass (target: 230+ tests, 0 failures)
2. `mvn -DskipTests compile` reactor-wide — whole reactor compiles
3. `mvn jbct:process` — reactor-wide format pass, **expect 0 diff** vs current state of `main` branch's files (idempotent, no semantic changes)
4. `mvn jbct:lint` — diagnostic count matches pre-migration baseline
5. `git diff main...HEAD -- '*.java' | wc -l` — sanity check on total change size
6. Spot check: pick 3 files known to have edge cases (BootstrapStateJson, AetherNode, ManageableNode), verify no content changes from pre-migration → post-migration formatted output

**Rollback:**
- `git branch -D spike/jbct-v6-migration` (drop the branch entirely)
- Or merge `main` back into the spike branch and start over
- Or `git revert` specific commits if granular rollback needed

---

## Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| v6 CST shape differs from legacy in ways that break B1-B4 fixtures | Medium | High | Probe v6 CST for all B1-B4 fixture inputs in Stage 1; document shape; design Stage 4 accordingly |
| Terminal/Token distinction in CstPrinter requires non-trivial discrimination | Medium | Medium | Build `Kinds.isTokenLike` and `Kinds.isLiteral` predicates in Stage 1; verify with formatter tests in Stage 4 |
| Lint rules use `findAncestorPath` semantics that depend on `CstNode` identity | Low | Medium | Verify Cursor equality is span-stable; add tests for path-walking helpers in CstNodes |
| ProcessMojo expects specific exception types from old `Java25Parser` | Low | Low | Adapt error handling at the ProcessMojo boundary |
| Token-index-vs-node-index confusion in trivia emission | Medium | High | Document the distinction in `TriviaToken` Javadoc; add explicit tests in V6SmokeTest |
| `IdGenerator` was used by formatter for stable node IDs | Low | Low | If used, replace with `(idx)` since `idx` is stable for a single parse |

---

## Execution log (filled in as stages complete)

### Stage 1 — Foundation
- Started: TBD
- Completed: TBD
- Commit: TBD
- Notes:

### Stage 2 — CstNodes
- Started: TBD
- Completed: TBD
- Commit: TBD
- Notes:

### Stage 3a — Linter framework
- Started: TBD
- Completed: TBD
- Commit: TBD
- Notes:

### Stage 3b — Lint rules sweep (49 rules)
- Subagent batches (10 rules each, parallelizable):
  - Batch 1: `CstAcronymNamingRule`, `CstAlwaysSuccessResultRule`, `CstAwaitRule`, `CstChainLengthRule`, `CstConditionalLoggingRule`, `CstConstructorBypassRule`, `CstConstructorReferenceRule`, `CstDiscardedResultRule`, `CstDomainIoRule`, `CstFactoryNamingRule` — status: TBD
  - Batch 2: `CstFluentFailureRule`, `CstFullyQualifiedNameRule`, `CstIfElseReturnRule`, `CstImportOrderingRule`, `CstLambdaBracesRule`, `CstLambdaComplexityRule`, `CstLambdaTernaryRule`, `CstLoggerParameterRule`, `CstMethodReferencePreferenceRule`, `CstNestedOperationsRule` — status: TBD
  - Batch 3: `CstNestedRecordFactoryRule`, `CstNestedWrapperRule`, `CstNoBusinessExceptionsRule`, `CstNullReturnRule`, `CstNullableParameterRule`, `CstOrElseThrowRule`, `CstParsingUtilitiesRule`, `CstPatternMixingRule`, `CstRawLoopRule`, `CstReturnKindRule` — status: TBD
  - Batch 4: `CstSealedErrorRule`, `CstStaticImportRule`, `CstUnnecessaryVarReturnRule`, `CstUtilityClassRule`, `CstValidatedNamingRule`, `CstValueObjectFactoryRule`, `CstVerifyPredicatesRule`, `CstVoidTypeRule`, `CstZoneMixingRule`, `CstZoneThreeVerbsRule`, `CstZoneTwoVerbsRule` (11 in this final batch) — status: TBD

### Stage 4 — Formatter
- Started: TBD
- Completed: TBD
- Commit: TBD
- Notes:

### Stage 5 — Cleanup + verification
- Started: TBD
- Completed: TBD
- Commit: TBD
- Notes:

---

## Resume protocol (for fresh context window)

If a session ends mid-migration, the next session can resume by:

1. Reading this file (`jbct/docs/v6-migration-plan.md`)
2. Checking the "Execution log" above for the last completed stage
3. Running `git log --oneline spike/jbct-v6-migration ^main` to see commits landed so far
4. Running `mvn -pl jbct/jbct-parser,jbct/jbct-format,jbct/jbct-lint test` to confirm current state's test status
5. Reading the next-stage section of this plan
6. Proceeding

Key invariant: **each stage's commit leaves the branch in a building state**, even if not all consumers are migrated (some may be temporarily broken at module boundary, but at least the migrated module compiles + its tests pass). This gives clean rollback points.
