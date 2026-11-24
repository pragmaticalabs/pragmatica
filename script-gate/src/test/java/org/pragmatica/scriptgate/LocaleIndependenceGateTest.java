package org.pragmatica.scriptgate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


/// #3051 (follow-up of #2974/#3037) - the invariant: every format or case-mapping call whose output
/// is wire, parsed-log or file content is locale-independent (`Locale.ROOT`). Human-only console
/// text may stay on the default locale.
///
/// Why a source scan and not only behaviour tests: a `String.format("%.2f", x)` is green on every
/// developer machine and CI runner (English default locale) and wrong only under a comma-decimal
/// locale (`de`: `0,50` inside a JSON body) or a non-Latin-digit one (`ar`: `%d` prints Arabic-Indic
/// digits inside a file name or a manifest port), and `toLowerCase()` only under Turkish (`I` ->
/// dotless `ı`). No test that runs under the default locale can see it. This gate reads the
/// sources, so it sees it everywhere, for every module, including a module nobody wrote a locale
/// test for.
///
/// What it flags (everything outside `core/`, tests, build output and archived/internal docs):
/// - a bare `toUpperCase()` / `toLowerCase()` / `::toUpperCase` / `::toLowerCase`;
/// - a `String.format(`, `.printf(` or `.formatted(` with a locale-sensitive conversion (`%d`, `%f`,
///   `%e`, `%g`, `%S`, `%t`) and no `Locale` first argument (`.formatted` has no locale overload at
///   all, so such a call is always flagged: use `String.format(Locale.ROOT, ...)`).
///
/// [#HUMAN_ONLY] is the deliberate exception list: each entry is a file whose every flagged call
/// is console text or an exception/diagnostic message for a person, never parsed, never stored.
/// Adding a file there is the classification decision and carries its reason; a new file with a
/// bare format is red until it is fixed or classified.
class LocaleIndependenceGateTest {
    private static final Pattern CASE_CALL = Pattern.compile("\\.to(Upper|Lower)Case\\(\\s*\\)|::to(Upper|Lower)Case\\b");

    private static final Pattern FORMAT_CALL = Pattern.compile("String\\.format\\(|\\.printf\\(|\\.formatted\\(");

    private static final Pattern SENSITIVE_CONVERSION = Pattern.compile("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[dfeEgGStT]");

    private static final Pattern LOCALE_ARGUMENT = Pattern.compile("^\\s*(?:java\\.util\\.)?Locale\\b");

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of("target",
                                                                  ".git",
                                                                  ".m2-local",
                                                                  "node_modules",
                                                                  ".bb",
                                                                  "changelog.d",
                                                                  ".internal",
                                                                  "archive");

    /// Console text printed for the operator by the CLIs (stdout/stderr progress and status lines).
    private static final String CLI_CONSOLE = "aether/cli/src/main/";

    /// Files whose flagged calls build exception or diagnostic messages for a person.
    private static final Set<String> HUMAN_ONLY = Set.of(
    // error/diagnostic message text (a person reads it; digits only, never parsed)
    "aether/aether-config/src/main/java/org/pragmatica/aether/config/ConfigValidator.java",
                                                         "aether/aether-stream/src/main/java/org/pragmatica/aether/stream/OwnerPeerReads.java",
                                                         "aether/aether-stream/src/main/java/org/pragmatica/aether/stream/StreamError.java",
                                                         "aether/aether-stream/src/main/java/org/pragmatica/aether/stream/replication/FailoverRecoveryError.java",
                                                         "aether/aether-stream/src/main/java/org/pragmatica/aether/stream/segment/SegmentError.java",
                                                         "aether/environment/hetzner/src/main/java/org/pragmatica/aether/environment/hetzner/HetznerComputeProvider.java",
                                                         "aether/node/src/main/java/org/pragmatica/aether/api/ManagementServerError.java",
                                                         "aether/node/src/main/java/org/pragmatica/aether/node/deployment/EffectTranslator.java",
                                                         "aether/pg-tools/pg-parser/src/main/java/org/pragmatica/aether/pg/parser/PostgresParser.java",
                                                         "integrations/serialization/api/src/main/java/org/pragmatica/serialization/SliceCodec.java",
                                                         "integrations/serialization/api/src/main/java/org/pragmatica/serialization/SystemTags.java",
                                                         "integrations/serialization/api/src/main/java/org/pragmatica/serialization/UnknownEnumOrdinalException.java",
                                                         "integrations/serialization/api/src/main/java/org/pragmatica/serialization/WireFormatError.java",
                                                         "integrations/storage/src/main/java/org/pragmatica/storage/AppendLog.java",
                                                         "jbct/jbct-format/src/main/java/org/pragmatica/jbct/format/FormattingError.java",
                                                         "jbct/jbct-parser/src/main/java/org/pragmatica/jbct/parser/Java25Parser.java",
                                                         "jbct/slice-processor/src/main/java/org/pragmatica/jbct/slice/generator/FactoryClassGenerator.java",

    // console report of the jbct CLI (human text); the CLI's JSON writers are gated, see HUMAN_ONLY_METHODS
    "jbct/jbct-lint/src/main/java/org/pragmatica/jbct/lint/cst/shape/ShapeCensus.java",

    // NOT human (#3643): a JSON result file the harness parses. Left unfixed only because pre-push-check cannot gate a loose
    // single-file source (no Maven module owns it: "rc 0 but no count line"); delete this entry together with the fix.
    "aether/tests/cloud/armxk/driver/ArmKDriver.java",

    // benchmark console table; example slice's tax description shown to a person
    "integrations/serialization/benchmark/src/main/java/org/pragmatica/serialization/benchmark/SliceCodecBenchmark.java",
                                                         "examples/ecommerce/pricing/src/main/java/org/pragmatica/aether/example/pricing/PricingService.java");

