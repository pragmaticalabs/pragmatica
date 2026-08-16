package org.pragmatica.jbct.lint.cst.rules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.cst.filetype.FileTypeClassifier;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;
import org.pragmatica.lang.Option;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-BND-01: Forbidden boundary types in business logic.
///
/// Flags the JDK-async, reactive, and framework "boundary" types that must not leak into
/// business logic: `java.util.Optional`, `CompletableFuture`, `CompletionStage`, `Mono`,
/// `Flux`, `ResponseEntity`. The JBCT replacements are `Option` (Optional), `Promise`
/// (CompletableFuture / CompletionStage / Mono / Flux), and a plain domain response record
/// (ResponseEntity).
///
/// Detection is structural and matches by ORIGIN, never by simple name alone. It inspects (a)
/// `import` declarations — flagged when the imported fully qualified name is a boundary type —
/// and (b) every `Type` / `RefType` node, so it covers return types, parameter types, field
/// types, local-variable types, and nested type arguments such as the inner `Optional` of
/// `Result<Optional<T>>`. A `Type` that merely wraps a `RefType` at the same offset is reported
/// once (dedup by start position). Adapter-boundary code is exempt through the shared
/// `excludePackages` glob (the same `shouldLint` gate every rule uses); the #452 layering
/// classifier will later own that exemption.
///
/// A QUALIFIED use names its own origin, so `Expression.Optional` is a domain type and only
/// `java.util.Optional` is the boundary type. An UNQUALIFIED use is resolved against the file's
/// imports: none of these types live in `java.lang`, so a bare `Optional` can only denote
/// `java.util.Optional` if the file imports it (explicitly or through `java.util.*`). A type the
/// file declares itself shadows any star import. That is enough to be exact without resolving
/// types across files.
///
/// Overlap: JBCT-RET-01 already flags `Optional` / `CompletableFuture` / `CompletionStage` in a
/// method *return* position. That overlap is intentional and reinforcing (both ERROR); BND-01
/// adds the import, parameter, field, local, and nested-type-argument coverage RET-01 does not.
///
/// FN surface: a boundary type reached through a same-package type alias or a re-export is not
/// visible to a single-file scan; an inline fully-qualified expression use with no type node and
/// no import (`java.util.Optional.empty()`) is not a type position; a type-use annotation prefix
/// is stripped best-effort only.
public class CstBoundaryTypeRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-BND-01";

    /// Forbidden boundary types, by simple name — used for the message and its suggested
    /// replacement. Matching NEVER keys on this alone; see [#FORBIDDEN_FQNS].
    private static final Set<String> FORBIDDEN = Set.of("Optional",
                                                        "CompletableFuture",
                                                        "CompletionStage",
                                                        "Mono",
                                                        "Flux",
                                                        "ResponseEntity");

    /// The same types by ORIGIN. A use is forbidden because of where the type comes from, not
    /// because of what it is called: `Expression.Optional` is a domain type that happens to
    /// share a simple name with `java.util.Optional`, and reducing a qualified use to its last
    /// dotted segment is what destroyed that distinction.
    ///
    /// None of these live in `java.lang`, so an UNQUALIFIED use can only denote one of them if
    /// this file imports it. That is what makes origin matching possible with no type
    /// resolution: no import and no qualifier means the name resolves to something local or
    /// same-package, which is by definition not the boundary type.
    private static final Set<String> FORBIDDEN_FQNS = Set.of("java.util.Optional",
                                                             "java.util.concurrent.CompletableFuture",
                                                             "java.util.concurrent.CompletionStage",
                                                             "reactor.core.publisher.Mono",
                                                             "reactor.core.publisher.Flux",
                                                             "org.springframework.http.ResponseEntity");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        var scope = NameScope.of(root);

        return Stream.concat(forbiddenImports(root, ctx), forbiddenTypes(root, scope, ctx));
    }

    /// What the simple names in this file can denote: explicit imports, star-imported
    /// packages, and the types the file declares itself (which shadow any star import).
    private record NameScope(Map<String, String> imports, Set<String> starPackages, Set<String> declaredTypes) {
        static NameScope of(Cursor root) {
            var imports = new HashMap<String, String>();
            var starPackages = new HashSet<String>();

            for (var imp : findAll(root, RuleKind.IMPORT_DECL)) {
                var fqn = importedName(imp);

                if (fqn.endsWith(".*")) {
                    starPackages.add(fqn.substring(0, fqn.length() - 2));
                } else {
                    var lastDot = fqn.lastIndexOf('.');

                    if (lastDot >= 0) {
                        imports.put(fqn.substring(lastDot + 1), fqn);
                    }
                }
            }

            var declared = new HashSet<String>();

            for (var typeKind : findAll(root, RuleKind.TYPE_KIND)) {
                var name = FileTypeClassifier.declaredName(typeKind);

                if (!name.isEmpty()) {
                    declared.add(name);
                }
            }

            return new NameScope(imports, starPackages, declared);
        }

        /// The origin a simple name denotes here, or `none` when nothing in this file can make
        /// it a boundary type.
        Option<String> originOf(String simpleName) {
            if (declaredTypes.contains(simpleName)) {
                return Option.none();
            }

            var explicit = imports.get(simpleName);

            if (explicit != null) {
                return Option.some(explicit);
            }

            for (var pkg : starPackages) {
                var candidate = pkg + "." + simpleName;

                if (FORBIDDEN_FQNS.contains(candidate)) {
                    return Option.some(candidate);
                }
            }

            return Option.none();
        }
    }

    private Stream<Diagnostic> forbiddenImports(Cursor root, LintContext ctx) {
        return findAll(root, RuleKind.IMPORT_DECL).stream()
                      .filter(imp -> FORBIDDEN_FQNS.contains(importedName(imp)))
                      .map(imp -> new Occurrence(imp, simpleNameOf(importedName(imp))))
                      .map(occurrence -> createDiagnostic(occurrence, ctx));
    }

    private Stream<Diagnostic> forbiddenTypes(Cursor root, NameScope scope, LintContext ctx) {
        var seen = new HashSet<Long>();
        var diagnostics = Stream.<Diagnostic> builder();

        for (var node : typeNodes(root)) {
            var used = headName(text(node));

            if (!isForbiddenUse(used, scope)) {
                continue;
            }

            var key = ((long) startLine(node) << 20) | startColumn(node);

            if (seen.add(key)) {
                diagnostics.add(createDiagnostic(new Occurrence(node, simpleNameOf(used)), ctx));
            }
        }

        return diagnostics.build();
    }

    /// A qualified use names its own origin, so it is forbidden only when that origin is. An
    /// unqualified use is forbidden only when this file imports it from a forbidden origin.
    private boolean isForbiddenUse(String used, NameScope scope) {
        if (used.indexOf('.') >= 0) {
            return FORBIDDEN_FQNS.contains(used);
        }

        if (!FORBIDDEN.contains(used)) {
            return false;
        }

        return scope.originOf(used)
                    .map(FORBIDDEN_FQNS::contains)
                    .or(false);
    }

    private static String simpleNameOf(String name) {
        var lastDot = name.lastIndexOf('.');

        return lastDot >= 0
               ? name.substring(lastDot + 1)
               : name;
    }

    private List<Cursor> typeNodes(Cursor root) {
        var nodes = new ArrayList<>(findAll(root, RuleKind.TYPE));

        nodes.addAll(findAll(root, RuleKind.REF_TYPE));

        return nodes;
    }

    /// Fully qualified name of an import declaration, `.*` retained for a star import. Static
    /// member imports yield a name that is simply not in the forbidden set.
    private static String importedName(Cursor imp) {
        return text(imp).trim()
                        .replaceFirst("^import\\s+", "")
                        .replaceFirst("^static\\s+", "")
                        .replaceAll(";\\s*$", "")
                        .replaceAll("\\s+", "")
                        .trim();
    }

    /// Leading type name of a `Type` / `RefType` node text: everything before the first generic
    /// `<` or array `[`, reduced to its last whitespace token. The QUALIFIER IS KEPT —
    /// `Expression.Optional` and `java.util.Optional` are different types, and collapsing both
    /// to `Optional` is precisely what made every domain type of that name a false positive.
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

        return head.trim();
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
