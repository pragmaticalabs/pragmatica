package org.pragmatica.jbct.init;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.config.ConfigLoader;
import org.pragmatica.lang.Option;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #2525: the `add-*` commands resolve `basePackage` through `ProjectConfig`; when the project's `jbct.toml` lacks the
/// key they write the resolved one into it, once, and never touch a value that is there. The write goes through the
/// loader: chain-aware, validated, loaded before the atomic move, original intact on any failure.
class ProjectConfigTest {
    @TempDir
    Path tempDir;

    private static final String POM = """
        <project>
            <groupId>com.example</groupId>
            <artifactId>my-slice</artifactId>
        </project>
        """;

    @Test
    void projectConfig_tomlWithoutKey_writesDerivedValueUnderExistingProjectTable() throws Exception {
        var dir = project("[format]\nmaxLineLength = 120\n\n[project]\nsourceDirectories = [\"src/main/java\"]\n");

        var config = ProjectConfig.projectConfig(dir).unwrap();

        assertThat(config.basePackage()).isEqualTo("com.example.myslice");
        assertThat(toml(dir)).isEqualTo(
            "[format]\nmaxLineLength = 120\n\n[project]\nbasePackage = \"com.example.myslice\"\nsourceDirectories = [\"src/main/java\"]\n");
    }

    @Test
    void projectConfig_tomlWithoutProjectTable_appendsOneProjectTable() throws Exception {
        var dir = project("[format]\nmaxLineLength = 120\n");

        ProjectConfig.projectConfig(dir).unwrap();

        assertThat(toml(dir)).isEqualTo("[format]\nmaxLineLength = 120\n\n[project]\nbasePackage = \"com.example.myslice\"\n");
    }

    @Test
    void projectConfig_secondCall_writesTheKeyOnce() throws Exception {
        var dir = project("[format]\nmaxLineLength = 120\n");

        ProjectConfig.projectConfig(dir).unwrap();
        var afterFirst = toml(dir);
        ProjectConfig.projectConfig(dir).unwrap();

        assertThat(toml(dir)).isEqualTo(afterFirst);
        assertThat(toml(dir).split("basePackage")).hasSize(2);
    }

    @Test
    void projectConfig_tomlWithKey_keepsItAndLeavesTheFileAlone() throws Exception {
        var original = "[project]\nbasePackage = \"org.other.name\"\n";
        var dir = project(original);

        var config = ProjectConfig.projectConfig(dir).unwrap();

        assertThat(config.basePackage()).isEqualTo("org.other.name");
        assertThat(toml(dir)).isEqualTo(original);
    }

    @Test
    void projectConfig_noToml_derivesAndCreatesNoFile() throws Exception {
        var dir = project(null);

        var config = ProjectConfig.projectConfig(dir).unwrap();

        assertThat(config.basePackage()).isEqualTo("com.example.myslice");
        assertThat(dir.resolve("jbct.toml")).doesNotExist();
    }

    @Test
    void projectConfig_parentTomlSetsTheKey_writesNothingAndReturnsTheChainValue() throws Exception {
        var dir = project("[format]\nmaxLineLength = 120\n");
        Files.writeString(tempDir.resolve("jbct.toml"), "[project]\nbasePackage = \"org.group.shared\"\n");

        var config = ProjectConfig.projectConfig(dir).unwrap();

        assertThat(config.basePackage()).isEqualTo("org.group.shared");
        assertThat(toml(dir)).isEqualTo("[format]\nmaxLineLength = 120\n");
    }

    @Test
    void projectConfig_derivedValueTheLoaderRefuses_failsNamingTheValueAndLeavesTheFileAlone() throws Exception {
        var original = "[format]\nmaxLineLength = 120\n";
        var dir = project(original, POM.replace("com.example", "com.my-co"));

        var result = ProjectConfig.projectConfig(dir);

        assertThat(result.isFailure()).isTrue();
        result.onSuccess(_ -> fail("a refused value must not be reported as written"))
              .onFailure(cause -> assertThat(cause.message()).contains("com.my-co.myslice")
                                                             .contains("set it by hand"));
        assertThat(toml(dir)).isEqualTo(original);
        assertThat(dir.resolve("jbct.toml.tmp")).doesNotExist();
    }

    @Test
    void projectConfig_projectHeaderWithTrailingComment_failsWithTheLoadersCauseAndNeverDuplicatesTheTable() throws Exception {
        // the TOML parser (not edited here) refuses a comment after a table header, so the file is unloadable before any write
        var original = "[project]  # the module\nsourceDirectories = [\"src/main/java\"]\n";
        var dir = project(original);

        var result = ProjectConfig.projectConfig(dir);

        assertThat(result.isFailure()).isTrue();
        assertThat(toml(dir)).isEqualTo(original);
        assertThat(dir.resolve("jbct.toml.tmp")).doesNotExist();
    }

    @Test
    void projectConfig_headerTheDetectionMisses_isCaughtByTheLoadBeforeTheMove() throws Exception {
        // `["project"]` is the same table to the parser but not to the detection pattern: the appended second [project]
        // table is a DuplicateSection, which only the load-before-move check can see
        var original = "[\"project\"]\nsourceDirectories = [\"src/main/java\"]\n";
        var dir = project(original);

        var result = ProjectConfig.projectConfig(dir);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("Cannot write basePackage"));
        assertThat(toml(dir)).isEqualTo(original);
        assertThat(dir.resolve("jbct.toml.tmp")).doesNotExist();
    }

    @Test
    void projectConfig_projectHeaderAtEndOfFileWithoutNewline_insertsUnderItAndTheFileLoads() throws Exception {
        var dir = project("[format]\nmaxLineLength = 120\n\n[project]");

        var config = ProjectConfig.projectConfig(dir).unwrap();

        assertThat(toml(dir).split("\\[project\\]")).hasSize(2);
        assertThat(loadedBasePackage(dir)).isEqualTo(Option.some(config.basePackage()));
    }

    @Test
    void projectConfig_writeFails_reportsFailureAndLeavesTheFileAlone() throws Exception {
        var original = "[format]\nmaxLineLength = 120\n";
        var dir = project(original);
        // a non-empty directory squats on the temp name: the temp write cannot succeed, on any platform and user
        var squatter = Files.createDirectories(dir.resolve("jbct.toml.tmp"));
        Files.writeString(squatter.resolve("keep"), "x");

        var result = ProjectConfig.projectConfig(dir);

        assertThat(result.isFailure()).isTrue();
        result.onSuccess(_ -> fail("a failed write must not be reported as success"))
              .onFailure(cause -> assertThat(cause.message()).contains("Cannot write basePackage"));
        assertThat(toml(dir)).isEqualTo(original);
    }

    private static Option<String> loadedBasePackage(Path dir) {
        return ConfigLoader.loadForSourceRoot(Option.none(), dir).unwrap().basePackage();
    }

    private Path project(String tomlContent) throws Exception {
        return project(tomlContent, POM);
    }

    private Path project(String tomlContent, String pom) throws Exception {
        Files.createDirectories(tempDir.resolve(".git"));
        var dir = Files.createDirectories(tempDir.resolve("proj"));
        Files.writeString(dir.resolve("pom.xml"), pom);
        if (tomlContent != null) {
            Files.writeString(dir.resolve("jbct.toml"), tomlContent);
        }
        return dir;
    }

    private static String toml(Path dir) throws Exception {
        return Files.readString(dir.resolve("jbct.toml"));
    }
}
