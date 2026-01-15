package org.pragmatica.jbct.init;

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
import java.time.Instant;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Resolves latest versions from GitHub Releases.
 * Caches results for 24 hours to avoid excessive API calls.
 */
public final class GitHubVersionResolver {
    private static final Path CACHE_FILE = Path.of(System.getProperty("user.home"), ".jbct", "cache", "versions.properties");
    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours

    private static final String GITHUB_API_BASE = "https://api.github.com/repos";
    private static final Pattern TAG_PATTERN = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");

    // Default fallback versions when offline or API fails
    private static final String DEFAULT_PRAGMATICA_VERSION = "0.9.10";
    private static final String DEFAULT_AETHER_VERSION = "0.7.4";
    private static final String DEFAULT_JBCT_VERSION = "0.4.9";

    private final HttpOperations http;
    private final Properties cache;

    private GitHubVersionResolver(HttpOperations http) {
        this.http = http;
        this.cache = loadCache();
    }

    /**
     * Create a new version resolver.
     */
    public static GitHubVersionResolver gitHubVersionResolver() {
        return new GitHubVersionResolver(HttpClients.httpOperations());
    }

    /**
     * Get latest pragmatica-lite version.
     */
    public String pragmaticaLiteVersion() {
        return getVersion("siy", "pragmatica-lite", DEFAULT_PRAGMATICA_VERSION);
    }

    /**
     * Get latest aether version.
     */
    public String aetherVersion() {
        return getVersion("siy", "aetherx", DEFAULT_AETHER_VERSION);
    }

    /**
     * Get latest jbct-cli version.
     */
    public String jbctVersion() {
        return getVersion("siy", "jbct-cli", DEFAULT_JBCT_VERSION);
    }

    private String getVersion(String owner, String repo, String defaultVersion) {
        var cacheKey = owner + "/" + repo;
        var timestampKey = cacheKey + ".timestamp";
        // Check cache
        var cachedVersion = cache.getProperty(cacheKey);
        var timestampStr = cache.getProperty(timestampKey);
        if (cachedVersion != null && timestampStr != null) {
            try{
                var timestamp = Long.parseLong(timestampStr);
                if (System.currentTimeMillis() - timestamp < CACHE_TTL_MS) {
                    return cachedVersion;
                }
            } catch (NumberFormatException e) {
                // Ignore invalid timestamp
            }
        }
        // Fetch from GitHub
        return fetchLatestVersion(owner, repo)
                                             .fold(_ -> defaultVersion,
                                                   version -> {
                                                       cache.setProperty(cacheKey, version);
                                                       cache.setProperty(timestampKey, String.valueOf(System.currentTimeMillis()));
                                                       saveCache();
                                                       return version;
                                                   });
    }

    private Result<String> fetchLatestVersion(String owner, String repo) {
        var url = GITHUB_API_BASE + "/" + owner + "/" + repo + "/releases/latest";
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create(url))
                                 .header("Accept", "application/vnd.github.v3+json")
                                 .header("User-Agent", "jbct-cli")
                                 .timeout(Duration.ofSeconds(10))
                                 .GET()
                                 .build();
        return http.sendString(request)
                   .await()
                   .flatMap(HttpResult::toResult)
                   .flatMap(body -> {
                                var matcher = TAG_PATTERN.matcher(body);
                                if (matcher.find()) {
                                    return Result.success(matcher.group(1));
                                }
                                return Causes.cause("Could not parse version from GitHub response")
                                             .result();
                            });
    }

    private Properties loadCache() {
        var props = new Properties();
        if (Files.exists(CACHE_FILE)) {
            try (var reader = Files.newBufferedReader(CACHE_FILE)) {
                props.load(reader);
            } catch (IOException e) {
                // Ignore, start with empty cache
            }
        }
        return props;
    }

    private void saveCache() {
        try{
            Files.createDirectories(CACHE_FILE.getParent());
            try (var writer = Files.newBufferedWriter(CACHE_FILE)) {
                cache.store(writer, "JBCT version cache");
            }
        } catch (IOException e) {
            // Ignore, cache will be regenerated next time
        }
    }

    /**
     * Clear the version cache.
     */
    public void clearCache() {
        cache.clear();
        try{
            Files.deleteIfExists(CACHE_FILE);
        } catch (IOException e) {
            // Ignore
        }
    }

    /**
     * Get default pragmatica-lite version (used as fallback).
     */
    public static String defaultPragmaticaVersion() {
        return DEFAULT_PRAGMATICA_VERSION;
    }

    /**
     * Get default aether version (used as fallback).
     */
    public static String defaultAetherVersion() {
        return DEFAULT_AETHER_VERSION;
    }

    /**
     * Get default jbct version (used as fallback).
     */
    public static String defaultJbctVersion() {
        return DEFAULT_JBCT_VERSION;
    }
}
