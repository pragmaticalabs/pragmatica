package org.pragmatica.jbct.init;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.jbct.shared.GitHubContentFetcher;
import org.pragmatica.jbct.shared.HttpClients;
import org.pragmatica.jbct.shared.PathValidation;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// Installs AI tools (Claude Code skills and agents) to project's .claude/ directory.
/// The tools are fetched at the tag `ai-tools-v<jbct version>` of the running build (#2363), never `main`
/// ([AiToolsRef]). Uses an offline cache at ~/.jbct/cache/ai-tools/ for faster installs, refreshing it
/// whenever the commit the tag points at, or the tag itself, differs from the cached one; the cache records the
/// ref it holds in `.ref` and is reused offline only when it holds this build's ref. A refresh removes the stamp,
/// then the cached `skills/` and `agents/`, before the first file is written, so the cache never mixes refs.
/// Files already present in the user's global ~/.claude/ are skipped (resolved globally).
public final class AiToolsInstaller {
    private static final Path CACHE_DIR = Path.of(System.getProperty("user.home"), ".jbct", "cache", "ai-tools");

    private static final Path GLOBAL_CLAUDE_DIR = Path.of(System.getProperty("user.home"), ".claude");
    private static final String VERSION_FILE = ".ref";
    private static final String AI_TOOLS_PREFIX = "ai-tools/";

    private final Path claudeDir;
    private final HttpOperations http;
    private final Path cacheDir;
    private final Result<AiToolsRef> ref;

    private AiToolsInstaller(Path claudeDir, HttpOperations http, Path cacheDir, Result<AiToolsRef> ref) {
        this.claudeDir = claudeDir;
        this.http = http;
        this.cacheDir = cacheDir;
        this.ref = ref;
    }

    /// Create installer for project directory.
    /// AI tools will be installed to projectDir/.claude/
    public static AiToolsInstaller aiToolsInstaller(Path projectDir) {
        return new AiToolsInstaller(projectDir.resolve(".claude"),
                                    HttpClients.httpOperations(),
                                    CACHE_DIR,
                                    AiToolsRef.aiToolsRef());
    }

    // Test seam: inject the HTTP seam, the cache directory and the ref (the running build's version is fixed by the jar).
    static AiToolsInstaller aiToolsInstaller(Path projectDir,
                                             HttpOperations http,
                                             Path cacheDir,
                                             Result<AiToolsRef> ref) {
        return new AiToolsInstaller(projectDir.resolve(".claude"), http, cacheDir, ref);
    }

    /// Install AI tools from cache (refreshed to this build's tag) into the project.
    ///
    /// @return Outcome listing installed files, files skipped as already-global and the ref installed
    public Result<AiToolsOutcome> install() {
        return ref.flatMap(this::installAt);
    }

    private Result<AiToolsOutcome> installAt(AiToolsRef pinned) {
        return ensureCachePopulated(pinned).flatMap(_ -> copyFromCache(pinned));
    }

    private Result<Unit> ensureCachePopulated(AiToolsRef pinned) {
        return pinned.resolveSha(http)
                     .fold(cause -> useExistingCache(pinned, cause),
                           latestSha -> refreshCacheIfStale(pinned, latestSha));
    }

    private Result<Unit> refreshCacheIfStale(AiToolsRef pinned, String latestSha) {
        return isCacheCurrent(pinned, latestSha)
               ? Result.unitResult()
               : downloadToCache(pinned, latestSha);
    }

    // A tag the repository does not carry is never papered over by a cache; any other failure (offline) may reuse
    // a cache that holds this build's own ref, never one of another ref.
    private Result<Unit> useExistingCache(AiToolsRef pinned, Cause cause) {
        return ! (cause instanceof AiToolsRef.AiToolsRefError.TagNotFound) && isCacheOf(pinned)
               ? Result.unitResult()
               : cause.result();
    }

