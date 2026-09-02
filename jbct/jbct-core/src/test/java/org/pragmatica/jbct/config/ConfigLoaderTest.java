package org.pragmatica.jbct.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// Config layering behaviour (issue #532): every layer is folded per key, nearest wins, and the
/// `jbct.toml` walk stops at the repository boundary.
///
/// `user.home` is redirected to a scratch directory for every test so a real `~/.jbct/config.toml`
/// on the developer's machine can never leak into the assertions.
class ConfigLoaderTest {
    private static final String USER_HOME_PROPERTY = "user.home";

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

    @Nested
    class UserLayer {
        /// The live bug: `~/.jbct/config.toml` sets `failOnWarning = true`, the project config
        /// touches only a rule severity, and section-level replacement silently reverts the flag.
        @Test
        void load_keepsUserFailOnWarning_whenProjectSetsOnlyRuleSeverity() throws Exception {
            writeUserConfig("""
                            [lint]
                            failOnWarning = true
                            """);
            var project = repositoryRoot("project", """
                                                     [lint.rules]
                                                     JBCT-STY-01 = "error"
                                                     """);
            var config = load(project);

            assertThat(config.lint()
                             .failOnWarning()).isTrue();
            assertThat(config.lint()
                             .ruleSeverities()).containsEntry("JBCT-STY-01", DiagnosticSeverity.ERROR);
        }

        @Test
        void load_appliesExplicitFalse_whenProjectRestatesFailOnWarning() throws Exception {
            writeUserConfig("""
                            [lint]
                            failOnWarning = true
                            """);
            var project = repositoryRoot("project", """
                                                     [lint]
                                                     failOnWarning = false
                                                     """);

            assertThat(load(project).lint()
                                    .failOnWarning()).isFalse();
        }

        @Test
        void load_keepsUserFormatterKeys_whenProjectOverridesOneOfThem() throws Exception {
            writeUserConfig("""
                            [format]
                            maxLineLength = 100
                            indentSize = 2
                            """);
            var project = repositoryRoot("project", """
                                                     [format]
                                                     indentSize = 8
                                                     """);
            var formatter = load(project).formatter();

            assertThat(formatter.maxLineLength()).isEqualTo(100);
            assertThat(formatter.indentSize()).isEqualTo(8);
        }

        @Test
        void load_clearsUserExcludes_whenProjectSetsEmptyList() throws Exception {
            writeUserConfig("""
                            [files]
                            excludes = ["**/generated/**"]
                            """);
            var project = repositoryRoot("project", """
                                                     [files]
                                                     excludes = []
                                                     """);

            assertThat(load(project).files()
                                    .excludes()).isEmpty();
        }

        @Test
        void load_appliesExplicitDefault_whenProjectRestatesDefaultMaxLineLength() throws Exception {
            writeUserConfig("""
                            [format]
                            maxLineLength = 100
                            """);
            var project = repositoryRoot("project", """
                                                     [format]
                                                     maxLineLength = 120
                                                     """);

            assertThat(load(project).formatter()
                                    .maxLineLength()).isEqualTo(120);
        }
    }

    @Nested
    class ProjectChain {
        @Test
        void load_mergesPerKey_acrossTwoLevels() throws Exception {
            var root = repositoryRoot("repo", """
                                              [format]
                                              maxLineLength = 100

                                              [lint]
                                              failOnWarning = true
                                              """);
            var module = writeConfig(root.resolve("module"), """
                                                             [format]
                                                             indentSize = 8
                                                             """);
            var config = load(module);

            assertThat(config.formatter()
                             .maxLineLength()).isEqualTo(100);
            assertThat(config.formatter()
                             .indentSize()).isEqualTo(8);
            assertThat(config.lint()
                             .failOnWarning()).isTrue();
        }

