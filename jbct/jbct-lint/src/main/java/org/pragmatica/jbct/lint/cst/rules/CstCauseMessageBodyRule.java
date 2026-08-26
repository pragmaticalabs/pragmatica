package org.pragmatica.jbct.lint.cst.rules;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.cst.filetype.FileTypeClassifier;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-CAUSE-02: no hand-written `message()` bodies.
///
/// A cause record's trailing `message` component IS the implementation — an explicit `message()`
/// method reintroduces the hand-written rendering R4 prohibits. A cause enum implements
/// `message()` as exactly the field-returning accessor; anything else (concatenation,
/// conditionals, formatting) is prose assembled at the wrong layer. A `default message()` on a
/// same-file cause interface hides the omission from every implementor at once.
public class CstCauseMessageBodyRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-CAUSE-02";
    private static final Pattern NO_PARAMS_MESSAGE = Pattern.compile("\\bmessage\\s*\\(\\s*\\)");
    private static final Pattern FIELD_RETURN_ONLY = Pattern.compile("^\\{?\\s*return\\s+(this\\s*\\.\\s*)?message\\s*;\\s*\\}?$");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root)) || CauseHierarchies.sanctionedLibraryPackage(packageName(root))) {
            return Stream.empty();
        }

        var causeNames = CauseHierarchies.causeInterfaceNames(root);
        var diagnostics = Stream.<Diagnostic>builder();

        for (var record : CauseHierarchies.causeRecords(root, causeNames)) {
            messageMethods(root, record).forEach(method -> diagnostics.add(
                diagnostic(method, ctx, "Cause record declares message(); the trailing message component's accessor is the implementation")));
        }

        for (var anEnum : CauseHierarchies.causeEnums(root, causeNames)) {
            messageMethods(root, anEnum).stream()
                                  .filter(method -> !isFieldReturningAccessor(method))
                                  .forEach(method -> diagnostics.add(
                diagnostic(method, ctx, "Cause enum's message() must be exactly the field-returning accessor (return message;)")));
        }

        for (var iface : findAllInterfaces(root)) {
            if (!causeNames.contains(DeclSupport.declName(iface))) {
                continue;
            }

            messageMethods(root, iface).stream()
                                 .filter(method -> isDefaultMethod(root, method))
                                 .forEach(method -> diagnostics.add(
                diagnostic(method, ctx, "Cause interface declares a default message(); each variant carries its own text")));
        }

        return diagnostics.build();
    }

    /// DIRECT members only: `findAllMethods` walks the whole subtree, so a nested interface's
    /// default `message()` would be attributed to the enclosing type too and emitted twice — the
    /// census caught exactly that on a nested `ServerError`. Same defect class as classifying a
    /// mutant by its enclosing class: scope to the member's nearest enclosing type, always.
    private java.util.List<Cursor> messageMethods(Cursor root, Cursor type) {
        return findAllMethods(type).stream()
                      .filter(method -> FileTypeClassifier.directlyEncloses(root, ownTypeKind(type), method))
                      .filter(method -> "message".equals(FileTypeClassifier.methodName(method)))
                      .filter(method -> NO_PARAMS_MESSAGE.matcher(memberDeclText(method)).find())
                      .toList();
    }

    /// The TYPE_KIND node member-scoping keys on: `type` itself when it is one (nested types are
    /// bare TYPE_KIND), else its TYPE_KIND child (top-level types arrive TypeDecl-wrapped).
    private Cursor ownTypeKind(Cursor type) {
        return type.kindIs(RuleKind.TYPE_KIND)
               ? type
               : childByRule(type, RuleKind.TYPE_KIND).or(type);
    }

    private boolean isFieldReturningAccessor(Cursor method) {
        return methodBody(method).map(body -> FIELD_RETURN_ONLY.matcher(text(body).trim()).matches())
                                 .or(false);
    }

    /// The modifier check runs over COMMENT-MASKED member text: a doc comment attaches to the
    /// member node, so raw `contains("default ")` matched prose ("\`false\` by default") and
    /// flagged the abstract `message()` on `Cause` itself — the trailing-trivia trap from the #600
    /// migration, in modifier clothing.
    private boolean isDefaultMethod(Cursor root, Cursor method) {
        return enclosingMember(root, method).map(member -> MapperSafety.blankNonCode(text(member)).contains("default "))
                            .or(false);
    }

    private Diagnostic diagnostic(Cursor method, LintContext ctx, String message) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(anchorOf(method)),
                                     startColumn(anchorOf(method)),
                                     message,
                                     "The message text exists in exactly one place: the factory's template or the "
                                    + "enum constant's literal, adjacent to the data it must agree with (R4).");
    }
}
