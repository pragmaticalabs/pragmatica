package org.pragmatica.jbct.lint.cst.rules;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.cst.filetype.FileType;
import org.pragmatica.jbct.lint.cst.filetype.FileTypeClassifier;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-UC-02: use-case interface structure.
///
/// A use-case interface (classified [FileType#USE_CASE] by [FileTypeClassifier] — an interface with
/// an `execute` Zone-1 entry) declares its Request/Response records and step interfaces inside
/// itself, exposes exactly one entry method, and provides a static factory. This rule flags each
/// deviation independently, all anchored on the interface declaration:
///
///   - **more than one entry method** — more than one abstract (non-`static`, non-`default`) method;
///     only `execute` should be the Zone-1 entry;
///   - **request/response declared outside** — the interface declares no nested `*Request` or
///     `*Response` record, so those types live elsewhere;
///   - **missing static factory** — no `static` factory method returning the use case.
///
/// FP surface: an interface that merely declares an `execute` method but is not a use case is read
/// as one (the classifier treats `execute` as the reserved Zone-1 entry). FN surface (single-file):
/// Request/Response declared outside cannot be distinguished from legitimately absent — the rule
/// only observes that neither is nested; a factory or entry method named unconventionally is missed.
public class CstUseCaseStructureRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-UC-02";
    private static final String REQUEST_SUFFIX = "Request";
    private static final String RESPONSE_SUFFIX = "Response";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        if (FileTypeClassifier.classify(root) != FileType.USE_CASE) {
            return Stream.empty();
        }

        return FileTypeClassifier.principalType(root)
                                 .map(useCase -> checkStructure(root, useCase, ctx))
                                 .or(Stream.empty());
    }

    private Stream<Diagnostic> checkStructure(Cursor root, Cursor useCase, LintContext ctx) {
        var diagnostics = Stream.<Diagnostic> builder();
        var methods = FileTypeClassifier.directMethods(root, useCase);

        if (entryMethodCount(root, methods) > 1) {
            diagnostics.add(diagnostic(useCase,
                                       ctx,
                                       "declares more than one entry method — only 'execute' is the Zone-1 entry"));
        }

        if (!declaresRequestResponse(root, useCase)) {
            diagnostics.add(diagnostic(useCase,
                                       ctx,
                                       "declares no nested Request/Response record — declare them inside the use case"));
        }

        if (!hasStaticFactory(root, methods, FileTypeClassifier.declaredName(useCase))) {
            diagnostics.add(diagnostic(useCase, ctx, "has no static factory method returning the use case"));
        }

        return diagnostics.build();
    }

    private long entryMethodCount(Cursor root, List<Cursor> methods) {
        return methods.stream()
                      .filter(method -> FileTypeClassifier.isAbstractMethod(root, method))
                      .count();
    }

    private boolean hasStaticFactory(Cursor root, List<Cursor> methods, String useCaseName) {
        return methods.stream()
                      .filter(method -> FileTypeClassifier.isStatic(root, method))
                      .anyMatch(method -> FileTypeClassifier.producesOwnType(method, useCaseName));
    }

    private boolean declaresRequestResponse(Cursor root, Cursor useCase) {
        return FileTypeClassifier.directNestedTypes(root, useCase)
                                 .stream()
                                 .map(FileTypeClassifier::declaredName)
                                 .anyMatch(this::isRequestOrResponse);
    }

    private boolean isRequestOrResponse(String name) {
        return name.endsWith(REQUEST_SUFFIX) || name.endsWith(RESPONSE_SUFFIX);
    }

    private Diagnostic diagnostic(Cursor useCase, LintContext ctx, String problem) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(useCase),
                                     startColumn(useCase),
                                     "Use-case interface '" + FileTypeClassifier.declaredName(useCase) + "' " + problem,
                                     "A use-case interface declares its Request/Response records and step interfaces "
                                    + "inside itself, exposes exactly one 'execute' Zone-1 entry, and provides a static "
                                    + "factory returning the use case.");
    }
}
