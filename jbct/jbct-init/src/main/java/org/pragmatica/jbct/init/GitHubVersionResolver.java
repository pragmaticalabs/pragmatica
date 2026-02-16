package org.pragmatica.jbct.init;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.jbct.shared.HttpClients;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Resolves latest version from the pragmatica monorepo GitHub Releases.
/// All components (pragmatica-lite, aether, jbct) share the same version.
/// Caches results for 24 hours to avoid excessive API calls.
public final class GitHubVersionResolver {
    private static final Logger log = LoggerFactory.getLogger(GitHubVersionResolver.class);
    private static final Path CACHE_FILE = Path.of(System.getProperty("user.home"),
                                                   ".jbct",
                                                   "cache",
                                                   "versions.properties");
    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000;

    private static final String GITHUB_API_BASE = "https://api.github.com/repos";
    private static final Pattern TAG_PATTERN = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");
    private static final Duration API_TIMEOUT = Duration.ofSeconds(10);

    // All components share the same version from the pragmatica monorepo
    private static final String REPO_OWNER = "siy";
    private static final String REPO_NAME = "pragmatica";
    private static final String DEFAULT_VERSION = "0.16.0";

    // Running binary version (loaded from jbct-version.properties)
    private static final String RUNNING_JBCT_VERSION = loadRunningVersion();

    private final HttpOperations http;
    private final Properties cache;
    private volatile String resolvedVersion;

    private static String loadRunningVersion() {
        var props = new Properties();
        try (var is = GitHubVersionResolver.class.getResourceAsStream("/jbct-version.properties")) {
            if (is != null) {
                props.load(is);
                return props.getProperty("version", DEFAULT_VERSION);
            }
        } catch (IOException e) {
            log.debug("Failed to load jbct-version.properties: {}", e.getMessage());
        }
        return DEFAULT_VERSION;
    }

    private GitHubVersionResolver(HttpOperations http) {
        this.http = http;
        this.cache = loadCache();
    }

    /// Create a new version resolver.
    public static GitHubVersionResolver gitHubVersionResolver() {
        return new GitHubVersionResolver(HttpClients.httpOperations());
    }

    /// Get latest pragmatica-lite version.
    public String pragmaticaLiteVersion() {
        return monorepoVersion();
    }

    /// Get latest aether version.
    public String aetherVersion() {
        return monorepoVersion();
    }

    /// Get latest jbct-cli version.
    /// Uses the newer of: running binary version or latest GitHub release.
    public String jbctVersion() {
        return maxVersion(RUNNING_JBCT_VERSION, monorepoVersion());
    }

    /// Get default pragmatica-lite version (used as fallback).
    public static String defaultPragmaticaVersion() {
        return DEFAULT_VERSION;
    }

    /// Get default aether version (used as fallback).
    public static String defaultAetherVersion() {
        return DEFAULT_VERSION;
    }

    /// Get default jbct version (used as fallback).
    public static String defaultJbctVersion() {
        return DEFAULT_VERSION;
    }

    /// Clear the version cache.
    public Result<Unit> clearCache() {
        resolvedVersion = null;
        cache.clear();
        return Result.lift(Causes::fromThrowable, () -> { Files.deleteIfExists(CACHE_FILE); });
    }

    // --- Private ---

    private String monorepoVersion() {
        if (resolvedVersion != null) {
            return resolvedVersion;
        }
        resolvedVersion = fetchVersionWithCache();
        return resolvedVersion;
    }

    private String fetchVersionWithCache() {
        var cacheKey = REPO_OWNER + "/" + REPO_NAME;
        var timestampKey = cacheKey + ".timestamp";

        var cachedVersion = cache.getProperty(cacheKey);
        var timestampStr = cache.getProperty(timestampKey);

        if (cachedVersion != null && timestampStr != null) {
            try {
                var timestamp = Long.parseLong(timestampStr);
                if (System.currentTimeMillis() - timestamp < CACHE_TTL_MS) {
                    return cachedVersion;
                }
            } catch (NumberFormatException e) {
                log.debug("Invalid timestamp in version cache for {}: {}", cacheKey, timestampStr);
            }
        }

        return fetchLatestVersion()
                   .onSuccess(version -> updateCache(cacheKey, timestampKey, version))
                   .or(DEFAULT_VERSION);
    }

    private Result<String> fetchLatestVersion() {
        var url = GITHUB_API_BASE + "/" + REPO_OWNER + "/" + REPO_NAME + "/releases/latest";
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create(url))
                                 .header("Accept", "application/vnd.github.v3+json")
                                 .header("User-Agent", "jbct-cli")
                                 .timeout(API_TIMEOUT)
                                 .GET()
                                 .build();
        return http.sendString(request)
                   .await()
                   .flatMap(HttpResult::toResult)
                   .flatMap(this::extractVersionFromResponse);
    }

    private Result<String> extractVersionFromResponse(String body) {
        var matcher = TAG_PATTERN.matcher(body);
        return matcher.find()
               ? Result.success(matcher.group(1))
               : Causes.cause("Could not parse version from GitHub response")
                       .result();
    }

    private void updateCache(String cacheKey, String timestampKey, String version) {
        cache.setProperty(cacheKey, version);
        cache.setProperty(timestampKey, String.valueOf(System.currentTimeMillis()));
        saveCache();
    }

    private Properties loadCache() {
        var props = new Properties();
        if (Files.exists(CACHE_FILE)) {
            try (var reader = Files.newBufferedReader(CACHE_FILE)) {
                props.load(reader);
            } catch (IOException e) {
                log.debug("Failed to load version cache from {}: {}", CACHE_FILE, e.getMessage());
            }
        }
        return props;
    }

    private Result<Unit> saveCache() {
        return Result.lift(Causes::fromThrowable, () -> {
            Files.createDirectories(CACHE_FILE.getParent());
            try (var writer = Files.newBufferedWriter(CACHE_FILE)) {
                cache.store(writer, "JBCT version cache");
            }
        });
    }

    /// Compare two semantic versions and return the newer one.
    private static String maxVersion(String v1, String v2) {
        var parts1 = v1.split("\\.");
        var parts2 = v2.split("\\.");
        for (int i = 0; i < Math.min(parts1.length, parts2.length); i++) {
            final var index = i;
            var cmp = Result.all(Number.parseInt(parts1[index]),
                                 Number.parseInt(parts2[index]))
                            .map((num1, num2) -> Integer.compare(num1, num2))
                            .or(0);
            if (cmp > 0) {
                return v1;
            }
            if (cmp < 0) {
                return v2;
            }
        }
        return parts1.length >= parts2.length ? v1 : v2;
    }
}
