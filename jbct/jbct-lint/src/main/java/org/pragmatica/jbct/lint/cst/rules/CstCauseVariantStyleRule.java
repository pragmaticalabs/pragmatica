package org.pragmatica.jbct.lint.cst.rules;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-SEAL-02: Cause variant style — fixed-message causes are enum constants, data-carrying
/// causes are records.
///
/// Within a `Cause` hierarchy, a fixed-message failure carries no data and belongs in a `General`
/// enum (`EMAIL_ALREADY_REGISTERED("...")`); a failure that carries data (a `Throwable`, an id)
/// belongs in a record (`PasswordHashingFailed(Throwable cause)`). This rule flags two
/// mismatches on a variant that `implements` a `Cause`-extending interface declared in the same
/// file (or `Cause` directly):
///   - a **zero-component record** — a fixed-message cause modelled as a record; use an enum
///     constant instead;
///   - a **class** (neither record nor enum) — use a record (data) or an enum constant (fixed).
///
/// A data-carrying record (one or more components) and an enum constant are both correct and are
/// never flagged. The `record unused()` sealed-interface placeholder filler is exempt (see
/// [#isPlaceholderFiller]) — it is a permitted-subtype stub, not a fixed-message cause.
///
/// FN surface: cause hierarchies whose `extends Cause` link is cross-file or transitive
/// (`extends AnotherError`) are not resolved, so their variants are not checked; and a declaration
/// annotation containing a brace truncates the parsed header (see [DeclSupport]), so its
/// `implements` clause is missed. FP surface: a zero-component record (other than the exempt
/// `unused` filler) that implements a `Cause`-extending interface for a reason other than being a
/// fixed-message cause (rare).
public class CstCauseVariantStyleRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-SEAL-02";
    private static final String CAUSE = "Cause";
    private static final String PLACEHOLDER_FILLER = "unused";
    private static final Pattern EXTENDS_CAUSE = Pattern.compile("\\bextends\\b[^{]*\\bCause\\b");
    private static final Pattern INTERFACE_NAME = Pattern.compile("\\binterface\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        var causeInterfaces = collectCauseInterfaces(root);
        var recordDiagnostics = findAllRecords(root).stream()
                                              .filter(record -> isCauseVariant(record, causeInterfaces))
                                              .filter(this::hasNoComponents)
                                              .filter(record -> !isPlaceholderFiller(record))
                                              .map(record -> createRecordDiagnostic(record, ctx));
        var classDiagnostics = findAllClasses(root).stream()
                                             .filter(cls -> isCauseVariant(cls, causeInterfaces))
                                             .map(cls -> createClassDiagnostic(cls, ctx));

        return Stream.concat(recordDiagnostics, classDiagnostics);
    }

    private Set<String> collectCauseInterfaces(Cursor root) {
        var causeInterfaces = new HashSet<String>();

        causeInterfaces.add(CAUSE);
        for (var iface : findAllInterfaces(root)) {
            if (extendsCause(iface)) {
                var matcher = INTERFACE_NAME.matcher(headerOf(text(iface)));

                if (matcher.find()) {
                    causeInterfaces.add(matcher.group(1));
                }
            }
        }

        return causeInterfaces;
    }

    private boolean extendsCause(Cursor iface) {
        return EXTENDS_CAUSE.matcher(headerOf(text(iface)))
                            .find();
    }

    private String headerOf(String declText) {
        var brace = declText.indexOf('{');

        return brace >= 0
               ? declText.substring(0, brace)
               : declText;
    }

    private boolean isCauseVariant(Cursor decl, Set<String> causeInterfaces) {
        return DeclSupport.implementedHeadNames(decl)
                          .stream()
                          .anyMatch(causeInterfaces::contains);
    }

    private boolean hasNoComponents(Cursor record) {
        return childByRule(record, RuleKind.RECORD_DECL).flatMap(rd -> childByRule(rd, RuleKind.RECORD_COMPONENTS))
                          .map(rc -> childrenByRule(rc, RuleKind.RECORD_COMP).isEmpty())
                          .or(true);
    }

    /// The `record unused()` sealed-interface placeholder filler — a permitted-subtype stub for a
    /// sealed cause hierarchy that has no fixed-message variants of its own — is a structural
    /// placeholder, not a fixed-message cause, and is exempt (a documented, widely-used idiom).
    private boolean isPlaceholderFiller(Cursor record) {
        return PLACEHOLDER_FILLER.equals(DeclSupport.declName(record));
    }

    private Diagnostic createRecordDiagnostic(Cursor record, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(record),
                                     startColumn(record),
                                     "Fixed-message cause '" + DeclSupport.declName(record)
                                    + "' should be an enum constant, not an empty record",
                                     "A cause that carries no data is a fixed-message failure; group such causes as "
                                    + "constants of a 'General' enum. Reserve records for data-carrying causes.");
    }

    private Diagnostic createClassDiagnostic(Cursor cls, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(cls),
                                     startColumn(cls),
                                     "Cause variant '" + DeclSupport.declName(cls)
                                    + "' should be a record (data-carrying) or an enum constant (fixed message)",
                                     "Model cause variants as records (when they carry data) or enum constants (when "
                                    + "the message is fixed), not classes.");
    }
}
