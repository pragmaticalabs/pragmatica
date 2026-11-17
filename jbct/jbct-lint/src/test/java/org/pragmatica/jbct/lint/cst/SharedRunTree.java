package org.pragmatica.jbct.lint.cst;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// The one tree and the one expected text that `jbct-cli` (`CliSharedRunTest`) and `jbct-maven-plugin` (`MojoSharedRunTest`)
/// share through this module's test-jar (#2525 P2c, N8): a business module `biz` and an infrastructure module `infra`, BOTH main
/// roots, one directory above them. Each front end reaches the tree its own way (the CLI as a directory argument, a goal as its
/// `sourceDirectory`) and must print exactly these lines, so the claim "same roots, modes and counts through either front end"
/// is one text compared twice, never two copies kept in step by a comment. The lines carry the test-root count: a main root
/// prints none, so a root that turned into a test root in one front end would show as a different line.
public final class SharedRunTree {
    private SharedRunTree() {}

    /// Build the tree below `repo` (marked as a repository root) and return `repo`.
    public static Path build(Path repo) throws IOException {
        Files.createDirectories(repo.resolve(".git"));
        module(repo, "biz", "business");
        module(repo, "infra", "infrastructure");

        return repo;
    }

    /// The module's main source root, as the summary line names it.
    public static Path mainRoot(Path repo, String name) {
        return repo.resolve(name)
                   .resolve("src/main/java")
                   .toAbsolutePath()
                   .normalize();
    }

    /// The violating source file of a module.
    public static Path source(Path repo, String name) {
        return mainRoot(repo, name).resolve("org/acme/" + name + "/domain/pricing/" + name + "Price.java");
    }

    /// The per-root summary lines both front ends print for [#build]`(repo)` in a lint run, in root order.
    public static List<String> expectedSummaryLines(Path repo) {
        return List.of(summary(repo, "biz", "business", ""), summary(repo, "infra", "infrastructure", "; structure checks off"));
    }

    /// The run's outcome line both front ends print for a lint run over the tree: both modules' files were linted.
    public static List<String> expectedLintLines() {
        return List.of("2 file(s) linted");
    }

    /// The findings by rule and module a lint of the tree yields, in any order.
    public static List<String> expectedFindings() {
        return List.of("JBCT-ARCH-02@biz", "JBCT-RET-03@biz", "JBCT-RET-03@infra");
    }

    private static String summary(Path repo, String name, String mode, String note) {
        return "mode=" + mode
               + " (from " + repo.resolve(name).resolve("jbct.toml").toAbsolutePath().normalize()
               + ") root=" + mainRoot(repo, name)
               + " basePackage=org.acme." + name
               + ": 1 examined by rules, 0 formatted or format-checked, 0 skipped by mode; test-root rule: src/test relative to the module directory"
               + note;
    }

    private static void module(Path repo, String name, String mode) throws IOException {
        var module = Files.createDirectories(repo.resolve(name));

        Files.writeString(module.resolve("pom.xml"), "<project/>\n");
        Files.writeString(module.resolve("jbct.toml"),
                          "[project]\nbasePackage = \"org.acme." + name + "\"\n[lint]\nmode = \"" + mode + "\"\n");
        Files.createDirectories(source(repo, name).getParent());
        Files.writeString(source(repo, name), violating(name));
    }

    private static String violating(String name) {
        return """
               package org.acme.%s.domain.pricing;

               public class %sPrice {
                   Object run() {
                       var attempt = Result.lift(Err::new, () -> 1);
                       return null;
                   }
               }
               """.formatted(name, name);
    }
}
