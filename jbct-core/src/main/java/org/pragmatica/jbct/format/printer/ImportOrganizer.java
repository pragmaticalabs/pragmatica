package org.pragmatica.jbct.format.printer;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Organizes imports according to JBCT style:
 * 1. Java imports (java.*)
 * 2. Javax imports (javax.*)
 * 3. Third-party imports
 * 4. Project imports
 *
 * With blank lines between groups.
 */
public class ImportOrganizer {

    private static final Comparator<ImportDeclaration> IMPORT_COMPARATOR =
            Comparator.comparingInt(ImportOrganizer::importGroup)
                    .thenComparing(ImportDeclaration::getNameAsString);

    /**
     * Factory method.
     */
    public static ImportOrganizer importOrganizer() {
        return new ImportOrganizer();
    }

    /**
     * Organize imports in the compilation unit.
     */
    public void organize(CompilationUnit cu) {
        var imports = cu.getImports();
        if (imports.isEmpty()) {
            return;
        }

        // Sort imports
        var sortedImports = imports.stream()
                .sorted(IMPORT_COMPARATOR)
                .toList();

        // Clear and re-add in sorted order
        cu.getImports().clear();
        sortedImports.forEach(cu::addImport);
    }

    /**
     * Format imports as a string with proper grouping.
     */
    public String formatImports(List<ImportDeclaration> imports) {
        if (imports.isEmpty()) {
            return "";
        }

        var sorted = imports.stream()
                .sorted(IMPORT_COMPARATOR)
                .toList();

        var sb = new StringBuilder();
        int lastGroup = -1;

        for (var imp : sorted) {
            int group = importGroup(imp);

            // Add blank line between groups
            if (lastGroup != -1 && group != lastGroup) {
                sb.append("\n");
            }
            lastGroup = group;

            sb.append(formatImport(imp)).append("\n");
        }

        return sb.toString();
    }

    private String formatImport(ImportDeclaration imp) {
        var sb = new StringBuilder("import ");
        if (imp.isStatic()) {
            sb.append("static ");
        }
        sb.append(imp.getNameAsString());
        if (imp.isAsterisk()) {
            sb.append(".*");
        }
        sb.append(";");
        return sb.toString();
    }

    private static int importGroup(ImportDeclaration imp) {
        var name = imp.getNameAsString();

        if (imp.isStatic()) {
            return 4; // Static imports last
        }
        if (name.startsWith("java.")) {
            return 0;
        }
        if (name.startsWith("javax.")) {
            return 1;
        }
        if (name.startsWith("org.pragmatica.")) {
            return 3; // Project imports
        }
        return 2; // Third-party
    }
}