        @Test
        void load_mergesPerKey_acrossThreeLevels() throws Exception {
            var root = repositoryRoot("repo", """
                                              [format]
                                              maxLineLength = 100
                                              useTabs = true

                                              [blueprint]
                                              schema = "external"

                                              [project]
                                              sourceDirectories = ["src/main/java", "src/gen/java"]
                                              """);
            var middle = writeConfig(root.resolve("middle"), """
                                                             [format]
                                                             indentSize = 8

                                                             [blueprint]
                                                             schema = "optional"
                                                             """);
            var leaf = writeConfig(middle.resolve("leaf"), """
                                                           [format]
                                                           organizeImports = false
                                                           """);
            var config = load(leaf);

            assertThat(config.formatter()
                             .maxLineLength()).isEqualTo(100);
            assertThat(config.formatter()
                             .useTabs()).isTrue();
            assertThat(config.formatter()
                             .indentSize()).isEqualTo(8);
            assertThat(config.formatter()
                             .organizeImports()).isFalse();
            assertThat(config.blueprint()
                             .schema()).isEqualTo(BlueprintConfig.SchemaMode.OPTIONAL);
            assertThat(config.sourceDirectories()).containsExactly("src/main/java", "src/gen/java");
        }

        @Test
        void load_appliesExplicitDefault_whenNearerLayerRestatesDefaultValue() throws Exception {
            var root = repositoryRoot("repo", """
                                              [format]
                                              maxLineLength = 100
                                              """);
            var module = writeConfig(root.resolve("module"), """
                                                             [format]
                                                             maxLineLength = 120
                                                             """);

            assertThat(load(module).formatter()
                                   .maxLineLength()).isEqualTo(120);
        }

        @Test
        void load_clearsInheritedList_whenNearerLayerSetsEmptyList() throws Exception {
            var root = repositoryRoot("repo", """
                                              [files]
                                              excludes = ["**/generated/**"]
                                              """);
            var module = writeConfig(root.resolve("module"), """
                                                             [files]
                                                             excludes = []
                                                             """);

            assertThat(load(module).files()
                                   .excludes()).isEmpty();
        }

        @Test
        void load_keepsSiblingKey_whenNearerLayerSetsOnlyMaxFileSize() throws Exception {
            var root = repositoryRoot("repo", """
                                              [files]
                                              excludes = ["**/generated/**"]
                                              """);
            var module = writeConfig(root.resolve("module"), """
                                                             [files]
                                                             maxFileSize = 2048
                                                             """);
            var files = load(module).files();

            assertThat(files.excludes()).containsExactly("**/generated/**");
            assertThat(files.maxFileSize()).isEqualTo(2048L);
        }

        @Test
        void load_mergesLayerGlobsPerKey_acrossLevels() throws Exception {
            var root = repositoryRoot("repo", """
                                              [lint.layers]
                                              domain = ["com.example.core.**"]
                                              """);
            var module = writeConfig(root.resolve("module"), """
                                                             [lint.layers]
                                                             adapter = ["com.example.adapter.**"]
                                                             """);
            var layers = load(module).layers();

            assertThat(layers.domainGlobs()).containsExactly("com.example.core.**");
            assertThat(layers.adapterGlobs()).containsExactly("com.example.adapter.**");
        }

        @Test
        void load_reEnablesRule_whenNearerLayerOverridesOffSetting() throws Exception {
            var root = repositoryRoot("repo", """
                                              [lint.rules]
                                              JBCT-STY-01 = "off"
                                              JBCT-LAM-01 = "off"
                                              """);
            var module = writeConfig(root.resolve("module"), """
                                                             [lint.rules]
                                                             JBCT-STY-01 = "error"
                                                             """);
            var lint = load(module).lint();

            assertThat(lint.disabledRules()).contains("JBCT-LAM-01");
            assertThat(lint.disabledRules()).doesNotContain("JBCT-STY-01");
            assertThat(lint.ruleSeverities()).containsEntry("JBCT-STY-01", DiagnosticSeverity.ERROR);
        }

        @Test
        void load_keepsInheritedRuleSeverity_whenNearerLayerTouchesAnotherRule() throws Exception {
            var root = repositoryRoot("repo", """
                                              [lint.rules]
                                              JBCT-STY-01 = "error"
                                              """);
            var module = writeConfig(root.resolve("module"), """
                                                             [lint.rules]
                                                             JBCT-LAM-01 = "info"
                                                             """);
            var lint = load(module).lint();

            assertThat(lint.ruleSeverities()).containsEntry("JBCT-STY-01", DiagnosticSeverity.ERROR);
            assertThat(lint.ruleSeverities()).containsEntry("JBCT-LAM-01", DiagnosticSeverity.INFO);
        }

