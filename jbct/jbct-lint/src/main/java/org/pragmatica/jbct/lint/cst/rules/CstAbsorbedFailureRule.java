package org.pragmatica.jbct.lint.cst.rules;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-REC-01: a failure absorbed without a recorded reason.
///
/// `.recover(...)` converts a failure into a value, ending the failure's journey. That is often
/// exactly right — a notification that fails must not fail the purchase it notifies about — but it
/// is always a decision, and an undocumented one is indistinguishable from an accident. This rule
/// does not object to absorption; it objects to silent absorption.
///
/// **It deliberately does NOT flag `.recover(...)` itself.** Measured first: the ticketing corpus has
/// eight absorbing sites in `src/main` and every one is deliberate and documented. A rule that fires
/// on those is a noise generator, and shipping it would train people to ignore it.
///
/// **What counts as a justification** is the recovery-triple vocabulary the corpus already uses —
/// `BER` (Backward Error Recovery: compensate by an inverse action), `FER` (Forward Error Recovery:
/// degrade and continue), `design-out` — found in a COMMENT, never in a string literal. Checked first
/// against the absorbing method's own comments and the comment block directly above it, then against
/// the file.
///
/// **Why a file-level fallback**, rather than requiring the tag on the method: the corpus documents
/// absorption in three different places, and only one of them is the method itself. `BuyTicket`'s
/// `voidReceipt` carries no comment — its justification sits on its sole caller, `voidAuthorization`.
/// The projection slices (`ProjectPrice`, `ProjectSeatSold`, `ProjectSeatReleased`) document theirs
/// in a companion `*Log.java` annotation, which a single-file rule cannot read at all. Requiring the
/// tag on the method would flag all four, so the fallback is what makes the rule usable rather than
/// a concession.
///
/// The fallback is NOT "the file mentions a tag once", which one comment would use to excuse any
/// number of absorptions. It is proportional: a file needs at least as many justifications as it has
/// absorptions. Adding a `.recover(...)` without adding a reason is exactly the regression worth
/// catching, and it is caught even in a file that already documents others.
///
/// **Scope.** A swallowing `.onFailure(...)` is deliberately not this rule's business: `onFailure`
/// observes a failure without absorbing it, and it only swallows when the whole expression is
/// discarded — which is already JBCT-RET-07 (discarded `Result`/`Promise`/`Option`). Two rules
/// firing on one site would double-count it.
///
/// Note this pulls the opposite way from JBCT-RET-05: a `.recover(...)`-terminated method still has
/// a real failure channel upstream of the absorption, so RET-05's infallibility detection must not
/// suppress this rule, and does not — they inspect different things.
public class CstAbsorbedFailureRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-REC-01";

    /// The absorbing combinator. `recover` is the one that turns a failure into a value.
    private static final Pattern ABSORPTION = Pattern.compile("\\.recover\\s*\\(");

    /// The recovery-triple vocabulary, as the corpus actually spells it. `best-effort` is still
    /// accepted because the corpus writes it that way more often than it writes the acronym, but it
    /// names degradation, which is FER, not BER (Backward Error Recovery); the message says so. All
    /// the others contain a hyphen or are standalone uppercase words, so none collides with an identifier.
    private static final Pattern JUSTIFICATION = Pattern.compile("\\bBER\\b|\\bFER\\b|design-out|best-effort",
                                                                 Pattern.CASE_INSENSITIVE);

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        if (countMatches(JUSTIFICATION, MapperSafety.commentsOnly(source)) >= countMatches(ABSORPTION, source)) {
            return Stream.empty();
        }

        return findAllMethods(root).stream()
                      .filter(method -> ABSORPTION.matcher(memberDeclText(method)).find())
                      .filter(this::isInnermostAbsorber)
                      .filter(method -> !isJustified(method))
                      .map(method -> createDiagnostic(method, ctx));
    }

    /// A reason recorded in a comment inside the method, or in the comment block directly above it.
    private boolean isJustified(Cursor method) {
        return JUSTIFICATION.matcher(MapperSafety.commentsOnly(memberDeclText(method))).find()
               || JUSTIFICATION.matcher(precedingComment(method)).find();
    }

    /// The contiguous comment/annotation lines directly above the method's declaration line (a blank line ends the block),
    /// as comment text only. `memberDeclText` starts at the declaration, so a comment above it was never read.
    private static String precedingComment(Cursor method) {
        var input = method.cst().input();
        // up to, not including, the newline that ends the previous line: a trailing separator would read as a blank line
        var lines = input.substring(0, Math.max(0, lineStart(input, anchorOf(method).spanStart()) - 1)).split("\n", -1);
        var block = new StringBuilder();

        // unmatched ')' seen below: lines of a multi-line annotation's argument list (`@SuppressWarnings({"a",\n "b"})`) sit between
        // the comment and the method, and are not comment or annotation lines on their own
        var open = 0;

        for (var i = lines.length - 1; i >= 0; i--) {
            var line = lines[i].trim();
            var commentLine = isCommentOrAnnotationLine(line);

            if (line.isEmpty() || !commentLine && open == 0 && !line.endsWith(")")) {
                break;
            }

            if (!line.startsWith("//") && !line.startsWith("/*") && !line.startsWith("*")) {
                open = Math.max(0, open + count(line, ')') - count(line, '('));
            }

            // the scan walks upward, so prepend: a multi-line /* */ or Javadoc must be reassembled in source order
            block.insert(0, line + "\n");
        }

        return MapperSafety.commentsOnly(block.toString());
    }

    private static int count(String text, char c) {
        return (int) text.chars()
                         .filter(ch -> ch == c)
                         .count();
    }

    private static int lineStart(String input, int offset) {
        return input.lastIndexOf('\n', offset - 1) + 1;
    }

    private static boolean isCommentOrAnnotationLine(String line) {
        return line.startsWith("//") || line.startsWith("/*") || line.startsWith("*") || line.startsWith("@");
    }

    /// Attribute an absorption to the method that actually performs it, not to every method whose
    /// text encloses it. A JBCT slice puts its implementation record inside its own factory method,
    /// so the factory's member text contains the record's `.recover(...)` too — reporting both means
    /// two diagnostics for one decision, at a line the reader cannot act on.
    private boolean isInnermostAbsorber(Cursor method) {
        return findAllMethods(method).stream()
                      .noneMatch(nested -> nested.idx() != method.idx()
                                          && ABSORPTION.matcher(memberDeclText(nested)).find());
    }

    private static int countMatches(Pattern pattern, String text) {
        var matcher = pattern.matcher(text);
        var count = 0;

        while (matcher.find()) {
            count++;
        }

        return count;
    }

    private Diagnostic createDiagnostic(Cursor method, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(anchorOf(method)),
                                     startColumn(anchorOf(method)),
                                     "Failure absorbed by recover(...) with no recorded reason",
                                     "Absorbing a failure is a decision, and an undocumented one cannot be told apart "
                                    + "from an accident. Record which of the recovery triple applies — BER (Backward Error "
                                    + "Recovery: compensate by an inverse action), FER (Forward Error Recovery: degrade and "
                                    + "continue), or design-out (change the model so the failure cannot arise) — and what "
                                    + "guarantee that buys, in a comment on the absorbing method.");
    }
}
