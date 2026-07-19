package org.pragmatica.jbct.lint.cst.rules;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.cst.filetype.FileType;
import org.pragmatica.jbct.lint.cst.filetype.FileTypeClassifier;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-INJ-01: constructor/factory injection only.
///
/// Steps and use cases are wired through the factory that returns the lambda/record, never by field
/// injection. This rule flags, in an implementation of a **step or use-case type**, a **non-final
/// instance field** or a **setter-shaped method** (`setX(...)`) — the shapes of field injection and
/// post-construction mutation.
///
/// Scope is what a single file can determine: an implementation (a class/record/enum with an
/// `implements` clause) is in scope only when it implements an interface **declared in this file**
/// that classifies as [FileType#USE_CASE] or [FileType#STEP_INTERFACE] (via
/// [FileTypeClassifier#classifyType]), or when it is nested inside a [FileType#USE_CASE] file (the
/// `record impl(...) implements UseCase` factory idiom). [FileType#TEST_CLASS] files are skipped
/// (mutable fixture / mock state is legitimate). A record cannot trip the field path — its
/// components are final and it has no instance fields — but a record declaring a `setX` method does
/// trip the setter path.
///
/// FN surface (by design): an implementation whose step/use-case interface lives in another file —
/// the common adapter case — is out of scope, because its role is not single-file determinable. FP
/// surface: a plain functional interface declared in-file that happens to classify as a step (one
/// abstract method) makes its implementations in scope.
public class CstInjectionRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-INJ-01";
    private static final Pattern IMPLEMENTS = Pattern.compile("\\bimplements\\b");
    private static final Pattern SETTER = Pattern.compile("^set[A-Z][A-Za-z0-9_$]*$");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        var fileType = FileTypeClassifier.classify(root);

        if (fileType == FileType.TEST_CLASS) {
            return Stream.empty();
        }

        var injectable = injectableInterfaceNames(root);
        var useCaseFile = fileType == FileType.USE_CASE;

        return findAll(root, RuleKind.TYPE_KIND).stream()
                      .filter(this::isImplementation)
                      .filter(typeKind -> isInjectableImplementation(root, typeKind, injectable, useCaseFile))
                      .flatMap(typeKind -> injectionDiagnostics(root, typeKind, ctx));
    }

    private Set<String> injectableInterfaceNames(Cursor root) {
        return findAll(root, RuleKind.TYPE_KIND).stream()
                      .filter(FileTypeClassifier::isInterface)
                      .filter(typeKind -> isInjectableRole(FileTypeClassifier.classifyType(root, typeKind)))
                      .map(FileTypeClassifier::declaredName)
                      .filter(name -> !name.isEmpty())
                      .collect(Collectors.toSet());
    }

    private boolean isInjectableRole(FileType fileType) {
        return fileType == FileType.USE_CASE || fileType == FileType.STEP_INTERFACE;
    }

    private boolean isImplementation(Cursor typeKind) {
        return IMPLEMENTS.matcher(headerOf(typeKind))
                         .find();
    }

    private boolean isInjectableImplementation(Cursor root, Cursor typeKind, Set<String> injectable, boolean useCaseFile) {
        if (DeclSupport.implementedHeadNames(typeKind)
                       .stream()
                       .anyMatch(injectable::contains)) {
            return true;
        }

        return useCaseFile && findAncestor(root, typeKind, RuleKind.TYPE_KIND).isPresent();
    }

    private Stream<Diagnostic> injectionDiagnostics(Cursor root, Cursor typeKind, LintContext ctx) {
        var mutableFields = directOf(root, typeKind, RuleKind.FIELD_DECL).stream()
                                   .filter(field -> isMutableInstanceField(root, field))
                                   .map(field -> fieldDiagnostic(field, ctx));
        var setters = FileTypeClassifier.directMethods(root, typeKind)
                                        .stream()
                                        .filter(this::isSetter)
                                        .map(method -> setterDiagnostic(method, ctx));

        return Stream.concat(mutableFields, setters);
    }

    private List<Cursor> directOf(Cursor root, Cursor typeKind, RuleKind kind) {
        return findAll(typeKind, kind).stream()
                     .filter(node -> FileTypeClassifier.directlyEncloses(root, typeKind, node))
                     .toList();
    }

    private boolean isMutableInstanceField(Cursor root, Cursor field) {
        var modifiers = fieldModifiers(root, field);

        return ! modifiers.contains("static ") && !modifiers.contains("final ");
    }

    private String fieldModifiers(Cursor root, Cursor field) {
        return findAncestor(root, field, RuleKind.CLASS_MEMBER).map(wrapper -> text(wrapper))
                            .or(text(field));
    }

    private boolean isSetter(Cursor method) {
        return SETTER.matcher(FileTypeClassifier.methodName(method))
                     .matches();
    }

    private String headerOf(Cursor typeKind) {
        var declText = text(typeKind);
        var brace = declText.indexOf('{');

        return brace >= 0
               ? declText.substring(0, brace)
               : declText;
    }

    private Diagnostic fieldDiagnostic(Cursor field, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(field),
                                     startColumn(field),
                                     "Non-final instance field in a step/use-case implementation — inject through the factory",
                                     "Steps and use cases are injected through the factory that constructs them, as "
                                    + "final fields. A non-final instance field is field injection / mutable state.");
    }

    private Diagnostic setterDiagnostic(Cursor method, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(method),
                                     startColumn(method),
                                     "Setter '" + FileTypeClassifier.methodName(method)
                                    + "' in a step/use-case implementation — inject through the factory",
                                     "Dependencies are passed to the factory, not set after construction. Replace the "
                                    + "setter with constructor/factory injection into a final field.");
    }
}
