package org.pragmatica.jbct.lint.cst.rules;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-MUT-01: No parameter reassignment.
///
/// A method or lambda parameter is read-only input. Reassigning it (`p = ...`, a compound
/// assignment `p += ...`, or `p++` / `--p`) mutates the caller-visible binding and breaks the
/// functional, thread-confined data flow JBCT relies on; introduce a local instead.
///
/// Method parameters are scanned across the whole method body (so a reassignment inside a nested
/// lambda is still caught); lambda parameters are scanned across their own lambda text. Java
/// forbids a lambda parameter from shadowing an enclosing name, so the two name sets never
/// collide and a reassignment is reported once. String literals and comments are blanked before
/// scanning, and the target must be a bare identifier — `this.p = x` and `obj.p = x` (assigning a
/// field) are excluded by the preceding-dot guard.
///
/// FP surface: a reassignment inside a nested anonymous-class member that shadows a parameter
/// name (rare). FN surface: element/field mutation of a parameter (`p[i] = x`, `p.field = x`) is
/// not a rebinding and is not flagged; a multi-variable declaration reuse is out of scope.
public class CstParameterReassignmentRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-MUT-01";
    private static final Pattern PARAM_NAME_PATTERN = Pattern.compile("\\b([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*(?:,|$)");
    private static final String ASSIGN_OP = "=(?!=)|\\+=|-=|\\*=|/=|%=|&=|\\|=|\\^=|<<=|>>>?=|\\+\\+|--";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        var methodDiagnostics = findAllMethods(root).stream()
                                              .flatMap(method -> scope(method, methodParamNames(method), ctx));
        var lambdaDiagnostics = findAllLambdas(root).stream()
                                              .flatMap(lambda -> scope(lambda, lambdaParamNames(lambda), ctx));

        return Stream.concat(methodDiagnostics, lambdaDiagnostics);
    }

    private Stream<Diagnostic> scope(Cursor node, Set<String> paramNames, LintContext ctx) {
        if (paramNames.isEmpty()) {
            return Stream.empty();
        }

        var masked = ScopeScan.bodyTextExcludingNestedTypes(node);
        var baseLine = startLine(node);
        var reported = new HashSet<String>();
        var diagnostics = Stream.<Diagnostic> builder();

        collectAssignments(masked, paramNames, baseLine, reported, diagnostics, ctx);

        return diagnostics.build();
    }

    private void collectAssignments(String masked,
                                    Set<String> paramNames,
                                    int baseLine,
                                    Set<String> reported,
                                    Stream.Builder<Diagnostic> diagnostics,
                                    LintContext ctx) {
        var names = alternation(paramNames);
        var assignMatcher = Pattern.compile("(?<![.\\w$])(" + names + ")\\s*(?:" + ASSIGN_OP + ")")
                                   .matcher(masked);

        while (assignMatcher.find()) {
            addReassignment(assignMatcher.group(1), assignMatcher.start(), masked, baseLine, reported, diagnostics, ctx);
        }

        var preIncrementMatcher = Pattern.compile("(?:\\+\\+|--)\\s*(" + names + ")(?![.\\w$])")
                                         .matcher(masked);

        while (preIncrementMatcher.find()) {
            addReassignment(preIncrementMatcher.group(1),
                            preIncrementMatcher.start(),
                            masked,
                            baseLine,
                            reported,
                            diagnostics,
                            ctx);
        }
    }

    private void addReassignment(String name,
                                 int offset,
                                 String masked,
                                 int baseLine,
                                 Set<String> reported,
                                 Stream.Builder<Diagnostic> diagnostics,
                                 LintContext ctx) {
        if (reported.add(name)) {
            diagnostics.add(createDiagnostic(name, baseLine + MapperSafety.newlinesBefore(masked, offset), ctx));
        }
    }

    private String alternation(Set<String> names) {
        return names.stream()
                    .map(Pattern::quote)
                    .reduce((a, b) -> a + "|" + b)
                    .orElse("");
    }

    private Set<String> methodParamNames(Cursor method) {
        var names = new HashSet<String>();

        methodParams(method).onPresent(params -> collectParamNames(params, names));

        return names;
    }

    private void collectParamNames(Cursor params, Set<String> names) {
        for (var param : childrenByRule(params, RuleKind.PARAM)) {
            lastIdentifier(text(param).trim()).ifPresent(names::add);
        }
    }

    private Set<String> lambdaParamNames(Cursor lambda) {
        var names = new HashSet<String>();

        for (var params : childrenByRule(lambda, RuleKind.LAMBDA_PARAMS)) {
            for (var entry : stripParens(text(params).trim()).split(",")) {
                lastIdentifier(entry.trim()).ifPresent(names::add);
            }
        }

        return names;
    }

    private String stripParens(String text) {
        var trimmed = text.trim();

        if (trimmed.startsWith("(") && trimmed.endsWith(")")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }

        return trimmed;
    }

    private Optional<String> lastIdentifier(String paramText) {
        var matcher = PARAM_NAME_PATTERN.matcher(paramText);
        String last = null;

        while (matcher.find()) {
            last = matcher.group(1);
        }

        return last == null || last.isEmpty()
               ? Optional.empty()
               : Optional.of(last);
    }

    private Diagnostic createDiagnostic(String name, int line, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     line,
                                     1,
                                     "Parameter '" + name + "' is reassigned; parameters are read-only input",
                                     "Assign to a new local instead of mutating the parameter binding. Immutable "
                                    + "inputs keep the data flow functional and thread-confined.");
    }
}
