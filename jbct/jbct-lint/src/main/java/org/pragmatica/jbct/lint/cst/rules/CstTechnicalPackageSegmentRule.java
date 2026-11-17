package org.pragmatica.jbct.lint.cst.rules;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.cst.LintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-NAM-07: a package segment carries a technology, pattern or retired layer word.
///
/// A package is named after the business capability it holds, never after the technology or the layer that
/// implements it. A segment is flagged when it equals a word of [org.pragmatica.jbct.lint.TechnicalWords#packageWords()]
/// (the type-name words plus `impl`-style and retired layer words such as `usecase`, `domain`, `adapter`,
/// `persistence`), adjusted by `[lint.naming] add / remove`.
///
/// Matching: a word matches when it equals one whole dot-separated segment of the package name, ignoring case, so
/// `restaurant` is not flagged for `rest`; the diagnostic names each matched segment and the word it equals.
///
/// One diagnostic per file, on the package declaration, listing every flagged segment. A package declaration sits
/// outside every declaration a `@SuppressWarnings` can scope, so the rule is silenced through `[lint.naming] remove`,
/// `[lint] excludePackages` or `[lint.rules]`, not through an annotation. INFO until a corpus run calibrates the list
/// (Q16 keeps it at INFO).
public class CstTechnicalPackageSegmentRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-NAM-07";

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
        var packageName = packageName(root);

        return ctx.shouldLint(packageName)
               ? findFirst(root, RuleKind.PACKAGE_DECL).stream()
                          .flatMap(decl -> diagnostic(decl, packageName, ctx))
               : Stream.empty();
    }

    private Stream<Diagnostic> diagnostic(Cursor decl, String packageName, LintContext ctx) {
        var flagged = flaggedSegments(packageName,
                                      ctx.config().technicalWords().packageWords());

        return flagged.isEmpty()
               ? Stream.empty()
               : Stream.of(createDiagnostic(decl, packageName, flagged, ctx));
    }

    private List<String> flaggedSegments(String packageName, Set<String> words) {
        return Arrays.stream(packageName.split("\\."))
                     .filter(segment -> words.contains(segment.toLowerCase(Locale.ROOT)))
                     .toList();
    }

    private String matches(List<String> flagged) {
        return flagged.stream()
                      .map(segment -> "segment '" + segment
                                     + "' matches word '" + segment.toLowerCase(Locale.ROOT)
                                     + "'")
                      .collect(Collectors.joining(", "));
    }

    private Diagnostic createDiagnostic(Cursor decl, String packageName, List<String> flagged, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(decl),
                                     startColumn(decl),
                                     "Package '" + packageName
                                    + "' names a technology, pattern or layer: " + matches(flagged),
                                     "A package is named after the business capability it holds, not the technology "
                                    + "or layer that implements it. A word matches one whole segment of the package, "
                                    + "ignoring case. Rename the segment after the capability. "
                                    + "No annotation suppresses a package segment: keep one by listing it in "
                                    + "[lint.naming] remove in the module's jbct.toml.");
    }
}
