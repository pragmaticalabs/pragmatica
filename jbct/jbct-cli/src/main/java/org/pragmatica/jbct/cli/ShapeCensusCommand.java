package org.pragmatica.jbct.cli;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.jbct.config.ConfigLoader;
import org.pragmatica.jbct.lint.cst.shape.MethodShape;
import org.pragmatica.jbct.lint.cst.shape.ShapeCensus;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.shared.FileCollector;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Option;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;


/// Reports the distribution of JBCT structural patterns across a source tree.
///
/// Every concrete method is classified into exactly one of LEAF / SEQUENCER / FORK_JOIN / CONDITION /
/// ITERATION / ASPECT, or one of two residual verdicts (MIXED, UNCLASSIFIED), and the command prints
/// the histogram plus the residual rate. No analysis logic is new here — [ShapeCensus] and
/// `MethodShapeClassifier` already do the work; this exposes them.
///
/// **Why a command and not a lint rule.** A shape is not a violation, so there is nothing to
/// diagnose per method — the useful artifact is the distribution. It is also the point of shipping
/// it: every structural measurement behind these patterns has been taken on corpora written by one
/// author, and a reproducible profile someone can run on their own code is the only route to
/// evidence from elsewhere.
///
/// **Parse failures are counted, not swallowed.** [ShapeCensus#census(java.util.Collection)] parses
/// internally and contributes nothing for a file that fails, which would let the denominator shrink
/// invisibly — acceptable in a test sweep over a corpus known to parse, wrong for an instrument
/// pointed at a stranger's code, where an unparseable file is exactly the thing worth knowing about.
/// So this parses per file and folds the per-root reports itself, reporting `filesParsed` and
/// `parseErrors` alongside the histogram.
@Command(name = "shape-census",
         description = "Report the distribution of JBCT structural patterns across a source tree",
         mixinStandardHelpOptions = true)
public class ShapeCensusCommand implements Callable<Integer> {
    static final int USAGE_ERROR = 2;
    static final List<String> SUPPORTED_FORMATS = List.of("text", "json");

    @Parameters(paramLabel = "<path>", description = "Files or directories to measure", arity = "1..*")
    List<Path> paths;

    @picocli.CommandLine.Option(names = {"--format", "-f"}, description = "Output format: text, json", defaultValue = "text")
    String format;

    @picocli.CommandLine.Option(names = {"--config"}, description = "Path to configuration file")
    Path configPath;

    @Override
    public Integer call() {
        if (!SUPPORTED_FORMATS.contains(format.toLowerCase(Locale.ROOT))) {
            System.err.println("Unknown format '" + format + "'; supported formats: " + String.join(", ", SUPPORTED_FORMATS));

            return USAGE_ERROR;
        }

        var config = ConfigLoader.load(Option.option(configPath), Option.none());
        var files = FileCollector.collectJavaFiles(paths, config.files(), System.err::println);

        if (files.isEmpty()) {
            System.err.println("No Java files found");

            return 1;
        }

        var parser = new Java25Parser();
        var histogram = new EnumMap<MethodShape, Integer>(MethodShape.class);
        var parsed = new AtomicInteger(0);
        var parseErrors = new AtomicInteger(0);

        for (var file : files) {
            SourceFile.sourceFile(file)
                      .flatMap(source -> parser.parse(source.content()))
                      .onSuccess(root -> {
                          parsed.incrementAndGet();
                          merge(histogram, ShapeCensus.census(root));
                      })
                      .onFailure(cause -> {
                          parseErrors.incrementAndGet();
                          System.err.println("  ✗ " + file + ": " + cause.message());
                      });
        }

        var total = histogram.values()
                             .stream()
                             .mapToInt(Integer::intValue)
                             .sum();
        var report = new ShapeCensus.CensusReport(java.util.Map.copyOf(histogram), total);

        if ("json".equalsIgnoreCase(format)) {
            printJson(report, parsed.get(), parseErrors.get());
        } else {
            printText(report, parsed.get(), parseErrors.get());
        }

        return 0;
    }

    private static void merge(EnumMap<MethodShape, Integer> histogram, ShapeCensus.CensusReport report) {
        report.histogram()
              .forEach((shape, count) -> histogram.merge(shape, count, Integer::sum));
    }

    private void printText(ShapeCensus.CensusReport report, int parsed, int parseErrors) {
        System.out.print(report.render());
        System.out.printf("  files parsed: %d%n", parsed);

        if (parseErrors > 0) {
            System.out.printf("  parse errors: %d (these contribute no methods; the counts above are a floor)%n",
                              parseErrors);
        }
    }

    private void printJson(ShapeCensus.CensusReport report, int parsed, int parseErrors) {
        var builder = new StringBuilder("{\n");

        builder.append("  \"totalMethods\": %d,\n".formatted(report.totalMethods()));
        builder.append("  \"filesParsed\": %d,\n".formatted(parsed));
        builder.append("  \"parseErrors\": %d,\n".formatted(parseErrors));
        builder.append("  \"residualRate\": %.4f,\n".formatted(report.residualRate()));
        builder.append("  \"mixedRate\": %.4f,\n".formatted(report.mixedRate()));
        builder.append("  \"unclassifiedRate\": %.4f,\n".formatted(report.unclassifiedRate()));
        builder.append("  \"histogram\": {\n");

        var shapes = MethodShape.values();

        for (int i = 0; i < shapes.length; i++) {
            builder.append("    \"%s\": %d".formatted(shapes[i], report.count(shapes[i])));
            builder.append(i < shapes.length - 1 ? ",\n" : "\n");
        }

        builder.append("  }\n}\n");
        System.out.print(builder);
    }
}
