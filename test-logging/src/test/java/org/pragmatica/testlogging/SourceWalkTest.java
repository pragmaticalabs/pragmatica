package org.pragmatica.testlogging;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// #3703: the walk behind every repository-rooted source scan never visits build output, so a build file that vanishes
/// while it runs cannot fail a scan, and it still fails loudly where a scan could silently go blind.
///
/// The race is reproduced deterministically, never with a thread: a directory's listing is read before its first entry is
/// reached, so deleting the other entries of a directory from inside the walk (the `onReached` hook, or the iterator of
/// `Files.walk`) deletes entries that are listed and not yet statted, which is what a parallel module build does.
class SourceWalkTest {
    @TempDir
    Path root;

    /// The instrument check: the old walk, over a tree whose `target/classes` loses its files after the listing, fails with
    /// the very exception #3703 reports. Without this, the green tests below would prove nothing about the old failure.
    @Test
    void oldWalk_failsWhenABuildFileVanishesAfterItsDirectoryWasListed() throws IOException {
        var classes = tree();

        assertThatThrownBy(() -> walkOldWayDeletingSiblingsOfTheFirstClassFile(root, classes))
            .isInstanceOf(UncheckedIOException.class)
            .hasCauseInstanceOf(NoSuchFileException.class);
    }

    @Test
    void files_returnsTheSourceFilesAndNothingFromBuildOutput() throws IOException {
        tree();

        assertThat(relative(SourceWalk.files(root))).containsExactly("mod/src/M.java",
                                                                     "mod/src/sub/S.java",
                                                                     "nested/mod2/Two.java",
                                                                     "src/A.java");
    }

    @Test
    void files_neverEntersAnyBuildOutputDirectory() throws IOException {
        for (var name : SourceWalk.BUILD_OUTPUT) {
            var dir = Files.createDirectories(root.resolve("pkg").resolve(name).resolve("deep"));
            Files.writeString(dir.resolve("f.txt"), name);
        }
        Files.writeString(root.resolve("pkg").resolve("Kept.java"), "class Kept {}");

        var reached = new ArrayList<Path>();

        assertThat(relative(SourceWalk.files(root, reached::add))).as("returned").containsExactly("pkg/Kept.java");
        assertThat(relative(reached)).as("reached: a pruned directory itself, nothing below it")
                                     .noneMatch(path -> path.contains("/deep") || path.endsWith("/f.txt"))
                                     .contains("pkg/target", "pkg/.git", "pkg/.m2-local", "pkg/node_modules", "pkg/.bb", "pkg/.idea");
    }

    /// A root that is itself named like build output is walked: scanning `target/classes` is a legitimate use.
    @Test
    void files_walksARootThatLivesInsideBuildOutput() throws IOException {
        var classes = tree();

        assertThat(SourceWalk.files(classes)).hasSize(CLASS_FILES);
    }

    /// Build output churns while the walk runs, as it does under a parallel build: whenever the walk reaches an entry of a
    /// build-output directory, that directory's other entries are deleted. A walk that entered build output would fail on the
    /// first vanished entry; this one never reaches any, so it neither fails nor loses a source.
    @Test
    void files_survivesBuildOutputThatVanishesDuringTheWalk() throws IOException {
        tree();

        var reached = new ArrayList<Path>();
        var found = SourceWalk.files(root, path -> recordAndChurn(reached, path));

        assertThat(relative(found)).containsExactly("mod/src/M.java", "mod/src/sub/S.java", "nested/mod2/Two.java", "src/A.java");
        assertThat(relative(reached)).noneMatch(path -> path.contains("target/classes") || path.contains("target/surefire"));
    }

    /// `mvn clean` removing a build-output directory between the parent's listing and the stat of that entry: tolerated.
    @Test
    void files_toleratesABuildOutputDirectoryThatVanishesBeforeItsStat() throws IOException {
        var module = Files.createDirectories(root.resolve("mod"));
        Files.createDirectories(module.resolve("target"));
        Files.createDirectories(module.resolve(".bb"));
        Files.writeString(root.resolve("Kept.java"), "class Kept {}");

        var found = SourceWalk.files(root, path -> removeTheOtherBuildOutput(module, path));

        assertThat(relative(found)).containsExactly("Kept.java");
    }

