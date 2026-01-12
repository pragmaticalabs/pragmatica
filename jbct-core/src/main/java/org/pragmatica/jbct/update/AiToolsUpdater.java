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
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Updates AI tools from the coding-technology GitHub repository.
 */
public final class AiToolsUpdater {
    private static final String GITHUB_API_URL = "https://api.github.com/repos/siy/coding-technology/commits/main";
    private static final String RAW_CONTENT_BASE = "https://raw.githubusercontent.com/siy/coding-technology/main/";
    private static final Pattern SHA_PATTERN = Pattern.compile("\"sha\"\\s*:\\s*\"([^\"]+)\"");

    private static final String VERSION_FILE = ".ai-tools-version";

    // Files to download - skills/jbct/
    private static final String[] JBCT_SKILL_FILES = {"skills/jbct/SKILL.md", "skills/jbct/README.md", "skills/jbct/fundamentals/four-return-kinds.md", "skills/jbct/fundamentals/parse-dont-validate.md", "skills/jbct/fundamentals/no-business-exceptions.md", "skills/jbct/patterns/leaf.md", "skills/jbct/patterns/sequencer.md", "skills/jbct/patterns/fork-join.md", "skills/jbct/patterns/condition.md", "skills/jbct/patterns/iteration.md", "skills/jbct/patterns/aspects.md", "skills/jbct/patterns/fold-alternatives.md", "skills/jbct/project-structure/organization.md", "skills/jbct/testing/patterns.md", "skills/jbct/use-cases/structure.md", "skills/jbct/use-cases/complete-example.md"};

    // Files to download - skills/jbct-review/
    private static final String[] JBCT_REVIEW_SKILL_FILES = {"skills/jbct-review/SKILL.md"};

    private static final String[] AGENT_FILES = {"jbct-coder.md", "jbct-reviewer.md"};

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
                                 .uri(URI.create(GITHUB_API_URL))
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

    private Result<List<Path>> downloadFiles(String commitSha) {
        return createDirectories()
                                .flatMap(_ -> downloadAllFiles())
                                .flatMap(files -> saveCurrentVersion(commitSha)
                                                                    .map(_ -> files));
    }

    private Result<Unit> createDirectories() {
        try{
            Files.createDirectories(claudeDir.resolve("skills/jbct"));
            Files.createDirectories(claudeDir.resolve("skills/jbct-review"));
            Files.createDirectories(claudeDir.resolve("agents"));
            return Result.success(Unit.unit());
        } catch (Exception e) {
            return Causes.cause("Failed to create directories: " + e.getMessage())
                         .result();
        }
    }

    private Result<List<Path>> downloadAllFiles() {
        var agentsDir = claudeDir.resolve("agents");
        // Fork-Join: Download skill files and agent files in parallel
        return Result.allOf(downloadSkillFiles(),
                            downloadAgentFiles(agentsDir))
                     .map(lists -> lists.stream()
                                        .flatMap(List::stream)
                                        .toList());
    }

    private Result<List<Path>> downloadSkillFiles() {
        // Combine all skill file arrays
        var allSkillFiles = Stream.concat(Arrays.stream(JBCT_SKILL_FILES), Arrays.stream(JBCT_REVIEW_SKILL_FILES))
                                  .toList();
        var results = allSkillFiles.stream()
                                   .map(file -> downloadFile(file, claudeDir.resolve(file)))
                                   .toList();
        // Collect successful downloads
        var files = new ArrayList<Path>();
        for (var result : results) {
            if (result.isSuccess()) {
                files.add(result.unwrap());
            }
        }
        return Result.success(files);
    }

    private Result<List<Path>> downloadAgentFiles(Path agentsDir) {
        var results = Stream.of(AGENT_FILES)
                            .map(file -> downloadFile(file,
                                                      agentsDir.resolve(file)))
                            .toList();
        // Collect successful downloads
        var files = new ArrayList<Path>();
        for (var result : results) {
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
