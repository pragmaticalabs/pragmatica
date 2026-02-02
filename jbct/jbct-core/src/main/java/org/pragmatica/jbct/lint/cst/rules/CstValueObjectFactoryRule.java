package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-VO-01: Value objects need factory returning Result<T>.
 */
public class CstValueObjectFactoryRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-VO-01";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(CstNode root, String source, LintContext ctx) {
        var packageName = findFirst(root, RuleId.PackageDecl.class).flatMap(pd -> findFirst(pd,
                                                                                            RuleId.QualifiedName.class))
                                   .map(qn -> text(qn, source))
                                   .or("");
        if (!ctx.isBusinessPackage(packageName)) {
            return Stream.empty();
        }
        // Check records
        var recordDiagnostics = findAll(root, RuleId.RecordDecl.class).stream()
                                       .filter(record -> needsFactoryMethod(record, source))
                                       .map(record -> createDiagnostic(record, source, ctx));
        return recordDiagnostics;
    }

    private boolean needsFactoryMethod(CstNode record, String source) {
        var recordName = childByRule(record, RuleId.Identifier.class).map(id -> text(id, source))
                                    .or("");
        if (recordName.isEmpty()) return false;
        // Check if has Result-returning static method
        var recordText = text(record, source);
        return ! recordText.contains("Result<" + recordName + ">") &&
        !recordText.contains("Result<" + recordName + " ");
    }

    private Diagnostic createDiagnostic(CstNode record, String source, LintContext ctx) {
        var name = childByRule(record, RuleId.Identifier.class).map(id -> text(id, source))
                              .or("(unknown)");
        var camelName = camelCase(name);
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(record),
                                     startColumn(record),
                                     "Record '" + name + "' should have a factory method returning Result<" + name + ">",
                                     "JBCT value objects use factory methods for validation.")
                         .withExample("""
            public record %s(...) {
                public static Result<%s> %s(...) {
                    // Validate and return
                    return Result.success(new %s(...));
                }
            }
            """.formatted(name, name, camelName, name));
    }

    private String camelCase(String name) {
        if (name == null || name.isEmpty()) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
