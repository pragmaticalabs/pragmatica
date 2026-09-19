package org.pragmatica.jbct.lint.cst.rules;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.cst.filetype.FileType;
import org.pragmatica.jbct.lint.cst.filetype.FileTypeClassifier;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-EX-03: `catch` clause outside a marked JDK boundary.
///
/// Catching converts an exception into something else, and done anywhere but the JDK call that throws it,
/// that conversion masks real defects (#1247: a boundary catching `IndexOutOfBoundsException` alongside
/// the closed-arena `IllegalStateException` reported an index-arithmetic bug as "buffer closed"). The one
/// legitimate site is a leaf method wrapping a JDK API that throws, and it must say so: annotate it
/// `@SuppressWarnings("JBCT-EX-03")`. Any other `catch` is flagged, one diagnostic per clause.
///
/// `try`/`finally` and try-with-resources without a `catch` convert nothing and are not flagged.
/// JBCT-EX-01 flags `throw`/`throws` but never saw `catch` at all, so its suppression does not exempt
/// a `catch` — the two constructs are marked separately. Test classes are exempt, as for JBCT-PAT-03.
public class CstTryCatchRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-EX-03";
    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("\\b([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\(");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        if (FileTypeClassifier.classify(root) == FileType.TEST_CLASS) {
            return Stream.empty();
        }

        return findAll(root, RuleKind.CATCH).stream()
                      .map(clause -> createDiagnostic(root, clause, ctx));
    }

    private Diagnostic createDiagnostic(Cursor root, Cursor clause, LintContext ctx) {
        var methodName = enclosingMethodMember(root, clause).map(member -> extractMethodName(memberDeclText(member)))
                                              .or("(unknown)");

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(clause),
                                     startColumn(clause),
                                     "catch clause in method '" + methodName + "' outside a marked JDK boundary",
                                     "Convert exceptions at the adapter boundary with Result.lift/Promise.lift. "
                                    + "If this method IS a JDK-boundary leaf (native allocation, Arena access, a JDK API "
                                    + "that throws), annotate it @SuppressWarnings(\"JBCT-EX-03\") and catch only "
                                    + "the exception that API documents.")
                         .withExample("""
            // Before: catch in business code
            Result<Config> load(Path path) {
                try {
                    return success(parse(Files.readString(path)));
                } catch (IOException e) {
                    return ConfigError.UNREADABLE.result();
                }
            }

            // After: lift at the boundary
            Result<Config> load(Path path) {
                return Result.lift(ConfigError::unreadable, () -> Files.readString(path))
                             .map(this::parse);
            }

            // A genuine JDK-boundary leaf, marked:
            @SuppressWarnings("JBCT-EX-03")
            private Result<MemorySegment> allocate(long bytes) {
                try {
                    return success(arena.allocate(bytes));
                } catch (OutOfMemoryError _) {
                    return MEMORY_EXCEEDED.result();
                }
            }
            """);
    }

    private static String extractMethodName(String memberText) {
        var matcher = METHOD_NAME_PATTERN.matcher(memberText);

        return matcher.find()
               ? matcher.group(1)
               : "(unknown)";
    }
}
