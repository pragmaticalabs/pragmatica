package org.pragmatica.jbct.lint.cst.rules;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.packageName;


/// JBCT-STAGE-01: stage-record conventions in growing-context pipelines.
///
/// A knowledge-gathering pipeline threads a container through stages; the previous-stage component
/// is named `request` uniformly, and once accessing accumulated state needs a `request().request()
/// .request()` chain of three-plus hops the container should be flattened to a named milestone
/// record. This rule performs the textual chain-depth check: three-plus chained `request()` calls
/// are flagged and the flattening suggested.
///
/// The scan runs over a string/comment-masked view ([MapperSafety#blankNonCode]) so a `request()`
/// mentioned in a literal or comment never triggers. FP surface: an unrelated fluent API whose
/// method also happens to be named `request` and is legitimately chained three-plus times.
public class CstStageRecordRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-STAGE-01";

    /// Three or more chained `request()` calls: one `request()` followed by two or more `.request()`.
    private static final Pattern REQUEST_CHAIN = Pattern.compile("request\\s*\\(\\s*\\)(?:\\s*\\.\\s*request\\s*\\(\\s*\\)){2,}");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        var masked = MapperSafety.blankNonCode(source);
        var matcher = REQUEST_CHAIN.matcher(masked);
        var diagnostics = Stream.<Diagnostic> builder();

        while (matcher.find()) {
            diagnostics.add(createDiagnostic(source, matcher.start(), ctx));
        }

        return diagnostics.build();
    }

    private Diagnostic createDiagnostic(String source, int start, LintContext ctx) {
        var lineStart = source.lastIndexOf('\n', start - 1) + 1;

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     MapperSafety.newlinesBefore(source, start) + 1,
                                     start - lineStart + 1,
                                     "Deep 'request().request().request()' stage chain — flatten to a named milestone record",
                                     "A three-plus-hop previous-stage chain is the signal to flatten the growing context "
                                    + "into a named milestone record other code can name, instead of reaching back through "
                                    + "stage containers.");
    }
}
