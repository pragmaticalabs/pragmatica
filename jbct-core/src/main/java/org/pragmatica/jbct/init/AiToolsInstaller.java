package org.pragmatica.jbct.init;

import org.pragmatica.jbct.update.AiToolsUpdater;
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
        return ensureCachePopulated()
                                   .flatMap(_ -> copyFromCache());
    }

    private Result<Unit> ensureCachePopulated() {
        if (isCacheValid()) {
            return Result.success(Unit.unit());
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
        // Use AiToolsUpdater to fetch files directly to cache
        var updater = AiToolsUpdater.aiToolsUpdater(CACHE_DIR.getParent());
        // The updater writes to CACHE_DIR.getParent()/.claude/ which is CACHE_DIR/../.claude/
        // We need to adjust - create an updater that writes directly to CACHE_DIR
        return fetchToCache();
    }

    private Result<Unit> fetchToCache() {
        // Create a temporary updater that targets cache directory
        var cacheUpdater = new CacheUpdater();
        return cacheUpdater.update()
                           .map(_ -> Unit.unit());
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

    /**
     * Inner class to handle cache population using the same logic as AiToolsUpdater
     * but targeting the cache directory directly.
     */
    private static final class CacheUpdater {
        private static final String GITHUB_API_BASE = "https://api.github.com/repos/siy/coding-technology";
        private static final String GITHUB_TREE_URL = GITHUB_API_BASE + "/git/trees/main?recursive=1";
        private static final String GITHUB_COMMITS_URL = GITHUB_API_BASE + "/commits/main";
        private static final String RAW_CONTENT_BASE = "https://raw.githubusercontent.com/siy/coding-technology/main/";

        private static final java.util.regex.Pattern SHA_PATTERN = java.util.regex.Pattern.compile("\"sha\"\\s*:\\s*\"([^\"]+)\"");
        private static final java.util.regex.Pattern TREE_ENTRY_PATTERN = java.util.regex.Pattern.compile(
            "\\{[^}]*\"path\"\\s*:\\s*\"(ai-tools/[^\"]+)\"[^}]*\"type\"\\s*:\\s*\"blob\"[^}]*\\}");

        private static final String AI_TOOLS_PREFIX = "ai-tools/";

        private final org.pragmatica.http.HttpOperations http;

        CacheUpdater() {
            this.http = org.pragmatica.jbct.shared.HttpClients.httpOperations();
        }

        Result<List<Path>> update() {
            return getLatestCommitSha()
                                     .flatMap(sha -> discoverFiles()
                                                                   .flatMap(files -> downloadAllFiles(files)
                                                                                                            .flatMap(paths -> saveVersion(sha)
                                                                                                                                          .map(_ -> paths))));
        }

        private Result<String> getLatestCommitSha() {
            var request = java.net.http.HttpRequest.newBuilder()
                                                   .uri(java.net.URI.create(GITHUB_COMMITS_URL))
                                                   .header("Accept", "application/vnd.github.v3+json")
                                                   .header("User-Agent", "jbct-cli")
                                                   .timeout(java.time.Duration.ofSeconds(30))
                                                   .GET()
                                                   .build();
            return http.sendString(request)
                       .await()
                       .flatMap(org.pragmatica.http.HttpResult::toResult)
                       .flatMap(body -> {
                                    var matcher = SHA_PATTERN.matcher(body);
                                    if (matcher.find()) {
                                        return Result.success(matcher.group(1));
                                    }
                                    return Causes.cause("Could not parse commit SHA")
                                                 .result();
                                });
        }

        private Result<List<String>> discoverFiles() {
            var request = java.net.http.HttpRequest.newBuilder()
                                                   .uri(java.net.URI.create(GITHUB_TREE_URL))
                                                   .header("Accept", "application/vnd.github.v3+json")
                                                   .header("User-Agent", "jbct-cli")
                                                   .timeout(java.time.Duration.ofSeconds(30))
                                                   .GET()
                                                   .build();
            return http.sendString(request)
                       .await()
                       .flatMap(org.pragmatica.http.HttpResult::toResult)
                       .map(body -> {
                                var files = new ArrayList<String>();
                                var matcher = TREE_ENTRY_PATTERN.matcher(body);
                                while (matcher.find()) {
                                    files.add(matcher.group(1));
                                }
                                return files;
                            });
        }

        private Result<List<Path>> downloadAllFiles(List<String> remotePaths) {
            var files = new ArrayList<Path>();
            for (var remotePath : remotePaths) {
                var relativePath = remotePath.substring(AI_TOOLS_PREFIX.length());
                var targetPath = CACHE_DIR.resolve(relativePath);
                var result = downloadFile(remotePath, targetPath);
                if (result.isSuccess()) {
                    files.add(result.unwrap());
                }
            }
            return Result.success(files);
        }

        private Result<Path> downloadFile(String remotePath, Path targetPath) {
            var url = RAW_CONTENT_BASE + remotePath;
            var request = java.net.http.HttpRequest.newBuilder()
                                                   .uri(java.net.URI.create(url))
                                                   .header("User-Agent", "jbct-cli")
                                                   .timeout(java.time.Duration.ofSeconds(30))
                                                   .GET()
                                                   .build();
            return http.sendString(request)
                       .await()
                       .flatMap(org.pragmatica.http.HttpResult::toResult)
                       .flatMap(content -> {
                                    try{
                                        Files.createDirectories(targetPath.getParent());
                                        Files.writeString(targetPath, content);
                                        return Result.success(targetPath);
                                    } catch (IOException e) {
                                        return Causes.cause("Failed to write " + targetPath + ": " + e.getMessage())
                                                     .result();
                                    }
                                });
        }

        private Result<Path> saveVersion(String sha) {
            try{
                Files.createDirectories(CACHE_DIR);
                var versionFile = CACHE_DIR.resolve(VERSION_FILE);
                Files.writeString(versionFile, sha);
                return Result.success(versionFile);
            } catch (IOException e) {
                return Causes.cause("Failed to save version: " + e.getMessage())
                             .result();
            }
        }
    }
}
