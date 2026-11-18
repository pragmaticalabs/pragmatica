package org.pragmatica.jbct.maven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.lint.cst.RunOutcome.Level;

import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;


/// #2525 P2c: the Maven goals resolve configuration, mode, formatter and rules per source root. Two modules, `main`
/// (infrastructure, indentSize 2) and `side` (business, indentSize 8), differ in everything a root can differ in; the
/// goal runs over `main`'s main root and, when tests are included, over a `testSourceDirectory` that is `side`'s test root.
///
/// Every mode, `format-only` and `disabled` included, reaches the goals through the module's own `jbct.toml`.
class MojoPerRootTest {
    private static final String USER_HOME = "user.home";
    private static final String MODE = "jbct.mode";

    private static final String UNFORMATTED = """
                                              package org.acme.%s.domain.pricing;

                                              public class %sPrice {
                                              int run(){
                                              var x=1;
                                              return x;
                                              }
                                              }
                                              """;

    /// `lift` in a `domain` package (ARCH-02, fires in business only) and `return null` (RET-03, fires in both).
    private static final String BOTH = """
                                       package org.acme.%s.domain.pricing;

                                       class %sBoth {
                                           Object run() {
                                               return Result.lift(Err::new, () -> compute());
                                           }
                                           String name() {
                                               return null;
                                           }
                                       }
                                       """;

    private static final String ARCH_02 = "[JBCT-ARCH-02]";
    private static final String RET_03 = "[JBCT-RET-03]";

    @TempDir
    Path tempDir;

    private final List<String> logs = new ArrayList<>();
    private final RunCapture capture = new RunCapture();
    private String savedUserHome;
    private Path module;
    private Path mainRoot;
    private Path sideTestRoot;
    private Path sideModule;

    @BeforeEach
    void fixture() throws Exception {
        savedUserHome = System.getProperty(USER_HOME);
        System.setProperty(USER_HOME,
                           tempDir.resolve("home").toString());
        System.clearProperty(MODE);
        var repo = Files.createDirectories(tempDir.resolve("repo"));

        Files.createDirectories(repo.resolve(".git"));
        module = module(repo, "main", "infrastructure", 2);
        sideModule = module(repo, "side", "business", 8);
        mainRoot = sources(module, "src/main/java", "main");
        sideTestRoot = sources(sideModule, "src/test/java", "side");
    }

    @AfterEach
    void restore() {
        System.setProperty(USER_HOME, savedUserHome);
        System.clearProperty(MODE);
    }

    private static Path module(Path repo, String name, String mode, int indent) throws Exception {
        var directory = Files.createDirectories(repo.resolve(name));

        Files.writeString(directory.resolve("pom.xml"), "<project/>\n");
        Files.writeString(directory.resolve("jbct.toml"),
                          "[project]\nbasePackage = \"org.acme." + name
                         + "\"\n[lint]\nmode = \"" + mode
                         + "\"\n[format]\nindentSize = " + indent
                         + "\n");

        return directory;
    }

    private static Path sources(Path moduleDirectory, String relative, String name) throws Exception {
        var root = moduleDirectory.resolve(relative);
        var directory = Files.createDirectories(root.resolve("org/acme/" + name + "/domain/pricing"));

        Files.writeString(directory.resolve(name + "Price.java"), UNFORMATTED.formatted(name, name));
        Files.writeString(directory.resolve(name + "Both.java"), BOTH.formatted(name, name));

        return root;
    }

    private static Path priceOf(Path root, String name) {
        return root.resolve("org/acme/" + name + "/domain/pricing/" + name + "Price.java");
    }

    private static int indentOfStatement(Path file) throws Exception {
        return Files.readAllLines(file)
                    .stream()
                    .filter(line -> line.strip()
                                        .startsWith("var x"))
                    .map(line -> line.length() - line.stripLeading()
                                                     .length())
                    .findFirst()
                    .orElseThrow();
    }

    /// Puts the module `name` in `mode` through its own `jbct.toml`, the way a user would.
    private void setMode(String name, JbctMode mode) throws Exception {
        module(module.getParent(), name, mode.token(), name.equals("main") ? 2 : 8);
    }

