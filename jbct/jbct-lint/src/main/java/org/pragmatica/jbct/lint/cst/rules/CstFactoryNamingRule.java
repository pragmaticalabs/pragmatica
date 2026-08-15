package org.pragmatica.jbct.lint.cst.rules;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-NAM-01: Factory method naming convention.
///
/// Factory methods should be named after the type: TypeName.typeName()
public class CstFactoryNamingRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-NAM-01";
    // v6: Identifier is a token, not a CST rule.
    private static final Pattern RECORD_NAME_PATTERN = Pattern.compile("\\brecord\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
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
        // Check records for factory methods
        return findAllRecords(root).stream()
                             .flatMap(record -> checkFactoryMethods(record, ctx));
    }

    private Stream<Diagnostic> checkFactoryMethods(Cursor record, LintContext ctx) {
        var nameMatcher = RECORD_NAME_PATTERN.matcher(text(record));

        if (!nameMatcher.find()) {
            return Stream.empty();
        }

        var typeName = nameMatcher.group(1);
        var expectedName = camelCase(typeName);
        // Find RecordMember nodes (wraps ClassMember in records) containing static factory methods
        return findAll(record, RuleKind.RECORD_MEMBER).stream()
                      .filter(member -> isStaticFactoryMember(member, typeName))
                      .flatMap(member -> findFirstMethod(member).stream())
                      .filter(method -> !isCorrectlyNamed(method, expectedName))
                      .map(method -> createDiagnostic(method, typeName, expectedName, ctx));
    }

    private boolean isStaticFactoryMember(Cursor member, String typeName) {
        var memberText = text(member);

        return memberText.contains("static ") && (memberText.contains("Result<" + typeName + ">") || memberText.contains(" " + typeName
                                                                                                                        + " "));
    }

    private boolean isCorrectlyNamed(Cursor method, String expectedName) {
        return extractMethodName(memberDeclText(method)).equals(expectedName);
    }

    private Diagnostic createDiagnostic(Cursor method, String typeName, String expectedName, LintContext ctx) {
        var actualName = extractMethodName(memberDeclText(method));

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(method),
                                     startColumn(method),
                                     "Factory method '" + actualName + "' should be named '" + expectedName + "'",
                                     "JBCT naming convention: " + typeName + "." + expectedName + "(...)");
    }

    private String camelCase(String name) {
        if (name == null || name.isEmpty()) return name;

        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static String extractMethodName(String memberText) {
        var matcher = METHOD_NAME_PATTERN.matcher(memberText);

        return matcher.find()
               ? matcher.group(1)
               : "(unknown)";
    }
}