        @Test
        void load_keepsRepositoryRootExcludes_whenModuleHasOwnConfig() throws Exception {
            var root = repositoryRoot("repo", """
                                              [files]
                                              excludes = ["**/jbct/parser/Java25Parser.java"]
                                              """);
            var module = writeConfig(root.resolve("module"), """
                                                             [lint]
                                                             failOnWarning = true
                                                             """);

            assertThat(load(module).files()
                                   .excludes()).containsExactly("**/jbct/parser/Java25Parser.java");
        }

        @Test
        void load_keepsRepositoryRootExcludes_whenModuleHasNoConfig() throws Exception {
            var root = repositoryRoot("repo", """
                                              [files]
                                              excludes = ["**/jbct/parser/Java25Parser.java"]
                                              """);
            var module = Files.createDirectories(root.resolve("module"));

            assertThat(load(module).files()
                                   .excludes()).containsExactly("**/jbct/parser/Java25Parser.java");
        }
    }

    @Nested
    class RepositoryBoundary {
        @Test
        void load_ignoresConfig_aboveGitDirectory() throws Exception {
            var workspace = writeConfig(tempDir.resolve("workspace"), """
                                                                      [format]
                                                                      maxLineLength = 66
                                                                      """);
            var root = repositoryRoot(workspace.resolve("repo"), """
                                                                 [lint]
                                                                 failOnWarning = true
                                                                 """);

            assertThat(load(root).formatter()
                                 .maxLineLength()).isEqualTo(120);
        }

        @Test
        void load_ignoresConfig_aboveGitFileOfWorktree() throws Exception {
            var workspace = writeConfig(tempDir.resolve("workspace"), """
                                                                      [format]
                                                                      maxLineLength = 66
                                                                      """);
            var worktree = writeConfig(workspace.resolve("worktree"), """
                                                                      [lint]
                                                                      failOnWarning = true
                                                                      """);

            Files.writeString(worktree.resolve(".git"), "gitdir: /elsewhere/.git/worktrees/wt\n");

            assertThat(load(worktree).formatter()
                                     .maxLineLength()).isEqualTo(120);
        }

        @Test
        void load_includesConfig_atGitBoundaryItself() throws Exception {
            var root = repositoryRoot("repo", """
                                              [format]
                                              maxLineLength = 88
                                              """);
            var module = Files.createDirectories(root.resolve("module"));

            assertThat(load(module).formatter()
                                   .maxLineLength()).isEqualTo(88);
        }
    }

    @Nested
    class ExplicitConfig {
        @Test
        void load_overridesPerKey_whenExplicitConfigGiven() throws Exception {
            var root = repositoryRoot("repo", """
                                              [format]
                                              maxLineLength = 100
                                              indentSize = 2

                                              [files]
                                              excludes = ["**/generated/**"]
                                              """);
            var explicit = tempDir.resolve("explicit.toml");

            Files.writeString(explicit, """
                                        [format]
                                        indentSize = 8
                                        """);

            var config = ConfigLoader.load(Option.some(explicit), Option.some(root));

            assertThat(config.formatter()
                             .indentSize()).isEqualTo(8);
            assertThat(config.formatter()
                             .maxLineLength()).isEqualTo(100);
            assertThat(config.files()
                             .excludes()).containsExactly("**/generated/**");
        }
    }

    private static JbctConfig load(Path workingDirectory) {
        return ConfigLoader.load(Option.none(), Option.some(workingDirectory));
    }

    private void writeUserConfig(String content) throws Exception {
        var userConfigDir = Files.createDirectories(tempDir.resolve("home")
                                                           .resolve(ConfigLoader.USER_CONFIG_DIR));

        Files.writeString(userConfigDir.resolve(ConfigLoader.USER_CONFIG_NAME), content);
    }

    private Path repositoryRoot(String name, String content) throws Exception {
        return repositoryRoot(tempDir.resolve(name), content);
    }

    private static Path repositoryRoot(Path directory, String content) throws Exception {
        var root = writeConfig(directory, content);

        Files.createDirectories(root.resolve(".git"));

        return root;
    }

    private static Path writeConfig(Path directory, String content) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(ConfigLoader.PROJECT_CONFIG_NAME), content);

        return directory;
    }
}
