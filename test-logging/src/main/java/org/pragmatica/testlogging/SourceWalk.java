package org.pragmatica.testlogging;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;


/// The one directory walk for tests that scan source files from a repository, module-tree or other multi-module root (#3703).
///
/// `Files.walk` stats every entry it lists before any filter runs and cannot prune a directory, so a scan rooted above
/// several modules visits other modules' `target/` while a parallel build (`-T 1C`) creates and deletes files there; a file
/// that vanishes between the listing and the stat fails the walk with `NoSuchFileException`, in a test that has nothing to do
/// with that module. This walk never descends into a directory named in [#BUILD_OUTPUT] (below the root), so such an entry is
/// never listed, never statted and never visited.
///
/// What it still refuses, so a scan cannot become vacuous: a root that is not a readable directory, a source directory that
/// cannot be listed, and a source file that vanishes during the walk. The one tolerated vanishing is the build-output
/// directory itself (`mvn clean` removing `target/` between the parent's listing and the stat of the entry).
///
/// Returns regular files only (a symbolic link to one counts), sorted by path, so the order does not depend on the file
/// system. A scan that needs a different set of directories to skip is not a source scan: declare its own walk and say why.
/// Test-only.
public final class SourceWalk {
    /// Directory names that hold build output or tool state, never source. Pruned below the root, so a root that is itself
    /// one of them (a scan of `target/classes`) is walked.
    public static final Set<String> BUILD_OUTPUT = Set.of("target", ".git", ".m2-local", "node_modules", ".bb", ".idea");

    private SourceWalk() {}

    /// Every regular file under `root`, outside [#BUILD_OUTPUT] directories, sorted by path.
    ///
    /// @throws IOException if `root` is not a readable directory, a source directory cannot be listed, or a source entry
    ///                     vanishes during the walk
    public static List<Path> files(Path root) throws IOException {
        return files(root,
                     path -> {});
    }

    /// As [#files(Path)], calling `onReached` for every entry the walk reaches (a build-output directory it then prunes
    /// included, the entries below one never). A directory's listing is read before its first entry is reached, so a test that
    /// changes the tree from the hook changes it after the listing and before the entry is statted: the window a parallel
    /// build opens.
    static List<Path> files(Path root, Consumer<Path> onReached) throws IOException {
        if (!Files.isDirectory(root)) {
            throw Files.exists(root)
                  ? new NotDirectoryException(root.toString())
                  : new NoSuchFileException(root.toString());
        }

        var found = new ArrayList<Path>();

        Files.walkFileTree(root, new Collector(root, found, onReached));
        Collections.sort(found);

        return found;
    }

    private static final class Collector extends SimpleFileVisitor<Path> {
        private final Path root;
        private final List<Path> found;
        private final Consumer<Path> onReached;

        private Collector(Path root, List<Path> found, Consumer<Path> onReached) {
            this.root = root;
            this.found = found;
            this.onReached = onReached;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            onReached.accept(dir);

            return ! dir.equals(root) && isBuildOutput(dir.getFileName())
                   ? FileVisitResult.SKIP_SUBTREE
                   : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            onReached.accept(file);
            if (attrs.isRegularFile() || attrs.isSymbolicLink() && Files.isRegularFile(file)) {
                found.add(file);
            }

            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
            if (!file.equals(root) && isBuildOutput(file.getFileName())) {
                return FileVisitResult.CONTINUE;
            }

            throw failure;
        }

        private static boolean isBuildOutput(Path name) {
            return name != null && BUILD_OUTPUT.contains(name.toString());
        }
    }
}
