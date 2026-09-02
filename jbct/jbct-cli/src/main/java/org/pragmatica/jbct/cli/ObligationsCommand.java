package org.pragmatica.jbct.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.pragmatica.jbct.config.ConfigLoader;
import org.pragmatica.jbct.lint.cst.filetype.FileTypeClassifier;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.shared.FileCollector;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Option;

import org.w3c.dom.Element;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// Reports composition obligations that no test discharges.
///
/// An obligation is something a composition can do that a test ought to exercise. This command
/// covers the two that can be decided from a method name plus coverage data: a **compensation** (a
/// saga's undo step) and an **absorbed failure** (a `.recover(...)` site). Both reduce to one
/// question — did this method ever execute under test? — which is exactly what JaCoCo answers, and
/// the spec names it the cleanest starting point for that reason.
///
/// **It is a gap list. It never generates test code**, deliberately. Measurement showed generation
/// emits tests that cannot be written, tests duplicating leaf tests, and at least one inverted
/// assertion; a list of what is missing is useful, a wrong test is worse than none.
///
/// **It does not match test names either.** Real test names are business-named
/// (`execute_failsWhenAmlFlags`), so structural name matching reports false gaps on the best-tested
/// code. Coverage is the only signal here that does not lie about intent.
///
/// **Scope, stated plainly.** The other two obligations the design calls for — the success path, and
/// each I/O failure separately — need the composition chain decomposed into steps and each step's
/// failure branch traced. That is a larger piece and is NOT attempted; this reports on compensations
/// and absorptions only, and says so in its output rather than implying full coverage of the idea.
@Command(name = "obligations",
         description = "Report composition obligations (compensations, absorbed failures) that no test exercises",
         mixinStandardHelpOptions = true)
public class ObligationsCommand implements Callable<Integer> {
    static final int USAGE_ERROR = 2;
    static final List<String> SUPPORTED_FORMATS = List.of("text", "json");

    /// A `.recover(...)` absorbs a failure; the absorbed branch is an obligation.
    private static final Pattern ABSORPTION = Pattern.compile("\\.recover\\s*\\(");

    /// A saga's undo step. Named rather than inferred: compensation is a role, and the codebases
    /// this serves name it consistently.
    private static final Pattern COMPENSATION = Pattern.compile("^(?:compensate|undo|rollback|revert)");

    @Parameters(paramLabel = "<path>", description = "Files or directories to analyse", arity = "1..*")
    List<Path> paths;

    @picocli.CommandLine.Option(names = {"--coverage"},
                                description = "JaCoCo XML report(s); repeat for a multi-module build",
                                required = true)
    List<Path> coverageReports;

    @picocli.CommandLine.Option(names = {"--format", "-f"}, description = "Output format: text, json", defaultValue = "text")
    String format;

    @picocli.CommandLine.Option(names = {"--config"}, description = "Path to configuration file")
    Path configPath;

    /// One obligation and whether anything exercised it.
    private record Obligation(String file, int line, String method, String kind, boolean covered) {}

    @Override
    public Integer call() {
        if (!SUPPORTED_FORMATS.contains(format.toLowerCase(Locale.ROOT))) {
            System.err.println("Unknown format '" + format + "'; supported formats: " + String.join(", ", SUPPORTED_FORMATS));

            return USAGE_ERROR;
        }

        var coverage = readCoverage();

        if (coverage.isEmpty()) {
            System.err.println("No coverage data found in " + coverageReports
                              + " — run the module's tests first; without it every obligation would read as a gap.");

            return USAGE_ERROR;
        }

        var config = ConfigLoader.load(Option.option(configPath), Option.none());
        var files = FileCollector.collectJavaFiles(paths, config.files(), System.err::println);
        var parser = new Java25Parser();
        var obligations = new ArrayList<Obligation>();

        for (var file : files) {
            SourceFile.sourceFile(file)
                      .flatMap(source -> parser.parse(source.content()))
                      .onSuccess(root -> collect(root, file.getFileName().toString(), coverage, obligations))
                      .onFailure(cause -> System.err.println("  ✗ " + file + ": " + cause.message()));
        }

        if ("json".equalsIgnoreCase(format)) {
            printJson(obligations);
        } else {
            printText(obligations);
        }

        return 0;
    }

