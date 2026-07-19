package org.pragmatica.jbct.lint.cst.shape;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.jbct.lint.cst.filetype.FileTypeClassifier;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;
import org.pragmatica.lang.Option;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// Method-shape classification engine (issue #448, phase 1 census).
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
/// is a join nested *inside a lambda*, which is owned for now by `JBCT-PAT-02` — see the phase-2
/// seam note below.
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
/// **Phase-2 seams (do not wire yet).** `JBCT-PAT-02` (fork-join nested in a sequencer lambda),
/// `JBCT-ZONE-03` (flatMap-text zone mixing), and `JBCT-NEST-01` (nested monadic ops) are
/// string-heuristic shadows of this classifier. When phase 2 folds them in, they become facets that
/// consult a per-method [Spine] descended into lambda arguments rather than only the top-level
/// return chain; [#extractSpine] is the shared primitive they will reuse. Nothing here reads inside
/// lambda arguments yet, by design — phase 1 classifies the top-level composition root only.
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
/// - Instance-style joins (`promise.all(f1, f2)`) and multi-statement bodies (a local var feeding a
///   return) read as Sequencer / [MethodShape#UNCLASSIFIED] respectively; both are accepted and are
///   the calibration signal, not silent guesses.
public final class MethodShapeClassifier {
    private MethodShapeClassifier() {}

    /// The shape verdict for one method: the assigned [MethodShape] and a short human reason string
    /// (the spine feature that decided it), consumed by the census and the diagnostic detail text.
    public record ShapeVerdict(MethodShape shape, String reason) {}

    /// Top-level links of a postfix call chain: the head expression text (`Promise.all`, `validate`,
    /// `raw.stream`) and the ordered names of the `.name(...)` method-call links after it. The
    /// `headInvoked` flag records whether the head itself is called (`foo(...)`), distinguishing a
    /// bare reference (a getter leaf) from an invoked leaf.
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

        if (statements.size() > 1) {
            return verdict(MethodShape.UNCLASSIFIED, statements.size() + " statements — no single composition root");
        }

        return Option.some(classifyStatement(methodMember, statements.getFirst()));
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

        return new Spine(headText, links, headInvoked);
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

    private static Option<ShapeVerdict> verdict(MethodShape shape, String reason) {
        return Option.some(new ShapeVerdict(shape, reason));
    }
}
