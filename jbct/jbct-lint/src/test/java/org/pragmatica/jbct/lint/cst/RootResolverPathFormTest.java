package org.pragmatica.jbct.lint.cst;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.pragmatica.jbct.lint.cst.RootResolver.Request;
import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;
import org.pragmatica.jbct.lint.cst.RunOutcome.Invocation;
import org.pragmatica.jbct.lint.cst.RunOutcome.Surface;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// The path a run prints for a file is the form its argument gave (#2525, R-b: as the release printed it): relative
/// to the argument as given, absolute only when the argument was absolute. Resolution, ownership and dedupe keep
/// reading the normalised path; only the printed form travels with the collected file. The relative fixtures live
/// under the module's `target/` directory, which is the working directory of the test JVM.
class RootResolverPathFormTest {
    private static final Path FIXTURE = Path.of("target/pathform");

    @TempDir
    Path temp;

    @BeforeEach
    void fixture() throws IOException {
        delete(FIXTURE);
        Files.createDirectories(FIXTURE.resolve("i/src"));
        Files.writeString(FIXTURE.resolve("i/src/Ugly.java"), "class Ugly {}\n");
        Files.writeString(FIXTURE.resolve("i/src/Broken.java"), "class {{{ not java");
        Files.createDirectories(temp.resolve("src"));
        Files.writeString(temp.resolve("src/Ugly.java"), "class Ugly {}\n");
    }

    @AfterEach
    void cleanUp() throws IOException {
        delete(FIXTURE);
    }

    private static void delete(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        try (var walk = Files.walk(directory)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static RootPlan plan(Path... paths) {
        return RootResolver.resolve(Request.request(List.of(paths)))
                           .fold(cause -> fail("resolve failed: " + cause.message()), plan -> plan);
    }

    @Test
    void printedPath_relativeDirectoryFromTheParent_isTheArgumentRelativeForm() {
        var plan = plan(Path.of("target/pathform/i/src"));

        assertEquals(List.of(Path.of("target/pathform/i/src/Broken.java"), Path.of("target/pathform/i/src/Ugly.java")),
                     plan.files().stream().sorted().toList());
        assertEquals("target/pathform/i/src/Ugly.java",
                     plan.files().stream().filter(file -> file.endsWith("Ugly.java")).findFirst().orElseThrow().toString());
    }

    @Test
    void printedPath_dotArgument_isTheDotSlashForm() {
        var plan = plan(Path.of("."));

        assertTrue(plan.files().contains(Path.of("./src/test/java/org/pragmatica/jbct/lint/cst/RootResolverPathFormTest.java")));
        assertEquals("./src/test/java/org/pragmatica/jbct/lint/cst/RootResolverPathFormTest.java",
                     plan.files()
                         .stream()
                         .filter(file -> file.endsWith("RootResolverPathFormTest.java"))
                         .findFirst()
                         .orElseThrow()
                         .toString());
        assertFalse(plan.files().stream().anyMatch(Path::isAbsolute));
    }

    @Test
    void printedPath_relativeSingleFile_isTheArgumentItself() {
        var plan = plan(Path.of("target/pathform/i/src/Ugly.java"));

        assertEquals(List.of(Path.of("target/pathform/i/src/Ugly.java")), plan.files());
    }

    @Test
    void printedPath_absoluteArgument_isAbsolute() {
        var plan = plan(temp.resolve("src"));

        assertEquals(List.of(temp.resolve("src/Ugly.java")), plan.files());
        assertTrue(plan.files().getFirst().isAbsolute());
    }

    @Test
    void printedPath_twoArgumentsNamingOneFileInDifferentForms_isTheFirstArgumentsForm() {
        var absolute = Path.of("target/pathform/i/src/Ugly.java").toAbsolutePath().normalize();
        var relativeFirst = plan(Path.of("target/pathform/i/src/Ugly.java"), absolute);
        var absoluteFirst = plan(absolute, Path.of("target/pathform/i/src/Ugly.java"));

        assertEquals(List.of(Path.of("target/pathform/i/src/Ugly.java")), relativeFirst.files());
        assertEquals(List.of(absolute), absoluteFirst.files());
    }

    @Test
    void printedPath_aRun_carriesTheArgumentRelativeFormToItsFileResults() {
        var result = LintRun.lintRun(plan(Path.of("target/pathform/i/src")))
                            .perform(Invocation.lint(Surface.CLI))
                            .fold(cause -> fail("perform failed: " + cause.message()), run -> run);

        assertEquals(List.of(Path.of("target/pathform/i/src/Broken.java"), Path.of("target/pathform/i/src/Ugly.java")),
                     result.files().stream().map(file -> file.file()).sorted().toList());
        assertEquals(List.of(Path.of("target/pathform/i/src/Broken.java")),
                     result.unanalysed().stream().map(unanalysed -> unanalysed.file()).toList());
    }
}