    private void collect(Cursor root, String fileName, Map<String, Boolean> coverage, List<Obligation> into) {
        for (var method : findAllMethods(root)) {
            var name = FileTypeClassifier.methodName(method);
            var kind = kindOf(method, name);

            if (kind == null) {
                continue;
            }

            into.add(new Obligation(fileName,
                                    startLine(anchorOf(method)),
                                    name,
                                    kind,
                                    coverage.getOrDefault(fileName + "#" + name, false)));
        }
    }

    private String kindOf(Cursor method, String name) {
        if (COMPENSATION.matcher(name).find()) {
            return "compensation";
        }

        return ABSORPTION.matcher(memberDeclText(method)).find() && isInnermostAbsorber(method)
               ? "absorbed-failure"
               : null;
    }

    /// Attribute an absorption to the method that performs it, not to every method whose text
    /// encloses it. A JBCT slice nests its implementation record inside its own factory, so the
    /// factory's text contains the record's `.recover(...)` too; without this, `LoanOrchestrator`
    /// reports a gap against the factory `loanOrchestrator` at a line no test could ever cover
    /// directly, and the count stops matching the four obligations that are actually there.
    private boolean isInnermostAbsorber(Cursor method) {
        return findAllMethods(method).stream()
                      .noneMatch(nested -> nested.idx() != method.idx()
                                          && ABSORPTION.matcher(memberDeclText(nested)).find());
    }

    /// `sourcefilename#methodName` -> whether any instruction in it executed. Keyed on the source
    /// file rather than the binary class name because a JBCT slice's implementation is a record
    /// nested inside its own factory, so the class name is `Outer$inner` while the CST only knows
    /// the file.
    private Map<String, Boolean> readCoverage() {
        var covered = new HashMap<String, Boolean>();

        for (var report : coverageReports) {
            if (!Files.exists(report)) {
                System.err.println("  ✗ coverage report not found: " + report);
                continue;
            }

            try {
                var factory = DocumentBuilderFactory.newInstance();

                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

                var document = factory.newDocumentBuilder().parse(report.toFile());
                var classes = document.getElementsByTagName("class");

                for (int i = 0; i < classes.getLength(); i++) {
                    var classElement = (Element) classes.item(i);
                    var sourceFile = classElement.getAttribute("sourcefilename");
                    var methods = classElement.getElementsByTagName("method");

                    for (int j = 0; j < methods.getLength(); j++) {
                        var methodElement = (Element) methods.item(j);
                        var key = sourceFile + "#" + methodElement.getAttribute("name");

                        covered.merge(key, executed(methodElement), Boolean::logicalOr);
                    }
                }
            } catch (Exception e) {
                System.err.println("  ✗ could not read " + report + ": " + e.getMessage());
            }
        }

        return covered;
    }

    private boolean executed(Element method) {
        var counters = method.getElementsByTagName("counter");

        for (int i = 0; i < counters.getLength(); i++) {
            var counter = (Element) counters.item(i);

            if ("INSTRUCTION".equals(counter.getAttribute("type"))) {
                return !"0".equals(counter.getAttribute("covered"));
            }
        }

        return false;
    }

    private void printText(List<Obligation> obligations) {
        var gaps = obligations.stream().filter(o -> !o.covered()).toList();

        System.out.println("Composition obligations (compensations and absorbed failures only)");
        System.out.printf("  found %d, discharged %d, COLD %d%n",
                          obligations.size(),
                          obligations.size() - gaps.size(),
                          gaps.size());

        if (gaps.isEmpty()) {
            System.out.println("  no gaps");
        } else {
            System.out.println();
            gaps.forEach(gap -> System.out.printf("  %s:%d  %-16s %s — never executed under test%n",
                                                  gap.file(), gap.line(), gap.kind(), gap.method()));
        }

        System.out.println();
        System.out.println("  Success-path and per-I/O-failure obligations are not analysed by this command.");
    }

    private void printJson(List<Obligation> obligations) {
        var builder = new StringBuilder("{\n  \"scope\": \"compensations and absorbed failures only\",\n");

        builder.append("  \"obligations\": [\n");

        for (int i = 0; i < obligations.size(); i++) {
            var o = obligations.get(i);

            builder.append("    {\"file\": \"%s\", \"line\": %d, \"method\": \"%s\", \"kind\": \"%s\", \"covered\": %s}"
                                   .formatted(o.file(), o.line(), o.method(), o.kind(), o.covered()));
            builder.append(i < obligations.size() - 1 ? ",\n" : "\n");
        }

        builder.append("  ]\n}\n");
        System.out.print(builder);
    }
}
