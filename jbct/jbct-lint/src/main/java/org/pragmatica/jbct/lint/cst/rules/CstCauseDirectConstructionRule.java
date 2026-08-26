package org.pragmatica.jbct.lint.cst.rules;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.packageName;


/// JBCT-CAUSE-08: no direct construction of cause records — `FACTORY` is the construction path.
///
/// `new ExceededLimit(a, b, "hand-typed prose")` compiles and silently decouples the stored
/// message from the declared template; the factory is the only path on which the R1 arity
/// equation holds AT RUNTIME. The cause-flavored JBCT-VO-02, and the same honest limit as the
/// rest of the pack: a cause record is recognized only inside its declaring file, so same-file
/// bypass is caught reliably while cross-file `new` is a documented FN — no language-level
/// enforcement exists, since a public nested record cannot restrict its canonical constructor.
///
/// The constructor REFERENCE (`ExceededLimit::new`) is not an instantiation expression and is
/// never matched — the one sanctioned constructor use, as the causeFactory argument.
///
/// **Gated on the idiom being present**: only a record that DECLARES a `forXValues` factory is
/// checked. The census falsified the ungated form loudly — 320 monorepo hits, nearly all the
/// pre-idiom smart-constructor pattern (`new InvalidRequest(...)` inside the record's own static
/// factory), which is not drift FROM the idiom because the idiom is not there yet. A record
/// without a factory is the pilot migration's business, not this rule's.
public class CstCauseDirectConstructionRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-CAUSE-08";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    private static final Pattern FACTORY_DECL = Pattern.compile("\\bfor(?:OneValue|TwoValues|ThreeValues)\\s*\\(");

    /// The idiom-presence gate: the record's own (comment- and string-masked) body declares a
    /// `forXValues` factory.
    private boolean declaresFactory(Cursor record) {
        return FACTORY_DECL.matcher(MapperSafety.blankNonCode(org.pragmatica.jbct.parser.CstNodes.text(record)))
                           .find();
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root)) || CauseHierarchies.sanctionedLibraryPackage(packageName(root))) {
            return Stream.empty();
        }

        var causeNames = CauseHierarchies.causeInterfaceNames(root);
        var masked = MapperSafety.blankNonCode(source);
        var diagnostics = Stream.<Diagnostic>builder();

        for (var record : CauseHierarchies.causeRecords(root, causeNames)) {
            var name = DeclSupport.declName(record);

            if (name.isEmpty() || !declaresFactory(record)) {
                continue;
            }

            var instantiation = Pattern.compile("\\bnew\\s+" + Pattern.quote(name) + "\\s*\\(")
                                       .matcher(masked);

            while (instantiation.find()) {
                diagnostics.add(Diagnostic.diagnostic(RULE_ID,
                                                      ctx.severityFor(RULE_ID),
                                                      ctx.fileName(),
                                                      CauseHierarchies.lineAt(source, instantiation.start()),
                                                      CauseHierarchies.columnAt(source, instantiation.start()),
                                                      "Direct construction of cause record '" + name
                                                     + "'; construct through its FACTORY",
                                                      "A hand-passed message can drift from the declared template; the "
                                                     + "factory is the only path on which template, values and components "
                                                     + "stay in agreement at runtime (R1/R4)."));
            }
        }

        return diagnostics.build();
    }
}
