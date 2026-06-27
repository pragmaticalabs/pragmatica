package org.pragmatica.jbct.init;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.jbct.shared.GitHubContentFetcher;
import org.pragmatica.jbct.shared.HttpClients;
import org.pragmatica.jbct.shared.PathValidation;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/// Installs AI tools (Claude Code skills and agents) to project's .claude/ directory.
/// Uses an offline cache at ~/.jbct/cache/ai-tools/ for faster installs, refreshing it
/// whenever the latest commit on the source repository differs from the cached one.
/// Files already present in the user's global ~/.claude/ are skipped (resolved globally).
public final class AiToolsInstaller {
    private static final Path CACHE_DIR = Path.of(System.getProperty("user.home"), ".jbct", "cache", "ai-tools");
    private static final Path GLOBAL_CLAUDE_DIR = Path.of(System.getProperty("user.home"), ".claude");
    private static final String VERSION_FILE = ".sha";
    private static final String REPO = "siy/coding-technology";
    private static final String BRANCH = "main";
    private static final String AI_TOOLS_PREFIX = "ai-tools/";

    private final Path claudeDir;

    private AiToolsInstaller(Path claudeDir) {
        this.claudeDir = claudeDir;
    }

    /// Create installer for project directory.
    /// AI tools will be installed to projectDir/.claude/
    public static AiToolsInstaller aiToolsInstaller(Path projectDir) {
        return new AiToolsInstaller(projectDir.resolve(".claude"));
    }

    /// Install AI tools from cache (refreshed to the latest commit) into the project.
    ///
    /// @return Outcome listing installed files and files skipped as already-global
    public Result<AiToolsOutcome> install() {
        return ensureCachePopulated().flatMap(_ -> copyFromCache());
    }

    private Result<Unit> ensureCachePopulated() {
        var http = HttpClients.httpOperations();
        return GitHubContentFetcher.getLatestCommitSha(http, REPO, BRANCH)
                                   .fold(this::useExistingCache, latestSha -> refreshCacheIfStale(http, latestSha));
    }

    private Result<Unit> refreshCacheIfStale(HttpOperations http, String latestSha) {
        return isCacheCurrent(latestSha)
               ? Result.unitResult()
               : downloadToCache(http, latestSha);
    }

    private Result<Unit> useExistingCache(Cause cause) {
        return isCacheValid()
               ? Result.unitResult()
               : cause.result();
    }

    private boolean isCacheCurrent(String latestSha) {
        return isCacheValid() && readCachedSha().filter(sha -> sha.equals(latestSha))
                                                .isPresent();
    }

    private boolean isCacheValid() {
        var versionFile = CACHE_DIR.resolve(VERSION_FILE);
        if (!Files.exists(versionFile)) {
            return false;
        }
        // Check if at least one skill and one agent exists
        var skillsDir = CACHE_DIR.resolve("skills");
        var agentsDir = CACHE_DIR.resolve("agents");
        return Files.exists(skillsDir) && Files.exists(agentsDir);
    }

    private Option<String> readCachedSha() {
        var versionFile = CACHE_DIR.resolve(VERSION_FILE);
        if (!Files.exists(versionFile)) {
            return Option.none();
        }
        try {
            return Option.option(Files.readString(versionFile)
                                      .trim());
        } catch (IOException e) {
            return Option.none();
        }
    }

    private Result<Unit> downloadToCache(HttpOperations http, String sha) {
        return GitHubContentFetcher.discoverFiles(http, REPO, BRANCH, AI_TOOLS_PREFIX)
                                   .flatMap(files -> downloadAllFiles(http, files))
                                   .flatMap(_ -> saveVersion(sha));
    }

    private Result<List<Path>> downloadAllFiles(HttpOperations http, List<String> remotePaths) {
        var downloads = remotePaths.stream()
                                   .map(remotePath -> remotePath.substring(AI_TOOLS_PREFIX.length()))
                                   .map(relativePath -> PathValidation.validateRelativePath(relativePath, CACHE_DIR)
                                                                      .flatMap(targetPath -> downloadFile(http,
                                                                                                          AI_TOOLS_PREFIX + relativePath,
                                                                                                          targetPath)))
                                   .toList();
        return Result.allOf(downloads);
    }

    private Result<Path> downloadFile(HttpOperations http, String remotePath, Path targetPath) {
        return GitHubContentFetcher.downloadFile(http, REPO, BRANCH, remotePath, targetPath)
                                   .map(_ -> targetPath);
    }

    private Result<Unit> saveVersion(String sha) {
        try {
            Files.createDirectories(CACHE_DIR);
            var versionFile = CACHE_DIR.resolve(VERSION_FILE);
            Files.writeString(versionFile, sha);
            return Result.unitResult();
        } catch (IOException e) {
            return Causes.cause("Failed to save version: " + e.getMessage())
                         .result();
        }
    }

    private Result<AiToolsOutcome> copyFromCache() {
        if (!Files.exists(CACHE_DIR)) {
            return Causes.cause("AI tools cache not found. Run 'jbct update' with network access first.")
                         .result();
        }
        var installed = new ArrayList<Path>();
        var skipped = new ArrayList<Path>();
        try {
            Files.createDirectories(claudeDir);
            copyTree(CACHE_DIR.resolve("skills"), claudeDir.resolve("skills"), installed, skipped);
            copyTree(CACHE_DIR.resolve("agents"), claudeDir.resolve("agents"), installed, skipped);
            return Result.success(AiToolsOutcome.aiToolsOutcome(installed, skipped));
        } catch (Exception e) {
            return Causes.cause("Failed to copy from cache: " + e.getMessage())
                         .result();
        }
    }

    private void copyTree(Path source, Path target, List<Path> installed, List<Path> skipped) throws IOException {
        if (!Files.exists(source)) {
            return;
        }
        Files.walkFileTree(source,
                           new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                               Files.createDirectories(target.resolve(source.relativize(dir)));
                               return FileVisitResult.CONTINUE;
                           }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                               copyOrSkip(file, target.resolve(source.relativize(file)), installed, skipped);
                               return FileVisitResult.CONTINUE;
                           }
        });
    }

    private void copyOrSkip(Path sourceFile, Path targetFile, List<Path> installed, List<Path> skipped) throws IOException {
        var relativePath = claudeDir.relativize(targetFile);
        if (existsGlobally(relativePath)) {
            skipped.add(relativePath);
            return;
        }
        Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        installed.add(targetFile);
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
