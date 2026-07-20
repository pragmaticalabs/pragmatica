package org.pragmatica.jbct.lint.cst.shape;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.jbct.lint.cst.filetype.FileTypeClassifier;
import org.pragmatica.jbct.lint.cst.rules.MapperSafety;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;
import org.pragmatica.lang.Option;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// Method-shape classification engine (issue #448, phase 1 census + phase 2 reach extension).
///
/// Assigns every concrete method exactly one [MethodShape] from the syntax of its returned
/// expression alone — no cross-file type resolution. The book's "single pattern per function" rule
/// says each method realises one of six composition patterns; this classifier mechanises that rule
/// so the shape distribution over a corpus becomes measurable ([ShapeCensus]) and the two residual
/// verdicts ([MethodShape#MIXED] / [MethodShape#UNCLASSIFIED]) can be surfaced as INFO diagnostics
/// (`JBCT-SHAPE-01` / `JBCT-SHAPE-02`).
///
/// **Two-stage design.** (1) *Spine extraction* — the returned expression is reduced through the
/// precedence chain to its composition root; when that root is a postfix call chain its top-level
/// links are read into a [Spine] (head text + the ordered method-call link names). (2) *Decision
/// table* — [#classifyChain] maps the spine's features (join head, stream pipeline, sequencing-link
/// count, aggregator head) to one shape. A root ternary / switch-expression is a [MethodShape#CONDITION];
/// a factory that returns a lambda is classified by the lambda's body (one unwrap); a `with*`
/// decorator or a body that applies an injected functional parameter is an [MethodShape#ASPECT].
///
/// **Legal composition does not flag.** A Fork-Join extracted to its own method and *called* as a
/// sequencer step reads as a plain `flatMap` link (the join is hidden behind the reference), so it
/// classifies as [MethodShape#SEQUENCER], never [MethodShape#MIXED]. The violation the book targets
/// is a join nested *inside a lambda*, which is owned by `JBCT-PAT-02` — see the phase-3 descent
/// note below.
///
/// **MIXED is stream-specific in phase 1, by design.** The only two-feature blend flagged is a
/// fork-join head *and* a stream pipeline at the same top-level altitude. A join head followed by
/// two or more carrier combinators (`Result.all(...).flatMap(a).flatMap(b)`) is the idiomatic
/// "validate-in-parallel then sequence" shape and classifies as [MethodShape#SEQUENCER] — NOT
/// MIXED — even though it superficially blends fork-join and sequencer. The asymmetry (join+stream
/// blend flags, join+sequencer blend does not) is deliberate: the join-seeded sequencer is
/// conformant, whereas a carrier chain that also runs a raw stream at one altitude is not. The
/// richer same-altitude blends (a join nested in a sequencer lambda) are `JBCT-PAT-02`'s until
/// phase 2 revisits MIXED and folds that rule in.
///
/// **Phase-3 lambda-argument descent (#448, now wired).** `JBCT-PAT-02` (fork-join nested in a
/// sequencer lambda), `JBCT-ZONE-03` (Zone-3 verb inside a `map`/`flatMap` lambda), and
/// `JBCT-NEST-01` (nested monadic ops in a lambda) were string-heuristic shadows of this classifier;
/// they are now facets that descend into a chain's lambda arguments — the descent [#extractSpine]
/// deliberately discards — instead of scanning raw method text. The shared primitive is
/// [#chainLambdaLinks] (the lambda-argument bodies of a chain, paired with their link names) plus
/// [#classifyLambdaBody] (a lambda body run through this same decision table). The rules become thin
/// delegators; their token/verb detection is unchanged in substance but now runs over
/// `MapperSafety.blankNonCode`-masked, CST-scoped text, so a verb / operator / join token spelled
/// inside a string or comment no longer fires. Phase 1's own verdicts still read the top-level
/// composition root only — the descent is a facet capability, not a change to [#classify].
///
/// **Phase-3 shape<->zone cross-check (JBCT-SHAPE-03, #448).** A NEW facet built directly on
/// [#classify] (not an absorbed regex shadow): [#shapeZoneMismatches] compares a method's composition
/// shape against the abstraction zone of its NAME verb and flags the two clear disagreements — a
/// Zone-3 implementation verb heading a SEQUENCER / FORK_JOIN (mis-leveled up), and a Zone-2
/// orchestration verb heading a LEAF (mis-leveled down). Naming zone and composition shape are
/// orthogonal axes, so this ships INFO for corpus calibration; its documented false-positive surface
/// lives on the facet.
///
/// **Known misclassification surface (documented, not worked around).**
/// - No type resolution: `Stream.map` vs `Result.map` is disambiguated by contextual heuristics
///   (a `.stream()`/`.parallelStream()` source plus a `toList`/`collect` terminal marks Iteration);
///   a stream pipeline held in a plain `List` variable and re-streamed can read as a sequencer.
/// - Method references hide callee monadicity: `x.flatMap(this::forkBoth)` is a sequencer link even
///   though `forkBoth` is itself a Fork-Join (this is the *legal-composition* case — intentionally
///   not flagged).
/// - A single combinator link (`call().map(f)`) is [MethodShape#LEAF] ("one-step sequencer is a
///   leaf in disguise", per the book's 2–5-step rule); a genuine sequencer needs two or more links.
/// - Aspect detection is bidirectionally imperfect. FN: an aspect lacking the `with*` name and the
///   `param.apply(..)`-then-decorate shape reads as Sequencer / Leaf. FP: a method that genuinely
///   sequences but happens to apply an injected functional parameter first and chain a combinator
///   (`step.apply(x).flatMap(next)`) reads as ASPECT rather than Sequencer. Aspects are the least
///   syntactically distinct pattern; both directions are accepted (histogram-only impact — the six
///   pure shapes are never diagnosed, so a shape↔shape swap changes only the census counts).
/// - Instance-style joins (`promise.all(f1, f2)`) read as Sequencer; accepted, the calibration
///   signal, not a silent guess.
/// - Multi-statement bodies are handled by the phase-2 preamble reach (see below): a body whose
///   leading statements are all skippable preamble (pure local declarations, narrow guard clauses,
///   or single logger calls) classifies by its composition-root tail; only a body with a genuinely
///   imperative leading statement (a side effect, a reassignment, a mutating-initializer local, a
///   loop / `if` / `try`) stays [MethodShape#UNCLASSIFIED].
///
/// **Phase-2 preamble reach (#448).** [#classifyBody] no longer bails on ANY multi-statement body.
/// A method's body is reduced to its single composition-root tail (the last statement — a `return`
/// or a trailing void expression) when every leading statement is *skippable preamble*: (a) a pure
/// local-variable declaration — a named sub-expression feeding the root, (b) a narrow guard clause
/// `if (cond) return …;` / `if (cond) throw …;` (single-branch, no `else`), or (c) a single logger
/// statement (`log.*(…)` / `LOG.*(…)`). The tail is then run through the same [#classifyStatement]
/// spine as a one-statement body, so `locals-then-Result.all(...)` reads FORK_JOIN and
/// `locals-then-map-chain` reads SEQUENCER. **Mutation-signal FP guard** (the real risk): a local
/// whose initializer text — masked via `MapperSafety.blankNonCode` so a token inside a string or
/// comment cannot match — contains a mutation token ([#MUTATION_SIGNALS]) is NOT pure; the method is
/// genuinely imperative and stays UNCLASSIFIED. Reassignment / shadowing is excluded structurally (an
/// assignment statement is not a `LOCAL_VAR` node, so it is never skippable preamble and falls
/// through). FP of the guard (flagging a pure `BigDecimal.add(…)` / no-arg `.start()` getter as
/// mutating) only *withholds* a reclassification — conservative. FN of the guard (a custom-named
/// mutator, a direct field write, `StringBuilder.append`) can promote an imperative method to a
/// shape — accepted, INFO census. This reach is expected to roughly halve the residual, not eliminate
/// it: a corpus of genuinely-imperative methods is a corpus fact, not a classifier defect.
public final class MethodShapeClassifier {
    private MethodShapeClassifier() {}

