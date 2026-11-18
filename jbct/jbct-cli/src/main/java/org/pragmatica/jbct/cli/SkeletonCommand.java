package org.pragmatica.jbct.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import org.pragmatica.jbct.cli.skeleton.SkeletonJson;
import org.pragmatica.jbct.cli.skeleton.SkeletonText;
import org.pragmatica.jbct.cli.skeleton.Skeletons;
import org.pragmatica.jbct.config.FilesConfig;
import org.pragmatica.jbct.shared.FileCollector;
import org.pragmatica.jbct.shared.SourceFile;
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
/// other files still print and the exit code is 2, as for the other commands that could not analyse a file.
@Command(name = "skeleton", description = "Print the types and members of Java files with exact ranges: rows and columns are 1-based, the end is inclusive (the closing brace or semicolon)", mixinStandardHelpOptions = true)
public class SkeletonCommand implements Callable<Integer> {
    static final int FAILURE = 2;
    private static final FilesConfig EVERYTHING = new FilesConfig(0L, List.of());

    @Parameters(paramLabel = "<file>", description = "Java files, or directories searched recursively", arity = "1..*")
    List<Path> paths;

    @picocli.CommandLine.Option(names = {"--json"}, description = "Print JSON: an array with one {file, declarations} object per file")
    boolean json;

    @picocli.CommandLine.Option(names = {"--fields"}, description = "Also list fields and enum constants")
    boolean fields;

    @Override
    public Integer call() {
        var files = FileCollector.collectJavaFiles(paths, EVERYTHING, System.err::println);

        return files.isEmpty()
               ? noFiles()
               : print(files,
                       files.stream().map(file -> skeletonOf(file, files.size() > 1)).toList());
    }

    private Integer noFiles() {
        System.err.println("No Java files found");

        return FAILURE;
    }

    private Result<String> skeletonOf(Path file, boolean withHeader) {
        return SourceFile.sourceFile(file)
                         .flatMap(source -> Skeletons.skeleton(source.content(),
                                                               fields))
                         .map(skeleton -> json
                                          ? SkeletonJson.file(file.toString(),
                                                              skeleton)
                                          : header(file, withHeader) + SkeletonText.render(skeleton))
                         .mapError(cause -> failure(file, cause));
    }

    private static String header(Path file, boolean withHeader) {
        return withHeader
               ? file + "\n"
               : "";
    }

    private static Cause failure(Path file, Cause cause) {
        return () -> file + ":" + cause.message();
    }

    private Integer print(List<Path> files, List<Result<String>> results) {
        var rendered = results.stream().flatMap(Result::stream).toList();

        results.forEach(result -> result.onFailure(cause -> System.err.println(cause.message())));
        System.out.print(json
                         ? SkeletonJson.document(rendered)
                         : String.join("", rendered));

        return rendered.size() == files.size()
               ? 0
               : FAILURE;
    }
}
