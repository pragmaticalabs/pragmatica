package org.pragmatica.jbct.lint.cst.rules;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.CstNodes;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-NAM-05: Test methods use `methodName_scenario_expectation` naming.
///
/// A `@Test` method name must have at least three underscore-separated, non-empty segments —
/// the project standard `methodName_scenario_expectation` (`register_succeeds_forNewEmail`). The
/// rule only fires on `@Test`-annotated methods, so files without tests are untouched and
/// non-test methods are never checked.
///
/// The `@Test` annotation is carried on the method's enclosing `ClassMember` holder (a sibling of
/// the method `Member`, per the grammar `Annotation* Modifier* Member`), so detection reads the
/// holder's annotation children; the member's own annotation children are checked too,
/// defensively.
///
/// FN surface: only the simple annotation name `Test` is matched, so `@ParameterizedTest` /
/// `@RepeatedTest` methods are not checked.
public class CstTestMethodNamingRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-NAM-05";
    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("\\b([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\(");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        return findAllMethods(root).stream()
                             .filter(method -> isTestMethod(root, method))
                             .filter(method -> !matchesTestNaming(extractMethodName(text(method))))
                             .map(method -> createDiagnostic(method, ctx));
    }

    private boolean isTestMethod(Cursor root, Cursor method) {
        return hasTestAnnotation(method) || findAncestor(root, method, RuleKind.CLASS_MEMBER).map(this::hasTestAnnotation)
                                                        .or(false);
    }

    private boolean hasTestAnnotation(Cursor holder) {
        return childrenByRule(holder, RuleKind.ANNOTATION).stream()
                             .anyMatch(this::isTestAnnotation);
    }

    private boolean isTestAnnotation(Cursor annotation) {
        return "Test".equals(simpleName(findFirst(annotation, RuleKind.QUALIFIED_NAME).map(CstNodes::text)
                                                 .map(String::trim)
                                                 .or("")));
    }

    private String simpleName(String qualifiedName) {
        var dot = qualifiedName.lastIndexOf('.');

        return dot >= 0
               ? qualifiedName.substring(dot + 1)
               : qualifiedName;
    }

    private boolean matchesTestNaming(String name) {
        var parts = name.split("_", -1);

        if (parts.length < 3) {
            return false;
        }

        for (var part : parts) {
            if (part.isEmpty()) {
                return false;
            }
        }

        return true;
    }

    private Diagnostic createDiagnostic(Cursor method, LintContext ctx) {
        var name = extractMethodName(text(method));

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(method),
                                     startColumn(method),
                                     "Test method '" + name + "' should be named methodName_scenario_expectation",
                                     "Test names use three underscore-separated segments: the method under test, the "
                                    + "scenario, and the expected outcome (e.g. register_succeeds_forNewEmail).");
    }

    private static String extractMethodName(String memberText) {
        var matcher = METHOD_NAME_PATTERN.matcher(memberText);

        return matcher.find()
               ? matcher.group(1)
               : "(unknown)";
    }
}
