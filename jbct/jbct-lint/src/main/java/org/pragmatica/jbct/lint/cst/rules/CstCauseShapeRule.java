package org.pragmatica.jbct.lint.cst.rules;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-CAUSE-01: cause representation shape — every same-file cause variant is a record or a
/// prescribed-shape enum.
///
/// Absorbs JBCT-SEAL-02, whose stance carries forward: fixed-text failures are enum constants,
/// data-carrying failures are records. On top of SEAL-02's class and zero-component-record
/// clauses, this adds the prescribed enum shape (a single `String message` field, one text
/// argument per constant), the message-only-record check (SEAL-02's zero-component clause carried
/// one component up), and anonymous-class detection. The `record unused()` sealed-interface
/// placeholder filler stays exempt, as it was under SEAL-02.
///
/// The mixins `Cause.Terminal` / `Cause.Wrapped` are recognized by their QUALIFIED spelling and
/// never flagged; hierarchy detection runs to a same-file fixpoint (see [CauseHierarchies]).
public class CstCauseShapeRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-CAUSE-01";
    private static final String PLACEHOLDER_FILLER = "unused";
    private static final Pattern MESSAGE_FIELD = Pattern.compile("\\bString\\s+message\\b");

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

        for (var cls : findAllClasses(root)) {
            if (CauseHierarchies.isCauseVariant(cls, causeNames)) {
                diagnostics.add(diagnostic(cls, ctx,
                                           "Cause variant '" + DeclSupport.declName(cls)
                                          + "' is a class; a data-carrying failure belongs in a record, a fixed-text one in the hierarchy's enum"));
            }
        }

        for (var record : CauseHierarchies.causeRecords(root, causeNames)) {
            var components = CauseHierarchies.recordComponentNames(record);

            if (components.isEmpty() && !PLACEHOLDER_FILLER.equals(DeclSupport.declName(record))) {
                diagnostics.add(diagnostic(record, ctx,
                                           "Cause record '" + DeclSupport.declName(record)
                                          + "' has no components and cannot implement message() structurally"));
            } else if (components.size() == 1 && "message".equals(components.getFirst())) {
                diagnostics.add(diagnostic(record, ctx,
                                           "Fixed-text failure '" + DeclSupport.declName(record)
                                          + "' modeled as a message-only record; it belongs in the hierarchy's enum"));
            }
        }

        for (var anEnum : CauseHierarchies.causeEnums(root, causeNames)) {
            malformedEnumDiagnostic(anEnum, ctx).ifPresent(diagnostics::add);
        }

        anonymousVariants(root, source, causeNames, ctx).forEach(diagnostics::add);

        return diagnostics.build();
    }

    /// The prescribed enum shape: a lone `String message` instance field and exactly one argument
    /// (the text) per constant. A second field or a second constant argument is data — a record
    /// wearing enum clothes.
    private java.util.Optional<Diagnostic> malformedEnumDiagnostic(Cursor anEnum, LintContext ctx) {
        var extraField = findAll(anEnum, RuleKind.FIELD_DECL).stream()
                                 .anyMatch(field -> !MESSAGE_FIELD.matcher(text(field)).find());

        var dataConstant = findAll(anEnum, RuleKind.ENUM_CONST).stream()
                                   .anyMatch(constant -> CauseHierarchies.enumConstantArgCount(constant) != 1);

        if (!extraField && !dataConstant) {
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(diagnostic(anEnum, ctx,
                                                "Cause enum '" + DeclSupport.declName(anEnum)
                                               + "' is not in the prescribed shape; a constant carrying data belongs in a record"
                                               + " (record Name(Data data, String message))"));
    }

    private Stream<Diagnostic> anonymousVariants(Cursor root, String source, java.util.Set<String> causeNames, LintContext ctx) {
        var masked = MapperSafety.blankNonCode(source);
        var out = Stream.<Diagnostic>builder();

        for (var name : causeNames) {
            var anonymous = Pattern.compile("\\bnew\\s+" + Pattern.quote(name) + "\\s*\\([^)]*\\)\\s*\\{")
                                   .matcher(masked);

            while (anonymous.find()) {
                out.add(Diagnostic.diagnostic(RULE_ID,
                                              ctx.severityFor(RULE_ID),
                                              ctx.fileName(),
                                              CauseHierarchies.lineAt(source, anonymous.start()),
                                              CauseHierarchies.columnAt(source, anonymous.start()),
                                              "Anonymous class over cause interface '" + name + "' in domain code",
                                              "Causes.cause and friends are the sanctioned anonymous form; a domain "
                                             + "failure worth a type is a record or an enum constant in its hierarchy."));
            }
        }

        return out.build();
    }

    private Diagnostic diagnostic(Cursor decl, LintContext ctx, String message) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(anchorOf(decl)),
                                     startColumn(anchorOf(decl)),
                                     message,
                                     "One canonical representation per failure kind: data-carrying failures are "
                                    + "records (data components plus a trailing String message), fixed-text failures "
                                    + "are constants of a prescribed-shape enum.");
    }
}