    /// The shape verdict for one method: the assigned [MethodShape] and a short human reason string
    /// (the spine feature that decided it), consumed by the census and the diagnostic detail text.
    public record ShapeVerdict(MethodShape shape, String reason) {}

    /// Top-level links of a postfix call chain: the head expression text (`Promise.all`, `validate`,
    /// `raw.stream`) and the ordered names of the `.name(...)` method-call links along it. The
    /// `headInvoked` flag records whether the head itself is called (`foo(...)`), distinguishing a
    /// bare reference (a getter leaf) from an invoked leaf. When the head is an *invoked dotted call
    /// target* (`valid.map(...)`) the v6 PRIMARY node absorbs the leading `.method` into `headText`,
    /// so that method-call segment is recovered into `linkMethods` (see [#extractSpine]) while the
    /// full dotted `headText` is retained for the join / aggregator / stream / aspect head patterns.
    private record Spine(String headText, List<String> linkMethods, boolean headInvoked) {}

    /// Fork-Join heads: the varargs / multi-carrier join forms. Collection aggregation (`allOf`) is
    /// deliberately excluded here — it is an Iteration aggregator (see [#AGGREGATOR_HEAD]).
    private static final Pattern JOIN_HEAD = Pattern.compile("^(Result|Promise|Option)\\.(all|allOrCancel|any)$");

    /// Iteration aggregator heads: the collection forms that fold a `Collection` of carriers into one
    /// carrier of a collection. Their presence marks Iteration regardless of the argument shape.
    private static final Pattern AGGREGATOR_HEAD =
        Pattern.compile("^(Result|Promise|Option)\\.(allOf|allOfOrCancel|allSuccess)$");

    /// Stream sources: a `.stream()` / `.parallelStream()` receiver, or a `*Stream` static factory.
    private static final Pattern STREAM_SOURCE_HEAD =
        Pattern.compile(".*\\.(stream|parallelStream)$|^(Stream|IntStream|LongStream|DoubleStream)\\..*");

    /// Functional return types marking a decorator/aspect definer even without a `with*` name.
    private static final Pattern FUNCTIONAL_TYPE =
        Pattern.compile("^(Fn0|Fn1|Fn2|Fn3|Function|BiFunction|Supplier|Consumer|Runnable|Predicate)$");

    /// Stream terminals: their presence with a source marks a stream pipeline (Iteration).
    private static final Set<String> STREAM_TERMINALS = Set.of("toList",
                                                               "collect",
                                                               "forEach",
                                                               "toArray",
                                                               "toSet",
                                                               "count",
                                                               "reduce",
                                                               "sum",
                                                               "average",
                                                               "min",
                                                               "max",
                                                               "anyMatch",
                                                               "allMatch",
                                                               "noneMatch",
                                                               "findFirst",
                                                               "findAny",
                                                               "joining");

    /// Stream source link names (when the source is a link rather than the head).
    private static final Set<String> STREAM_SOURCE_LINKS = Set.of("stream", "parallelStream");

    /// Carrier combinators that thread a value through a chain — two or more at one altitude is a
    /// Sequencer. `map`/`filter` also occur on streams, but a stream pipeline is resolved to
    /// Iteration before link counting, so their carrier sense is what remains here.
    private static final Set<String> SEQ_COMBINATORS = Set.of("map",
                                                              "flatMap",
                                                              "andThen",
                                                              "then",
                                                              "mapWith",
                                                              "flatMapWith",
                                                              "ensureWith",
                                                              "recover",
                                                              "replaceResult",
                                                              "mapToUnit",
                                                              "mapError",
                                                              "filter",
                                                              "async",
                                                              "fold",
                                                              "or",
                                                              "orElse",
                                                              "onSuccess",
                                                              "onFailure");

    /// Statement-leading keywords that make a body imperative residue (no composition root).
    private static final Set<String> IMPERATIVE_KEYWORDS = Set.of("for",
                                                                  "if",
                                                                  "while",
                                                                  "do",
                                                                  "try",
                                                                  "switch",
                                                                  "synchronized",
                                                                  "throw",
                                                                  "break",
                                                                  "continue",
                                                                  "yield",
                                                                  "assert");

