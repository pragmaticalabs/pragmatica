package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.Set;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-MIX-01: No I/O operations in domain packages.
 */
public class CstDomainIoRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-MIX-01";

    private static final Set<String>IO_PACKAGES = Set.of(
    "java.io", "java.nio", "java.net", "java.sql", "javax.net", "java.util.concurrent");

    private static final Set<String>IO_CLASSES = Set.of(
    "File",
    "Path",
    "InputStream",
    "OutputStream",
    "Reader",
    "Writer",
    "Socket",
    "ServerSocket",
    "HttpClient",
    "Connection",
    "Statement");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(CstNode root, String source, LintContext ctx) {
        var packageName = packageName(root, source);
        // Only check domain packages (not usecase)
        if (!isDomainPackage(packageName)) {
            return Stream.empty();
        }
        // Check imports for I/O packages
        return findAll(root, RuleId.ImportDecl.class)
               .stream()
               .filter(imp -> isIoImport(imp, source))
               .map(imp -> createDiagnostic(imp, source, ctx));
    }

    private boolean isDomainPackage(String packageName) {
        return packageName.contains(".domain.") || packageName.endsWith(".domain");
    }

    private boolean isIoImport(CstNode imp, String source) {
        var importText = text(imp, source);
        for (var ioPkg : IO_PACKAGES) {
            if (importText.contains(ioPkg)) {
                return true;
            }
        }
        for (var ioCls : IO_CLASSES) {
            if (importText.contains("." + ioCls + ";") ||
            importText.endsWith("." + ioCls)) {
                return true;
            }
        }
        return false;
    }

    private Diagnostic createDiagnostic(CstNode imp, String source, LintContext ctx) {
        var importText = text(imp, source)
                         .trim();
        return Diagnostic.diagnostic(
        RULE_ID,
        ctx.severityFor(RULE_ID),
        ctx.fileName(),
        startLine(imp),
        startColumn(imp),
        "I/O import in domain package: " + importText,
        "Domain packages should be pure. Move I/O to infrastructure layer.");
    }
}
