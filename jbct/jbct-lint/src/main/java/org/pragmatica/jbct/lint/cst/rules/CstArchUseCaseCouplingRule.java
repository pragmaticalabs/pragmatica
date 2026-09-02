package org.pragmatica.jbct.lint.cst.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-ARCH-03: a use case must not call another use case.
///
/// The book requires that when one use case needs another's behaviour, the shared work is extracted
/// into a step interface both depend on — a use case never references a sibling use case directly.
/// This rule identifies a *use-case file* (a top-level type whose name ends `UseCase`, or an
/// interface whose header extends the `UseCase` marker) and flags every reference it makes to a
/// *different* `*UseCase` type, whether imported or used as a fully-qualified / same-package type.
///
/// The file's own type names (including nested ones) are excluded, so a use case referencing itself
/// or its own nested steps is clean. An imported `*UseCase` is flagged once at the import; a
/// same-package or fully-qualified use of a `*UseCase` not covered by an import is flagged at the
/// use site.
///
/// FP surface: a non-use-case type the project names `*UseCase`. FN surface (syntax only, no
/// cross-file resolution): a referenced use case that does NOT follow the `*UseCase` naming
/// convention — e.g. a verb-named `RegisterUser` — cannot be recognised as a use case from
/// single-file facts, so coupling to it is not flagged; and a use case reached only through an
/// injected step interface (the correct pattern) is, by design, never flagged.
public class CstArchUseCaseCouplingRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-ARCH-03";
    private static final String USECASE_SUFFIX = "UseCase";

    /// An interface header that extends the `UseCase` marker (`extends UseCase.WithPromise<...>`).
    private static final Pattern EXTENDS_USECASE = Pattern.compile("\\bextends\\b[\\s\\S]*\\bUseCase\\b");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        var ownNames = declaredNames(root);

        if (!isUseCaseFile(root, ownNames)) {
            return Stream.empty();
        }

        return Stream.concat(importReferences(root, ownNames, ctx),
                             typeReferences(root, ownNames, importedUseCaseNames(root, ownNames), ctx));
    }

    private Set<String> declaredNames(Cursor root) {
        var names = new HashSet<String>();

        for (var typeKind : findAll(root, RuleKind.TYPE_KIND)) {
            var name = DeclSupport.declName(typeKind);

            if (!name.isEmpty()) {
                names.add(name);
            }
        }

        return names;
    }

    private boolean isUseCaseFile(Cursor root, Set<String> ownNames) {
        if (ownNames.stream()
                    .anyMatch(name -> name.endsWith(USECASE_SUFFIX))) {
            return true;
        }

        return findAllInterfaces(root).stream()
                                      .anyMatch(this::extendsUseCaseMarker);
    }

    private boolean extendsUseCaseMarker(Cursor interfaceDecl) {
        var declText = text(interfaceDecl);
        var brace = declText.indexOf('{');
        var header = brace >= 0
                     ? declText.substring(0, brace)
                     : declText;

        return EXTENDS_USECASE.matcher(header)
                              .find();
    }

    private Set<String> importedUseCaseNames(Cursor root, Set<String> ownNames) {
        var names = new HashSet<String>();

        for (var imp : findAll(root, RuleKind.IMPORT_DECL)) {
            var simpleName = ArchSupport.importedSimpleName(text(imp));

            if (isForeignUseCase(simpleName, ownNames)) {
                names.add(simpleName);
            }
        }

        return names;
    }

    private Stream<Diagnostic> importReferences(Cursor root, Set<String> ownNames, LintContext ctx) {
        return findAll(root, RuleKind.IMPORT_DECL).stream()
                      .filter(imp -> isForeignUseCase(ArchSupport.importedSimpleName(text(imp)), ownNames))
                      .map(imp -> createDiagnostic(imp, ArchSupport.importedSimpleName(text(imp)), ctx));
    }

    private Stream<Diagnostic> typeReferences(Cursor root,
                                              Set<String> ownNames,
                                              Set<String> importedUseCaseNames,
                                              LintContext ctx) {
        var seen = new HashSet<Long>();
        var diagnostics = Stream.<Diagnostic> builder();

        for (var node : typeNodes(root)) {
            var simpleName = ArchSupport.referencedSimpleName(text(node));

            if (!isForeignUseCase(simpleName, ownNames) || importedUseCaseNames.contains(simpleName)) {
                continue;
            }

            var key = ((long) startLine(node) << 20) | startColumn(node);

            if (seen.add(key)) {
                diagnostics.add(createDiagnostic(node, simpleName, ctx));
            }
        }

        return diagnostics.build();
    }

    private List<Cursor> typeNodes(Cursor root) {
        var nodes = new ArrayList<>(findAll(root, RuleKind.TYPE));

        nodes.addAll(findAll(root, RuleKind.REF_TYPE));

        return nodes;
    }

    private boolean isForeignUseCase(String simpleName, Set<String> ownNames) {
        return simpleName.endsWith(USECASE_SUFFIX) && !ownNames.contains(simpleName);
    }

    private Diagnostic createDiagnostic(Cursor node, String referenced, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(node),
                                     startColumn(node),
                                     "Use case references another use case '" + referenced + "'",
                                     "A use case must not call another use case. Extract the shared behaviour into a "
                                    + "step interface both use cases depend on.");
    }
}