    /// Mutation tokens marking a local-variable initializer as impure (phase-2 FP guard, #448). Their
    /// presence in an initializer (masked via `MapperSafety.blankNonCode` first, so a token inside a
    /// string or comment cannot match) means the declaration performs a side effect — the method is
    /// genuinely imperative and must not be reclassified by its tail. Curated in the token-heuristic
    /// style of [#SEQ_COMBINATORS]: collection / map / queue / stack mutators and atomic
    /// read-modify-write forms. Documented FP surface (over-flags, only withholding a
    /// reclassification — conservative): pure value-returning `.add(` / `.remove(` on `BigDecimal` /
    /// immutable collections, and a no-argument `.start(` getter. Documented FN surface (under-flags,
    /// can promote an imperative method — accepted at INFO): custom-named mutators, direct field
    /// writes, and `StringBuilder.append`.
    private static final Set<String> MUTATION_SIGNALS = Set.of(".set(",
                                                              ".put(",
                                                              ".putIfAbsent(",
                                                              ".add(",
                                                              ".addAll(",
                                                              ".remove(",
                                                              ".removeAll(",
                                                              ".incrementAndGet(",
                                                              ".decrementAndGet(",
                                                              ".getAndIncrement(",
                                                              ".getAndDecrement(",
                                                              ".getAndSet(",
                                                              ".compareAndSet(",
                                                              ".start(",
                                                              ".offer(",
                                                              ".poll(",
                                                              ".push(",
                                                              ".pop(");

    /// A leading logger statement (phase-2 skippable preamble, #448): a `log` / `LOG` / `logger` /
    /// `LOGGER` receiver calling any method. Matched against the masked statement text so a logger
    /// mention inside a string / comment cannot match. Logging is cross-cutting noise with no
    /// data-flow role, so it is skipped before the tail is classified.
    private static final Pattern LOGGER_STATEMENT = Pattern.compile("^\\s*(log|LOG|logger|LOGGER)\\s*\\.\\s*\\w+\\s*\\(");

