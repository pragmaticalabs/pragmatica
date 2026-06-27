package org.pragmatica.jbct.update;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.jbct.init.AiToolsOutcome;
import org.pragmatica.jbct.shared.GitHubContentFetcher;
import org.pragmatica.jbct.shared.HttpClients;
import org.pragmatica.jbct.shared.PathValidation;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Updates AI tools from the coding-technology GitHub repository.
/// Uses GitHub Tree API to dynamically discover files under ai-tools/.
/// Files already present in the user's global ~/.claude/ are skipped (resolved globally).
public final class AiToolsUpdater {
    private static final Logger log = LoggerFactory.getLogger(AiToolsUpdater.class);
    private static final Path GLOBAL_CLAUDE_DIR = Path.of(System.getProperty("user.home"), ".claude");
    private static final String REPO = "siy/coding-technology";
    private static final String BRANCH = "main";
    private static final String AI_TOOLS_PREFIX = "ai-tools/";
    private static final String VERSION_FILE = ".ai-tools-version";

    private final HttpOperations http;
    private final Path claudeDir;

    private AiToolsUpdater(HttpOperations http, Path claudeDir) {
        this.http = http;
        this.claudeDir = claudeDir;
    }

    /// Create updater for project directory.
    /// AI tools will be updated in projectDir/.claude/
    public static AiToolsUpdater aiToolsUpdater(Path projectDir) {
        return new AiToolsUpdater(HttpClients.httpOperations(), projectDir.resolve(".claude"));
    }

    /// Check for updates without installing.
    ///
    /// @return Latest commit SHA if update available
    public Result<Option<String>> checkForUpdate() {
        return GitHubContentFetcher.getLatestCommitSha(http, REPO, BRANCH)
                                   .map(this::checkIfUpdateNeeded);
    }

    private Option<String> checkIfUpdateNeeded(String latestSha) {
        var currentSha = getCurrentVersion();
        var isUpToDate = currentSha.filter(sha -> sha.equals(latestSha))
                                   .isPresent();
        return isUpToDate
               ? Option.none()
               : Option.some(latestSha);
    }

    /// Update AI tools from GitHub.
    ///
    /// @return Outcome listing updated files and files skipped as already-global
    public Result<AiToolsOutcome> update() {
        return update(false);
    }

    /// Update AI tools from GitHub.
    ///
    /// @param force Force update even if already up to date
    /// @return Outcome listing updated files and files skipped as already-global
    public Result<AiToolsOutcome> update(boolean force) {
        return GitHubContentFetcher.getLatestCommitSha(http, REPO, BRANCH)
                                   .flatMap(latestSha -> performUpdate(latestSha, force));
    }

    private Result<AiToolsOutcome> performUpdate(String latestSha, boolean force) {
        var currentSha = getCurrentVersion();
        var isUpToDate = !force && currentSha.filter(sha -> sha.equals(latestSha))
                                             .isPresent();
        if (isUpToDate) {
            return Result.success(AiToolsOutcome.aiToolsOutcome(List.of(), List.of()));
        }
        return downloadFiles(latestSha);
    }

    private Result<AiToolsOutcome> downloadFiles(String commitSha) {
        return GitHubContentFetcher.discoverFiles(http, REPO, BRANCH, AI_TOOLS_PREFIX)
                                   .flatMap(this::downloadAllFiles)
                                   .flatMap(outcome -> saveCurrentVersion(commitSha).map(_ -> outcome));
    }

    private Result<AiToolsOutcome> downloadAllFiles(List<String> remotePaths) {
        var installed = new ArrayList<Path>();
        var skipped = new ArrayList<Path>();
        var results = remotePaths.stream()
                                 .map(remotePath -> remotePath.substring(AI_TOOLS_PREFIX.length()))
                                 .map(relativePath -> processFile(relativePath, installed, skipped))
                                 .toList();
        return Result.allOf(results)
                     .map(_ -> AiToolsOutcome.aiToolsOutcome(installed, skipped));
    }

    private Result<Unit> processFile(String relativePath, List<Path> installed, List<Path> skipped) {
        if (existsGlobally(relativePath)) {
            skipped.add(Path.of(relativePath));
            return Result.unitResult();
        }
        return PathValidation.validateRelativePath(relativePath, claudeDir)
                             .flatMap(targetPath -> downloadFile(AI_TOOLS_PREFIX + relativePath, targetPath))
                             .onSuccess(installed::add)
                             .mapToUnit();
    }

    private static boolean existsGlobally(String relativePath) {
        return Files.exists(GLOBAL_CLAUDE_DIR.resolve(relativePath));
    }

    private Result<Path> downloadFile(String remotePath, Path targetPath) {
        return GitHubContentFetcher.downloadFile(http, REPO, BRANCH, remotePath, targetPath)
                                   .map(_ -> targetPath);
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
            log.debug("Failed to read version file {}: {}", versionFile, e.getMessage());
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

    /// Get the Claude directory.
    public Path claudeDir() {
        return claudeDir;
    }
}