    private <M extends AbstractJbctMojo> M wire(M mojo,
                                                Path sourceDirectory,
                                                Path testDirectory,
                                                Properties userProperties) {
        var mavenProject = new MavenProject();
        var request = new DefaultMavenExecutionRequest();

        mavenProject.setFile(new File(module.toFile(), "pom.xml"));
        request.setUserProperties(userProperties);
        mojo.project = mavenProject;
        capture.on(mojo);
        mojo.session = new MavenSession(null, null, request, new DefaultMavenExecutionResult());
        mojo.sourceDirectory = sourceDirectory.toFile();
        mojo.testSourceDirectory = testDirectory.toFile();
        mojo.setLog(new SystemStreamLog() {
            @Override
            public void info(CharSequence content) {
                logs.add(content.toString());
            }

            @Override
            public void warn(CharSequence content) {
                logs.add(content.toString());
            }

            @Override
            public void error(CharSequence content) {
                logs.add(content.toString());
            }
        });

        return mojo;
    }

    private LintMojo lint(boolean includeTests, Path testDirectory) {
        var mojo = wire(new LintMojo(), mainRoot, testDirectory, new Properties());

        mojo.includeTests = includeTests;

        return mojo;
    }

    private LintMojo lint(boolean includeTests) {
        return lint(includeTests, sideTestRoot);
    }

    private CheckMojo check() {
        var mojo = wire(new CheckMojo(), mainRoot, sideTestRoot, new Properties());

        mojo.includeTests = true;

        return mojo;
    }

    private ScoreMojo score() {
        var mojo = wire(new ScoreMojo(), mainRoot, sideTestRoot, new Properties());

        mojo.includeTests = true;

        return mojo;
    }

    private ProcessMojo process() {
        var mojo = wire(new ProcessMojo(), mainRoot, sideTestRoot, new Properties());

        mojo.includeTests = true;

        return mojo;
    }

    private FormatMojo format() {
        var mojo = wire(new FormatMojo(), mainRoot, sideTestRoot, new Properties());

        mojo.includeTests = true;

        return mojo;
    }

    private FormatCheckMojo formatCheck() {
        var mojo = wire(new FormatCheckMojo(), mainRoot, sideTestRoot, new Properties());

        mojo.includeTests = true;

        return mojo;
    }

    private static Optional<Exception> attempt(Mojo mojo) {
        try {
            mojo.execute();

            return Optional.empty();
        } catch (Exception e) {
            return Optional.of(e);
        }
    }

    private String everything(Optional<Exception> outcome) {
        return String.join("\n", logs) + outcome.map(e -> "\n" + e.getMessage())
                                                .orElse("");
    }

    private List<String> linesWith(String needle) {
        return logs.stream()
                   .filter(line -> line.contains(needle))
                   .toList();
    }

    private static boolean anyContains(List<String> lines, String needle) {
        return lines.stream()
                    .anyMatch(line -> line.contains(needle));
    }

    private static int count(String text, String needle) {
        return text.split(Pattern.quote(needle), -1).length - 1;
    }

    /// A diagnostic names its file by file name only, so the root a finding came from is read off the file name.
    private static String sideFinding() {
        return "sideBoth.java";
    }

    private static String mainFinding() {
        return "mainBoth.java";
    }

    @Test
    void lint_mainAndTestRootsOfDifferentModules_differInWhatFires() {
        attempt(lint(true));
        var arch = linesWith(ARCH_02);
        var ret = linesWith(RET_03);

        assertThat(anyContains(arch, sideFinding())).isTrue();
        assertThat(anyContains(arch, mainFinding())).isFalse();
        assertThat(anyContains(ret, sideFinding())).isTrue();
        assertThat(anyContains(ret, mainFinding())).isTrue();
        assertThat(logs).anyMatch(line -> line.contains("mode=infrastructure (from ") && line.contains("root=" + mainRoot));
        assertThat(logs).anyMatch(line -> line.contains("mode=business (from ") && line.contains("root=" + sideTestRoot) && line.contains("test-root files"));
    }

    @Test
    void lint_nonDefaultTestSourceDirectory_isADeclaredTestRoot() throws Exception {
        var integration = sources(module, "integration/java", "main").resolve(".").normalize();

        attempt(lint(true, integration));
        assertThat(logs).anyMatch(line -> line.contains("root=" + integration) && line.contains("test-root files"));
        assertThat(logs).noneMatch(line -> line.contains("root=" + mainRoot) && line.contains("test-root files"));
    }

    @Test
    void lint_includeTestsFalse_collectsOnlyTheMainRoot() {
        var outcome = attempt(lint(false));
        var text = everything(outcome);

        assertThat(count(text, "mode=")).isEqualTo(1);
        assertThat(text).contains("mode=infrastructure (from ", "root=" + mainRoot)
                  .doesNotContain(File.separator + "side" + File.separator);
        assertThat(linesWith(ARCH_02)).isEmpty();
        assertThat(anyContains(linesWith(RET_03), mainFinding())).isTrue();
    }

