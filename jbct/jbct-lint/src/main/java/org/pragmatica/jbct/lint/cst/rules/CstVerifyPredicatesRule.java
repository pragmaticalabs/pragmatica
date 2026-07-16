package org.pragmatica.jbct.lint.cst.rules;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-UTIL-02: Use Verify.Is predicates for validation.
///
/// Detects manual validation patterns that could use Verify.ensure() with
/// built-in predicates from Verify.Is for cleaner, more expressive code.
public class CstVerifyPredicatesRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-UTIL-02";

    // Patterns for manual validations that could use Verify.Is
    private static final List<ValidationPattern> PATTERNS = List.of(
    // Numeric checks
    new ValidationPattern("\\b\\w+\\s*>\\s*0\\b", "Verify.Is::positive", "Use Verify.ensure(value, Verify.Is::positive)"),
                                                                    new ValidationPattern("\\b\\w+\\s*<\\s*0\\b",
                                                                                          "Verify.Is::negative",
                                                                                          "Use Verify.ensure(value, Verify.Is::negative)"),
                                                                    new ValidationPattern("\\b\\w+\\s*>=\\s*0\\b",
                                                                                          "Verify.Is::nonNegative",
                                                                                          "Use Verify.ensure(value, Verify.Is::nonNegative)"),
                                                                    new ValidationPattern("\\b\\w+\\s*<=\\s*0\\b",
                                                                                          "Verify.Is::nonPositive",
                                                                                          "Use Verify.ensure(value, Verify.Is::nonPositive)"),

    // Null checks in conditions
    new ValidationPattern("\\b\\w+\\s*!=\\s*null\\b",
                          "Verify.Is::notNull",
                          "Use Verify.ensure(value, Verify.Is::notNull)"),

    // String checks
    new ValidationPattern("\\.isEmpty\\s*\\(\\s*\\)", "Verify.Is::empty", "Use Verify.ensure(value, Verify.Is::empty)"),
                                                                    new ValidationPattern("!\\.isEmpty\\s*\\(\\s*\\)",
                                                                                          "Verify.Is::notEmpty",
                                                                                          "Use Verify.ensure(value, Verify.Is::notEmpty)"),
                                                                    new ValidationPattern("\\.isBlank\\s*\\(\\s*\\)",
                                                                                          "Verify.Is::blank",
                                                                                          "Use Verify.ensure(value, Verify.Is::blank)"),
                                                                    new ValidationPattern("!\\.isBlank\\s*\\(\\s*\\)",
                                                                                          "Verify.Is::notBlank",
                                                                                          "Use Verify.ensure(value, Verify.Is::notBlank)"),

    // Option checks
    new ValidationPattern("\\.isPresent\\s*\\(\\s*\\)", "Verify.Is::some", "Use Verify.ensure(option, Verify.Is::some)"),
                                                                    new ValidationPattern("\\.isEmpty\\s*\\(\\s*\\).*Option",
                                                                                          "Verify.Is::none",
                                                                                          "Use Verify.ensure(option, Verify.Is::none)"),

    // Range/bound checks
    new ValidationPattern("\\b\\w+\\s*>=\\s*\\w+\\s*&&\\s*\\w+\\s*<=\\s*\\w+\\b",
                          "Verify.Is::between",
                          "Use Verify.ensure(value, Verify.Is::between, min, max)"),

    // String contains
    new ValidationPattern("\\.contains\\s*\\([^)]+\\)",
                          "Verify.Is::contains",
                          "Use Verify.ensure(str, Verify.Is::contains, substring)"),

    // Regex matching
    new ValidationPattern("\\.matches\\s*\\([^)]+\\)",
                          "Verify.Is::matches",
                          "Use Verify.ensure(str, Verify.Is::matches, pattern)"));

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }
        // Find if statements with validation patterns
        return findAllStatements(root).stream()
                                .filter(this::isIfStatement)
                                .flatMap(stmt -> findValidationPatterns(stmt, ctx));
    }

    private boolean isIfStatement(Cursor stmt) {
        var stmtText = text(stmt).trim();

        return stmtText.startsWith("if");
    }

    private Stream<Diagnostic> findValidationPatterns(Cursor stmt, LintContext ctx) {
        var stmtText = text(stmt);

        return PATTERNS.stream()
                       .filter(pattern -> Pattern.compile(pattern.regex())
                                                 .matcher(stmtText)
                                                 .find())
                       .map(pattern -> createDiagnostic(stmt, pattern, ctx))
                       .limit(1);
    }

    private Diagnostic createDiagnostic(Cursor node, ValidationPattern pattern, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(node),
                                     startColumn(node),
                                     "Consider using " + pattern.predicate(),
                                     pattern.suggestion() + " for cleaner validation code.");
    }

    private record ValidationPattern(String regex, String predicate, String suggestion) {}
}
