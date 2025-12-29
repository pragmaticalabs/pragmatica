package org.pragmatica.jbct.update;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.jbct.shared.HttpClients;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Updates AI tools from the coding-technology GitHub repository.
 */
public final class AiToolsUpdater {

    private static final String GITHUB_API_URL = "https://api.github.com/repos/siy/coding-technology/commits/main";
    private static final String RAW_CONTENT_BASE = "https://raw.githubusercontent.com/siy/coding-technology/main/";
    private static final Pattern SHA_PATTERN = Pattern.compile("\"sha\"\\s*:\\s*\"([^\"]+)\"");

    private static final String VERSION_FILE = "ai-tools-version.txt";

    // Files to download
    private static final String[] SKILL_FILES = {
            "skills/jbct/SKILL.md",
            "skills/jbct/README.md",
            "skills/jbct/fundamentals/four-return-kinds.md",
            "skills/jbct/fundamentals/parse-dont-validate.md",
            "skills/jbct/fundamentals/no-business-exceptions.md",
            "skills/jbct/patterns/leaf.md",
            "skills/jbct/patterns/sequencer.md",
            "skills/jbct/patterns/fork-join.md",
            "skills/jbct/patterns/condition.md",
            "skills/jbct/patterns/iteration.md",
            "skills/jbct/patterns/aspects.md",
            "skills/jbct/project-structure/organization.md",
            "skills/jbct/testing/patterns.md",
            "skills/jbct/use-cases/structure.md",
            "skills/jbct/use-cases/complete-example.md"
    };

    private static final String[] AGENT_FILES = {
            "jbct-coder.md",
            "jbct-reviewer.md"
    };

    private final HttpOperations http;
    private final Path claudeDir;
    private final Path jbctDir;

    private AiToolsUpdater(HttpOperations http, Path claudeDir, Path jbctDir) {
        this.http = http;
        this.claudeDir = claudeDir;
        this.jbctDir = jbctDir;
    }

    /**
     * Create updater with default settings.
     */
    public static AiToolsUpdater aiToolsUpdater() {
        var userHome = System.getProperty("user.home");
        return new AiToolsUpdater(
                HttpClients.httpOperations(),
                Path.of(userHome, ".claude"),
                Path.of(userHome, ".jbct")
        );
    }

    /**
     * Create updater with custom directories.
     */
    public static AiToolsUpdater aiToolsUpdater(Path claudeDir, Path jbctDir) {
        return new AiToolsUpdater(HttpClients.httpOperations(), claudeDir, jbctDir);
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
                    var isUpToDate = !force && currentSha.filter(sha -> sha.equals(latestSha)).isPresent();
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
                    return new org.pragmatica.http.HttpError.InvalidResponse(
                            "Could not parse commit SHA from response",
                            org.pragmatica.lang.Option.none()
                    ).result();
                });
    }

    private Result<List<Path>> downloadFiles(String commitSha) {
        var downloadedFiles = new ArrayList<Path>();

        try {
            // Ensure directories exist
            var skillsDir = claudeDir.resolve("skills/jbct");
            var agentsDir = claudeDir.resolve("agents");
            Files.createDirectories(skillsDir);
            Files.createDirectories(agentsDir);

            // Download skill files
            for (var file : SKILL_FILES) {
                var targetPath = claudeDir.resolve(file);
                downloadFile(file, targetPath).onSuccess(downloadedFiles::add);
            }

            // Download agent files
            for (var file : AGENT_FILES) {
                var targetPath = agentsDir.resolve(file);
                downloadFile(file, targetPath).onSuccess(downloadedFiles::add);
            }

            // Save version
            saveCurrentVersion(commitSha);

            return Result.success(downloadedFiles);
        } catch (Exception e) {
            return Causes.cause("Failed to download files: " + e.getMessage()).result();
        }
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
                    try {
                        Files.createDirectories(targetPath.getParent());
                        Files.writeString(targetPath, content);
                        return Result.success(targetPath);
                    } catch (IOException e) {
                        return Causes.cause("Failed to write " + targetPath + ": " + e.getMessage()).result();
                    }
                });
    }

    private Option<String> getCurrentVersion() {
        var versionFile = jbctDir.resolve(VERSION_FILE);
        if (!Files.exists(versionFile)) {
            return Option.none();
        }

        try {
            var content = Files.readString(versionFile).trim();
            return Option.option(content);
        } catch (IOException e) {
            return Option.none();
        }
    }

    private void saveCurrentVersion(String commitSha) {
        try {
            Files.createDirectories(jbctDir);
            Files.writeString(jbctDir.resolve(VERSION_FILE), commitSha);
        } catch (IOException e) {
            // Ignore - version tracking is optional
        }
    }

    /**
     * Get the Claude directory.
     */
    public Path claudeDir() {
        return claudeDir;
    }

    /**
     * Get the JBCT configuration directory.
     */
    public Path jbctDir() {
        return jbctDir;
    }
}