    @Test
    void lint_userPropertyMode_winsOverTheTomlOfEveryRoot() {
        var properties = new Properties();

        properties.setProperty(MODE, "infrastructure");
        var mojo = wire(new LintMojo(), mainRoot, sideTestRoot, properties);

        mojo.includeTests = true;
        attempt(mojo);
        assertThat(logs).anyMatch(line -> line.contains("mode=infrastructure (from -Djbct.mode)") && line.contains("root=" + sideTestRoot));
        assertThat(linesWith(ARCH_02)).isEmpty();
    }

    @Test
    void lint_unknownModeInTheTestRootsToml_failsTheGoalNamingTheValue() throws Exception {
        Files.writeString(sideModule.resolve("jbct.toml"), "[lint]\nmode = \"strict\"\n");
        var outcome = attempt(lint(true));

        assertThat(outcome).isPresent();
        assertThat(outcome.get()).isInstanceOf(MojoExecutionException.class);
        assertThat(outcome.get().getMessage()).contains("strict");
    }

    @Test
    void lint_refusalInTheModulesToml_failsTheGoal_asForASingleRoot() throws Exception {
        Files.writeString(module.resolve("jbct.toml"), "[lint]\nmode = \"strict\"\n");
        var outcome = attempt(lint(false));

        assertThat(outcome).isPresent();
        assertThat(outcome.get()).isInstanceOf(MojoExecutionException.class);
        assertThat(outcome.get().getMessage()).contains("strict");
    }

    @Test
    void lint_formatOnlyTestRoot_runsNoRuleThereAndKeepsTheMainRoot() throws Exception {
        setMode("side", JbctMode.FORMAT_ONLY);
        attempt(lint(true));
        assertThat(anyContains(linesWith(RET_03), sideFinding())).isFalse();
        assertThat(anyContains(linesWith(RET_03), mainFinding())).isTrue();
        assertThat(logs).anyMatch(line -> line.contains("mode=format-only (from ") && line.contains("root=" + sideTestRoot));
    }

    @Test
    void lint_disabledTestRoot_isNotLinted() throws Exception {
        setMode("side", JbctMode.DISABLED);
        attempt(lint(true));
        assertThat(anyContains(linesWith(RET_03), sideFinding())).isFalse();
        assertThat(anyContains(linesWith(RET_03), mainFinding())).isTrue();
        assertThat(logs).anyMatch(line -> line.contains("mode=disabled (from ") && line.contains("root=" + sideTestRoot));
    }

    @Test
    void score_formatOnlyRoot_isAnnouncedNotScored_andScoringContinuesOnTheOther() throws Exception {
        setMode("side", JbctMode.FORMAT_ONLY);
        var text = everything(attempt(score()));

        assertThat(text).contains("Not scored: " + sideTestRoot + " (mode format-only skips it)").contains("file(s) scored");
        assertThat(text).doesNotContain("Not scored: " + mainRoot);
    }

    @Test
    void score_everyRootWithoutRules_scoresNothingAndSaysSo() throws Exception {
        setMode("side", JbctMode.FORMAT_ONLY);
        setMode("main", JbctMode.DISABLED);
        var text = everything(attempt(score()));

        assertThat(text).contains("Nothing was scored: ").doesNotContain("file(s) scored");
    }

    @Test
    void format_twoRootsWithDifferentIndent_eachFileIsFormattedWithItsOwnRoot() throws Exception {
        var outcome = attempt(format());

        assertThat(outcome).isEmpty();
        assertThat(indentOfStatement(priceOf(mainRoot, "main"))).isEqualTo(4);
        assertThat(indentOfStatement(priceOf(sideTestRoot, "side"))).isEqualTo(16);
    }

    @Test
    void formatCheck_afterFormat_isCleanForBothRoots() {
        attempt(format());
        logs.clear();
        var outcome = attempt(formatCheck());

        assertThat(outcome).isEmpty();
        assertThat(logs).containsAll(capture.texts(Level.INFO))
                        .contains("Checking format of 4 Java file(s)")
                        .noneMatch(line -> line.contains("not properly formatted"));
    }

