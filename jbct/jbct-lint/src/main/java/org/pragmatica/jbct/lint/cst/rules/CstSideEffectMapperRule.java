package org.pragmatica.jbct.lint.cst.rules;

import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.lang.Option;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-SIDE-01: side effects in transformation lambdas (heuristic, INFO).
///
/// Transformation combinators (`map`/`filter`) take a pure function; side effects belong in a
/// terminal `onSuccess`/`onFailure`. This rule flags a `map`/`filter` lambda whose expression body
/// is a single call to a side-effect-verb method (`log`/`save`/`send`/`set`/`publish`/…), the
/// textual signature of a void side effect run in mapper position.
///
/// **Severity: INFO — expected false-positive surface, promote after corpus calibration.** Without
/// type resolution a call cannot be proven `void`, so the verb-name heuristic stands in; a
/// value-returning method that merely shares a side-effect verb name (`getLogger`, `addAll` used as
/// a builder) is a false positive, and a side effect whose method name is not in the verb catalog
/// is a false negative. Block-body lambdas are left to JBCT-LAM-02. Suppressible like any rule.
public class CstSideEffectMapperRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-SIDE-01";

    /// Carrier/stream combinators whose lambda argument is a transformation (pure) position.
    private static final Set<String> MAPPER_METHODS = Set.of("map", "filter");

    /// The outermost invoked method name inside a lambda body — identifier before the first `(`.
    private static final Pattern OUTERMOST_CALL = Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");

    /// Side-effect verbs: the method name equals a verb or is `verb` + a CamelCase tail
    /// (`save`, `saveUser`, `set`, `setName`, `println`).
    private static final Pattern SIDE_EFFECT_VERB = Pattern.compile("^(?:log|debug|info|warn|error|trace|print|println"
                                                                    + "|save|store|persist|update|insert|delete|remove"
                                                                    + "|add|put|set|write|append|flush|close|send"
                                                                    + "|publish|emit|notify|record|register|increment"
                                                                    + "|report|track)(?:[A-Z][A-Za-z0-9_$]*)?$");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        var masked = MapperSafety.blankNonCode(source);

        return findAllLambdas(root).stream()
                      .filter(lambda -> isMapperLambda(masked, lambda))
                      .filter(lambda -> hasSideEffectBody(lambda))
                      .map(lambda -> createDiagnostic(lambda, ctx));
    }

    private boolean isMapperLambda(String masked, Cursor lambda) {
        return MapperSafety.enclosingCallName(masked, lambda.spanStart())
                           .map(MAPPER_METHODS::contains)
                           .or(false);
    }

    private boolean hasSideEffectBody(Cursor lambda) {
        return expressionBody(lambda).flatMap(this::outermostCall)
                                     .map(name -> SIDE_EFFECT_VERB.matcher(name)
                                                                  .matches())
                                     .or(false);
    }

    /// Expression body of the lambda (text after `->`), or none for a block body (handled by
    /// JBCT-LAM-02). Read from a masked view so an arrow inside a literal is never the split point.
    private Option<String> expressionBody(Cursor lambda) {
        var masked = MapperSafety.blankNonCode(text(lambda));
        var arrow = masked.indexOf("->");

        if (arrow < 0) {
            return Option.none();
        }

        var body = masked.substring(arrow + 2)
                         .trim();

        return body.isEmpty() || body.charAt(0) == '{'
               ? Option.none()
               : Option.some(body);
    }

    private Option<String> outermostCall(String body) {
        var matcher = OUTERMOST_CALL.matcher(body);

        return matcher.find()
               ? Option.some(matcher.group(1))
               : Option.none();
    }

    private Diagnostic createDiagnostic(Cursor lambda, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(lambda),
                                     startColumn(lambda),
                                     "Possible side effect in a map/filter lambda — move it to a terminal onSuccess/onFailure",
                                     "map/filter take pure transformations; a void side effect (logging, persistence, "
                                    + "notification) belongs in a terminal onSuccess/onFailure, not mid-chain.");
    }
}
