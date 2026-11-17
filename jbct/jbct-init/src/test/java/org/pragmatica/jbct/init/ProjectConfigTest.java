package org.pragmatica.jbct.init;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #2525: the `add-*` commands resolve `basePackage` through `ProjectConfig`; when the project's `jbct.toml` lacks the
/// key they write the resolved one into it, once, and never touch a value that is there.
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

    private Path project(String tomlContent) throws Exception {
        var dir = Files.createDirectories(tempDir.resolve("proj"));
        Files.writeString(dir.resolve("pom.xml"), POM);
        if (tomlContent != null) {
            Files.writeString(dir.resolve("jbct.toml"), tomlContent);
        }
        return dir;
    }

    private static String toml(Path dir) throws Exception {
        return Files.readString(dir.resolve("jbct.toml"));
    }
}
