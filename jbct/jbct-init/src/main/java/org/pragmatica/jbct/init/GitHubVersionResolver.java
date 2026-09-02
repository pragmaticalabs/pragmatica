package org.pragmatica.jbct.init;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.regex.Pattern;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.jbct.shared.HttpClients;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Resolves the platform version used to scaffold new projects.
/// All components (pragmatica-lite, aether, jbct) share the same monorepo version.
///
/// Resolution order: latest GitHub release -> the resolver's own embedded build version
/// (self-derived) -> a fail-loud [Result] failure carrying an actionable message. Successful
/// GitHub lookups are cached for 24 hours to avoid excessive API calls.
///
/// The embedded version is injected at build time into `jbct-init-build.properties` via Maven
/// resource filtering, mirroring the slice-processor #403 staleness guard ([org.pragmatica.jbct.slice.BuildInfo]).
/// Self-derivation replaces a hand-maintained default constant, so the offline fallback can never
/// silently go stale: it is always the version of the running binary itself.
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
    // All components share the same version from the pragmatica monorepo.
    private static final String REPO_OWNER = "pragmaticalabs";
    private static final String REPO_NAME = "pragmatica";
    private static final String CACHE_KEY = REPO_OWNER + "/" + REPO_NAME;
    private static final String TIMESTAMP_KEY = CACHE_KEY + ".timestamp";
    // Reported by the String accessors when even the embedded version is unavailable (mirrors
    // slice-processor BuildInfo#UNKNOWN). Real builds always carry a filtered embedded version;
    // the fail-loud path lives in resolveMonorepoVersion().
    private static final String UNKNOWN = "unknown";
    private static final String BUILD_RESOURCE = "/jbct-init-build.properties";
    // Own build version, injected via the filtered build resource. Absent only when the resource is
    // missing or its placeholder was not filtered (an unpackaged/corrupt classpath).
    private static final Option<String> EMBEDDED_VERSION = loadEmbeddedVersion();

    // Actionable failure for the both-lookups-failed case: never guess a version, tell the user
    // which flag to pass (see InitCommand). Carries no version literal, so version-sweep ignores it.
    private static final Cause UNRESOLVABLE_VERSION = Causes.cause("Unable to resolve the platform version: the GitHub release lookup failed and no embedded "
                                                                  + "build version is available. Re-run with an explicit version, e.g. "
                                                                  + "`jbct init --version <X.Y.Z>` (or --pragmatica-version / --aether-version / --jbct-version).");

    private final HttpOperations http;
    private final Properties cache;
    private final Option<String> selfVersion;
    private volatile Option<String> resolvedVersion = Option.none();

    private GitHubVersionResolver(HttpOperations http, Properties cache, Option<String> selfVersion) {
        this.http = http;
        this.cache = cache;
        this.selfVersion = selfVersion;
    }

    /// Create a new version resolver backed by the shared HTTP client and the embedded build version.
    public static GitHubVersionResolver gitHubVersionResolver() {
        return new GitHubVersionResolver(HttpClients.httpOperations(), loadCache(), EMBEDDED_VERSION);
    }

    // Test seam: inject the HTTP client, cache, and self-derived version.
    static GitHubVersionResolver gitHubVersionResolver(HttpOperations http,
                                                       Properties cache,
                                                       Option<String> selfVersion) {
        return new GitHubVersionResolver(http, cache, selfVersion);
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
    /// Uses the newer of: own build version or latest GitHub release.
    /// The own version is only considered when it is a stable release (no "-" suffix like -SNAPSHOT or -candidate).
    public String jbctVersion() {
        return selfVersion.filter(version -> !version.contains("-"))
                          .map(this::maxWithMonorepo)
                          .or(this::monorepoVersion);
    }

    /// The resolver's own build version, self-derived from the filtered build resource,
    /// or [Option#none] when the resource is missing or was not filtered.
    public Option<String> selfDerivedVersion() {
        return selfVersion;
    }

    /// Resolve the monorepo version, failing loud when it cannot be determined.
    /// Resolution order: latest GitHub release -> self-derived build version -> actionable failure.
    public Result<String> resolveMonorepoVersion() {
        return fetchVersionWithCache().orElse(this::selfDerivedFallback);
    }

    /// Get default pragmatica-lite version (self-derived fallback, used when offline).
    public static String defaultPragmaticaVersion() {
        return EMBEDDED_VERSION.or(UNKNOWN);
    }

    /// Get default aether version (self-derived fallback, used when offline).
    public static String defaultAetherVersion() {
        return EMBEDDED_VERSION.or(UNKNOWN);
    }

    /// Get default jbct version (self-derived fallback, used when offline).
    public static String defaultJbctVersion() {
        return EMBEDDED_VERSION.or(UNKNOWN);
    }

    /// Clear the version cache.
    public Result<Unit> clearCache() {
        resolvedVersion = Option.none();
        cache.clear();

        return Result.lift(Causes::fromThrowable,
                           () -> {
                               Files.deleteIfExists(CACHE_FILE);
                           });
    }

    // --- Private ---
    private String maxWithMonorepo(String stableSelfVersion) {
        return maxVersion(stableSelfVersion, monorepoVersion());
    }

    private String monorepoVersion() {
        return resolvedVersion.or(this::resolveAndCache);
    }

    private String resolveAndCache() {
        return resolveMonorepoVersion().onSuccess(this::memoize)
                                     .or(selfVersion.or(UNKNOWN));
    }

    private void memoize(String version) {
        resolvedVersion = Option.some(version);
    }

    private Result<String> selfDerivedFallback() {
        return selfVersion.toResult(UNRESOLVABLE_VERSION);
    }

    private Result<String> fetchVersionWithCache() {
        return freshCachedVersion().map(Result::success)
                                 .or(this::fetchAndCache);
    }

    private Result<String> fetchAndCache() {
        return fetchLatestVersion().onSuccess(this::cacheVersion);
    }

    private Option<String> freshCachedVersion() {
        return Option.option(cache.getProperty(CACHE_KEY)).filter(_ -> isCacheFresh());
    }

    private boolean isCacheFresh() {
        return cacheTimestamp().filter(timestamp -> System.currentTimeMillis() - timestamp < CACHE_TTL_MS)
                             .isPresent();
    }

    private Option<Long> cacheTimestamp() {
        return Option.option(cache.getProperty(TIMESTAMP_KEY)).flatMap(this::parseTimestamp);
    }

    private Option<Long> parseTimestamp(String raw) {
        return Number.parseLong(raw).option();
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
               : Causes.cause("Could not parse version from GitHub response").result();
    }

    private void cacheVersion(String version) {
        cache.setProperty(CACHE_KEY, version);
        cache.setProperty(TIMESTAMP_KEY,
                          String.valueOf(System.currentTimeMillis()));
        saveCache().onFailure(cause -> log.debug("Failed to persist version cache: {}", cause.message()));
    }

    private static Properties loadCache() {
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
        return Result.lift(Causes::fromThrowable,
                           () -> {
                               Files.createDirectories(CACHE_FILE.getParent());
                               try (var writer = Files.newBufferedWriter(CACHE_FILE)) {
                               cache.store(writer, "JBCT version cache");
                           }
                           });
    }

    // Self-derived build version, read once from the filtered build resource on this jar's own
    // classpath. Mirrors slice-processor BuildInfo: degrades gracefully to absent when the resource
    // is missing or its ${...} placeholder was not filtered.
    private static Option<String> loadEmbeddedVersion() {
        var props = new Properties();

        try (var in = GitHubVersionResolver.class.getResourceAsStream(BUILD_RESOURCE)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            log.debug("Failed to load {}: {}", BUILD_RESOURCE, e.getMessage());
        }

        return Option.option(props.getProperty("version"))
                     .map(String::trim)
                     .filter(version -> !version.isBlank())
                     .filter(version -> !version.startsWith("${"));
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

        return parts1.length >= parts2.length
               ? v1
               : v2;
    }
}