    private boolean isCacheCurrent(AiToolsRef pinned, String latestSha) {
        return isCacheValid() && readCachedStamp().filter(stamp -> stamp.equals(pinned.stamp(latestSha)))
                                                .isPresent();
    }

    private boolean isCacheOf(AiToolsRef pinned) {
        return isCacheValid() && readCachedStamp().filter(pinned::isStampOfThisRef)
                                                .isPresent();
    }

    private boolean isCacheValid() {
        var versionFile = cacheDir.resolve(VERSION_FILE);

        if (!Files.exists(versionFile)) {
            return false;
        }
        // Check if at least one skill and one agent exists
        var skillsDir = cacheDir.resolve("skills");
        var agentsDir = cacheDir.resolve("agents");

        return Files.exists(skillsDir) && Files.exists(agentsDir);
    }

    private Option<String> readCachedStamp() {
        var versionFile = cacheDir.resolve(VERSION_FILE);

        if (!Files.exists(versionFile)) {
            return Option.none();
        }

        try {
            return Option.option(Files.readString(versionFile).trim());
        } catch (IOException e) {
            return Option.none();
        }
    }

    // Fetched at the commit the tag resolved to, so the content is exactly what the recorded ref names. The files are
    // listed first (a ref that cannot be listed leaves the cache untouched); then the stamp goes FIRST, so a refresh
    // that dies half-way leaves a cache no ref claims, and skills/ and agents/ are emptied, so a file the ref does
    // not carry (left by another ref, or by main) cannot survive under this ref's stamp.
    private Result<Unit> downloadToCache(AiToolsRef pinned, String sha) {
        return GitHubContentFetcher.discoverFiles(http, AiToolsRef.REPO, sha, AI_TOOLS_PREFIX)
                                   .flatMap(files -> clearCache().flatMap(_ -> downloadAllFiles(sha, files)))
                                   .flatMap(_ -> saveVersion(pinned.stamp(sha)));
    }

    private Result<Unit> clearCache() {
        return Result.lift(AiToolsInstaller::clearFailure,
                           () -> Files.deleteIfExists(cacheDir.resolve(VERSION_FILE)))
                     .flatMap(_ -> deleteTree(cacheDir.resolve("skills")))
                     .flatMap(_ -> deleteTree(cacheDir.resolve("agents")));
    }

    // A symbolic link is deleted itself, never followed: walkFileTree does not follow links unless asked to.
    private static Result<Unit> deleteTree(Path root) {
        return Files.exists(root, LinkOption.NOFOLLOW_LINKS)
               ? Result.lift(AiToolsInstaller::clearFailure,
                             () -> Files.walkFileTree(root,
                                                      new DeleteTreeVisitor()))
                       .mapToUnit()
               : Result.unitResult();
    }

    private static Cause clearFailure(Throwable throwable) {
        return Causes.cause("Failed to clear the AI tools cache: " + throwable.getMessage(),
                            Option.some(Causes.fromThrowable(throwable)));
    }

    private Result<List<Path>> downloadAllFiles(String sha, List<String> remotePaths) {
        var downloads = remotePaths.stream()
                                   .map(remotePath -> remotePath.substring(AI_TOOLS_PREFIX.length()))
                                   .map(relativePath -> PathValidation.validateRelativePath(relativePath, cacheDir).flatMap(targetPath -> downloadFile(sha,
                                                                                                                                                       AI_TOOLS_PREFIX + relativePath,
                                                                                                                                                       targetPath)))
                                   .toList();

        return Result.allOf(downloads);
    }

    private Result<Path> downloadFile(String sha, String remotePath, Path targetPath) {
        return GitHubContentFetcher.downloadFile(http, AiToolsRef.REPO, sha, remotePath, targetPath).map(_ -> targetPath);
    }