    /// File -> console-only methods (#3640 review): the rest of such a file is gated. These commands also write JSON, where a
    /// `%d` under an `ar`/`hi` locale prints non-ASCII digits; an exemption per file would hide that, so it is per method.
    private static final Map<String, Set<String>> HUMAN_ONLY_METHODS = Map.of("jbct/jbct-cli/src/main/java/org/pragmatica/jbct/cli/ObligationsCommand.java",
                                                                              Set.of("printText"),
                                                                              "jbct/jbct-cli/src/main/java/org/pragmatica/jbct/cli/ShapeCensusCommand.java",
                                                                              Set.of("printText"));

    @Test
    void repository_hasNoLocaleDependentWireFileOrLogOutput() {
        var root = ScriptRunner.repoRoot();
        var violations = new ArrayList<String>();

        scannedFiles(root).forEach(path -> collect(root, path, violations));
        assertThat(violations).as("locale-dependent case mapping or format call (use Locale.ROOT, or classify the file in HUMAN_ONLY with a reason)")
                  .isEmpty();
    }

    @Test
    void humanOnlyList_namesOnlyFilesThatStillNeedIt() {
        var root = ScriptRunner.repoRoot();
        var stale = HUMAN_ONLY.stream().filter(file -> flagged(root.resolve(file)).isEmpty()).toList();

        assertThat(stale).as("HUMAN_ONLY entries with no flagged call left (delete them)").isEmpty();
    }

    @Test
    void humanOnlyMethods_namesOnlyMethodsThatStillNeedIt() {
        var root = ScriptRunner.repoRoot();
        var stale = HUMAN_ONLY_METHODS.entrySet()
                                      .stream()
                                      .flatMap(entry -> entry.getValue()
                                                             .stream()
                                                             .filter(method -> inMethod(root.resolve(entry.getKey()),
                                                                                        method).isEmpty())
                                                             .map(method -> entry.getKey() + "#" + method))
                                      .toList();

        assertThat(stale).as("HUMAN_ONLY_METHODS entries with no flagged call left (delete them)").isEmpty();
    }

    @Test
    void scanner_flagsBareCallsAndAcceptsLocaleRootOnes() {
        assertThat(flagged("var s = String.format(\"%.2f\", x);")).hasSize(1);
        assertThat(flagged("var s = String.format(\"%03d\", x);")).hasSize(1);
        assertThat(flagged("var s = \"v%d\".formatted(x);")).hasSize(1);
        assertThat(flagged("out.printf(\"%d%n\", x);")).hasSize(1);
        assertThat(flagged("var s = name.toLowerCase();")).hasSize(1);
        assertThat(flagged("names.map(String::toUpperCase);")).hasSize(1);
        assertThat(flagged("var s = String.format(Locale.ROOT, \"%.2f\", x);")).isEmpty();
        assertThat(flagged("var s = String.format(java.util.Locale.ROOT, \"%d\", x);")).isEmpty();
        assertThat(flagged("var s = name.toLowerCase(Locale.ROOT);")).isEmpty();
        assertThat(flagged("var s = String.format(\"%s-%s\", a, b);")).as("%s alone is locale-independent").isEmpty();
        assertThat(flagged("var s = String.format(\"100%%d\", a);")).as("%% is a literal percent").isEmpty();
        assertThat(flagged("// name.toLowerCase() in a comment")).isEmpty();
    }

    /// An unreadable tree fails the test loudly: an empty scan would pass vacuously.
    private static List<Path> scannedFiles(Path root) {
        try (var files = Files.walk(root)) {
            return files.filter(path -> isScanned(root, path))
                        .toList();
        } catch (IOException e) {
            return fail("cannot walk the repository tree", e);
        }
    }

    private static boolean isScanned(Path root, Path path) {
        var relative = root.relativize(path);
        var name = relative.getFileName().toString();

        return Files.isRegularFile(path)
               && (name.endsWith(".java") || name.endsWith(".md"))
               && !skipped(relative);
    }

