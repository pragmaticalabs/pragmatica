package org.pragmatica.jbct.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.config.FilesConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/// Regression gate for #624: `jbct.includeTests` was inert for the format-family goals.
///
/// `ProcessMojo`, `FormatMojo` and `FormatCheckMojo` each declared their own `includeTests` field
/// with `defaultValue = "true"`, shadowing an `AbstractJbctMojo` field of the same name defaulting
/// to `"false"`. Collection ran in the parent and read the parent's field, and a field read is
/// statically bound — so `jbct:process` and `jbct:format` never saw `src/test/java`, and
/// `-Djbct.includeTests=true` did nothing for them. Both halves were invisible: the parameter
/// existed, was documented, appeared in the generated `plugin.xml` with `default-value=true`, and a
/// prior handover recorded the support as already working on the strength of that.
///
/// The defect class is SHADOWING, not one wrong default, so the first test targets the class rather
/// than the instance: no goal may inherit a second field of this name for its own to hide. The
/// second pins the consequence that made it matter — that the value a goal declares is the value
/// collection actually uses.
class IncludeTestsParameterTest {
    private static List<Class<?>> collectingMojos() {
        return List.of(ProcessMojo.class,
                       FormatMojo.class,
                       FormatCheckMojo.class,
                       CheckMojo.class,
                       LintMojo.class,
                       ScoreMojo.class);
    }

    @ParameterizedTest
    @MethodSource("collectingMojos")
    void includeTests_isDeclaredExactlyOnce_perMojoHierarchy(Class<?> mojo) {
        var declarations = 0;

        for (Class<?> type = mojo; type != null; type = type.getSuperclass()) {
            for (var field : type.getDeclaredFields()) {
                if (field.getName().equals("includeTests")) {
                    declarations++;
                }
            }
        }

        assertThat(declarations)
                  .as("%s: a second inherited 'includeTests' would be shadowed and silently ignored",
                      mojo.getSimpleName())
                  .isEqualTo(1);
    }

    @Test
    void collectJavaFiles_honoursTheValueThatIsPassedIn(@TempDir Path dir) throws Exception {
        var main = Files.createDirectories(dir.resolve("src/main/java"));
        var test = Files.createDirectories(dir.resolve("src/test/java"));

        Files.writeString(main.resolve("Main.java"), "package p; class Main {}\n");
        Files.writeString(test.resolve("MainTest.java"), "package p; class MainTest {}\n");

        var mojo = new ProcessMojo();

        mojo.sourceDirectory = main.toFile();
        mojo.testSourceDirectory = test.toFile();

        assertThat(mojo.collectJavaFiles(FilesConfig.DEFAULT, false))
                  .extracting(path -> path.getFileName().toString())
                  .containsExactly("Main.java");

        assertThat(mojo.collectJavaFiles(FilesConfig.DEFAULT, true))
                  .extracting(path -> path.getFileName().toString())
                  .containsExactlyInAnyOrder("Main.java", "MainTest.java");
    }
}
