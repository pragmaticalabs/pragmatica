package org.pragmatica.jbct.init;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #2385: the copy from the AI-tools cache into a project (`copyFromCache`, its visitor and `copyFailure`).
///
/// The cache and the global `~/.claude/` are static paths derived from `user.home`, and `install()` reaches the
/// network first, so each case runs [#main] in a child JVM whose `user.home` is a fixture directory and calls the
/// private `copyFromCache` directly. The child prints the outcome; the parent asserts on it and on the files.
class AiToolsInstallerCopyTest {
    private static final String CACHE = ".jbct/cache/ai-tools";

    /// Child-JVM entry: `args[0]` is the project directory.
    public static void main(String[] args) throws Exception {
        var project = Path.of(args[0]);
        Method copyFromCache = AiToolsInstaller.class.getDeclaredMethod("copyFromCache");

        copyFromCache.setAccessible(true);
        ((Result<?>) copyFromCache.invoke(AiToolsInstaller.aiToolsInstaller(project)))
            .onSuccess(outcome -> System.out.println(render(project, (AiToolsOutcome) outcome)))
            .onFailure(cause -> System.out.println("FAILURE=" + cause.message()));
    }

    private static String render(Path project, AiToolsOutcome outcome) {
        return "INSTALLED=" + outcome.installed().stream().map(path -> project.relativize(path).toString()).sorted().toList()
               + "\nSKIPPED=" + outcome.skippedGlobal().stream().map(Path::toString).sorted().toList();
    }

    @Test
    void copyFromCache_copiesNestedFiles_skipsGloballyPresentOnes_andMirrorsDirectories(@TempDir Path dir) throws Exception {
        var home = fixtureHome(dir);
        var project = Files.createDirectories(dir.resolve("project"));

        var output = runChild(home, project);

        assertThat(output).contains("INSTALLED=[.claude/agents/b.md, .claude/skills/a/SKILL.md]")
                          .contains("SKIPPED=[skills/a/sub/x.md]");
        assertThat(project.resolve(".claude/skills/a/sub")).as("directories are mirrored even when their only file is skipped")
                                                           .isDirectory();
        assertThat(project.resolve(".claude/skills/a/sub/x.md")).as("a globally present file is not copied").doesNotExist();
        assertThat(Files.readString(project.resolve(".claude/skills/a/SKILL.md"))).isEqualTo("s");
    }

    @Test
    void copyFromCache_missingCacheSubtree_isNotAFailure(@TempDir Path dir) throws Exception {
        var home = fixtureHome(dir);
        var project = Files.createDirectories(dir.resolve("project"));

        deleteTree(home.resolve(CACHE).resolve("agents"));

        assertThat(runChild(home, project)).contains("INSTALLED=[.claude/skills/a/SKILL.md]");
    }

    @Test
    void copyFromCache_claudeDirIsAFile_failsWithCopyFailure(@TempDir Path dir) throws Exception {
        var home = fixtureHome(dir);
        var project = Files.createDirectories(dir.resolve("project"));

        Files.writeString(project.resolve(".claude"), "a file where a directory must go");

        assertThat(runChild(home, project)).contains("FAILURE=Failed to copy from cache: ");
    }

    @Test
    void copyFromCache_skillsTargetIsAFile_failsWithCopyFailure_andStopsBeforeAgents(@TempDir Path dir) throws Exception {
        var home = fixtureHome(dir);
        var project = Files.createDirectories(dir.resolve("project").resolve(".claude")).getParent();

        Files.writeString(project.resolve(".claude/skills"), "a file where a directory must go");

        assertThat(runChild(home, project)).contains("FAILURE=Failed to copy from cache: ");
        assertThat(project.resolve(".claude/agents/b.md")).as("agents are not copied after skills failed").doesNotExist();
    }

    private static Path fixtureHome(Path dir) throws Exception {
        var home = dir.resolve("home");
        var cache = home.resolve(CACHE);

        Files.createDirectories(cache.resolve("skills/a/sub"));
        Files.createDirectories(cache.resolve("agents"));
        Files.writeString(cache.resolve("skills/a/SKILL.md"), "s");
        Files.writeString(cache.resolve("skills/a/sub/x.md"), "x");
        Files.writeString(cache.resolve("agents/b.md"), "b");
        Files.createDirectories(home.resolve(".claude/skills/a/sub"));
        Files.writeString(home.resolve(".claude/skills/a/sub/x.md"), "global");

        return home;
    }

    private static String runChild(Path home, Path project) throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var output = home.resolveSibling("child-output.txt");
        var process = new ProcessBuilder(List.of(java,
                                                 "-Duser.home=" + home,
                                                 "-cp",
                                                 System.getProperty("java.class.path"),
                                                 AiToolsInstallerCopyTest.class.getName(),
                                                 project.toString()))
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
        var finished = process.waitFor(60, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
        }
        var text = Files.readString(output);

        assertThat(finished).as("child JVM finished; output:\n" + text).isTrue();
        assertThat(process.exitValue()).as("child JVM output:\n" + text).isZero();

        return text;
    }

    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }
}
