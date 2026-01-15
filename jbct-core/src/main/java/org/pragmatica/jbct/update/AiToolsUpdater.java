package org.pragmatica.jbct.update;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.jbct.shared.HttpClients;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Updates AI tools from the coding-technology GitHub repository.
 * Uses GitHub Tree API to dynamically discover files under ai-tools/.
 */
public final class AiToolsUpdater {
    private static final String GITHUB_API_BASE = "https://api.github.com/repos/siy/coding-technology";
    private static final String GITHUB_TREE_URL = GITHUB_API_BASE + "/git/trees/main?recursive=1";
    private static final String GITHUB_COMMITS_URL = GITHUB_API_BASE + "/commits/main";
    private static final String RAW_CONTENT_BASE = "https://raw.githubusercontent.com/siy/coding-technology/main/";

    private static final Pattern SHA_PATTERN = Pattern.compile("\"sha\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TREE_ENTRY_PATTERN = Pattern.compile(
        "\\{[^}]*\"path\"\\s*:\\s*\"(ai-tools/[^\"]+)\"[^}]*\"type\"\\s*:\\s*\"blob\"[^}]*\\}");

    private static final String VERSION_FILE = ".ai-tools-version";
    private static final String AI_TOOLS_PREFIX = "ai-tools/";

    private final HttpOperations http;
    private final Path claudeDir;

    private AiToolsUpdater(HttpOperations http, Path claudeDir) {
        this.http = http;
        this.claudeDir = claudeDir;
    }

    /**
     * Create updater for project directory.
     * AI tools will be updated in projectDir/.claude/
     */
    public static AiToolsUpdater aiToolsUpdater(Path projectDir) {
        return new AiToolsUpdater(HttpClients.httpOperations(), projectDir.resolve(".claude"));
    }

    /**
     * Check for updates without installing.
     *
     * @return Latest commit SHA if update available
     */
    public Result<Option<String>> checkForUpdate() {
        return getLatestCommitSha()
                                 .map(latestSha -> {
                                          var currentSha = getCurrentVersion();
                                          return currentSha.filter(sha -> sha.equals(latestSha))
                                                           .map(_ -> Option.<String>none())
                                                           .or(() -> Option.option(latestSha));
                                      });
    }

    /**
     * Update AI tools from GitHub.
     *
     * @return List of updated files
     */
    public Result<List<Path>> update() {
        return update(false);
    }

    /**
     * Update AI tools from GitHub.
     *
     * @param force Force update even if already up to date
     * @return List of updated files
     */
    public Result<List<Path>> update(boolean force) {
        return getLatestCommitSha()
                                 .flatMap(latestSha -> {
                                              var currentSha = getCurrentVersion();
                                              var isUpToDate = !force && currentSha.filter(sha -> sha.equals(latestSha))
                                                                                   .isPresent();
                                              if (isUpToDate) {
                                                  return Result.success(List.<Path>of());
                                              }
                                              return downloadFiles(latestSha);
                                          });
    }

    private Result<String> getLatestCommitSha() {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create(GITHUB_COMMITS_URL))
                                 .header("Accept", "application/vnd.github.v3+json")
                                 .header("User-Agent", "jbct-cli")
                                 .timeout(Duration.ofSeconds(30))
                                 .GET()
                                 .build();
        return http.sendString(request)
                   .await()
                   .flatMap(HttpResult::toResult)
                   .flatMap(body -> {
                                var matcher = SHA_PATTERN.matcher(body);
                                if (matcher.find()) {
                                    return Result.success(matcher.group(1));
                                }
                                return new org.pragmatica.http.HttpError.InvalidResponse("Could not parse commit SHA from response",
                                                                                         org.pragmatica.lang.Option.none()).result();
                            });
    }

    private Result<List<String>> discoverFiles() {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create(GITHUB_TREE_URL))
                                 .header("Accept", "application/vnd.github.v3+json")
                                 .header("User-Agent", "jbct-cli")
                                 .timeout(Duration.ofSeconds(30))
                                 .GET()
                                 .build();
        return http.sendString(request)
                   .await()
                   .flatMap(HttpResult::toResult)
                   .map(body -> {
                            var files = new ArrayList<String>();
                            var matcher = TREE_ENTRY_PATTERN.matcher(body);
                            while (matcher.find()) {
                                files.add(matcher.group(1));
                            }
                            return files;
                        });
    }

    private Result<List<Path>> downloadFiles(String commitSha) {
        return discoverFiles()
                            .flatMap(this::downloadAllFiles)
                            .flatMap(files -> saveCurrentVersion(commitSha)
                                                                .map(_ -> files));
    }

    private Result<List<Path>> downloadAllFiles(List<String> remotePaths) {
        var files = new ArrayList<Path>();
        for (var remotePath : remotePaths) {
            // Convert ai-tools/agents/file.md -> agents/file.md
            // Convert ai-tools/skills/jbct/SKILL.md -> skills/jbct/SKILL.md
            var relativePath = remotePath.substring(AI_TOOLS_PREFIX.length());
            var targetPath = claudeDir.resolve(relativePath);
            var result = downloadFile(remotePath, targetPath);
            if (result.isSuccess()) {
                files.add(result.unwrap());
            }
        }
        return Result.success(files);
    }

    private Result<Path> downloadFile(String remotePath, Path targetPath) {
        var url = RAW_CONTENT_BASE + remotePath;
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create(url))
                                 .header("User-Agent", "jbct-cli")
                                 .timeout(Duration.ofSeconds(30))
                                 .GET()
                                 .build();
        return http.sendString(request)
                   .await()
                   .flatMap(HttpResult::toResult)
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

    private Option<String> getCurrentVersion() {
        var versionFile = claudeDir.resolve(VERSION_FILE);
        if (!Files.exists(versionFile)) {
            return Option.none();
        }
        try{
            var content = Files.readString(versionFile)
                               .trim();
            return Option.option(content);
        } catch (IOException e) {
            return Option.none();
        }
    }

    private Result<Path> saveCurrentVersion(String commitSha) {
        try{
            Files.createDirectories(claudeDir);
            var versionFile = claudeDir.resolve(VERSION_FILE);
            Files.writeString(versionFile, commitSha);
            return Result.success(versionFile);
        } catch (IOException e) {
            return Causes.cause("Failed to save version file: " + e.getMessage())
                         .result();
        }
    }

    /**
     * Get the Claude directory.
     */
    public Path claudeDir() {
        return claudeDir;
    }
}
