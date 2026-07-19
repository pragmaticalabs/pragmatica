package org.pragmatica.jbct.lint.cst.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// Shared reference extraction for the architecture / layering rules (JBCT-ARCH-01, JBCT-ARCH-04).
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
    record unused() implements ArchSupport {}

    /// A package reference sourced from an import declaration or a fully-qualified type node.
    record Reference(Cursor node, String packageName) {}

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

    private static String importBody(String importText) {
        return importText.trim()
                         .replaceFirst("^import\\s+", "")
                         .replaceFirst("^static\\s+", "")
                         .replaceAll(";\\s*$", "")
                         .trim();
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