    private Result<Unit> saveVersion(String stamp) {
        try {
            Files.createDirectories(cacheDir);
            var versionFile = cacheDir.resolve(VERSION_FILE);

            Files.writeString(versionFile, stamp);

            return Result.unitResult();
        } catch (IOException e) {
            return Causes.cause("Failed to save version: " + e.getMessage()).result();
        }
    }

    private Result<AiToolsOutcome> copyFromCache(AiToolsRef pinned) {
        if (!Files.exists(cacheDir)) {
            return Causes.cause("AI tools cache not found. Run 'jbct update' with network access first.").result();
        }

        var installed = new ArrayList<Path>();
        var skipped = new ArrayList<Path>();

        return Result.lift(AiToolsInstaller::copyFailure,
                           () -> Files.createDirectories(claudeDir))
                     .flatMap(_ -> copyTree(cacheDir.resolve("skills"),
                                            claudeDir.resolve("skills"),
                                            installed,
                                            skipped))
                     .flatMap(_ -> copyTree(cacheDir.resolve("agents"),
                                            claudeDir.resolve("agents"),
                                            installed,
                                            skipped))
                     .map(_ -> AiToolsOutcome.aiToolsOutcome(installed,
                                                             skipped,
                                                             pinned.tag()));
    }

    private static Cause copyFailure(Throwable throwable) {
        return Causes.cause("Failed to copy from cache: " + throwable.getMessage(),
                            Option.some(Causes.fromThrowable(throwable)));
    }

    private Result<Unit> copyTree(Path source, Path target, List<Path> installed, List<Path> skipped) {
        if (!Files.exists(source)) {
            return Result.unitResult();
        }

        return Result.lift(AiToolsInstaller::copyFailure,
                           () -> Files.walkFileTree(source,
                                                    new CopyOrSkipVisitor(source, target, installed, skipped)))
                     .mapToUnit();
    }

    /// Mirrors `source` under `target`, skipping files the user's global `~/.claude/` already provides.
    /// The `throws IOException` signatures are dictated by [java.nio.file.FileVisitor]; [#copyTree] lifts them.
    private final class CopyOrSkipVisitor extends SimpleFileVisitor<Path> {
        private final Path source;
        private final Path target;
        private final List<Path> installed;
        private final List<Path> skipped;

        private CopyOrSkipVisitor(Path source, Path target, List<Path> installed, List<Path> skipped) {
            this.source = source;
            this.target = target;
            this.installed = installed;
            this.skipped = skipped;
        }

        @Contract
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            Files.createDirectories(target.resolve(source.relativize(dir)));

            return FileVisitResult.CONTINUE;
        }

        @Contract
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            var targetFile = target.resolve(source.relativize(file));
            var relativePath = claudeDir.relativize(targetFile);

            if (existsGlobally(relativePath)) {
                skipped.add(relativePath);

                return FileVisitResult.CONTINUE;
            }

            Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
            installed.add(targetFile);

            return FileVisitResult.CONTINUE;
        }
    }

    /// Deletes a directory tree bottom-up. The `throws IOException` signatures are dictated by [java.nio.file.FileVisitor];
    /// [#deleteTree] lifts them.
    private static final class DeleteTreeVisitor extends SimpleFileVisitor<Path> {
        @Contract
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Files.delete(file);

            return FileVisitResult.CONTINUE;
        }

        @Contract
        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            Files.delete(dir);

            return FileVisitResult.CONTINUE;
        }
    }

    private static boolean existsGlobally(Path relativePath) {
        return Files.exists(GLOBAL_CLAUDE_DIR.resolve(relativePath));
    }

    /// Get the Claude directory.
    public Path claudeDir() {
        return claudeDir;
    }

    /// Get the path where skills are installed.
    public Path skillsDir() {
        return claudeDir.resolve("skills");
    }

    /// Get the path where agents are installed.
    public Path agentsDir() {
        return claudeDir.resolve("agents");
    }

    /// Get the cache directory path.
    public static Path cacheDir() {
        return CACHE_DIR;
    }
}
