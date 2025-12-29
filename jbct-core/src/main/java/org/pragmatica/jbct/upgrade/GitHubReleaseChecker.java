package org.pragmatica.jbct.upgrade;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.jbct.shared.HttpClients;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Checks GitHub Releases for the latest JBCT version.
 */
public final class GitHubReleaseChecker {

    private static final String GITHUB_API_URL = "https://api.github.com/repos/siy/jbct-cli/releases/latest";
    private static final Pattern VERSION_PATTERN = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");
    private static final Pattern ASSET_URL_PATTERN = Pattern.compile(
            "\"browser_download_url\"\\s*:\\s*\"([^\"]+jbct[^\"]*\\.jar)\""
    );

    private final HttpOperations http;
    private final String apiUrl;

    private GitHubReleaseChecker(HttpOperations http, String apiUrl) {
        this.http = http;
        this.apiUrl = apiUrl;
    }

    /**
     * Create a release checker with default settings.
     */
    public static GitHubReleaseChecker releaseChecker() {
        return new GitHubReleaseChecker(HttpClients.httpOperations(), GITHUB_API_URL);
    }

    /**
     * Create a release checker with custom API URL (for testing).
     */
    public static GitHubReleaseChecker releaseChecker(String apiUrl) {
        return new GitHubReleaseChecker(HttpClients.httpOperations(), apiUrl);
    }

    /**
     * Check for the latest release.
     *
     * @return ReleaseInfo with version and download URL if available
     */
    public Result<ReleaseInfo> checkLatestRelease() {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "jbct-cli")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        return http.sendString(request)
                .await()
                .flatMap(this::handleResponse);
    }

    /**
     * Check if a newer version is available.
     *
     * @param currentVersion Current installed version
     * @return Option with new version info if available
     */
    public Result<Option<ReleaseInfo>> checkForUpdate(String currentVersion) {
        return checkLatestRelease()
                .map(release -> {
                    if (isNewerVersion(currentVersion, release.version())) {
                        return Option.option(release);
                    }
                    return Option.none();
                });
    }

    private Result<ReleaseInfo> handleResponse(HttpResult<String> response) {
        if (response.statusCode() == 404) {
            return new org.pragmatica.http.HttpError.RequestFailed(404, "No releases found").result();
        }

        return response.toResult()
                .flatMap(this::parseReleaseInfo);
    }

    private Result<ReleaseInfo> parseReleaseInfo(String json) {
        var versionMatcher = VERSION_PATTERN.matcher(json);
        if (!versionMatcher.find()) {
            return new org.pragmatica.http.HttpError.InvalidResponse("Could not parse version from release", Option.none()).result();
        }
        var version = versionMatcher.group(1);

        var assetMatcher = ASSET_URL_PATTERN.matcher(json);
        var downloadUrl = assetMatcher.find()
                ? Option.some(assetMatcher.group(1))
                : Option.<String>none();

        return Result.success(new ReleaseInfo(version, downloadUrl));
    }

    /**
     * Compare versions to check if newVersion is newer than currentVersion.
     * Supports semantic versioning (e.g., 1.2.3, 0.3.1-SNAPSHOT).
     */
    public static boolean isNewerVersion(String currentVersion, String newVersion) {
        var current = normalizeVersion(currentVersion);
        var newer = normalizeVersion(newVersion);

        var currentParts = current.split("\\.");
        var newerParts = newer.split("\\.");

        for (int i = 0; i < Math.max(currentParts.length, newerParts.length); i++) {
            int currentPart = i < currentParts.length ? parseVersionPart(currentParts[i]) : 0;
            int newerPart = i < newerParts.length ? parseVersionPart(newerParts[i]) : 0;

            if (newerPart > currentPart) {
                return true;
            } else if (newerPart < currentPart) {
                return false;
            }
        }

        // If versions are equal, SNAPSHOT is older than release
        boolean currentIsSnapshot = currentVersion.contains("SNAPSHOT");
        boolean newerIsSnapshot = newVersion.contains("SNAPSHOT");
        return currentIsSnapshot && !newerIsSnapshot;
    }

    private static String normalizeVersion(String version) {
        // Remove 'v' prefix and '-SNAPSHOT' suffix for comparison
        return version
                .replaceFirst("^v", "")
                .replaceFirst("-SNAPSHOT$", "")
                .replaceFirst("-.*$", ""); // Remove other suffixes like -RC1
    }

    private static int parseVersionPart(String part) {
        try {
            // Extract numeric prefix
            var numericPart = part.replaceAll("[^0-9].*$", "");
            return numericPart.isEmpty() ? 0 : Integer.parseInt(numericPart);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Information about a GitHub release.
     */
    public record ReleaseInfo(
            String version,
            Option<String> downloadUrl
    ) {
        /**
         * Check if this release has a downloadable JAR.
         */
        public boolean hasDownloadUrl() {
            return downloadUrl.isPresent();
        }
    }
}
