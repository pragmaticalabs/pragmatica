package org.pragmatica.jbct.shared;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Utility for fetching content from GitHub repositories.
 * Provides common operations for GitHub API access.
 */
public sealed interface GitHubContentFetcher permits GitHubContentFetcher.unused {
    record unused() implements GitHubContentFetcher {}

    Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    Pattern SHA_PATTERN = Pattern.compile("\"sha\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * File information from GitHub tree API.
     */
    record FileInfo(String path, String type) {
        boolean isBlob() {
            return "blob".equals(type);
        }
    }

    /**
     * Get the latest commit SHA for a branch.
     *
     * @param http   HTTP operations
     * @param repo   Repository in format "owner/repo"
     * @param branch Branch name
     * @return SHA of the latest commit
     */
    static Result<String> getLatestCommitSha(HttpOperations http, String repo, String branch) {
        var url = "https://api.github.com/repos/" + repo + "/commits/" + branch;
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create(url))
                                 .header("Accept", "application/vnd.github.v3+json")
                                 .header("User-Agent", "jbct-cli")
                                 .timeout(DEFAULT_TIMEOUT)
                                 .GET()
                                 .build();
        return http.sendString(request)
                   .await()
                   .flatMap(HttpResult::toResult)
                   .flatMap(GitHubContentFetcher::extractSha);
    }

    /**
     * Discover files in a repository path using GitHub Tree API.
     *
     * @param http       HTTP operations
     * @param repo       Repository in format "owner/repo"
     * @param branchOrSha Branch name or commit SHA
     * @param pathPrefix Path prefix to filter files (e.g., "ai-tools/")
     * @return List of file paths matching the prefix
     */
    static Result<List<String>> discoverFiles(HttpOperations http, String repo, String branchOrSha, String pathPrefix) {
        var url = "https://api.github.com/repos/" + repo + "/git/trees/" + branchOrSha + "?recursive=1";
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create(url))
                                 .header("Accept", "application/vnd.github.v3+json")
                                 .header("User-Agent", "jbct-cli")
                                 .timeout(DEFAULT_TIMEOUT)
                                 .GET()
                                 .build();
        return http.sendString(request)
                   .await()
                   .flatMap(HttpResult::toResult)
                   .map(body -> extractPaths(body, pathPrefix));
    }

    /**
     * Fetch raw file content from GitHub.
     *
     * @param http   HTTP operations
     * @param repo   Repository in format "owner/repo"
     * @param branch Branch name or commit SHA
     * @param path   File path within the repository
     * @return File content as string
     */
    static Result<String> fetchFileContent(HttpOperations http, String repo, String branch, String path) {
        var url = "https://raw.githubusercontent.com/" + repo + "/" + branch + "/" + path;
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create(url))
                                 .header("User-Agent", "jbct-cli")
                                 .timeout(DEFAULT_TIMEOUT)
                                 .GET()
                                 .build();
        return http.sendString(request)
                   .await()
                   .flatMap(HttpResult::toResult);
    }

    /**
     * Download a file to a local path.
     *
     * @param http        HTTP operations
     * @param repo        Repository in format "owner/repo"
     * @param branch      Branch name or commit SHA
     * @param remotePath  File path within the repository
     * @param destination Local destination path
     * @return Unit on success
     */
    static Result<Unit> downloadFile(HttpOperations http,
                                     String repo,
                                     String branch,
                                     String remotePath,
                                     Path destination) {
        return fetchFileContent(http, repo, branch, remotePath).flatMap(content -> writeFile(destination, content));
    }

    private static Result<String> extractSha(String body) {
        var matcher = SHA_PATTERN.matcher(body);
        if (matcher.find()) {
            return Result.success(matcher.group(1));
        }
        return Causes.cause("Could not parse commit SHA from response")
                     .result();
    }

    private static List<String> extractPaths(String body, String pathPrefix) {
        var pattern = Pattern.compile("\\{[^}]*\"path\"\\s*:\\s*\"(" + Pattern.quote(pathPrefix)
                                      + "[^\"]+)\"[^}]*\"type\"\\s*:\\s*\"blob\"[^}]*\\}");
        return pattern.matcher(body)
                      .results()
                      .map(match -> match.group(1))
                      .toList();
    }

    private static Result<Unit> writeFile(Path destination, String content) {
        try{
            Files.createDirectories(destination.getParent());
            Files.writeString(destination, content);
            return Result.unitResult();
        } catch (IOException e) {
            return Causes.cause("Failed to write " + destination + ": " + e.getMessage())
                         .result();
        }
    }
}
