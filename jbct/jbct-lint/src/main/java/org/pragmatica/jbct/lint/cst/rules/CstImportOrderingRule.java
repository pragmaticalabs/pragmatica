package org.pragmatica.jbct.lint.cst.rules;

import java.util.ArrayList;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;
import org.pragmatica.jbct.shared.ImportGroups;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-STY-06: Import ordering convention.
///
/// Expected order (matching JBCT formatter, defined once in {@link ImportGroups}):
/// 1. java.* / javax.* (JDK imports)
/// 2. org.pragmatica.* (framework imports)
/// 3. Third-party (com.*, io.*, other org.*), alphabetical
/// 4. Project imports
/// 5. (blank line)
/// 6. Static imports (same grouping order)
public class CstImportOrderingRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-STY-06";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        var packageName = packageName(root);

        if (!ctx.shouldLint(packageName)) {
            return Stream.empty();
        }

        var projectPackage = ImportGroups.projectPackage(packageName);
        // Collect all imports
        var imports = findAll(root, RuleKind.IMPORT_DECL);

        if (imports.isEmpty()) {
            return Stream.empty();
        }

        var diagnostics = new ArrayList<Diagnostic>();
        // Check import ordering via the shared monotonic ordinal: book-ordered source is
        // non-decreasing, so any drop marks an out-of-order import.
        int lastOrdinal = -1;
        Cursor lastImport = null;

        for (var importNode : imports) {
            var importText = text(importNode).trim();
            var isStatic = ImportGroups.isStatic(importText);
            var importPath = ImportGroups.stripToPath(importText);
            var currentOrdinal = ImportGroups.ordinal(importPath, isStatic, projectPackage);

            if (currentOrdinal < lastOrdinal) {
                diagnostics.add(createDiagnostic(importNode, importPath, lastImport, ctx, isStatic));
            }

            lastOrdinal = currentOrdinal;
            lastImport = importNode;
        }

        return diagnostics.stream();
    }

    private Diagnostic createDiagnostic(Cursor importNode,
                                        String importPath,
                                        Cursor lastImport,
                                        LintContext ctx,
                                        boolean isStatic) {
        var lastPath = lastImport != null
                       ? ImportGroups.stripToPath(text(lastImport))
                       : "(none)";
        var prefix = isStatic
                     ? "Static import"
                     : "Import";

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(importNode),
                                     startColumn(importNode),
                                     prefix + " '" + importPath + "' should come before '" + lastPath + "'",
                                     "Follow import ordering: java/javax → org.pragmatica → third-party → project → static")
                         .withExample("""
                // Correct import order:
                import java.util.List;
                import java.util.Map;
                import javax.annotation.Nonnull;

                import org.pragmatica.lang.Result;
                import org.pragmatica.lang.Option;

                import com.google.common.collect.ImmutableList;
                import org.slf4j.Logger;

                import com.example.project.MyClass;

                import static java.util.Objects.requireNonNull;
                import static org.pragmatica.lang.Result.success;
                """);
    }
}