    private static final Pattern LEADING_WORD = Pattern.compile("^\\s*([A-Za-z]+)");
    private static final Pattern PARAM_NAME_TAIL = Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)\\s*$");

    /// Classify a method member, or [Option#none()] when the member is not a classifiable body —
    /// an abstract interface method (no body) or an empty body, both excluded from the census.
    public static Option<ShapeVerdict> classify(Cursor methodMember) {
        return methodBody(methodMember).flatMap(body -> classifyBody(methodMember, body));
    }

    private static Option<ShapeVerdict> classifyBody(Cursor methodMember, Cursor body) {
        var statements = realStatements(body);

        if (statements.isEmpty()) {
            return Option.none();
        }

        if (statements.size() == 1) {
            return Option.some(classifyStatement(methodMember, statements.getFirst()));
        }

        return Option.some(classifyPreambleThenTail(methodMember, statements));
    }

    /// Phase-2 reach (#448): a multi-statement body classifies by its composition-root tail (the last
    /// statement) when every leading statement is skippable preamble (a pure local declaration, a
    /// narrow guard clause, or a single logger call). The first non-skippable leading statement makes
    /// the body imperative residue with a precise reason; a body that ends with a local declaration
    /// has no composition tail and is likewise residue.
    private static ShapeVerdict classifyPreambleThenTail(Cursor methodMember, List<Cursor> statements) {
        for (var leading : statements.subList(0, statements.size() - 1)) {
            if (!isSkippablePreamble(leading)) {
                return residueVerdict(leading);
            }
        }

        var tail = statements.getLast();

        if (isLocalVarDecl(tail)) {
            return new ShapeVerdict(MethodShape.UNCLASSIFIED, "body ends with a local declaration — no composition tail");
        }

        return classifyStatement(methodMember, tail);
    }

    /// True when a leading statement carries no data-flow weight of its own and can be skipped before
    /// the tail is classified: a pure local declaration, a narrow guard clause, or a single logger call.
    private static boolean isSkippablePreamble(Cursor statement) {
        return isPureLocalVarDecl(statement) || isGuardClause(statement) || isLoggerStatement(statement);
    }

    /// A local-variable declaration all of whose initializers are pure (carry no [#MUTATION_SIGNALS]).
    /// A declaration with no initializer is not pure — it produces no value to feed the root.
    private static boolean isPureLocalVarDecl(Cursor statement) {
        return childByRule(statement, RuleKind.LOCAL_VAR)
            .map(local -> contains(local, RuleKind.VAR_INIT) && !containsMutationSignal(text(local)))
            .or(false);
    }

    /// True when the masked declaration text carries any mutation token. Masking blanks strings and
    /// comments so a token spelled inside a literal cannot match; identifiers and type names cannot
    /// produce a `.name(` token, so scanning the whole declaration is equivalent to scanning the
    /// initializer for these tokens.
    private static boolean containsMutationSignal(String declarationText) {
        var masked = MapperSafety.blankNonCode(declarationText);

        return MUTATION_SIGNALS.stream().anyMatch(masked::contains);
    }

    /// A narrow guard clause: `if (cond) return …;` or `if (cond) throw …;` with a single branch and
    /// no `else`. Anything richer (an `else`, a braced body, a non-return/throw branch) is not a guard
    /// and falls through to imperative residue.
    private static boolean isGuardClause(Cursor statement) {
        return childByRule(statement, RuleKind.STMT)
            .map(MethodShapeClassifier::isGuardStmt)
            .or(false);
    }

    private static boolean isGuardStmt(Cursor stmt) {
        if (!leadingWord(text(stmt)).equals("if")) {
            return false;
        }

        var branches = childrenByRule(stmt, RuleKind.STMT);

        return branches.size() == 1 && isReturnOrThrow(leadingWord(text(branches.getFirst())));
    }

    private static boolean isReturnOrThrow(String keyword) {
        return keyword.equals("return") || keyword.equals("throw");
    }

    /// A single logger call statement (`log.*(…)` / `LOG.*(…)`), matched against masked statement text.
    private static boolean isLoggerStatement(Cursor statement) {
        return childByRule(statement, RuleKind.STMT)
            .map(stmt -> LOGGER_STATEMENT.matcher(MapperSafety.blankNonCode(text(stmt))).find())
            .or(false);
    }

    private static boolean isLocalVarDecl(Cursor statement) {
        return hasChildOfRule(statement, RuleKind.LOCAL_VAR);
    }

    /// Precise reason for a leading statement that is not skippable preamble: an imperative keyword
    /// (`for` / non-guard `if` / `try` / …), a local with a mutating initializer, or a bare
    /// side-effect statement (a discarded call or a reassignment).
    private static ShapeVerdict residueVerdict(Cursor statement) {
        var keyword = leadingWord(text(statement));

        if (IMPERATIVE_KEYWORDS.contains(keyword)) {
            return new ShapeVerdict(MethodShape.UNCLASSIFIED, "leading imperative statement (" + keyword + ")");
        }

        if (isLocalVarDecl(statement)) {
            return new ShapeVerdict(MethodShape.UNCLASSIFIED, "leading local with a mutating initializer");
        }

        return new ShapeVerdict(MethodShape.UNCLASSIFIED, "leading side-effect statement — no single composition root");
    }

    private static ShapeVerdict classifyStatement(Cursor methodMember, Cursor statement) {
        var keyword = leadingWord(text(statement));

        if (IMPERATIVE_KEYWORDS.contains(keyword)) {
            return new ShapeVerdict(MethodShape.UNCLASSIFIED, "imperative statement (" + keyword + ")");
        }

        return statementExpression(statement).map(expr -> classifyExpression(methodMember, expr, false))
                                             .or(new ShapeVerdict(MethodShape.UNCLASSIFIED, "no returned expression"));
    }

    private static ShapeVerdict classifyExpression(Cursor methodMember, Cursor expr, boolean viaLambda) {
        var node = significant(expr);

        if (node.kindIs(RuleKind.LAMBDA)) {
            return classifyLambda(methodMember, node);
        }

        if (isSwitchExpression(node) || isActualTernary(node)) {
            return new ShapeVerdict(MethodShape.CONDITION, "root " + (isActualTernary(node) ? "ternary" : "switch-expression"));
        }

        if (node.kindIs(RuleKind.POSTFIX)) {
            return classifyChain(methodMember, extractSpine(node), viaLambda);
        }

        return classifyNonChain(node);
    }

    private static ShapeVerdict classifyLambda(Cursor methodMember, Cursor lambda) {
        if (FileTypeClassifier.methodName(methodMember).startsWith("with")) {
            return new ShapeVerdict(MethodShape.ASPECT, "with* decorator returning a lambda");
        }

        return lambdaBodyExpression(lambda).map(body -> classifyExpression(methodMember, body, true))
                                           .or(new ShapeVerdict(MethodShape.UNCLASSIFIED, "multi-statement lambda body"));
    }

    // ===== Phase-3 argument-lambda descent primitive (#448) =====
    //
    // The phase-1 classifier reads only a chain's top-level composition root; [#extractSpine]
    // sees the `(… -> …)` argument lists but discards their lambda bodies. Phase 3 exposes those
    // discarded lambda-argument bodies ([#chainLambdaLinks]) and classifies a lambda body through
    // the same decision table used for method bodies ([#classifyLambdaBody]). The absorbed rules
    // JBCT-ZONE-03 / JBCT-NEST-01 / JBCT-PAT-02 are facets built on this descent — see their
    // rule classes. This ARGUMENT-lambda descent is distinct from [#classifyLambda], which owns
    // the separate factory-returns-a-lambda ASPECT case at a method's own top level.

    /// One lambda passed to a chain link: the link's method name (`map`, `flatMap`, `andThen`, …)
    /// and the LAMBDA cursor it received. The name lets a facet restrict itself to a link family
    /// (`map`/`flatMap` for zone mixing, `flatMap`/`andThen` for pattern mixing).
    public record LambdaLink(String link, Cursor lambda) {}

    /// The lambda arguments attached to the top-level links of a postfix call chain, each paired
    /// with the name of the link it is passed to. Reads only the chain's own direct `POST_OP`
    /// children (mirroring [#extractSpine]), so a lambda nested inside a deeper sub-chain is not
    /// reported here — it surfaces when that sub-chain is read as its own postfix. The
    /// absorbed-head-call link (`value.flatMap(x -> …)`, whose `flatMap` the v6 PRIMARY folds into
    /// the head text) is recovered so its lambda pairs with `flatMap`, never lost. A `POST_OP` with
    /// no lambda argument (`.async()`, `.map(this::f)`) contributes nothing. Non-postfix input
    /// yields an empty list. Documented edge: when a link's direct argument is a *call* that itself
    /// wraps a lambda (`.flatMap(save(x -> …))`), the inner lambda is attributed to the outer link —
    /// accepted, since the only consumer (JBCT-PAT-02, corpus baseline 0) reasons about lambda-body
    /// content, not attachment depth.
    public static List<LambdaLink> chainLambdaLinks(Cursor postfix) {
        if (!postfix.kindIs(RuleKind.POSTFIX)) {
            return List.of();
        }

        var kids = children(postfix);
        var headText = kids.isEmpty() ? "" : text(kids.getFirst()).trim();
        var out = new ArrayList<LambdaLink>();

        for (var i = 1; i < kids.size(); i++) {
            var op = kids.get(i);
            var opText = text(op).trim();
            var linkName = opText.startsWith("(")
                           ? absorbedHeadCallLink(headText, true).or("")
                           : methodLinkName(opText).or("");

            firstArgLambda(op).onPresent(lambda -> out.add(new LambdaLink(linkName, lambda)));
        }

        return out;
    }

    /// The first LAMBDA in a `POST_OP`'s argument subtree, or none for a non-lambda argument.
    private static Option<Cursor> firstArgLambda(Cursor postOp) {
        return findFirst(postOp, RuleKind.LAMBDA);
    }

    /// Recursively classify a lambda body through the same decision table used for method bodies —
    /// the phase-3 argument-lambda descent (#448). A block body (`x -> { … }`) runs through the
    /// full [#classifyBody] preamble/tail reach; a single-expression body (`x -> expr`) runs through
    /// [#classifyExpression] with the in-lambda flag set. An empty body, or a multi-statement block
    /// with no composition-root tail, is UNCLASSIFIED. Distinct from [#classifyLambda] (the
    /// top-level factory-returns-a-lambda ASPECT case): this descends into an *argument* lambda and
    /// never applies the method-name `with*` aspect heuristic.
    public static ShapeVerdict classifyLambdaBody(Cursor methodMember, Cursor lambda) {
        return childByRule(lambda, RuleKind.BLOCK).flatMap(block -> classifyBody(methodMember, block))
                                                  .orElse(() -> lambdaBodyExpression(lambda).map(body -> classifyExpression(methodMember, body, true)))
                                                  .or(new ShapeVerdict(MethodShape.UNCLASSIFIED, "empty or multi-statement lambda body"));
    }

    private static ShapeVerdict classifyChain(Cursor methodMember, Spine spine, boolean viaLambda) {
        if (isAspectShape(methodMember, spine, viaLambda)) {
            return new ShapeVerdict(MethodShape.ASPECT, "applies an injected functional parameter and decorates it");
        }

        var join = JOIN_HEAD.matcher(spine.headText()).matches();
        var aggregator = AGGREGATOR_HEAD.matcher(spine.headText()).matches();
        var streamPipeline = isStreamPipeline(spine);
        var seq = sequencingLinkCount(spine);

        if (join && streamPipeline) {
            return new ShapeVerdict(MethodShape.MIXED, "fork-join head and a stream pipeline at the same altitude");
        }

        if (join && seq <= 1) {
            return new ShapeVerdict(MethodShape.FORK_JOIN, "join head (" + spine.headText() + ") with a single combine step");
        }

        if (aggregator || streamPipeline) {
            return new ShapeVerdict(MethodShape.ITERATION, aggregator ? "collection aggregator (" + spine.headText() + ")" : "stream source with a terminal");
        }

        if (seq >= 2) {
            return new ShapeVerdict(MethodShape.SEQUENCER, seq + " dependent combinator steps");
        }

        if (seq == 1) {
            return new ShapeVerdict(MethodShape.LEAF, "single-combinator transform (one-step sequencer is a leaf in disguise)");
        }

        return new ShapeVerdict(MethodShape.LEAF, spine.headInvoked() ? "atomic call (" + spine.headText() + ")" : "value reference (" + spine.headText() + ")");
    }

    /// A root that is neither a call chain, ternary, nor lambda — a bare arithmetic / boolean /
    /// relational expression. Pure computation with no combinator is a Leaf; an expression that
    /// mixes composition roots (a combinator chain inside an operand) has no single shape. The
    /// combinator scan is on raw text — a string literal spelling a combinator would misread, an
    /// accepted edge on this rare fallback path (INFO census).
    private static ShapeVerdict classifyNonChain(Cursor node) {
        var text = text(node);

        if (text.contains(".map(") || text.contains(".flatMap(") || text.contains(".stream(")) {
            return new ShapeVerdict(MethodShape.UNCLASSIFIED, "binary expression mixing composition roots");
        }

        return new ShapeVerdict(MethodShape.LEAF, "pure expression");
    }

    /// True when a lambda body applies an injected functional parameter (`step.apply(..)`) and then
    /// chains at least one decorator link — the structural Aspect shape independent of the method
    /// name. Requires the unwrap to have happened (a raw non-lambda body cannot be this shape).
    private static boolean isAspectShape(Cursor methodMember, Spine spine, boolean viaLambda) {
        if (!viaLambda || !spine.headText().endsWith(".apply") || spine.linkMethods().isEmpty()) {
            return false;
        }

        var receiver = spine.headText().substring(0, spine.headText().length() - ".apply".length());

        return paramNames(methodMember).contains(receiver);
    }

    private static boolean isStreamPipeline(Spine spine) {
        var hasSource = STREAM_SOURCE_HEAD.matcher(spine.headText()).matches()
                        || spine.linkMethods().stream().anyMatch(STREAM_SOURCE_LINKS::contains);
        var hasTerminal = spine.linkMethods().stream().anyMatch(STREAM_TERMINALS::contains);

        return hasSource && hasTerminal;
    }

    private static int sequencingLinkCount(Spine spine) {
        return (int) spine.linkMethods().stream()
                                        .filter(SEQ_COMBINATORS::contains)
                                        .count();
    }

    /// Read the top-level links of a postfix chain: the head node's text plus, for each `POST_OP`,
    /// either a `.name(...)` method-call link (recorded) or the head's own `(...)` invocation.
    ///
    /// **Absorbed head-call recovery.** The v6 grammar folds a dotted call target into one `PRIMARY`
    /// leaf: `valid.map(this::enrich)` parses as `PRIMARY [valid.map]` + `POST_OP [(this::enrich)]`,
    /// not `PRIMARY [valid]` + `POST_OP [.map]` + `POST_OP [(…)]`. Without recovery the leading
    /// combinator link is lost and a two-step chain reads as a one-step Leaf (`valid.map(f).flatMap(g)`
    /// → seq 1 → LEAF instead of SEQUENCER). When the head is a dotted name *and* it is invoked (a
    /// following `(...)`), the segment after its last `.` is that call's method name: it is recovered
    /// as the chain's first link. The full dotted `headText` is kept unchanged so the join
    /// (`Promise.all`), aggregator (`Result.allOf`), stream-source (`raw.stream`) and aspect
    /// (`step.apply`) head patterns still match — those segments simply aren't sequencing combinators,
    /// so recovering them is inert for the decision table. One second-order effect (histogram-only,
    /// accepted): recovery makes `linkMethods` non-empty for an invoked bare `param.apply(x)` body, so
    /// it now reads ASPECT rather than LEAF via [#isAspectShape]'s non-empty-links path — the
    /// reclassifications this fix produces are therefore mostly LEAF→SEQUENCER but include this
    /// LEAF→ASPECT edge. Both are within the accepted aspect/leaf census tolerance.
    private static Spine extractSpine(Cursor postfix) {
        var kids = children(postfix);
        var headText = kids.isEmpty() ? "" : text(kids.getFirst()).trim();
        var links = new ArrayList<String>();
        var headInvoked = false;

        for (var i = 1; i < kids.size(); i++) {
            var opText = text(kids.get(i)).trim();

            if (opText.startsWith("(")) {
                headInvoked = true;
            } else if (opText.startsWith(".")) {
                methodLinkName(opText).onPresent(links::add);
            }
        }

        absorbedHeadCallLink(headText, headInvoked).onPresent(link -> links.add(0, link));

        return new Spine(headText, links, headInvoked);
    }

    /// The method-call segment the v6 PRIMARY absorbed from an invoked dotted head — the identifier
    /// after the head's last `.`, or none when the head carries no `.` or is not invoked. A bare
    /// dotted field access (`a.b` with no following `(...)`) contributes no call link; an empty or
    /// non-identifier trailing segment (an explicit type witness such as `Result.<X>all`) is skipped.
    private static Option<String> absorbedHeadCallLink(String headText, boolean headInvoked) {
        if (!headInvoked) {
            return Option.none();
        }

        var dot = headText.lastIndexOf('.');

        if (dot < 0) {
            return Option.none();
        }

        var segment = leadingIdentifier(headText, dot + 1);

        return segment.isEmpty() ? Option.none() : Option.some(segment);
    }

    /// The maximal run of Java identifier characters in `text` starting at `start` (empty when the
    /// character at `start` is not an identifier part).
    private static String leadingIdentifier(String text, int start) {
        var i = start;

        while (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) {
            i++;
        }

        return text.substring(start, i);
    }

    /// The method name of a `.name(...)` post-op, or none for a field access (`.name` with no call).
    private static Option<String> methodLinkName(String opText) {
        var i = 1;

        while (i < opText.length() && Character.isJavaIdentifierPart(opText.charAt(i))) {
            i++;
        }

        var name = opText.substring(1, i);
        var j = i;

        while (j < opText.length() && Character.isWhitespace(opText.charAt(j))) {
            j++;
        }

        return !name.isEmpty() && j < opText.length() && opText.charAt(j) == '('
               ? Option.some(name)
               : Option.none();
    }

    /// Descend an expression through single-child precedence wrappers to its composition root,
    /// stopping at a call chain (a postfix that carries `POST_OP` links), a lambda, a leaf, or a
    /// multi-child operator (an actual ternary or a binary operator). A *bare* postfix with no
    /// `POST_OP` (the precedence-chain wrapper around a primary) is transparent — descending it
    /// reaches a wrapped lambda (`POSTFIX > PRIMARY > LAMBDA`) or a bare reference.
    private static Cursor significant(Cursor expr) {
        var node = expr;

        while (true) {
            if (node instanceof Cursor.Leaf || node.kindIs(RuleKind.LAMBDA)) {
                return node;
            }

            if (node.kindIs(RuleKind.POSTFIX) && hasChildOfRule(node, RuleKind.POST_OP)) {
                return node;
            }

            var kids = children(node);

            if (kids.size() == 1) {
                node = kids.getFirst();
                continue;
            }

            return node;
        }
    }

    private static boolean isActualTernary(Cursor node) {
        return node.kindIs(RuleKind.TERNARY) && node instanceof Cursor.Branch branch && branch.children().count() > 1;
    }

    private static boolean isSwitchExpression(Cursor node) {
        return leadingWord(text(node)).equals("switch");
    }

    /// The returned/evaluated expression of a return or expression statement — the first non-leaf
    /// child of the enclosing `STMT` node (the `return`/`;`/`}` punctuation are leaves).
    private static Option<Cursor> statementExpression(Cursor statement) {
        return findFirst(statement, RuleKind.STMT).flatMap(MethodShapeClassifier::firstExpressionChild)
                                                  .orElse(() -> firstExpressionChild(statement));
    }

    private static Option<Cursor> firstExpressionChild(Cursor stmt) {
        for (var child : children(stmt)) {
            if (!(child instanceof Cursor.Leaf)) {
                return Option.some(child);
            }
        }

        return Option.none();
    }

    /// The single expression a lambda body evaluates, or none for a block (multi-statement) body.
    private static Option<Cursor> lambdaBodyExpression(Cursor lambda) {
        var seenParams = false;

        for (var child : children(lambda)) {
            if (child.kindIs(RuleKind.LAMBDA_PARAMS)) {
                seenParams = true;
                continue;
            }

            if (child instanceof Cursor.Leaf) {
                continue;
            }

            if (child.kindIs(RuleKind.BLOCK)) {
                return Option.none();
            }

            if (seenParams || !child.kindIs(RuleKind.LAMBDA_PARAMS)) {
                return Option.some(child);
            }
        }

        return Option.none();
    }

    /// Statement subtrees directly under a method body block — its non-leaf children (the enclosing
    /// braces are leaves).
    private static List<Cursor> realStatements(Cursor body) {
        var out = new ArrayList<Cursor>();

        for (var child : children(body)) {
            if (!(child instanceof Cursor.Leaf)) {
                out.add(child);
            }
        }

        return out;
    }

    private static Set<String> paramNames(Cursor methodMember) {
        return methodParams(methodMember).map(MethodShapeClassifier::collectParamNames)
                                         .or(Set.of());
    }

    private static Set<String> collectParamNames(Cursor params) {
        var names = new HashSet<String>();

        for (var param : childrenByRule(params, RuleKind.PARAM)) {
            var matcher = PARAM_NAME_TAIL.matcher(text(param).stripTrailing());

            if (matcher.find()) {
                names.add(matcher.group(1));
            }
        }

        return names;
    }

    private static String leadingWord(String text) {
        var matcher = LEADING_WORD.matcher(text);

        return matcher.find() ? matcher.group(1) : "";
    }

    // ===== Phase-3 absorbed-rule facets (#448) =====
    //
    // JBCT-ZONE-03 / JBCT-NEST-01 / JBCT-PAT-02 were regex shadows of this classifier; their
    // detection now lives here as facets and the rule classes are thin delegators. Every facet reads
    // `MapperSafety.blankNonCode`-masked text (strings and comments blanked, offsets and line numbers
    // preserved), so a verb / operator / join token spelled inside a string or comment no longer fires
    // — the ONLY behavioural change from the regex originals. Because the facets return CST cursors
    // (the offending method or lambda) and the rules report at those cursors' own line/column, reported
    // locations are byte-identical to the regex era. Completeness vs. the regex is stated per facet.

    /// Zone-3 (implementation-level) verbs that must be wrapped in a Zone-2 step, not called inline in
    /// a carrier chain. Absorbed from `CstZoneMixingRule`; also the name-verb table for the
    /// JBCT-SHAPE-03 "mis-leveled up" arm ([#shapeZoneMismatches]).
    private static final Set<String> ZONE_THREE_VERBS = Set.of("get", "set", "fetch", "parse", "calculate",
                                                               "convert", "hash", "format", "encode", "decode",
                                                               "extract", "split", "join", "log", "send",
                                                               "receive", "read", "write", "add", "remove",
                                                               "find", "query", "insert", "update", "delete");

    /// Zone-2 (orchestration-level) verbs — the abstraction altitude of a step interface or use-case
    /// orchestration, too abstract to head a bare Leaf. The name-verb table for the JBCT-SHAPE-03
    /// "mis-leveled down" arm ([#shapeZoneMismatches]). COPIED VERBATIM from
    /// `CstZoneThreeVerbsRule.ZONE_2_VERBS` (the "orchestration verb too abstract for a leaf" set, the
    /// exact sibling concern) rather than consolidated to one shared set: `CstZoneTwoVerbsRule` and
    /// `CstZoneThreeVerbsRule` carry DIFFERENT Zone-2 tables — the former additionally lists
    /// `create`/`build` — so a single shared constant would silently change one of JBCT-ZONE-01/02's
    /// behaviour. Copying keeps those two rules untouched; the divergence is a pre-existing corpus
    /// fact, not this rule's to reconcile.
    private static final Set<String> ZONE_TWO_VERBS = Set.of("validate", "process", "handle", "transform",
                                                             "apply", "check", "load", "save",
                                                             "manage", "configure", "initialize", "execute",
                                                             "prepare", "complete", "resolve", "verify");

    /// A `.map`/`.flatMap` whose lambda argument calls `receiver.verb(` — verb captured in group 2.
    private static final Pattern ZONE_CHAIN_CALL =
        Pattern.compile("\\.(map|flatMap)\\s*\\([^)]*->\\s*[^)]*\\.([a-z][a-zA-Z]*)\\s*\\(");

    /// A `.map`/`.flatMap` whose argument is a method reference `Type::verb` — verb captured in group 2.
    private static final Pattern ZONE_METHOD_REF =
        Pattern.compile("\\.(map|flatMap)\\s*\\([^:]*::([a-z][a-zA-Z]*)\\s*\\)");

    /// A monadic-operation call (`.map(`, `.flatMap(`, …) inside a lambda body — two or more mark
    /// NEST-01. Absorbed from `CstNestedOperationsRule`; precompiled as one alternation (counting
    /// every match is equivalent to the former per-op scan) so it is not rebuilt per lambda.
    private static final Pattern NEST_MONADIC_OP_CALL =
        Pattern.compile("\\.(map|flatMap|fold|recover|filter|mapFailure|onSuccess|onFailure)\\s*\\(");

    /// A monadic op chained directly onto a closing paren (`).map(`) inside a lambda body — a re-chain.
    private static final Pattern NEST_RECHAIN = Pattern.compile("\\)\\s*\\.(map|flatMap|fold|recover|filter)\\s*\\(");

    /// Fork-Join call heads whose presence inside a `flatMap`/`andThen` lambda body marks PAT-02.
    /// Absorbed from `CstPatternMixingRule`.
    private static final Set<String> FORK_JOIN_CALLS = Set.of("Result.all(", "Promise.all(", "Option.all(",
                                                             "Result.allOf(", "Promise.allOf(", "Option.allOf(");

    /// Chain-link names whose lambda argument is a Sequencer step (PAT-02's nesting site).
    private static final Set<String> SEQUENCER_LAMBDA_LINKS = Set.of("flatMap", "andThen");

    /// One JBCT-ZONE-03 hit: the method whose carrier chain mixes in Zone-3 verbs, and the distinct
    /// verbs found (first-seen order). Empty verb lists are never returned.
    public record ZoneMixing(Cursor method, List<String> verbs) {}

    /// JBCT-ZONE-03 facet: methods whose `map`/`flatMap` lambda- or method-reference arguments call a
    /// Zone-3 verb directly. The two regexes are preserved verbatim — the method-reference form and the
    /// regex's paren-non-crossing `[^)]*` reach cannot be reproduced by structural lambda descent
    /// without changing the hit set — and only the input changes: masked method text, so a verb spelled
    /// inside a string or comment no longer fires. Completeness: every real site the regex caught, minus
    /// exactly the string/comment false positives.
    public static List<ZoneMixing> mapperChainZoneMixings(Cursor root) {
        var out = new ArrayList<ZoneMixing>();

        for (var method : findAllMethods(root)) {
            var masked = MapperSafety.blankNonCode(text(method));

            if (!masked.contains(".flatMap(") && !masked.contains(".map(")) {
                continue;
            }

            var verbs = zoneThreeVerbs(masked);

            if (!verbs.isEmpty()) {
                out.add(new ZoneMixing(method, verbs));
            }
        }

        return out;
    }

    /// The distinct Zone-3 verbs (first-seen order) found in the masked method text's `map`/`flatMap`
    /// lambda-call and method-reference arguments.
    private static List<String> zoneThreeVerbs(String masked) {
        var verbs = new ArrayList<String>();

        for (var pattern : List.of(ZONE_CHAIN_CALL, ZONE_METHOD_REF)) {
            var matcher = pattern.matcher(masked);

            while (matcher.find()) {
                firstWord(matcher.group(2)).filter(verb -> ZONE_THREE_VERBS.contains(verb.toLowerCase()))
                                           .filter(verb -> !verbs.contains(verb))
                                           .onPresent(verbs::add);
            }
        }

        return verbs;
    }

    /// The leading camelCase word of a method name (`fetchData` -> `fetch`), or none when blank.
    private static Option<String> firstWord(String methodName) {
        return Option.option(methodName)
                     .filter(name -> !name.isEmpty())
                     .flatMap(MethodShapeClassifier::splitLeadingWord);
    }

    private static Option<String> splitLeadingWord(String name) {
        var sb = new StringBuilder();

        for (var c : name.toCharArray()) {
            if (Character.isUpperCase(c) && !sb.isEmpty()) {
                break;
            }

            sb.append(c);
        }

        return sb.isEmpty() ? Option.none() : Option.some(sb.toString());
    }

    /// JBCT-NEST-01 facet: lambda bodies that nest two or more monadic operations, or re-chain a
    /// combinator onto a call result (`inner(x).map(f)`). The original per-lambda body analysis (a
    /// re-chain regex plus an op-count of 2+) is preserved; only the input changes: masked lambda text,
    /// so a combinator name inside a string or comment no longer counts. The single-combinator re-chain
    /// is why structural [#classifyLambdaBody] alone cannot replace this (it reads that shape as LEAF).
    /// Completeness: every real site the regex caught, minus the string/comment false positives.
    public static List<Cursor> nestedOperationLambdas(Cursor root) {
        return findAllLambdas(root).stream()
                                   .filter(MethodShapeClassifier::lambdaBodyNestsOperations)
                                   .toList();
    }

    private static boolean lambdaBodyNestsOperations(Cursor lambda) {
        var masked = MapperSafety.blankNonCode(text(lambda));
        var arrow = masked.indexOf("->");

        if (arrow < 0) {
            return false;
        }

        var body = masked.substring(arrow + 2);

        return NEST_RECHAIN.matcher(body).find() || monadicOpCount(body) > 1;
    }

    private static int monadicOpCount(String body) {
        var matcher = NEST_MONADIC_OP_CALL.matcher(body);
        var count = 0;

        while (matcher.find()) {
            count++;

            if (count > 1) {
                return count;
            }
        }

        return count;
    }

    /// JBCT-PAT-02 facet: the lambda argument of a `flatMap`/`andThen` link whose body nests a Fork-Join
    /// call (`Result.all(...)`, `Promise.all(...)`, …) that is not the body's lone call. The sequencer-step
    /// lambda is located structurally through the [#chainLambdaLinks] descent (replacing the former
    /// ancestor-text scan), and its body is masked before the join-token check, so a join token inside a
    /// string or comment no longer fires. Corpus baseline 0; the fixture violation still fires.
    public static List<Cursor> forkJoinInSequencerLambdas(Cursor root) {
        var out = new ArrayList<Cursor>();

        for (var postfix : findAll(root, RuleKind.POSTFIX)) {
            for (var link : chainLambdaLinks(postfix)) {
                if (SEQUENCER_LAMBDA_LINKS.contains(link.link()) && bodyNestsForkJoin(link.lambda())) {
                    out.add(link.lambda());
                }
            }
        }

        return out;
    }

    private static boolean bodyNestsForkJoin(Cursor lambda) {
        var masked = MapperSafety.blankNonCode(text(lambda)).trim();

        return !isLoneForkJoinCall(masked) && FORK_JOIN_CALLS.stream().anyMatch(masked::contains);
    }

    private static boolean isLoneForkJoinCall(String lambdaText) {
        var arrow = lambdaText.indexOf("->");

        if (arrow < 0) {
            return false;
        }

        var body = lambdaText.substring(arrow + 2).trim();

        return FORK_JOIN_CALLS.stream().anyMatch(call -> bodyIsLoneCall(body, call));
    }

    /// True when `body` is exactly one call to `call` (which includes its trailing `(`) with nothing
    /// chained after the matching `)`. Balanced-paren scan so argument-internal parens don't false-trigger.
    private static boolean bodyIsLoneCall(String body, String call) {
        if (!body.startsWith(call) || body.contains(";")) {
            return false;
        }

        var depth = 0;

        for (var i = call.length() - 1; i < body.length(); i++) {
            var c = body.charAt(i);

            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;

                if (depth == 0) {
                    return i == body.length() - 1;
                }
            }
        }

        return false;
    }

    // ===== Phase-3 shape<->zone cross-check facet (JBCT-SHAPE-03, #448) =====
    //
    // Not an absorbed regex shadow (unlike the ZONE-03/NEST-01/PAT-02 facets above): a NEW rule
    // built directly on [#classify]. It compares two orthogonal axes of a method — its composition
    // shape ([#classify]) and the abstraction zone of its NAME verb — and flags the two clear
    // disagreements. The verb tables are [#ZONE_THREE_VERBS] / [#ZONE_TWO_VERBS]; the name verb is
    // the leading camelCase word of [FileTypeClassifier#methodName], run through the same
    // [#firstWord] split the ZONE-03 facet uses.

    /// One JBCT-SHAPE-03 hit: a method whose composition [MethodShape] disagrees with the abstraction
    /// zone of its name verb. `misLeveledUp` true means a Zone-3 implementation verb heads an
    /// orchestration shape (SEQUENCER / FORK_JOIN) — an implementation-named method doing
    /// orchestration; false means a Zone-2 orchestration verb heads a LEAF — an orchestration-named
    /// method that is a bare leaf. `verb` is the lower-cased leading name verb; `shape` the classified
    /// shape.
    public record ShapeZoneMismatch(Cursor method, MethodShape shape, String verb, boolean misLeveledUp) {}

    /// JBCT-SHAPE-03 facet: methods whose composition shape and name-verb zone disagree. Only the two
    /// unambiguous disagreements are reported — a Zone-3 (implementation) verb on a multi-step
    /// SEQUENCER / FORK_JOIN, and a Zone-2 (orchestration) verb on a LEAF. MIXED / CONDITION /
    /// ITERATION / ASPECT / UNCLASSIFIED shapes are not cross-checked (no clear altitude signal), and
    /// a method whose leading verb is in neither table is skipped. Abstract / bodiless methods yield no
    /// verdict from [#classify] and never appear here.
    ///
    /// **False-positive surface — the two axes are orthogonal, agreement is a heuristic not a rule.**
    /// Mis-leveled-DOWN over-flags most: a Zone-2 verb legitimately heads a one-line LEAF delegate —
    /// notably `apply` (the step-interface SAM) and `execute` (the use-case SAM) forwarding to a single
    /// call, `validate`/`check` one-liners, and `load`/`save` leaf delegates — so this arm is expected
    /// high-volume noise (622 corpus hits at introduction, ~460 of them this arm). Mis-leveled-UP
    /// over-flags a Zone-3 verb (`get`/`find`/…) on a small two-combinator getter that reads as SEQUENCER
    /// (`return raw.map(f).filter(p)`). FN surface: the four non-cross-checked shapes, and any verb outside
    /// the two tables (a same-altitude blend hidden inside a lambda is `JBCT-PAT-02`'s, not this rule's).
    /// Because this reproduces neither an existing regex nor a build gate, it ships INFO for corpus
    /// calibration (precedent JBCT-SIDE-01 / JBCT-SHAPE-01/02).
    public static List<ShapeZoneMismatch> shapeZoneMismatches(Cursor root) {
        var out = new ArrayList<ShapeZoneMismatch>();

        for (var method : findAllMethods(root)) {
            shapeZoneMismatch(method).onPresent(out::add);
        }

        return out;
    }

    private static Option<ShapeZoneMismatch> shapeZoneMismatch(Cursor method) {
        return classify(method).flatMap(verdict -> mismatchForShape(method, verdict.shape()));
    }

    private static Option<ShapeZoneMismatch> mismatchForShape(Cursor method, MethodShape shape) {
        return firstWord(FileTypeClassifier.methodName(method)).map(String::toLowerCase)
                                                               .flatMap(verb -> mismatchForVerb(method, shape, verb));
    }

    private static Option<ShapeZoneMismatch> mismatchForVerb(Cursor method, MethodShape shape, String verb) {
        if (isOrchestrationShape(shape) && ZONE_THREE_VERBS.contains(verb)) {
            return Option.some(new ShapeZoneMismatch(method, shape, verb, true));
        }

        if (shape == MethodShape.LEAF && ZONE_TWO_VERBS.contains(verb)) {
            return Option.some(new ShapeZoneMismatch(method, shape, verb, false));
        }

        return Option.none();
    }

    private static boolean isOrchestrationShape(MethodShape shape) {
        return shape == MethodShape.SEQUENCER || shape == MethodShape.FORK_JOIN;
    }
}
