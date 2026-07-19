package org.pragmatica.jbct.lint.cst.rules;

import java.util.Set;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.cst.filetype.FileType;
import org.pragmatica.jbct.lint.cst.filetype.FileTypeClassifier;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-VAL-01: boolean validation methods.
///
/// Parse-don't-validate: a domain type is constructed only when valid, through a static factory
/// returning `Result<T>` — never validated after the fact by a boolean predicate. This rule flags
/// an `isValid()` / `validate()` method returning `boolean` on a domain type, gated by
/// [FileTypeClassifier] to [FileType#VALUE_OBJECT] and [FileType#USE_CASE] files (the value object
/// or the use case's nested validated record). The sanctioned form is a `Result<T>`-returning
/// factory.
///
/// FN surface (single-file): a boolean validator with a different name (`check`, `ensureValid`) is
/// not recognised. A boolean `validate()` in a non-domain file is out of scope by design.
public class CstBooleanValidationRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-VAL-01";
    private static final Set<String> VALIDATION_NAMES = Set.of("validate", "isValid");
    private static final Set<String> BOOLEAN_TYPES = Set.of("boolean", "Boolean");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        if (!isDomainType(FileTypeClassifier.classify(root))) {
            return Stream.empty();
        }

        return findAllMethods(root).stream()
                      .filter(this::isBooleanValidator)
                      .map(method -> createDiagnostic(method, ctx));
    }

    private boolean isDomainType(FileType fileType) {
        return fileType == FileType.VALUE_OBJECT || fileType == FileType.USE_CASE;
    }

    private boolean isBooleanValidator(Cursor method) {
        return VALIDATION_NAMES.contains(FileTypeClassifier.methodName(method)) && returnsBoolean(method);
    }

    private boolean returnsBoolean(Cursor method) {
        return methodReturnType(method).map(type -> BOOLEAN_TYPES.contains(text(type).trim()))
                                       .or(false);
    }

    private Diagnostic createDiagnostic(Cursor method, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(method),
                                     startColumn(method),
                                     "Boolean validation method '" + FileTypeClassifier.methodName(method)
                                    + "' on a domain type — parse, don't validate",
                                     "Construct the type only when valid through a static factory returning Result<T>, "
                                    + "instead of a boolean isValid()/validate() checked after construction.");
    }
}
