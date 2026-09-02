package org.pragmatica.jbct.lint.cst.rules;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.cst.filetype.FileType;
import org.pragmatica.jbct.lint.cst.filetype.FileTypeClassifier;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.CstNodes;
import org.pragmatica.jbct.parser.RuleKind;

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
/// Two slice-framework shapes are conforming without nested Request/Response records (#647):
///
///   - a **fact consumer**, whose sole entry method takes a subscription-qualified parameter
///     (`execute(@SeatEvents SeatReleased event)`) — the subscription contract IS the request type,
///     and a synthetic Request wrapper around it would be pure indirection;
///   - a **scheduled hook**, a zero-parameter qualified `Promise<Unit>` method mandated by the
///     `Scheduled` resource contract — it is not an entry method and does not make the slice
///     dual-entry.
///
/// FP surface: an interface that merely declares an `execute` method but is not a use case is read
/// as one (the classifier treats `execute` as the reserved Zone-1 entry). FN surface (single-file):
/// Request/Response declared outside cannot be distinguished from legitimately absent — the rule
/// only observes that neither is nested; a factory or entry method named unconventionally is missed.
/// A resource qualifier is recognised by SHAPE, not by resolution: the `@ResourceQualifier`
/// meta-annotation lives in the qualifier annotation's own declaration file, which a single-file
/// linter cannot read, so any annotation outside [#NON_QUALIFIER_ANNOTATIONS] on a parameter or on
/// a zero-parameter `Promise<Unit>` method is taken for one. A `Promise<Unit>` spelled through a
/// fully-qualified name is not recognised (accepted FN).
public class CstUseCaseStructureRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-UC-02";
    private static final String REQUEST_SUFFIX = "Request";
    private static final String RESPONSE_SUFFIX = "Response";
    private static final String UNIT_PROMISE = "Promise<Unit>";

    /// Annotations that are never resource qualifiers — everything else on a parameter or on a
    /// scheduled-hook-shaped method is treated as one. Kept as an EXCLUSION list because qualifier
    /// annotations are user-defined (`@SeatEvents`, `@Heartbeat`) and cannot be enumerated.
    private static final Set<String> NON_QUALIFIER_ANNOTATIONS = Set.of("Override",
                                                                        "Deprecated",
                                                                        "SuppressWarnings",
                                                                        "SafeVarargs",
                                                                        "FunctionalInterface",
                                                                        "Contract",
                                                                        "TerminalOperation",
                                                                        "NullReturn",
                                                                        "Nullable",
                                                                        "NonNull",
                                                                        "Nonnull",
                                                                        "NotNull",
                                                                        "Valid");

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
        var entryMethods = entryMethods(root, methods);

        if (entryMethods.size() > 1) {
            diagnostics.add(diagnostic(useCase,
                                       ctx,
                                       "declares more than one entry method — only 'execute' is the Zone-1 entry"));
        }

        if (!declaresRequestResponse(root, useCase, entryMethods)) {
            diagnostics.add(diagnostic(useCase,
                                       ctx,
                                       "declares no nested Request/Response record — declare them inside the use case"));
        }

        if (!hasStaticFactory(root, methods, FileTypeClassifier.declaredName(useCase))) {
            diagnostics.add(diagnostic(useCase, ctx, "has no static factory method returning the use case"));
        }

        return diagnostics.build();
    }

    /// The interface's entry methods: its abstract methods, minus scheduled hooks. A hook is
    /// declared by the `Scheduled` resource contract, not by the use case's callers, so counting it
    /// would make every scheduled slice read as dual-entry and force the hook into a second @Slice.
    private List<Cursor> entryMethods(Cursor root, List<Cursor> methods) {
        return methods.stream()
                      .filter(method -> FileTypeClassifier.isAbstractMethod(root, method))
                      .filter(method -> !isScheduledHook(root, method))
                      .toList();
    }

    /// `@Heartbeat Promise<Unit> heartbeat();` — the shape the `Scheduled` contract mandates: no
    /// parameters, `Promise<Unit>` return, and a resource-qualifier annotation naming the schedule.
    private boolean isScheduledHook(Cursor root, Cursor method) {
        return hasNoParameters(method) && returnsUnitPromise(method) && carriesResourceQualifier(root, method);
    }

    private boolean hasNoParameters(Cursor method) {
        return methodParams(method).map(params -> parameterNodes(params).isEmpty())
                           .or(true);
    }

    private boolean returnsUnitPromise(Cursor method) {
        return methodReturnType(method).map(CstNodes::tokenText)
                               .map(UNIT_PROMISE::equals)
                               .or(false);
    }

    private boolean carriesResourceQualifier(Cursor root, Cursor method) {
        return enclosingMember(root, method).map(this::declaresQualifierAnnotation)
                           .or(false);
    }

    private boolean declaresQualifierAnnotation(Cursor node) {
        return childrenByRule(node, RuleKind.ANNOTATION).stream()
                             .map(CstNodes::annotationSimpleName)
                             .anyMatch(this::isResourceQualifier);
    }

    private boolean isResourceQualifier(String annotationName) {
        return ! annotationName.isEmpty() && !NON_QUALIFIER_ANNOTATIONS.contains(annotationName);
    }

    private boolean hasStaticFactory(Cursor root, List<Cursor> methods, String useCaseName) {
        return methods.stream()
                      .filter(method -> FileTypeClassifier.isStatic(root, method))
                      .anyMatch(method -> FileTypeClassifier.producesOwnType(method, useCaseName));
    }

    private boolean declaresRequestResponse(Cursor root, Cursor useCase, List<Cursor> entryMethods) {
        return nestsRequestOrResponse(root, useCase) || isFactConsumer(entryMethods);
    }

    private boolean nestsRequestOrResponse(Cursor root, Cursor useCase) {
        return FileTypeClassifier.directNestedTypes(root, useCase)
                                 .stream()
                                 .map(FileTypeClassifier::declaredName)
                                 .anyMatch(this::isRequestOrResponse);
    }

    /// A fact consumer subscribes to a published fact: its sole entry method's parameter carries the
    /// subscription qualifier AND is typed by the fact — an externally-declared type, not the
    /// Request/Response shape this rule polices. The type gate fails the exemption CLOSED
    /// (pre-merge field review): an annotated parameter that IS Request-shaped still demands the
    /// nested pair, so an incidental annotation cannot exempt an ordinary use case.
    private boolean isFactConsumer(List<Cursor> entryMethods) {
        return entryMethods.size() == 1 && hasQualifiedFactParameter(entryMethods.getFirst());
    }

    private boolean hasQualifiedFactParameter(Cursor method) {
        return methodParams(method).map(CstNodes::parameterNodes)
                           .or(List.of())
                           .stream()
                           .anyMatch(param -> declaresQualifierAnnotation(param) && carriesFactType(param));
    }

    /// The gate's type half. The parameter's declared type is read from its TYPE node
    /// (`PlainParam <- Annotation* Modifier* Type Identifier` by grammar), never
    /// reconstructed from annotation-stripped source text: an annotation argument may
    /// contain `)` and an annotation may be fully qualified, so text surgery mis-reads the
    /// type on both spellings (#661). Fail-closed in both directions: a Request/Response-typed
    /// parameter demands the nested pair regardless of its qualifier, and a parameter whose
    /// type yields no readable name (a published fact is never a primitive array) is denied
    /// the exemption rather than granted it.
    private boolean carriesFactType(Cursor param) {
        return childByRule(param, RuleKind.TYPE).flatMap(CstNodes::typeSimpleName)
                           .map(name -> !isRequestOrResponse(name))
                           .or(false);
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
