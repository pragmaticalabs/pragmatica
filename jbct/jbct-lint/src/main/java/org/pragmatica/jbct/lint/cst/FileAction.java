package org.pragmatica.jbct.lint.cst;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.format.JbctFormatter;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.layer.LayerCoverage;
import org.pragmatica.jbct.lint.cst.RootResolver.ResolvedRoot;
import org.pragmatica.jbct.lint.cst.RunOutcome.FileResult;
import org.pragmatica.jbct.lint.cst.RunOutcome.FormatAction;
import org.pragmatica.jbct.lint.cst.RunOutcome.FormatOutcome;
import org.pragmatica.jbct.lint.cst.RunOutcome.LintOutcome;
import org.pragmatica.jbct.lint.cst.RunOutcome.Linted;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.jbct.lint.cst.RunOutcome.FileResult.fileResult;
import static org.pragmatica.jbct.lint.cst.RunOutcome.Linted.linted;
import static org.pragmatica.jbct.lint.cst.RunOutcome.NotAsked.notAsked;
import static org.pragmatica.jbct.lint.cst.RunOutcome.ParseFailed.parseFailed;
import static org.pragmatica.jbct.lint.cst.RunOutcome.ReadFailed.readFailed;
import static org.pragmatica.jbct.lint.cst.RunOutcome.Refused.refused;
import static org.pragmatica.jbct.lint.cst.RunOutcome.Rewritten.rewritten;
import static org.pragmatica.jbct.lint.cst.RunOutcome.SkippedByMode.skippedByMode;
import static org.pragmatica.jbct.lint.cst.RunOutcome.Unchanged.unchanged;
import static org.pragmatica.jbct.lint.cst.RunOutcome.Withheld.withheld;
import static org.pragmatica.jbct.lint.cst.RunOutcome.WouldChange.wouldChange;
import static org.pragmatica.jbct.lint.cst.RunOutcome.WriteFailed.writeFailed;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Every per-file action of one root of a run (#2525), in one order: (1) per asked activity, the root's mode gives
/// `SkippedByMode` or "to do" without opening the file; (2) nothing to do: done; (3) one read; (4) one parse; (5) lint
/// on the tree; (6) format on the same tree, compared with the source; (7) the write, when the action is WRITE, the
/// content differs and the file is not withheld. A read or parse failure becomes the outcome of every "to do"
/// activity of the file. Each outcome is built from the value its action returned, after the action: `Rewritten`
/// means the content derived from the single read was written and the write returned success; it does not assert
/// that the file was unchanged in between (the write is `Files.writeString`, neither atomic nor compare-before-write).
/// After a successful parse the write can fail and the formatter's content guard can refuse the file (`Refused`, #3691: the
/// file is left untouched); lint of a parsed tree returns no failure value.
///
/// `Withheld` is recorded when the invocation withholds the format on lint errors (`process` with
/// `failBeforeFormat`) and the file's lint outcome holds a diagnostic of severity ERROR; the decision is taken after
/// the lint and the format is not run for that file, so such a file is never `Unchanged`.
///
/// The parser is a seam: the run hands every [FileAction] of an invocation the same one, and a test counts its calls.
record FileAction(ResolvedRoot root,
                  Activities activities,
                  CstLinter linter,
                  JbctFormatter formatter,
                  Function<String, Result<Cursor>> parser) {
    static FileAction fileAction(ResolvedRoot root, Activities activities, Function<String, Result<Cursor>> parser) {
        return new FileAction(root,
                              activities,
                              CstLinter.cstLinter(root.context()),
                              JbctFormatter.jbctFormatter(root.config().formatter()),
                              parser);
    }

    /// Perform the asked activities on one file of this root and record one outcome per activity.
    FileResult perform(Path file) {
        var lintBefore = lintBeforeOpening();
        var formatBefore = formatBeforeOpening();

        return Option.all(lintBefore, formatBefore)
                     .map((lint, format) -> fileResult(file,
                                                       root.path(),
                                                       lint,
                                                       format,
                                                       0,
                                                       ""))
                     .or(() -> opened(file, lintBefore, formatBefore));
    }

    /// The lint outcome decided before the file is opened, or none when the lint is to do. The criterion is the one
    /// the run uses everywhere: a mode that runs no rule skips the lint.
    private Option<LintOutcome> lintBeforeOpening() {
        return activities.lint()
               ? lintSkippedByMode()
               : some(notAsked());
    }

    private Option<LintOutcome> lintSkippedByMode() {
        return lintsFiles()
               ? none()
               : some(skippedByMode(mode()));
    }

    /// The format outcome decided before the file is opened, or none when the format is to do: every mode but
    /// `disabled` formats.
    private Option<FormatOutcome> formatBeforeOpening() {
        return activities.format() == FormatAction.NONE
               ? some(notAsked())
               : formatSkippedByMode();
    }

    private Option<FormatOutcome> formatSkippedByMode() {
        return mode() == JbctMode.DISABLED
               ? some(skippedByMode(mode()))
               : none();
    }

    private boolean lintsFiles() {
        return ! CstLinter.rulesFor(mode()).isEmpty();
    }

    private JbctMode mode() {
        return root.context()
                   .mode();
    }

    private FileResult opened(Path file, Option<LintOutcome> lintBefore, Option<FormatOutcome> formatBefore) {
        return SourceFile.sourceFile(file).fold(cause -> fileResult(file,
                                                                    root.path(),
                                                                    lintBefore.or(readFailed(cause.message())),
                                                                    formatBefore.or(readFailed(cause.message())),
                                                                    0,
                                                                    ""),
                                                source -> parsed(source, lintBefore, formatBefore));
    }

    private FileResult parsed(SourceFile source, Option<LintOutcome> lintBefore, Option<FormatOutcome> formatBefore) {
        return parser.apply(source.content())
                     .fold(cause -> fileResult(source.path(),
                                               root.path(),
                                               lintBefore.or(parseFailed(cause.message())),
                                               formatBefore.or(parseFailed(cause.message())),
                                               0,
                                               LayerCoverage.packageOf(source.content())),
                           tree -> acted(tree, source, lintBefore, formatBefore));
    }

    private FileResult acted(Cursor tree,
                             SourceFile source,
                             Option<LintOutcome> lintBefore,
                             Option<FormatOutcome> formatBefore) {
        var lint = lintBefore.or(() -> linted(linter.lintParsed(tree, source)));

        return fileResult(source.path(),
                          root.path(),
                          lint,
                          formatBefore.or(() -> formatted(tree, source, lint)),
                          source.nonBlankLines(),
                          LayerCoverage.packageOf(source.content()));
    }

    private FormatOutcome formatted(Cursor tree, SourceFile source, LintOutcome lint) {
        return withholds(lint)
               ? withheld()
               : formatter.formatParsed(tree, source)
                          .<FormatOutcome> fold(cause -> refused(cause.message()),
                                                formatted -> compared(source, formatted));
    }

    private boolean withholds(LintOutcome lint) {
        return activities.withholdFormatOnLintErrors()
               && lint instanceof Linted(var diagnostics)
               && hasError(diagnostics);
    }

    private static boolean hasError(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                          .anyMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR);
    }

    private FormatOutcome compared(SourceFile source, SourceFile formatted) {
        return formatted.content()
                        .equals(source.content())
               ? unchanged()
               : changed(formatted);
    }

    private FormatOutcome changed(SourceFile formatted) {
        return activities.format() == FormatAction.WRITE
               ? written(formatted)
               : wouldChange();
    }

    /// The write's own result decides the outcome: `Rewritten` only when it returned success.
    private FormatOutcome written(SourceFile formatted) {
        return formatted.write()
                        .<FormatOutcome> fold(cause -> writeFailed(cause.message()),
                                              _ -> rewritten());
    }
}
