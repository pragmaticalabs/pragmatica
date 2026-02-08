package org.pragmatica.jbct.init;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.jbct.shared.GitHubContentFetcher;
import org.pragmatica.jbct.shared.HttpClients;
import org.pragmatica.jbct.shared.PathValidation;
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

/**
 * Installs AI tools (Claude Code skills and agents) to project's .claude/ directory.
 * Uses offline cache at ~/.jbct/cache/ai-tools/ for faster installs.
 */
public final class AiToolsInstaller {
    private static final Path CACHE_DIR = Path.of(System.getProperty("user.home"), ".jbct", "cache", "ai-tools");
    private static final String VERSION_FILE = ".sha";
    private static final String REPO = "siy/coding-technology";
    private static final String BRANCH = "main";
    private static final String AI_TOOLS_PREFIX = "ai-tools/";

    private final Path claudeDir;

    private AiToolsInstaller(Path claudeDir) {
        this.claudeDir = claudeDir;
    }

    /**
     * Create installer for project directory.
     * AI tools will be installed to projectDir/.claude/
     */
    public static AiToolsInstaller aiToolsInstaller(Path projectDir) {
        return new AiToolsInstaller(projectDir.resolve(".claude"));
    }

    /**
     * Install AI tools from cache or fetch from GitHub.
     *
     * @return List of installed files
     */
    public Result<List<Path>> install() {
        return ensureCachePopulated().flatMap(_ -> copyFromCache());
    }

    private Result<Unit> ensureCachePopulated() {
        if (isCacheValid()) {
            return Result.unitResult();
        }
        return populateCache();
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

    private Result<Unit> populateCache() {
        var http = HttpClients.httpOperations();
        return GitHubContentFetcher.getLatestCommitSha(http, REPO, BRANCH)
                                   .flatMap(sha -> downloadToCache(http, sha));
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
        try{
            Files.createDirectories(CACHE_DIR);
            var versionFile = CACHE_DIR.resolve(VERSION_FILE);
            Files.writeString(versionFile, sha);
            return Result.unitResult();
        } catch (IOException e) {
            return Causes.cause("Failed to save version: " + e.getMessage())
                         .result();
        }
    }

    private Result<List<Path>> copyFromCache() {
        if (!Files.exists(CACHE_DIR)) {
            return Causes.cause("AI tools cache not found. Run 'jbct update' with network access first.")
                         .result();
        }
        var installedFiles = new ArrayList<Path>();
        try{
            Files.createDirectories(claudeDir);
            // Copy skills
            var sourceSkills = CACHE_DIR.resolve("skills");
            var targetSkills = claudeDir.resolve("skills");
            if (Files.exists(sourceSkills)) {
                copyDirectory(sourceSkills, targetSkills, installedFiles);
            }
            // Copy agents
            var sourceAgents = CACHE_DIR.resolve("agents");
            var targetAgents = claudeDir.resolve("agents");
            if (Files.exists(sourceAgents)) {
                copyDirectory(sourceAgents, targetAgents, installedFiles);
            }
            return Result.success(installedFiles);
        } catch (Exception e) {
            return Causes.cause("Failed to copy from cache: " + e.getMessage())
                         .result();
        }
    }

    private void copyDirectory(Path source, Path target, List<Path> installedFiles) throws IOException {
        Files.walkFileTree(source,
                           new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                               var targetDir = target.resolve(source.relativize(dir));
                               Files.createDirectories(targetDir);
                               return FileVisitResult.CONTINUE;
                           }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                               var targetFile = target.resolve(source.relativize(file));
                               Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                               installedFiles.add(targetFile);
                               return FileVisitResult.CONTINUE;
                           }
        });
    }

    /**
     * Get the Claude directory.
     */
    public Path claudeDir() {
        return claudeDir;
    }

    /**
     * Get the path where skills are installed.
     */
    public Path skillsDir() {
        return claudeDir.resolve("skills");
    }

    /**
     * Get the path where agents are installed.
     */
    public Path agentsDir() {
        return claudeDir.resolve("agents");
    }

    /**
     * Get the cache directory path.
     */
    public static Path cacheDir() {
        return CACHE_DIR;
    }
}