    @Test
    void formatCheck_onlyTheRootWhoseFileDriftedIsNamed() throws Exception {
        attempt(format());
        Files.writeString(priceOf(sideTestRoot, "side"), UNFORMATTED.formatted("side", "side"));
        logs.clear();
        var outcome = attempt(formatCheck());

        assertThat(outcome).isPresent();
        assertThat(outcome.get().getMessage()).contains(priceOf(sideTestRoot, "side").toString())
                  .doesNotContain(priceOf(mainRoot, "main").toString());
    }

    @Test
    void format_disabledRoot_isLeftByteIdenticalAndAnnouncedOnce() throws Exception {
        setMode("side", JbctMode.DISABLED);
        var before = Files.readString(priceOf(sideTestRoot, "side"));
        var outcome = attempt(format());
        var text = everything(outcome);

        assertThat(outcome).isEmpty();
        assertThat(Files.readString(priceOf(sideTestRoot, "side"))).isEqualTo(before);
        assertThat(indentOfStatement(priceOf(mainRoot, "main"))).isEqualTo(4);
        assertThat(logs).anyMatch(line -> line.contains("mode=disabled (from ") && line.contains("root=" + sideTestRoot)
                                          && line.contains("2 skipped by mode"));
        assertThat(count(text, "mode=disabled (from ")).isEqualTo(1);
        assertThat(logs).containsAll(capture.texts(Level.INFO)).contains("Formatting 2 Java file(s)");
    }

    @Test
    void format_formatOnlyRoot_isFormattedWithItsOwnSettings() throws Exception {
        setMode("side", JbctMode.FORMAT_ONLY);
        var outcome = attempt(format());

        assertThat(outcome).isEmpty();
        assertThat(indentOfStatement(priceOf(sideTestRoot, "side"))).isEqualTo(16);
        assertThat(String.join("\n", logs)).doesNotContain("Nothing was formatted");
    }

    @Test
    void formatCheck_disabledRoot_hasNoFinding_formatOnlyRootKeepsIt() throws Exception {
        setMode("side", JbctMode.DISABLED);
        var disabled = attempt(formatCheck());

        assertThat(disabled).isPresent();
        assertThat(disabled.get().getMessage()).contains(priceOf(mainRoot, "main").toString())
                  .doesNotContain(priceOf(sideTestRoot, "side").toString());
        setMode("side", JbctMode.FORMAT_ONLY);
        var formatOnly = attempt(formatCheck());

        assertThat(formatOnly).isPresent();
        assertThat(formatOnly.get().getMessage()).contains(priceOf(sideTestRoot, "side").toString());
    }

    @Test
    void check_disabledRoot_hasNoFormatFindingAndNoRule() throws Exception {
        setMode("side", JbctMode.DISABLED);
        var text = everything(attempt(check()));

        assertThat(text).doesNotContain(priceOf(sideTestRoot, "side").toString());
        assertThat(anyContains(linesWith(RET_03), sideFinding())).isFalse();
        assertThat(logs).anyMatch(line -> line.contains("mode=disabled (from ") && line.contains("root=" + sideTestRoot)
                                          && line.contains("2 skipped by mode"));
    }

    @Test
    void check_formatOnlyRoot_keepsTheFormatFindingAndRunsNoRule() throws Exception {
        setMode("side", JbctMode.FORMAT_ONLY);
        var text = everything(attempt(check()));

        assertThat(text).contains(priceOf(sideTestRoot, "side").toString());
        assertThat(anyContains(linesWith(RET_03), sideFinding())).isFalse();
        assertThat(anyContains(linesWith(RET_03), mainFinding())).isTrue();
    }

    @Test
    void process_formatOnlyRoot_formatsButRunsNoRule() throws Exception {
        setMode("side", JbctMode.FORMAT_ONLY);
        attempt(process());
        assertThat(indentOfStatement(priceOf(sideTestRoot, "side"))).isEqualTo(16);
        assertThat(anyContains(linesWith(RET_03), sideFinding())).isFalse();
        assertThat(anyContains(linesWith(RET_03), mainFinding())).isTrue();
    }

    @Test
    void process_disabledRoot_isNeitherFormattedNorLinted() throws Exception {
        setMode("side", JbctMode.DISABLED);
        var before = Files.readString(priceOf(sideTestRoot, "side"));
        var text = everything(attempt(process()));

        assertThat(Files.readString(priceOf(sideTestRoot, "side"))).isEqualTo(before);
        assertThat(anyContains(linesWith(RET_03), sideFinding())).isFalse();
        assertThat(logs).anyMatch(line -> line.contains("mode=disabled (from ") && line.contains("root=" + sideTestRoot)
                                          && line.contains("2 skipped by mode"));
    }
}
