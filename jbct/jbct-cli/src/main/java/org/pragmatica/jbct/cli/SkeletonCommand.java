package org.pragmatica.jbct.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.pragmatica.jbct.cli.skeleton.Skeleton;
import org.pragmatica.jbct.cli.skeleton.SkeletonJson;
import org.pragmatica.jbct.cli.skeleton.SkeletonText;
import org.pragmatica.jbct.cli.skeleton.Skeletons;
import org.pragmatica.jbct.cli.skeleton.SourceNotParsed;
import org.pragmatica.jbct.cli.skeleton.SourceNotRead;
import org.pragmatica.jbct.config.FilesConfig;
import org.pragmatica.jbct.shared.FileCollector;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;


/// Prints the skeleton of Java source files: every type and member with the exact range it occupies, so that a
/// reader (a person, a script, a coding agent) can open the one method it needs instead of reading the whole file.
///
/// Read-only and built on the same parse tree `format` and `lint` use. One line per declaration, indented by nesting:
///
/// `<kind> <name><signature> <startRow>:<startCol>-<endRow>:<endCol>[ doc@<row>][ @Annotation ...]`
///
/// **Coordinates.** Rows and columns are 1-based; the END IS INCLUSIVE, `endRow:endCol` is the closing brace or the
/// semicolon itself. The range runs from the first annotation or modifier of the declaration to its end. A doc
/// comment is not inside the range: its first row is given as `doc@<row>`. Anonymous classes, local classes and
/// lambdas are not listed; they lie inside the range of the method that holds them.
///
/// **A file that does not parse** gets its error and position on stderr and no skeleton, never a partial one; the
/// other files still print and the exit code is 2, as for the other commands that could not analyse a file. A file
/// that cannot be read gets one line, `<path>: <reason>`. With `--json` the output is always one valid JSON array on
/// stdout: a failed file is an entry with an `error` object, and no Java files at all is `[]`.
@Command(name = "skeleton", description = "Print the types and members of Java files with exact ranges: rows and columns are 1-based, the end is inclusive (the closing brace or semicolon)", footerHeading = "%nNotes:%n", footer = {"  Columns count UTF-16 code units (a tab is 1, an emoji is 2); a leading BOM is one column on row 1.", "  doc@<row> is the first row of the NEAREST preceding doc comment.", "  Legacy array forms (int f()[], int a[]) are reported as written before the name: the brackets after it are not in the signature.", "  Annotation arguments are not printed.", "  Exit codes: 0 every file printed; 2 no Java files found, or a file could not be read or parsed (its error goes to stderr, the other files still print)."}, mixinStandardHelpOptions = true)
public class SkeletonCommand implements Callable<Integer> {
    static final int FAILURE = 2;
    private static final FilesConfig EVERYTHING = new FilesConfig(0L, List.of());

    @Parameters(paramLabel = "<file>", description = "Java files, or directories searched recursively", arity = "1..*")
    List<Path> paths;

    @picocli.CommandLine.Option(names = {"--json"}, description = "Print JSON: an array with one {file, declarations} object per file, {file, error} for a file that failed; [] when no Java files are found")
    boolean json;

    @picocli.CommandLine.Option(names = {"--fields"}, description = "Also list fields and enum constants")
    boolean fields;

    @Override
    public Integer call() {
        var files = FileCollector.collectJavaFiles(paths, EVERYTHING, System.err::println);

        return files.isEmpty()
               ? noFiles()
               : print(files.stream().map(this::outcome).toList());
    }

    private Integer noFiles() {
        System.err.println("No Java files found");
        System.out.print(json
                         ? SkeletonJson.document(List.of())
                         : "");

        return FAILURE;
    }

    private Outcome outcome(Path file) {
        return Outcome.outcome(file,
                               read(file).flatMap(content -> Skeletons.skeleton(content, fields)));
    }

    private static Result<String> read(Path file) {
        return Result.lift(SourceNotRead::sourceNotRead, () -> Files.readString(file));
    }

    private Integer print(List<Outcome> outcomes) {
        outcomes.forEach(outcome -> outcome.result()
                                           .onFailure(cause -> System.err.println(failureLine(outcome.file(),
                                                                                              cause))));
        System.out.print(json
                         ? SkeletonJson.document(outcomes.stream().map(Outcome::asJson).toList())
                         : outcomes.stream()
                                   .map(outcome -> outcome.asText(outcomes.size() > 1))
                                   .collect(Collectors.joining()));

        return outcomes.stream()
                       .allMatch(outcome -> outcome.result()
                                                   .isSuccess())
               ? 0
               : FAILURE;
    }

    /// `<path>:<row>:<column>: <detail>` for a source that does not parse, `<path>: <reason>` for one that cannot be read.
    private static String failureLine(Path file, Cause cause) {
        return switch (cause) {
            case SourceNotParsed _ -> file + ":" + cause.message();
            default -> file + ": " + cause.message();
        };
    }

    /// What became of one file: its skeleton or the reason it has none.
    private record Outcome(Path file, Result<Skeleton> result) {
        static Outcome outcome(Path file, Result<Skeleton> result) {
            return new Outcome(file, result);
        }

        String asJson() {
            return result.fold(cause -> SkeletonJson.failure(file.toString(), cause),
                               skeleton -> SkeletonJson.file(file.toString(), skeleton));
        }

        String asText(boolean withHeader) {
            return result.map(skeleton -> header(withHeader) + SkeletonText.render(skeleton))
                         .or("");
        }

        private String header(boolean withHeader) {
            return withHeader
                   ? file + "\n"
                   : "";
        }
    }
}
