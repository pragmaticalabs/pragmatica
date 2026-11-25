package org.pragmatica.jbct.lint.tree;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.shared.PackageGlob;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The package-tree model behind the TREE rules (#2526): which packages belong to the project, which role a package
/// plays, and which imports between packages are sideways.
///
/// `root` is the `[project] basePackage` of the source root and nothing else. Every judgement is a function of two
/// package names, the root and the exclude globs, never of the directory layout.
///
///   - [#anc(String, String)] — `anc(q, p)`: `q` equals `p` or is a package ancestor of it.
///   - [#own(String)] — `q` is the root or below it, and no exclude glob matches it.
///   - [#role(String)] — over the segments of an owned package after its first, any `io` makes it an I/O package,
///     else any `shared` makes it a shared package, else none; `io` wins over `shared`.
///   - [#allowed(String, String)] — an import from package `p` of package `q` is allowed when `q` is not owned, or
///     one of the two is an ancestor of the other, or `q` has a role and the package before its first role segment
///     is an ancestor of `p` (or `p` itself); the first role segment is the first `io` or `shared` segment, whichever
///     role wins. Anything else is a sideways import.
///   - [#target(String)] — the package a written reference points at: the reference cut at its first segment that
///     starts with an upper-case letter, so a nested type is never read as a package.
public record PackageTree(String root, List<Pattern> excludes) {
    private static final String SEPARATOR = ".";
    private static final String WILDCARD = "*";

    public PackageTree {
        excludes = List.copyOf(excludes);
    }

    /// A segment that gives a package its role. [#IO] precedes [#SHARED]: `values()` order is the precedence.
    public enum Role {
        IO("io"),
        SHARED("shared");
        private final String segment;
        Role(String segment) {
            this.segment = segment;
        }
        public String segment() {
            return segment;
        }
    }

    /// Factory method for creating PackageTree.
    public static PackageTree packageTree(String root, TreeConfig config) {
        return new PackageTree(root,
                               config.excludeGlobs().stream().map(PackageGlob::compile).toList());
    }

    /// `ancestor` equals `descendant` or is a package ancestor of it. A textual prefix that is not on a segment
    /// boundary (`com.shop` and `com.shopping`) is no ancestor.
    public static boolean anc(String ancestor, String descendant) {
        return descendant.equals(ancestor) || descendant.startsWith(ancestor + SEPARATOR);
    }

    /// The package a written reference points at: the leading segments up to the first one starting with an
    /// upper-case letter, a trailing wildcard dropped. Absent when no package segment is left (a type in the
    /// default package).
    public static Option<String> target(String reference) {
        var segments = Arrays.stream(reference.split(Pattern.quote(SEPARATOR)))
                             .takeWhile(PackageTree::isPackageSegment)
                             .toList();

        return segments.isEmpty()
               ? none()
               : some(String.join(SEPARATOR, segments));
    }

    /// The package belongs to the project tree.
    public boolean own(String candidate) {
        return anc(root, candidate) && !excluded(candidate);
    }

    /// An exclude glob matches the package, wherever it lies: the package is outside the tree, and a file declared in
    /// it escapes the root check.
    public boolean excluded(String candidate) {
        return excludes.stream()
                       .anyMatch(glob -> glob.matcher(candidate)
                                             .matches());
    }

    /// The role of a package of the tree, none for a plain package and for a package outside the root.
    public Option<Role> role(String candidate) {
        return anc(root, candidate)
               ? roleOf(afterFirst(segmentsOf(candidate)))
               : none();
    }

    /// An import from package `importer` of package `target` is not sideways.
    public boolean allowed(String importer, String target) {
        return ! own(target) || anc(target, importer) || anc(importer, target) || isVisibleThroughRole(importer, target);
    }

    private boolean isVisibleThroughRole(String importer, String target) {
        return role(target).map(_ -> anc(prefixBeforeFirstRoleSegment(target),
                                         importer))
                   .or(false);
    }

    private static boolean isPackageSegment(String segment) {
        return ! segment.isEmpty()
               && !WILDCARD.equals(segment)
               && !Character.isUpperCase(segment.charAt(0));
    }

    private static Option<Role> roleOf(List<String> segments) {
        return Option.from(Stream.of(Role.values()).filter(role -> segments.contains(role.segment())).findFirst());
    }

    /// The package made of the segments before the first role segment of either kind (`io` or `shared`), whichever
    /// role the package ended up with; the first segment is never a role segment.
    private static String prefixBeforeFirstRoleSegment(String candidate) {
        var segments = segmentsOf(candidate);
        var index = afterFirst(segments).stream().takeWhile(segment -> !isRoleSegment(segment)).count() + 1;

        return String.join(SEPARATOR, segments.subList(0, (int) index));
    }

    private static boolean isRoleSegment(String segment) {
        return Stream.of(Role.values()).anyMatch(role -> role.segment()
                                                             .equals(segment));
    }

    private static List<String> segmentsOf(String candidate) {
        return List.of(candidate.split(Pattern.quote(SEPARATOR)));
    }

    private static List<String> afterFirst(List<String> segments) {
        return segments.subList(1, segments.size());
    }
}
