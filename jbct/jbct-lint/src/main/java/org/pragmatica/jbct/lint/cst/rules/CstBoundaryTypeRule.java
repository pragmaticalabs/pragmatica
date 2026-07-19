package org.pragmatica.jbct.lint.cst.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-BND-01: Forbidden boundary types in business logic.
///
/// Flags the JDK-async, reactive, and framework "boundary" types that must not leak into
/// business logic: `java.util.Optional`, `CompletableFuture`, `CompletionStage`, `Mono`,
/// `Flux`, `ResponseEntity`. The JBCT replacements are `Option` (Optional), `Promise`
/// (CompletableFuture / CompletionStage / Mono / Flux), and a plain domain response record
/// (ResponseEntity).
///
/// Detection is structural. It inspects (a) `import` declarations — the imported type's simple
/// name — and (b) every `Type` / `RefType` node, so it covers return types, parameter types,
/// field types, local-variable types, and nested type arguments such as the inner `Optional` of
/// `Result<Optional<T>>`. A `Type` that merely wraps a `RefType` at the same offset is reported
/// once (dedup by start position). Adapter-boundary code is exempt through the shared
/// `excludePackages` glob (the same `shouldLint` gate every rule uses); the #452 layering
/// classifier will later own that exemption.
///
/// Overlap: JBCT-RET-01 already flags `Optional` / `CompletableFuture` / `CompletionStage` in a
/// method *return* position. That overlap is intentional and reinforcing (both ERROR); BND-01
/// adds the import, parameter, field, local, and nested-type-argument coverage RET-01 does not.
///
/// FP surface: a domain type the project itself names `Optional` / `Mono` / `Flux` /
/// `ResponseEntity` collides with the forbidden simple names. FN surface: a star import
/// (`import java.util.*;`) hides the simple name; an inline fully-qualified expression use with no
/// type node and no import (`java.util.Optional.empty()`) is not a type position; a type-use
/// annotation prefix is stripped best-effort only.
public class CstBoundaryTypeRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-BND-01";

    /// Forbidden boundary types, by simple name.
    private static final Set<String> FORBIDDEN = Set.of("Optional",
                                                        "CompletableFuture",
                                                        "CompletionStage",
                                                        "Mono",
                                                        "Flux",
                                                        "ResponseEntity");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        return Stream.concat(forbiddenImports(root, ctx), forbiddenTypes(root, ctx));
    }

    private Stream<Diagnostic> forbiddenImports(Cursor root, LintContext ctx) {
        return findAll(root, RuleKind.IMPORT_DECL).stream()
                      .map(imp -> new Occurrence(imp, importedSimpleName(imp)))
                      .filter(occurrence -> FORBIDDEN.contains(occurrence.name()))
                      .map(occurrence -> createDiagnostic(occurrence, ctx));
    }

    private Stream<Diagnostic> forbiddenTypes(Cursor root, LintContext ctx) {
        var seen = new HashSet<Long>();
        var diagnostics = Stream.<Diagnostic> builder();

        for (var node : typeNodes(root)) {
            var name = headName(text(node));

            if (!FORBIDDEN.contains(name)) {
                continue;
            }

            var key = ((long) startLine(node) << 20) | startColumn(node);

            if (seen.add(key)) {
                diagnostics.add(createDiagnostic(new Occurrence(node, name), ctx));
            }
        }

        return diagnostics.build();
    }

    private List<Cursor> typeNodes(Cursor root) {
        var nodes = new ArrayList<>(findAll(root, RuleKind.TYPE));

        nodes.addAll(findAll(root, RuleKind.REF_TYPE));

        return nodes;
    }

    /// Last dotted segment of an import declaration's qualified name. Star imports and
    /// static-member imports yield a name that is simply not in the forbidden set.
    private String importedSimpleName(Cursor imp) {
        var body = text(imp).trim()
                            .replaceFirst("^import\\s+", "")
                            .replaceFirst("^static\\s+", "")
                            .replaceAll(";\\s*$", "")
                            .trim();
        var lastDot = body.lastIndexOf('.');

        return lastDot >= 0
               ? body.substring(lastDot + 1)
               : body;
    }

    /// Leading type name of a `Type` / `RefType` node text: everything before the first generic
    /// `<` or array `[`, reduced to its last whitespace token and last dotted segment.
    /// `Optional<String>` and `java.util.Optional` both reduce to `Optional`.
    private String headName(String typeText) {
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

        var lastDot = head.lastIndexOf('.');

        return (lastDot >= 0
                ? head.substring(lastDot + 1)
                : head).trim();
    }

    private Diagnostic createDiagnostic(Occurrence occurrence, LintContext ctx) {
        var name = occurrence.name();

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(occurrence.node()),
                                     startColumn(occurrence.node()),
                                     "Forbidden boundary type '" + name + "' in business logic; use " + replacement(name),
                                     "Boundary types (JDK async, reactive publishers, framework responses) must not "
                                    + "leak into business logic. Convert them at the adapter boundary.");
    }

    private String replacement(String name) {
        return switch (name) {
            case "Optional" -> "Option<T>";
            case "CompletableFuture", "CompletionStage", "Mono", "Flux" -> "Promise<T>";
            case "ResponseEntity" -> "a plain domain response record";
            default -> "a JBCT type";
        };
    }

    private record Occurrence(Cursor node, String name) {}
}
