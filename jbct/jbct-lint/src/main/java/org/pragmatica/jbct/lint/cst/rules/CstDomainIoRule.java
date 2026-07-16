package org.pragmatica.jbct.lint.cst.rules;

import java.util.Set;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-MIX-01: No I/O operations in domain packages.
public class CstDomainIoRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-MIX-01";

    private static final Set<String> IO_PACKAGES = Set.of("java.io",
                                                          "java.nio",
                                                          "java.net",
                                                          "java.sql",
                                                          "javax.net",
                                                          "java.util.concurrent");

    private static final Set<String> IO_CLASSES = Set.of("File",
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
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        var packageName = packageName(root);
        // Only check domain packages (not usecase)
        if (!isDomainPackage(packageName)) {
            return Stream.empty();
        }
        // Check imports for I/O packages
        return findAll(root, RuleKind.IMPORT_DECL).stream()
                      .filter(this::isIoImport)
                      .map(imp -> createDiagnostic(imp, ctx));
    }

    private boolean isDomainPackage(String packageName) {
        return packageName.contains(".domain.") || packageName.endsWith(".domain");
    }

    private boolean isIoImport(Cursor imp) {
        var importText = text(imp);

        for (var ioPkg : IO_PACKAGES) {
            if (importText.contains(ioPkg)) {
                return true;
            }
        }

        for (var ioCls : IO_CLASSES) {
            if (importText.contains("." + ioCls + ";") || importText.endsWith("." + ioCls)) {
                return true;
            }
        }

        return false;
    }

    private Diagnostic createDiagnostic(Cursor imp, LintContext ctx) {
        var importText = text(imp).trim();

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(imp),
                                     startColumn(imp),
                                     "I/O import in domain package: " + importText,
                                     "Domain packages should be pure. Move I/O to infrastructure layer.");
    }
}
