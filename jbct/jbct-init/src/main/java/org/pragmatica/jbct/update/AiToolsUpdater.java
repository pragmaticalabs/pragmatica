package org.pragmatica.jbct.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.jbct.init.AiToolsOutcome;
import org.pragmatica.jbct.init.AiToolsRef;
import org.pragmatica.jbct.shared.GitHubContentFetcher;
import org.pragmatica.jbct.shared.HttpClients;
import org.pragmatica.jbct.shared.PathValidation;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Updates AI tools from the coding-technology GitHub repository, at the tag `ai-tools-v<jbct version>` of the
/// running build (#2363), never `main` ([AiToolsRef]): an update moves to the tag of the new version.
/// Uses GitHub Tree API to dynamically discover files under ai-tools/.
/// The installed ref is recorded in `.claude/.ai-tools-version` as `<tag>@<commit sha>`.
/// Files already present in the user's global ~/.claude/ are skipped (resolved globally).
/// Limit: files a previous ref installed and the new ref no longer carries are NOT deleted from `.claude/`, which also
/// holds the user's own skills and agents; only the init cache is cleared (`AiToolsInstaller`). A failed update
/// removes `.ai-tools-version` before the first file is written, so it never leaves an old stamp over mixed content.
public final class AiToolsUpdater {
    private static final Logger log = LoggerFactory.getLogger(AiToolsUpdater.class);
    private static final Path GLOBAL_CLAUDE_DIR = Path.of(System.getProperty("user.home"), ".claude");
    private static final String AI_TOOLS_PREFIX = "ai-tools/";
    private static final String VERSION_FILE = ".ai-tools-version";

    private final HttpOperations http;
    private final Path claudeDir;
    private final Result<AiToolsRef> ref;

    private AiToolsUpdater(HttpOperations http, Path claudeDir, Result<AiToolsRef> ref) {
        this.http = http;
        this.claudeDir = claudeDir;
        this.ref = ref;
    }

    /// Create updater for project directory.
    /// AI tools will be updated in projectDir/.claude/
    public static AiToolsUpdater aiToolsUpdater(Path projectDir) {
        return new AiToolsUpdater(HttpClients.httpOperations(), projectDir.resolve(".claude"), AiToolsRef.aiToolsRef());
    }

    // Test seam: inject the HTTP seam and the ref (the running build's version is fixed by the jar).
    static AiToolsUpdater aiToolsUpdater(Path projectDir, HttpOperations http, Result<AiToolsRef> ref) {
        return new AiToolsUpdater(http, projectDir.resolve(".claude"), ref);
    }

    /// Check for updates without installing.
    ///
    /// @return The ref record (`<tag>@<commit sha>`) the update would install, if an update is available
    public Result<Option<String>> checkForUpdate() {
        return ref.flatMap(this::checkAt);
    }

    private Result<Option<String>> checkAt(AiToolsRef pinned) {
        return pinned.resolveSha(http)
                     .map(latestSha -> checkIfUpdateNeeded(pinned, latestSha));
    }

    private Option<String> checkIfUpdateNeeded(AiToolsRef pinned, String latestSha) {
        var stamp = pinned.stamp(latestSha);
        var isUpToDate = getCurrentVersion().filter(stamp::equals).isPresent();

        return isUpToDate
               ? Option.none()
               : Option.some(stamp);
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
        return ref.flatMap(pinned -> updateAt(pinned, force));
    }

    private Result<AiToolsOutcome> updateAt(AiToolsRef pinned, boolean force) {
        return pinned.resolveSha(http)
                     .flatMap(latestSha -> performUpdate(pinned, latestSha, force));
    }

    private Result<AiToolsOutcome> performUpdate(AiToolsRef pinned, String latestSha, boolean force) {
        var isUpToDate = !force && getCurrentVersion().filter(pinned.stamp(latestSha)::equals).isPresent();

        if (isUpToDate) {
            return Result.success(AiToolsOutcome.aiToolsOutcome(List.of(), List.of(), pinned.tag()));
        }

        return downloadFiles(pinned, latestSha);
    }