    @Test
    void files_failsWhenASourceFileVanishesDuringTheWalk() throws IOException {
        var sources = Files.createDirectories(root.resolve("src"));

        for (var name : List.of("A.java", "B.java", "C.java")) {
            Files.writeString(sources.resolve(name), "class " + name.replace(".java", "") + " {}");
        }

        assertThatThrownBy(() -> SourceWalk.files(root, path -> deleteOtherChildren(path, sources)))
            .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void files_failsWhenASourceDirectoryCannotBeRead() throws IOException {
        var locked = Files.createDirectories(root.resolve("src").resolve("locked"));

        Files.writeString(locked.resolve("Hidden.java"), "class Hidden {}");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(locked), "the owner reads a mode-000 directory (running as root): nothing to prove here");
            assertThatThrownBy(() -> SourceWalk.files(root)).isInstanceOf(AccessDeniedException.class);
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void files_failsOnARootThatIsMissingOrNotADirectory() throws IOException {
        var file = Files.writeString(root.resolve("plain.txt"), "x");

        assertThatThrownBy(() -> SourceWalk.files(root.resolve("absent"))).isInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> SourceWalk.files(file)).isInstanceOf(NotDirectoryException.class);
    }

    private static final int CLASS_FILES = 12;

    /// Source files `src/A.java`, `mod/src/M.java`, `mod/src/sub/S.java`, `nested/mod2/Two.java`; build output beside them:
    /// `mod/target/classes` (12 class files), `mod/target/surefire`, `nested/mod2/target`, `.git`, `.m2-local`, `node_modules`,
    /// `.bb`, `.idea`. Returns `mod/target/classes`.
    private Path tree() throws IOException {
        write("src/A.java");
        write("mod/src/M.java");
        write("mod/src/sub/S.java");
        write("nested/mod2/Two.java");
        write("mod/target/surefire/report.xml");
        write("nested/mod2/target/classes/Two.class");
        write(".git/HEAD");
        write(".m2-local/repo/artifact.jar");
        write("node_modules/pkg/index.js");
        write(".bb/rc");
        write(".idea/workspace.xml");

        var classes = root.resolve("mod/target/classes");

        for (var i = 0; i < CLASS_FILES; i++) {
            write("mod/target/classes/C" + i + ".class");
        }
        return classes;
    }

    private void write(String relative) throws IOException {
        var path = root.resolve(relative);

        Files.createDirectories(path.getParent());
        Files.writeString(path, relative);
    }

    private List<String> relative(List<Path> paths) {
        return paths.stream().map(path -> root.relativize(path).toString().replace('\\', '/')).sorted().toList();
    }

    private static void walkOldWayDeletingSiblingsOfTheFirstClassFile(Path root, Path classes) throws IOException {
        var deleted = false;

        try (var walk = Files.walk(root)) {
            var entries = walk.iterator();

            while (entries.hasNext()) {
                var entry = entries.next();

                if (!deleted && entry.getParent().equals(classes)) {
                    deleteOtherChildren(entry, classes);
                    deleted = true;
                }
            }
        }
    }

    /// Deletes every other child of `directory` the first time an entry of it is reached.
    private static void deleteOtherChildren(Path reached, Path directory) {
        if (directory.equals(reached.getParent())) {
            try (var siblings = Files.list(directory)) {
                for (var sibling : siblings.filter(path -> !path.equals(reached)).toList()) {
                    Files.deleteIfExists(sibling);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private void recordAndChurn(List<Path> reached, Path path) {
        reached.add(path);
        if (isInsideBuildOutput(path)) {
            deleteOtherChildren(path, path.getParent());
        }
    }

    private boolean isInsideBuildOutput(Path path) {
        for (var name : root.relativize(path.getParent())) {
            if (SourceWalk.BUILD_OUTPUT.contains(name.toString())) {
                return true;
            }
        }
        return false;
    }

    private static void removeTheOtherBuildOutput(Path module, Path reached) {
        if (module.equals(reached.getParent())) {
            deleteTreeQuietly(module.resolve(reached.getFileName().toString().equals("target") ? ".bb" : "target"));
        }
    }

    private static void deleteTreeQuietly(Path tree) {
        try (var walk = Files.walk(tree)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            // already gone: that is the point of the call
        }
    }
}
