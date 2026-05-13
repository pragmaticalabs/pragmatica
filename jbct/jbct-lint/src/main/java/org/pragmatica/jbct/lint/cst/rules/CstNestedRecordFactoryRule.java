package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/// JBCT-UC-01: Use case factories should return lambdas, not nested records.
public class CstNestedRecordFactoryRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-UC-01";
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
        // Find ClassMember nodes with static methods containing local record declarations
        return findAll(root, RuleKind.CLASS_MEMBER).stream()
                      .filter(this::isStaticMember)
                      .filter(member -> !isMultiMethodInterface(root, member))
                      .flatMap(member -> findFirstMethod(member).stream())
                      .filter(this::containsSimpleLocalRecord)
                      .map(method -> createDiagnostic(method, ctx));
    }

    private boolean isMultiMethodInterface(Cursor root, Cursor member) {
        return findAncestor(root, member, RuleKind.TYPE_DECL)
                          .flatMap(td -> findFirstInterface(td))
                          .map(iface -> countAbstractMethods(iface) > 1)
                          .or(false);
    }

    private int countAbstractMethods(Cursor iface) {
        // Use ClassBody → direct ClassMember children to avoid counting methods inside nested types
        return childByRule(iface, RuleKind.CLASS_BODY)
                          .map(body -> (int) childrenByRule(body, RuleKind.CLASS_MEMBER).stream()
                                                    .filter(member -> !contains(member, RuleKind.TYPE_KIND))
                                                    .filter(member -> containsMethod(member))
                                                    .filter(this::isAbstractMethod)
                                                    .count())
                          .or(0);
    }

    private boolean isAbstractMethod(Cursor member) {
        var memberText = text(member);
        return !memberText.contains("static ") && !memberText.contains("default ");
    }

    private boolean isStaticMember(Cursor member) {
        var memberText = text(member);
        return memberText.contains("static ");
    }

    private boolean containsSimpleLocalRecord(Cursor method) {
        // Find local records implementing an interface
        return findAllRecords(method).stream()
                      .filter(this::hasImplementsClause)
                      .anyMatch(this::isSimpleImplementation);
    }

    private boolean hasImplementsClause(Cursor record) {
        return contains(record, RuleKind.IMPLEMENTS_CLAUSE);
    }

    private boolean isSimpleImplementation(Cursor record) {
        // A simple record has at most 1 method — can be replaced by a lambda.
        // Complex records (with helper methods) justify the nested record pattern.
        return countMethods(record) <= 1;
    }

    private Diagnostic createDiagnostic(Cursor method, LintContext ctx) {
        var methodName = extractMethodName(text(method));
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(method),
                                     startColumn(method),
                                     "Factory method '" + methodName + "' uses nested record implementation",
                                     "Return lambdas directly instead of nested record implementations.")
                         .withExample("""
            // Before (nested record)
            static UseCase useCase(Dep dep) {
                record Impl(Dep dep) implements UseCase { ... }
                return new Impl(dep);
            }

            // After (direct lambda)
            static UseCase useCase(Dep dep) {
                return request -> dep.process(request);
            }
            """);
    }

    private static String extractMethodName(String memberText) {
        var matcher = METHOD_NAME_PATTERN.matcher(memberText);
        return matcher.find() ? matcher.group(1) : "(unknown)";
    }
}
