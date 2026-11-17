package org.pragmatica.jbct.cli;

import java.nio.file.Files;
import java.nio.file.Path;

import org.pragmatica.jbct.config.ConfigLoader;
import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.init.ProjectInitializer;
import org.pragmatica.jbct.init.SliceProjectInitializer;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #2525: a generated project's `jbct.toml` declares `mode = "business"` and its own `basePackage`, in one
/// `[project]` table, and the config loader reads both back. Both generators are covered: the plain project
/// (the resource template, `jbct init --no-slice`) and the slice project (the inline template).
class GeneratedConfigTemplateTest {
    @TempDir
    Path tempDir;

    @Test
    void plainProjectTemplate_declaresBusinessModeAndBasePackage_inOneProjectTable() throws Exception {
        var projectDir = tempDir.resolve("plain");

        ProjectInitializer.projectInitializerOffline(projectDir, "org.example", "my-app")
                          .initialize()
                          .unwrap();

        assertDeclared(projectDir, "org.example.myapp");
    }

    @Test
    void sliceProjectTemplate_declaresBusinessModeAndBasePackage_inOneProjectTable() throws Exception {
        var projectDir = tempDir.resolve("slice");

        SliceProjectInitializer.sliceProjectInitializer(projectDir, "org.example", "my-slice")
                               .flatMap(SliceProjectInitializer::initialize)
                               .unwrap();

        assertDeclared(projectDir, "org.example.myslice");
    }

    private static void assertDeclared(Path projectDir, String basePackage) throws Exception {
        var toml = projectDir.resolve("jbct.toml");
        var text = Files.readString(toml);
        var config = ConfigLoader.load(Option.some(toml), Option.none()).unwrap();

        assertThat(text.split("\\[project\\]", -1)).hasSize(2);
        assertThat(text).contains("mode = \"business\"");
        assertThat(config.mode()).isEqualTo(JbctMode.BUSINESS);
        assertThat(config.basePackage()).isEqualTo(Option.some(basePackage));
    }
}