    private static boolean skipped(Path relative) {
        var elements = relative.getParent() == null
                       ? List.<Path> of()
                       : toList(relative.getParent());

        return relative.getFileName()
                       .toString()
                       .equals("CHANGELOG.md") || isCoreModule(elements) || hasSkippedElement(elements);
    }

    /// `core/` is covered by #3037; only its top-level directory is skipped, not packages named `core`.
    private static boolean isCoreModule(List<Path> elements) {
        return ! elements.isEmpty() && elements.getFirst()
                                               .toString()
                                               .equals("core");
    }

    private static boolean hasSkippedElement(List<Path> elements) {
        for (int i = 0; i < elements.size(); i++) {
            var element = elements.get(i).toString();

            if (SKIPPED_DIRECTORIES.contains(element) || isTestSourceRoot(elements, i)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isTestSourceRoot(List<Path> elements, int index) {
        return elements.get(index)
                       .toString()
                       .equals("test")
               && index > 0
               && elements.get(index - 1)
                          .toString()
                          .equals("src");
    }

    private static List<Path> toList(Path path) {
        var elements = new ArrayList<Path>();

        path.forEach(elements::add);

        return elements;
    }

    private static void collect(Path root, Path path, List<String> violations) {
        var relative = root.relativize(path).toString();

        if (relative.startsWith(CLI_CONSOLE) || HUMAN_ONLY.contains(relative)) {
            return;
        }

        var exemptLines = HUMAN_ONLY_METHODS.getOrDefault(relative, Set.of())
                                            .stream()
                                            .flatMap(method -> inMethod(path, method).stream())
                                            .toList();

        flagged(path).stream()
                     .filter(line -> !exemptLines.contains(line))
                     .forEach(line -> violations.add(relative + ":" + line));
    }

    /// Flagged lines inside the body of the 4-space-indented member `method` (declaration line to the closing `    }`).
    private static List<Integer> inMethod(Path path, String method) {
        try {
            var lines = Files.readAllLines(path);
            var declaration = Pattern.compile("^    \\S.* " + method + "\\(.*\\{\\s*$");
            var start = IntStream.range(0, lines.size())
                                 .filter(i -> declaration.matcher(lines.get(i)).matches())
                                 .findFirst()
                                 .orElse(-1);
            var end = start < 0
                      ? -1
                      : IntStream.range(start, lines.size())
                                 .filter(i -> lines.get(i).equals("    }"))
                                 .findFirst()
                                 .orElse(-1);

            return start < 0 || end < 0
                   ? List.of()
                   : flagged(path).stream()
                                  .filter(line -> line > start && line <= end + 1)
                                  .toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private static List<Integer> flagged(Path path) {
        try {
            return flagged(Files.readString(path));
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /// 1-based line numbers of every flagged call in `source`.
    private static List<Integer> flagged(String source) {
        var lines = new ArrayList<Integer>();
        var codeLines = source.split("\n", -1);

        for (int i = 0; i < codeLines.length; i++) {
            if (CASE_CALL.matcher(code(codeLines[i])).find()) {
                lines.add(i + 1);
            }
        }

        var matcher = FORMAT_CALL.matcher(source);

        while (matcher.find()) {
            if (isSensitiveFormat(source, matcher.start(), matcher.end(), matcher.group())) {
                lines.add(lineOf(source, matcher.start()));
            }
        }

        return lines.stream()
                    .distinct()
                    .sorted()
                    .toList();
    }

    private static String code(String line) {
        var trimmed = line.stripLeading();

        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
               ? ""
               : line;
    }

    private static boolean isSensitiveFormat(String source, int start, int end, String call) {
        var lineStart = source.lastIndexOf('\n', start) + 1;

        if (code(source.substring(lineStart,
                                  source.indexOf('\n', start) < 0
                                  ? source.length()
                                  : source.indexOf('\n', start))).isEmpty()) {
            return false;
        }

        if (call.equals(".formatted(")) {
            return hasSensitiveConversion(formatTextBefore(source, start));
        }

        return ! LOCALE_ARGUMENT.matcher(source.substring(end)).find() && hasSensitiveConversion(formatTextAfter(source,
                                                                                                                 end));
    }

    private static String formatTextBefore(String source, int callStart) {
        var from = Math.max(source.lastIndexOf(';', callStart), source.lastIndexOf('{', callStart));

        return source.substring(Math.max(from, 0), callStart);
    }

    private static String formatTextAfter(String source, int callEnd) {
        var semicolon = source.indexOf(';', callEnd);

        return source.substring(callEnd,
                                semicolon < 0
                                ? source.length()
                                : semicolon);
    }

    private static boolean hasSensitiveConversion(String text) {
        return SENSITIVE_CONVERSION.matcher(text.replace("%%", "")).find();
    }

    private static int lineOf(String source, int index) {
        int line = 1;

        for (int i = 0; i < index; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }

        return line;
    }
}
