package org.pragmatica.jbct.lint.cst.rules;

import java.util.Set;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.layer.Layer;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-MIX-01: No I/O operations in domain packages.
///
/// Domain packages must stay pure — no JDK I/O imports (`java.io`, `java.nio`, `java.net`,
/// `java.sql`, `javax.net`, `java.util.concurrent`, or the well-known I/O classes). This rule is
/// the precise JDK-I/O specialization of the #452 layering engine: "domain" is now decided by the
/// shared package classifier ([org.pragmatica.jbct.lint.layer.LayerClassifier]) — a package
/// classified as [Layer#DOMAIN] — instead of a hand-rolled substring check, so the whole engine
/// shares one definition of the domain layer.
///
/// It is complementary to JBCT-ARCH-01: ARCH-01 owns the broad layer-direction and framework-import
/// checks, while MIX-01 keeps its own ID, ERROR severity, and this focused JDK-I/O catalog.
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
        // Domain packages are classified by the shared layering engine, not a substring check.
        if (!isDomainPackage(root, ctx)) {
            return Stream.empty();
        }
        // Check imports for I/O packages
        return findAll(root, RuleKind.IMPORT_DECL).stream()
                      .filter(this::isIoImport)
                      .map(imp -> createDiagnostic(imp, ctx));
    }

    private boolean isDomainPackage(Cursor root, LintContext ctx) {
        return ctx.layers()
                  .layerOf(packageName(root))
                  .filter(layer -> layer == Layer.DOMAIN)
                  .isPresent();
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