    // Fetched at the commit the tag resolved to, so the content is exactly what the recorded ref names. The stamp goes
    // before the first file is written (after the files were listed: a ref that cannot be resolved or listed changes
    // nothing), so a download that dies half-way leaves no stamp for an older build to call "up to date".
    private Result<AiToolsOutcome> downloadFiles(AiToolsRef pinned, String commitSha) {
        return GitHubContentFetcher.discoverFiles(http, AiToolsRef.REPO, commitSha, AI_TOOLS_PREFIX)
                                   .flatMap(remotePaths -> clearVersion().flatMap(_ -> downloadAllFiles(pinned,
                                                                                                        commitSha,
                                                                                                        remotePaths)))
                                   .flatMap(outcome -> saveCurrentVersion(pinned.stamp(commitSha)).map(_ -> outcome));
    }

    private Result<AiToolsOutcome> downloadAllFiles(AiToolsRef pinned, String commitSha, List<String> remotePaths) {
        var installed = new ArrayList<Path>();
        var skipped = new ArrayList<Path>();
        var results = remotePaths.stream()
                                 .map(remotePath -> remotePath.substring(AI_TOOLS_PREFIX.length()))
                                 .map(relativePath -> processFile(commitSha, relativePath, installed, skipped))
                                 .toList();

        return Result.allOf(results).map(_ -> AiToolsOutcome.aiToolsOutcome(installed, skipped, pinned.tag()));
    }

    private Result<Unit> processFile(String commitSha, String relativePath, List<Path> installed, List<Path> skipped) {
        if (existsGlobally(relativePath)) {
            skipped.add(Path.of(relativePath));

            return Result.unitResult();
        }

        return PathValidation.validateRelativePath(relativePath, claudeDir)
                             .flatMap(targetPath -> downloadFile(commitSha, AI_TOOLS_PREFIX + relativePath, targetPath))
                             .onSuccess(installed::add)
                             .mapToUnit();
    }

    private static boolean existsGlobally(String relativePath) {
        return Files.exists(GLOBAL_CLAUDE_DIR.resolve(relativePath));
    }

    private Result<Path> downloadFile(String commitSha, String remotePath, Path targetPath) {
        return GitHubContentFetcher.downloadFile(http, AiToolsRef.REPO, commitSha, remotePath, targetPath).map(_ -> targetPath);
    }

    private Option<String> getCurrentVersion() {
        var versionFile = claudeDir.resolve(VERSION_FILE);

        if (!Files.exists(versionFile)) {
            return Option.none();
        }

        try {
            var content = Files.readString(versionFile).trim();

            return Option.option(content);
        } catch (IOException e) {
            log.debug("Failed to read version file {}: {}", versionFile, e.getMessage());

            return Option.none();
        }
    }

    private Result<Unit> clearVersion() {
        return Result.lift(AiToolsUpdater::clearFailure,
                           () -> Files.deleteIfExists(claudeDir.resolve(VERSION_FILE)))
                     .mapToUnit();
    }

    private static Cause clearFailure(Throwable throwable) {
        return Causes.cause("Failed to clear the version file: " + throwable.getMessage(),
                            Option.some(Causes.fromThrowable(throwable)));
    }

    private Result<Path> saveCurrentVersion(String stamp) {
        try {
            Files.createDirectories(claudeDir);
            var versionFile = claudeDir.resolve(VERSION_FILE);

            Files.writeString(versionFile, stamp);

            return Result.success(versionFile);
        } catch (IOException e) {
            return Causes.cause("Failed to save version file: " + e.getMessage()).result();
        }
    }

    /// Get the Claude directory.
    public Path claudeDir() {
        return claudeDir;
    }
}
