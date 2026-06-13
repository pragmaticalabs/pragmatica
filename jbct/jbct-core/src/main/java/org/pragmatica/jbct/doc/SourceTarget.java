package org.pragmatica.jbct.doc;

import org.pragmatica.lang.Option;

import java.util.List;

import static org.pragmatica.lang.Option.option;

/// Pure mapping from a positional `doc` argument to candidate source-relative paths.
///
/// The argument may be:
/// - a fully-qualified class name (`org.pragmatica.lang.Verify`);
/// - a package name (`org.pragmatica.lang.vo`), resolving to its `package-info.java`;
/// - a simple name (`Verify`, `Result`), searched across the known core packages.
///
/// A "candidate" is a path relative to a source root such as
/// `org/pragmatica/lang/Verify.java`. The resolver tries candidates in order until one yields
/// readable source content.
public sealed interface SourceTarget {
    /// Packages searched, in order, for a simple name.
    List<String> SEARCH_PACKAGES = List.of("org.pragmatica.lang",
                                           "org.pragmatica.lang.vo",
                                           "org.pragmatica.lang.parse",
                                           "org.pragmatica.lang.utils",
                                           "org.pragmatica.lang.io",
                                           "org.pragmatica.lang.type");

    record unused() implements SourceTarget {}

    /// Ordered candidate source-relative paths for the requested name.
    static List<String> candidates(String name) {
        return name.contains(".") ? qualifiedCandidates(name) : simpleCandidates(name);
    }

    private static List<String> qualifiedCandidates(String name) {
        return lastSegmentStartsUpper(name) ? List.of(classPath(name), packageInfoPath(name))
                                            : List.of(packageInfoPath(name), classPath(name));
    }

    private static List<String> simpleCandidates(String name) {
        return SEARCH_PACKAGES.stream()
                              .map(pkg -> classPath(pkg + "." + name))
                              .toList();
    }

    /// Map a fully-qualified type name to `a/b/C.java`.
    static String classPath(String fqcn) {
        return fqcn.replace('.', '/') + ".java";
    }

    /// Map a package name to `a/b/package-info.java`.
    static String packageInfoPath(String packageName) {
        return packageName.replace('.', '/') + "/package-info.java";
    }

    private static boolean lastSegmentStartsUpper(String name) {
        return option(lastSegment(name)).filter(segment -> !segment.isEmpty())
                                        .map(segment -> Character.isUpperCase(segment.charAt(0)))
                                        .or(false);
    }

    private static String lastSegment(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
