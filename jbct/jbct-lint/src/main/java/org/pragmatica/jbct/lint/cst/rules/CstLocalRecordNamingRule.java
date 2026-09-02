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


/// JBCT-NAM-04: Local records use lowercase camelCase names.
///
/// A record declared inside a method body (the JBCT nested-implementation pattern — a factory
/// returning `new myService(dep)`) is named lowercase-first (`generationCache`, `myService`), the
/// same convention as a local variable, because it is an implementation detail, not a type in the
/// public vocabulary. This rule flags a local record whose name starts with an uppercase letter.
///
/// "Local" means the record's nearest enclosing member is a method — the same test JBCT-VO-01
/// uses to exempt local records from the value-object factory requirement. Top-level and
/// class/interface-body-nested records (which are PascalCase) are not touched.
///
/// FP surface: a record declared inside an anonymous class body that itself sits in a method is
/// seen as "local" (rare). FN surface: none of note — every in-method record is a candidate.
public class CstLocalRecordNamingRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-NAM-04";
    private static final Pattern RECORD_NAME_PATTERN = Pattern.compile("\\brecord\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        return findAllRecords(root).stream()
                             .filter(record -> isLocalRecord(root, record))
                             .filter(record -> isPascalCase(recordName(record)))
                             .map(record -> createDiagnostic(record, ctx));
    }

    private boolean isLocalRecord(Cursor root, Cursor record) {
        return FileTypeClassifier.isLocalDeclaration(root, record);
    }

    private boolean isPascalCase(String name) {
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    private String recordName(Cursor record) {
        var matcher = RECORD_NAME_PATTERN.matcher(text(record));

        return matcher.find()
               ? matcher.group(1)
               : "";
    }

    private Diagnostic createDiagnostic(Cursor record, LintContext ctx) {
        var name = recordName(record);
        var suggested = Character.toLowerCase(name.charAt(0)) + name.substring(1);

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(record),
                                     startColumn(record),
                                     "Local record '" + name + "' should be lowercase camelCase '" + suggested + "'",
                                     "Records declared inside a method body are implementation details and follow "
                                    + "local-variable naming (lowercase camelCase), not type-name PascalCase.");
    }
}
