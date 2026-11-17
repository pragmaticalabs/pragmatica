package org.pragmatica.jbct.lint.cst.rules;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.TechnicalWords;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.cst.LintRule;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-NAM-06: a type name carries a technology or pattern word.
///
/// A name says what the code does for the business, never which technology or pattern implements it: a step is
/// `LoadSeat`, and the technology shows only in parameter types.
///
/// Matching: a word matches when it equals one whole camel-case token of the type name, ignoring case; the diagnostic
/// names each matched token and the word it equals. The word list is [org.pragmatica.jbct.lint.TechnicalWords]
/// (`LintConfig#technicalWords()`, adjusted by `[lint.naming] add / remove`); a type name is split at its camel-case
/// boundaries and flagged when any part is a word, so `JooqSeatRepository` is flagged for `Jooq` and `Repository`
/// while `Restaurant` is not flagged for `Rest`. Every class, interface, record and enum declaration is judged,
/// nested ones included. INFO until a corpus run calibrates the list (Q16 keeps it at INFO).
///
/// FP surface: a business noun that happens to be a word (`Client` in a CRM, `Manager` in an HR domain); the answer is
/// `[lint.naming] remove` or a suppression. FN surface: a technology word the list does not carry.
public class CstTechnicalTypeNameRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-NAM-06";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Set<JbctMode> modes() {
        return LintRule.BUSINESS_ONLY;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        var words = ctx.config().technicalWords().typeWords();

        return Stream.of(findAllClasses(root),
                         findAllInterfaces(root),
                         findAllRecords(root),
                         findAllEnums(root))
                     .flatMap(List::stream)
                     .flatMap(decl -> diagnostic(decl, words, ctx));
    }

    private Stream<Diagnostic> diagnostic(Cursor decl, Set<String> words, LintContext ctx) {
        var name = DeclSupport.declName(decl);
        var flagged = flaggedParts(name, words);

        return flagged.isEmpty()
               ? Stream.empty()
               : Stream.of(createDiagnostic(decl, name, flagged, ctx));
    }

    private List<String> flaggedParts(String name, Set<String> words) {
        return TechnicalWords.tokens(name)
                             .stream()
                             .filter(part -> words.contains(part.toLowerCase(Locale.ROOT)))
                             .toList();
    }

    private String matches(List<String> flagged) {
        return flagged.stream()
                      .map(token -> "token '" + token + "' matches word '" + token.toLowerCase(Locale.ROOT) + "'")
                      .collect(Collectors.joining(", "));
    }

    private Diagnostic createDiagnostic(Cursor decl, String name, List<String> flagged, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(decl),
                                     startColumn(decl),
                                     "Type '" + name + "' names a technology or pattern: " + matches(flagged),
                                     "A name says what the code does for the business, not which technology or pattern "
                                    + "implements it. A word matches one whole camel-case token of the name, ignoring "
                                    + "case. Rename the type after its business operation or noun; the "
                                    + "technology shows in parameter types. Configure the list with "
                                    + "[lint.naming] add / remove.");
    }
}
