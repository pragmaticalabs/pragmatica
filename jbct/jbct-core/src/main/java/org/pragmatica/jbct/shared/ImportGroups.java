package org.pragmatica.jbct.shared;

/// Canonical JBCT import ordering — the single source of truth shared by the linter
/// (JBCT-STY-06) and the formatter's import organizer, so the ordering is defined
/// exactly once.
///
/// Book order (blank line between groups):
/// 1. `java.*` / `javax.*` (JDK)
/// 2. `org.pragmatica.*` (framework)
/// 3. third-party (`com.*`, `io.*`, `net.*`, other `org.*`), alphabetical
/// 4. project (the file's own package tree, and everything else)
///
/// then static imports last, in the same grouping order.
public final class ImportGroups {
    private ImportGroups() {}

    /// Non-static import groups, in canonical book order (declaration order == sort order).
    public enum Group {
        JDK, PRAGMATICA, THIRD_PARTY, PROJECT
    }

    /// Classify an import path (already stripped of `import`/`static`/`;`) into its
    /// book-order group. `projectPackage` is the leading package prefix considered
    /// project-local; pass `""` to treat nothing as project-local.
    public static Group classify(String importPath, String projectPackage) {
        if (importPath.startsWith("java.") || importPath.equals("java") || importPath.startsWith("javax.")) {
            return Group.JDK;
        }
        if (importPath.startsWith("org.pragmatica.")) {
            return Group.PRAGMATICA;
        }
        if (!projectPackage.isEmpty() && importPath.startsWith(projectPackage)) {
            return Group.PROJECT;
        }
        if (importPath.startsWith("org.")
            || importPath.startsWith("com.")
            || importPath.startsWith("io.")
            || importPath.startsWith("net.")) {
            return Group.THIRD_PARTY;
        }
        return Group.PROJECT;
    }

    /// Monotonic ordinal folding the static section and the group into one non-decreasing
    /// sort key: non-static imports occupy `[0, N)` and static imports `[N, 2N)` where
    /// `N` is the group count. Book-ordered source therefore has non-decreasing ordinals.
    public static int ordinal(String importPath, boolean isStatic, String projectPackage) {
        int base = classify(importPath, projectPackage).ordinal();
        return isStatic
               ? base + Group.values().length
               : base;
    }

    /// The project package prefix (first two segments) of a file's package name, used to
    /// distinguish project-local imports from third-party ones.
    public static String projectPackage(String packageName) {
        var parts = packageName.split("\\.");
        if (parts.length >= 2) {
            return parts[0] + "." + parts[1];
        }
        return parts.length > 0
               ? parts[0]
               : "";
    }

    /// True if the given import statement text is a static import.
    public static boolean isStatic(String importText) {
        return importText.trim()
                         .startsWith("import static ");
    }

    /// Strip an import statement down to its dotted path, dropping the leading
    /// `import` / `import static` / `module` keywords and the trailing `;`.
    public static String stripToPath(String importText) {
        var path = importText.trim();
        if (path.startsWith("import static ")) {
            path = path.substring("import static ".length());
        } else if (path.startsWith("import ")) {
            path = path.substring("import ".length());
        }
        if (path.startsWith("module ")) {
            path = path.substring("module ".length());
        }
        if (path.endsWith(";")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.trim();
    }
}
