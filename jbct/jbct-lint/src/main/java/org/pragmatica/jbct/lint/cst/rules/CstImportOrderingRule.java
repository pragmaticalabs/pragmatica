package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/// JBCT-STY-06: Import ordering convention.
///
/// Expected order (matching JBCT formatter):
/// 1. org.pragmatica.* (framework imports)
/// 2. java.* / javax.* (JDK imports)
/// 3. Third-party (org.*, com.*, etc.)
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
    public Stream<Diagnostic> analyze(CstNode root, String source, LintContext ctx) {
        var packageName = findFirst(root, RuleId.PackageDecl.class).flatMap(pd -> findFirst(pd,
                                                                                            RuleId.QualifiedName.class))
                                   .map(qn -> text(qn, source))
                                   .or("");
        if (!ctx.shouldLint(packageName)) {
            return Stream.empty();
        }
        // Get project root package (first segment of package name)
        var projectPackage = getProjectPackage(packageName);
        // Collect all imports
        var imports = findAll(root, RuleId.ImportDecl.class);
        if (imports.isEmpty()) {
            return Stream.empty();
        }
        var diagnostics = new ArrayList<Diagnostic>();
        // Check import ordering
        int lastGroup = - 1;
        CstNode lastImportInGroup = null;
        boolean inStaticSection = false;
        for (var importNode : imports) {
            var importText = text(importNode, source).trim();
            var isStatic = importText.startsWith("import static ");
            var importPath = extractImportPath(importText);
            if (isStatic && !inStaticSection) {
                // Transitioning to static imports - reset group
                inStaticSection = true;
                lastGroup = - 1;
                lastImportInGroup = null;
            }
            var currentGroup = getImportGroup(importPath, projectPackage);
            if (currentGroup < lastGroup) {
                // Import is out of order
                diagnostics.add(createDiagnostic(importNode, importPath, lastImportInGroup, source, ctx, inStaticSection));
            }
            lastGroup = currentGroup;
            lastImportInGroup = importNode;
        }
        return diagnostics.stream();
    }

    private String getProjectPackage(String packageName) {
        // Get first two segments as project package (e.g., com.example)
        var parts = packageName.split("\\.");
        if (parts.length >= 2) {
            return parts[0] + "." + parts[1];
        }
        return parts.length > 0
               ? parts[0]
               : "";
    }

    private String extractImportPath(String importText) {
        // Remove "import " or "import static " prefix and trailing semicolon
        var path = importText;
        if (path.startsWith("import static ")) {
            path = path.substring(14);
        } else if (path.startsWith("import ")) {
            path = path.substring(7);
        }
        // Handle module imports (import module java.base)
        if (path.startsWith("module ")) {
            path = path.substring(7);
        }
        if (path.endsWith(";")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.trim();
    }

    private int getImportGroup(String importPath, String projectPackage) {
        // Group 0: org.pragmatica.* (framework imports first)
        if (importPath.startsWith("org.pragmatica.")) {
            return 0;
        }
        // Group 1: java.* and javax.*
        if (importPath.startsWith("java.") || importPath.equals("java") || importPath.startsWith("javax.")) {
            return 1;
        }
        // Group 2: Third-party (org.*, com.*, etc. but not project)
        if (!importPath.startsWith(projectPackage) &&
        (importPath.startsWith("org.") ||
        importPath.startsWith("com.") ||
        importPath.startsWith("io.") ||
        importPath.startsWith("net."))) {
            return 2;
        }
        // Group 3: Project imports
        return 3;
    }

    private Diagnostic createDiagnostic(CstNode importNode,
                                        String importPath,
                                        CstNode lastImport,
                                        String source,
                                        LintContext ctx,
                                        boolean isStatic) {
        var lastPath = lastImport != null
                       ? extractImportPath(text(lastImport, source))
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
                                     "Follow import ordering: org.pragmatica → java/javax → third-party → project → static")
                         .withExample("""
                // Correct import order:
                import org.pragmatica.lang.Result;
                import org.pragmatica.lang.Option;

                import java.util.List;
                import java.util.Map;
                import javax.annotation.Nonnull;

                import org.slf4j.Logger;
                import com.google.common.collect.ImmutableList;

                import com.example.project.MyClass;

                import static org.pragmatica.lang.Result.success;
                import static java.util.Objects.requireNonNull;
                """);
    }
}
