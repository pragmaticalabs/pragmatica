package org.pragmatica.jbct.lint.cst.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.cst.filetype.FileType;
import org.pragmatica.jbct.lint.cst.filetype.FileTypeClassifier;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.LineIndex;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-ASYNC-01: a `Promise` callback (`onSuccess`/`onFailure`/`onResult` and their `*Run`/`*Async` forms) that writes shared
/// state.
///
/// `Promise.on*` callbacks are events: each is submitted to its own virtual thread when the promise resolves, concurrently with
/// the promise's dependents (the awaiting caller, the next `map`/`flatMap`), in no order relative to them. A callback that sets
/// a flag, fills a map or bumps a counter therefore races with the very code that runs next, and a reader that follows the
/// promise can observe the state before the write. `withSuccess`/`withFailure`/`withResult` run in chain order, before any
/// dependent sees the value; so does a plain chain step. (Measured on core 1.0.0-rc4: after a promise resolved from another
/// thread, the effect was still missing at `await()` in 106-172 of 200 trials for every `on*` form, and in 0 of 200 for the
/// `with*` forms.) `Result.on*` runs inline and is not affected, so it is never reported.
///
/// **What is reported.** A callback on a receiver this file proves to be a `Promise` (see [PromiseCarrier]) whose lambda, or a
/// same-file method it calls or references, writes shared state (see [SharedWrites]): an assignment to a field or captured
/// variable, a mutating call on one (`put`/`add`/`remove`/`clear`/`set`/`incrementAndGet`/`compareAndSet`/...), or an atomic or
/// flag set. A callback that only logs, schedules, or resolves another promise is never reported.
///
/// **The fix** is `withSuccess`/`withFailure`/`withResult`, or a plain chain step, when anything after the promise can observe
/// the write. When the effect is deliberately detached — nothing downstream reads it — keep `on*` and record why in a comment on
/// the statement: `// detached: <reason>` (a reason is required; the bare word does not suppress). `@SuppressWarnings` on the
/// enclosing declaration works as for every rule, but carries no reason.
///
/// **Severity: WARNING while the existing sites are converted** (the census counted 162 race-likely sites at rc5); the rule is
/// an ERROR candidate once they are gone.
///
/// **Limits, stated because the rule cannot see types.** It reports a site only when it can prove the receiver is a `Promise`
/// and can see the write, so it under-reports: a receiver typed in another file and a write made by a method in another file are
/// missed. It does not over-report `Result` (the carrier is `UNKNOWN` unless proven). Catalog verbs are matched by name, so a
/// non-mutating method that shares one (`remove` on a private copy) can be a false positive; a write that is deliberately
/// harmless (a health-score heuristic, a metric) is still a write and needs the `detached` comment.
public class CstPromiseCallbackWriteRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-ASYNC-01";

    private static final Pattern CALLBACK = Pattern.compile("\\.\\s*(on(?:Success|Failure|Result)(?:Run)?(?:Async)?)\\s*\\(");
    private static final Pattern DETACHED_TAG = Pattern.compile("\\bdetached\\s*[:\\-–—]\\s*\\S[^\\n]{2,}",
                                                                Pattern.CASE_INSENSITIVE);

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root)) || FileTypeClassifier.classify(root) == FileType.TEST_CLASS) {
            return Stream.empty();
        }

        var masked = MapperSafety.blankNonCode(source);

        if (!masked.contains("Promise")) {
            return Stream.empty();
        }

        return findings(masked, source, ctx).stream();
    }

    private List<Diagnostic> findings(String masked, String source, LintContext ctx) {
        var facts = PromiseCarrier.facts(masked);
        var lines = new LineIndex(source);
        var comments = MapperSafety.commentsOnly(source);
        var result = new ArrayList<Diagnostic>();
        var matcher = CALLBACK.matcher(masked);

        while (matcher.find()) {
            var open = matcher.end() - 1;
            var close = PromiseCarrier.closeOf(masked, open);

            if (close < 0) {
                continue;
            }

            var dot = matcher.start();
            var start = PromiseCarrier.chainStart(masked, dot);

            if (isPromise(masked, facts, start, dot, close)) {
                SharedWrites.inCallback(masked.substring(open + 1, close), facts, masked)
                            .filter(_ -> !isDetached(comments, lines, start, close))
                            .onPresent(write -> result.add(diagnostic(matcher.group(1), matcher.start(1), write, lines, ctx)));
            }
        }

        return result;
    }

    private boolean isPromise(String masked, PromiseCarrier.FileFacts facts, int start, int dot, int close) {
        var chain = PromiseCarrier.chain(masked.substring(start, dot));

        return switch (PromiseCarrier.kindOf(chain.text(), facts)) {
            case PROMISE -> true;
            case RESULT -> false;
            case UNKNOWN -> returnsPromise(masked, facts, chain.isReturn(), start, close);
        };
    }

    /// `return <chain>.onX(...);` in a method declared to return `Promise<...>`: `onX` returns the carrier it is called on.
    private boolean returnsPromise(String masked, PromiseCarrier.FileFacts facts, boolean isReturn, int start, int close) {
        return isReturn && endsStatement(masked, close) && "Promise".equals(PromiseCarrier.enclosingReturnType(masked, start, facts));
    }

    private static boolean endsStatement(String masked, int close) {
        var i = close + 1;

        while (i < masked.length() && Character.isWhitespace(masked.charAt(i))) {
            i++;
        }

        return i < masked.length() && masked.charAt(i) == ';';
    }

    /// A `// detached: <reason>` comment inside the statement, or in the comment block directly above it.
    private boolean isDetached(String comments, LineIndex lines, int start, int end) {
        var firstLine = lines.lineAt(start);
        var lastLine = lines.lineAt(end);
        var text = linesOf(comments, firstLine, lastLine);

        return DETACHED_TAG.matcher(text).find() || DETACHED_TAG.matcher(commentBlockAbove(comments, firstLine)).find();
    }

    private static String commentBlockAbove(String comments, int firstLine) {
        var all = comments.split("\n", -1);
        var block = new StringBuilder();

        for (var i = firstLine - 2; i >= 0 && !all[i].isBlank(); i--) {
            block.insert(0, all[i] + "\n");
        }

        return block.toString();
    }

    private static String linesOf(String comments, int first, int last) {
        var all = comments.split("\n", -1);
        var text = new StringBuilder();

        for (var i = first - 1; i < last && i < all.length; i++) {
            text.append(all[i])
                .append('\n');
        }

        return text.toString();
    }

    private Diagnostic diagnostic(String callback, int offset, SharedWrites.Write write, LineIndex lines, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     lines.lineAt(offset),
                                     lines.columnAt(offset),
                                     "Promise." + callback + " callback " + write.description() + describeVia(write)
                                     + ": it runs on its own thread, so a dependent can run before the write lands",
                                     "Promise on*/on*Run/on*Async callbacks run on a separate virtual thread, concurrently with the "
                                     + "promise's dependents (the awaiting caller, the next map/flatMap), in no order relative to them. "
                                     + "If anything after this promise can observe the state, use withSuccess/withFailure/withResult "
                                     + "(chain order, before any dependent) or a plain chain step. If the effect is deliberately "
                                     + "detached, keep the callback and record why in a comment on the statement: "
                                     + "'// detached: <reason>'.");
    }

    private static String describeVia(SharedWrites.Write write) {
        return write.via()
                    .isEmpty()
               ? ""
               : " (via " + String.join(" -> ", write.via()) + ")";
    }
}
