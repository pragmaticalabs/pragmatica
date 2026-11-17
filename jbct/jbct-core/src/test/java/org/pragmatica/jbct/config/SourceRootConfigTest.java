package org.pragmatica.jbct.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// The per-source-root config resolver (#2525, Q11, R1, O1): where the `jbct.toml` walk starts, which files it reads,
/// and the base the test-root predicate is judged against. Every fixture lives in a temp directory; no Maven project
/// is needed, and the working directory never takes part.
class SourceRootConfigTest {
    private static final String USER_HOME_PROPERTY = "user.home";
    private static final String INFRASTRUCTURE = "[lint]\nmode = \"infrastructure\"\n";
    private static final String BUSINESS = "[lint]\nmode = \"business\"\n";
    private static final String FAIL_ON_WARNING = "[lint]\nfailOnWarning = true\n";

    @TempDir
    Path tempDir;

    private String savedUserHome;

    @BeforeEach
    void redirectUserHome() {
        savedUserHome = System.getProperty(USER_HOME_PROPERTY);
        System.setProperty(USER_HOME_PROPERTY, tempDir.resolve("home").toString());
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty(USER_HOME_PROPERTY, savedUserHome);
    }

    @Test
    void moduleWithPom_startsAtPomDirectory_andSkipsTomlsBetweenModuleAndSourceRoot() throws Exception {
        var repo = repository("repo");
        var module = marked(repo.resolve("module"));
        var sourceRoot = Files.createDirectories(module.resolve("src/main/java"));

        write(repo, INFRASTRUCTURE);
        write(module, BUSINESS);
        write(module.resolve("src"), FAIL_ON_WARNING);
        write(sourceRoot, FAIL_ON_WARNING);

        var config = resolve(sourceRoot);

        assertThat(ConfigLoader.moduleDirectory(sourceRoot)).isEqualTo(module);
        assertThat(config.mode()).isEqualTo(JbctMode.BUSINESS);
        assertThat(config.lint().failOnWarning()).isFalse();
    }

    @Test
    void noPom_gitBoundary_startsAtSourceRoot_andReadsEveryTomlFromThereUpToGit() throws Exception {
        var repo = repository("repo");
        var sourceRoot = Files.createDirectories(repo.resolve("src/main/java"));

        write(repo, INFRASTRUCTURE);
        write(repo.resolve("src"), FAIL_ON_WARNING);
        write(sourceRoot, BUSINESS);

        var config = resolve(sourceRoot);

        assertThat(ConfigLoader.moduleDirectory(sourceRoot)).isEqualTo(sourceRoot);
        assertThat(ConfigLoader.boundary(sourceRoot)).isEqualTo(repo);
        assertThat(config.mode()).isEqualTo(JbctMode.BUSINESS);
        assertThat(config.lint().failOnWarning()).isTrue();
    }

    @Test
    void noPomNoGit_boundaryIsThePathGiven_andItsOwnTomlIsRead() throws Exception {
        var given = Files.createDirectories(tempDir.resolve("loose/src/test/java"));

        write(given, INFRASTRUCTURE);

        assertThat(ConfigLoader.moduleDirectory(given)).isEqualTo(given);
        assertThat(ConfigLoader.boundary(given)).isEqualTo(given);
        assertThat(resolve(given).mode()).isEqualTo(JbctMode.INFRASTRUCTURE);
        assertThat(ConfigLoader.isTestRoot(given)).isFalse();
    }

    @Test
    void nestedTomls_nearerLayerWins_whicheverModeItNames() throws Exception {
        var repo = repository("repo");
        var aggregator = Files.createDirectories(repo.resolve("agg"));
        var module = marked(aggregator.resolve("module"));
        var sourceRoot = Files.createDirectories(module.resolve("src/main/java"));

        write(repo, INFRASTRUCTURE + "failOnWarning = true\n");
        write(aggregator, BUSINESS);

        assertThat(resolve(sourceRoot).mode()).isEqualTo(JbctMode.BUSINESS);
        assertThat(resolve(sourceRoot).lint().failOnWarning()).isTrue();

        write(module, INFRASTRUCTURE);

        assertThat(resolve(sourceRoot).mode()).isEqualTo(JbctMode.INFRASTRUCTURE);
    }

    @Test
    void testRoot_isJudgedRelativeToTheBoundary_notTheAbsolutePath() throws Exception {
        var checkout = repository("src/test/checkout");
        var module = marked(checkout.resolve("module"));
        var main = Files.createDirectories(module.resolve("src/main/java"));
        var test = Files.createDirectories(module.resolve("src/test/java"));

        assertThat(ConfigLoader.boundary(test)).isEqualTo(module);
        assertThat(ConfigLoader.isTestRoot(test)).isTrue();
        assertThat(ConfigLoader.isTestRoot(main)).isFalse();
    }

    @Test
    void testRoot_pomLessRoot_isJudgedRelativeToTheGitAncestor() throws Exception {
        var repo = repository("repo");
        var test = Files.createDirectories(repo.resolve("src/test/java"));
        var main = Files.createDirectories(repo.resolve("src/main/java"));

        assertThat(ConfigLoader.isTestRoot(test)).isTrue();
        assertThat(ConfigLoader.isTestRoot(main)).isFalse();
    }

    @Test
    void pathGivenOutsideTheWorkingDirectory_readsItsOwnChain_forDirectoryAndFileAlike() throws Exception {
        var repo = repository("elsewhere/repo");
        var module = marked(repo.resolve("module"));
        var file = Files.createDirectories(module.resolve("src/main/java/demo"))
                        .resolve("Demo.java");

        write(module, INFRASTRUCTURE);
        Files.writeString(file, "package demo;\n");

        assertThat(Path.of(System.getProperty("user.dir"))).isNotEqualTo(module);
        assertThat(resolve(file).mode()).isEqualTo(JbctMode.INFRASTRUCTURE);
        assertThat(resolve(module.resolve("src/main/java")).mode()).isEqualTo(JbctMode.INFRASTRUCTURE);
    }

    @Test
    void mavenBasedirAndCliSourceRoot_resolveTheSameConfig() throws Exception {
        var repo = repository("repo");
        var module = marked(repo.resolve("module"));
        var sourceRoot = Files.createDirectories(module.resolve("src/main/java"));

        write(repo, FAIL_ON_WARNING);
        write(module, INFRASTRUCTURE);

        assertThat(resolve(module)).isEqualTo(resolve(sourceRoot));
        assertThat(ConfigLoader.moduleDirectory(sourceRoot)).isEqualTo(ConfigLoader.moduleDirectory(module));
    }

    private static JbctConfig resolve(Path path) {
        return ConfigLoader.loadForSourceRoot(Option.none(), path).unwrap();
    }

    private Path repository(String name) throws Exception {
        var root = Files.createDirectories(tempDir.resolve(name));

        Files.createDirectories(root.resolve(".git"));

        return root;
    }

    private static Path marked(Path module) throws Exception {
        Files.createDirectories(module);
        Files.writeString(module.resolve("pom.xml"), "<project/>\n");

        return module;
    }

    private static void write(Path directory, String content) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(ConfigLoader.PROJECT_CONFIG_NAME), content);
    }
}
