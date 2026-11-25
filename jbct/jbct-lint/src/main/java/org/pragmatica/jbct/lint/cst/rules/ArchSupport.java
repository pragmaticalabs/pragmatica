package org.pragmatica.jbct.lint.cst.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.tree.PackageTree;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;
import org.pragmatica.lang.Option;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// Shared reference extraction for the architecture / layering rules (JBCT-ARCH-01, JBCT-ARCH-04) and the package-tree
/// rule (JBCT-TREE-01, [#collectTargets]).
///
/// A single file couples to another package in two syntactic ways: through an `import` declaration
/// and through a fully-qualified type reference in code (`com.example.adapter.Row field;`). Both are
/// single-file facts. This helper harvests both as [Reference]s carrying the offending node (for
/// line/column) and the referenced package, so the layering rules classify one uniform stream.
///
/// A type reference that is *not* fully qualified (a simple name resolved by an import) yields an
/// empty package and is dropped here — its coupling is already represented by the corresponding
/// import [Reference], so the two never double-count.
///
/// Known limits (syntax only): a star import (`import a.b.*;`) contributes package `a.b`; a static
/// member import (`import static a.b.C.m;`) reduces to `a.b.C`, whose layer/slice classification is
/// still correct under segment/prefix matching; a nested-type reference keeps its qualifier.
sealed interface ArchSupport permits ArchSupport.unused {
    /// A line (`//`, `///`) or block (`/* */`) comment inside an import node's text.
    Pattern IMPORT_COMMENT = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    record unused() implements ArchSupport {}

    /// A package reference sourced from an import declaration or a fully-qualified type node.
    record Reference(Cursor node, String packageName) {
        static Reference qualified(Cursor node, String packageName) {
            return new Reference(node, packageName);
        }
    }

    /// All package references in a compilation unit: every import, plus every fully-qualified type
    /// node (deduplicated by source position, mirroring [CstBoundaryTypeRule]).
    static List<Reference> collectReferences(Cursor root) {
        var references = new ArrayList<Reference>();

        for (var imp : findAll(root, RuleKind.IMPORT_DECL)) {
            references.add(new Reference(imp, importedPackage(text(imp))));
        }

        var seen = new HashSet<Long>();

        for (var node : typeNodes(root)) {
            var packageName = referencedPackage(text(node));

            if (packageName.isEmpty()) {
                continue;
            }

            var key = ((long) startLine(node) << 20) | startColumn(node);

            if (seen.add(key)) {
                references.add(new Reference(node, packageName));
            }
        }

        return references;
    }

    /// The package every reference of a compilation unit points at (#2526): each import and each fully-qualified type
    /// node, its written name cut by [PackageTree#target] at the first segment that starts with an upper-case letter,
    /// so `import a.b.HoldSeat.LoadSeat;` and `import static a.b.C.m;` both point at `a.b`. A reference that leaves no
    /// package segment (a simple or nested simple name, a default-package type) is dropped; type nodes are
    /// deduplicated by source position, as in [#collectReferences].
    static List<Reference> collectTargets(Cursor root) {
        var imports = findAll(root, RuleKind.IMPORT_DECL).stream()
                             .flatMap(node -> targetOf(node,
                                                       importBody(text(node))).stream());
        var types = distinctByPosition(typeNodes(root)).stream().flatMap(node -> qualifiedTargetOf(node).stream());

        return Stream.concat(imports, types).toList();
    }

    /// A type node's target, only when its written name is qualified: a simple name is resolved through an import,
    /// which is counted already, and a primitive has no package.
    private static Option<Reference> qualifiedTargetOf(Cursor node) {
        var head = typeHead(text(node));

        return head.contains(".")
               ? targetOf(node, head)
               : Option.none();
    }

    private static Option<Reference> targetOf(Cursor node, String qualifiedName) {
        return PackageTree.target(qualifiedName).map(target -> Reference.qualified(node, target));
    }

    private static Collection<Cursor> distinctByPosition(List<Cursor> nodes) {
        return nodes.stream()
                    .collect(Collectors.toMap(ArchSupport::position,
                                              node -> node,
                                              (first, _) -> first,
                                              LinkedHashMap::new))
                    .values();
    }

    private static long position(Cursor node) {
        return ((long) startLine(node) << 20) | startColumn(node);
    }

    private static List<Cursor> typeNodes(Cursor root) {
        var nodes = new ArrayList<>(findAll(root, RuleKind.TYPE));

        nodes.addAll(findAll(root, RuleKind.REF_TYPE));

        return nodes;
    }

    /// Package portion of an import declaration: the qualified name minus its final (type) segment.
    /// For a wildcard import the `*` is that final segment, so the on-demand package is returned.
    static String importedPackage(String importText) {
        var body = importBody(importText);
        var lastDot = body.lastIndexOf('.');

        return lastDot >= 0
               ? body.substring(0, lastDot)
               : "";
    }

    /// Last (simple-name) segment of an import declaration.
    static String importedSimpleName(String importText) {
        var body = importBody(importText);
        var lastDot = body.lastIndexOf('.');

        return lastDot >= 0
               ? body.substring(lastDot + 1)
               : body;
    }

    /// The import node's text also carries the trivia after its terminating `;` (a following `///` block, #3065), so comments are
    /// stripped first (a `;` inside one must not end the body) and the body then ends at the first `;`; whitespace inside the
    /// qualified name (an import split over lines) is dropped.
    private static String importBody(String importText) {
        var declaration = IMPORT_COMMENT.matcher(importText)
                                        .replaceAll(" ")
                                        .trim()
                                        .replaceFirst("^import\\s+", "")
                                        .replaceFirst("^static\\s+", "");
        var terminator = declaration.indexOf(';');

        return (terminator >= 0
                ? declaration.substring(0, terminator)
                : declaration).replaceAll("\\s+", "");
    }

    /// Qualifier package of a fully-qualified type node's text (generics / array dims stripped), or
    /// `""` when the type is a simple name with no dotted qualifier.
    static String referencedPackage(String typeText) {
        var head = typeHead(typeText);
        var lastDot = head.lastIndexOf('.');

        return lastDot >= 0
               ? head.substring(0, lastDot)
               : "";
    }

    /// Simple (last-segment) name of a `Type` / `RefType` node's text.
    static String referencedSimpleName(String typeText) {
        var head = typeHead(typeText);
        var lastDot = head.lastIndexOf('.');

        return lastDot >= 0
               ? head.substring(lastDot + 1)
               : head;
    }

    /// Leading type name of a `Type` / `RefType` node: everything before the first generic `<` or
    /// array `[`, reduced to its last whitespace-delimited token (drops annotations / modifiers).
    private static String typeHead(String typeText) {
        var head = typeText.trim();
        var lt = head.indexOf('<');

        if (lt >= 0) {
            head = head.substring(0, lt);
        }

        var br = head.indexOf('[');

        if (br >= 0) {
            head = head.substring(0, br);
        }

        head = head.trim();
        var lastSpace = Math.max(head.lastIndexOf(' '), head.lastIndexOf('\t'));

        if (lastSpace >= 0) {
            head = head.substring(lastSpace + 1);
        }

        return head.trim();
    }
}
