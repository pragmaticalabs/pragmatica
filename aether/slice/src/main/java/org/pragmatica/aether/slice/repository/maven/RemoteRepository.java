package org.pragmatica.aether.slice.repository.maven;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.slice.repository.Location.location;

/// Repository implementation that downloads artifacts from remote Maven repositories.
///
/// Downloads JARs from remote HTTP(S) endpoints, verifies SHA-1 checksums,
/// and caches to the local Maven repository (~/.m2/repository).
/// Subsequent resolves for the same artifact are instant (local cache hit).
///
/// Supports Basic authentication via ~/.m2/settings.xml server entries.
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-ZONE-02", "JBCT-RET-03", "JBCT-EX-01"})
public interface RemoteRepository extends Repository {
    Logger log = LoggerFactory.getLogger(RemoteRepository.class);
    Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(30);

    /// Create a RemoteRepository for the given Maven repository.
    ///
    /// @param repoId Repository ID (for settings.xml credential matching)
    /// @param baseUrl Base URL of the remote Maven repository
    /// @return RemoteRepository instance
    static RemoteRepository remoteRepository(String repoId, String baseUrl) {
        return remoteRepository(repoId, baseUrl, DEFAULT_HTTP_TIMEOUT);
    }

    /// Create a RemoteRepository with custom HTTP timeout.
    static RemoteRepository remoteRepository(String repoId, String baseUrl, Duration httpTimeout) {
        var normalizedUrl = baseUrl.endsWith("/")
                            ? baseUrl
                            : baseUrl + "/";
        var credentials = MavenSettingsCredentials.forServer(repoId);
        var localRepo = Path.of(MavenLocalRepoLocator.findLocalRepository());
        return artifact -> resolveArtifact(artifact, normalizedUrl, credentials, localRepo, httpTimeout);
    }

    private static Promise<Location> resolveArtifact(Artifact artifact,
                                                     String baseUrl,
                                                     Option<MavenSettingsCredentials.Credentials> credentials,
                                                     Path localRepo,
                                                     Duration httpTimeout) {
        var cachedPath = localPath(artifact, localRepo);
        if ( Files.exists(cachedPath)) {
            log.debug("Cache hit for {} at {}", artifact.asString(), cachedPath);
            return toLocation(artifact, cachedPath);
        }
        return downloadAndCache(artifact, baseUrl, credentials, cachedPath, httpTimeout);
    }

    private static Promise<Location> downloadAndCache(Artifact artifact,
                                                      String baseUrl,
                                                      Option<MavenSettingsCredentials.Credentials> credentials,
                                                      Path targetPath,
                                                      Duration httpTimeout) {
        var httpOps = JdkHttpOperations.jdkHttpOperations(httpTimeout, HttpClient.Redirect.NORMAL, Option.none());
        var artifactPath = remotePath(artifact);
        var jarUrl = baseUrl + artifactPath;
        var sha1Url = jarUrl + ".sha1";
        return downloadJar(httpOps, jarUrl, credentials, artifact, httpTimeout).flatMap(jarBytes -> verifySha1AndCache(httpOps,
                                                                                                                       sha1Url,
                                                                                                                       credentials,
                                                                                                                       jarBytes,
                                                                                                                       artifact,
                                                                                                                       targetPath,
                                                                                                                       httpTimeout))
                          .flatMap(path -> toLocation(artifact, path));
    }

    private static Promise<byte[]> downloadJar(HttpOperations httpOps,
                                               String jarUrl,
                                               Option<MavenSettingsCredentials.Credentials> credentials,
                                               Artifact artifact,
                                               Duration httpTimeout) {
        var jarRequest = buildRequest(jarUrl, credentials, httpTimeout);
        return httpOps.sendBytes(jarRequest).flatMap(result -> handleJarResponse(result, jarUrl, artifact));
    }

    private static Promise<byte[]> handleJarResponse(HttpResult<byte[]> result,
                                                     String jarUrl,
                                                     Artifact artifact) {
        if ( result.statusCode() != 200) {
        return new RemoteRepositoryError.DownloadFailed(jarUrl, new RemoteDownloadException(jarUrl, result.statusCode())).promise();}
        var jarBytes = result.body();
        log.info("Downloaded {} ({} bytes) from {}", artifact.asString(), jarBytes.length, jarUrl);
        return Promise.success(jarBytes);
    }

    private static Promise<Path> verifySha1AndCache(HttpOperations httpOps,
                                                    String sha1Url,
                                                    Option<MavenSettingsCredentials.Credentials> credentials,
                                                    byte[] jarBytes,
                                                    Artifact artifact,
                                                    Path targetPath,
                                                    Duration httpTimeout) {
        var sha1Request = buildRequest(sha1Url, credentials, httpTimeout);
        return httpOps.sendString(sha1Request).flatMap(result -> handleSha1Response(result, jarBytes, artifact))
                                 .flatMap(_ -> cacheAndReturn(targetPath, jarBytes, artifact));
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private static Promise<byte[]> handleSha1Response(HttpResult<String> result,
                                                      byte[] jarBytes,
                                                      Artifact artifact) {
        if ( result.statusCode() == 200) {
        return verifySha1Checksum(result.body(), jarBytes, artifact);}
        log.debug("No SHA-1 checksum available for {} ({}), skipping verification",
                  artifact.asString(),
                  result.statusCode());
        return Promise.success(jarBytes);
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private static Promise<byte[]> verifySha1Checksum(String sha1Body, byte[] jarBytes, Artifact artifact) {
        var expectedSha1 = sha1Body.trim().split("\\s") [0];
        return Promise.lift(cause -> new RemoteRepositoryError.DownloadFailed(artifact.asString(), cause),
                            () -> computeSha1(jarBytes))
        .flatMap(actualSha1 -> matchChecksum(expectedSha1, actualSha1, jarBytes, artifact));
    }

    private static Promise<byte[]> matchChecksum(String expected, String actual, byte[] jarBytes, Artifact artifact) {
        if ( !expected.equalsIgnoreCase(actual)) {
        return new RemoteRepositoryError.DownloadFailed(artifact.asString(),
                                                        new ChecksumMismatchException(artifact.asString(),
                                                                                      expected,
                                                                                      actual)).promise();}
        log.debug("SHA-1 verified for {}: {}", artifact.asString(), actual);
        return Promise.success(jarBytes);
    }

    private static Promise<Path> cacheAndReturn(Path targetPath, byte[] jarBytes, Artifact artifact) {
        return Promise.lift(cause -> new RemoteRepositoryError.DownloadFailed(artifact.asString(), cause),
                            () -> cacheAndReturnPath(targetPath, jarBytes, artifact));
    }

    private static Path cacheAndReturnPath(Path targetPath, byte[] jarBytes, Artifact artifact) throws IOException {
        cacheArtifact(targetPath, jarBytes);
        log.info("Cached {} to {}", artifact.asString(), targetPath);
        return targetPath;
    }

    private static HttpRequest buildRequest(String url,
                                            Option<MavenSettingsCredentials.Credentials> credentials,
                                            Duration httpTimeout) {
        var builder = HttpRequest.newBuilder().uri(URI.create(url))
                                            .GET()
                                            .timeout(httpTimeout);
        credentials.onPresent(creds -> builder.header("Authorization", creds.toBasicAuthHeader()));
        return builder.build();
    }

    private static String computeSha1(byte[] data) throws Exception {
        var digest = MessageDigest.getInstance("SHA-1");
        var hash = digest.digest(data);
        var sb = new StringBuilder();
        for ( byte b : hash) {
        sb.append(String.format("%02x", b));}
        return sb.toString();
    }

    private static void cacheArtifact(Path targetPath, byte[] content) throws IOException {
        Files.createDirectories(targetPath.getParent());
        var tempFile = Files.createTempFile(targetPath.getParent(), ".download-", ".tmp");
        try {
            Files.write(tempFile, content);
            Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }


        catch (Exception e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }

    private static Path localPath(Artifact artifact, Path localRepo) {
        var version = artifact.version();
        var artifactId = artifact.artifactId().id();
        return localRepo.resolve(artifact.groupId().id()
                                                 .replace('.', '/')).resolve(artifactId)
                                .resolve(version.withQualifier())
                                .resolve(artifactId + "-" + version.withQualifier() + ".jar");
    }

    private static String remotePath(Artifact artifact) {
        var version = artifact.version();
        var artifactId = artifact.artifactId().id();
        return artifact.groupId().id()
                               .replace('.', '/') + "/" + artifactId + "/" + version.withQualifier() + "/" + artifactId + "-" + version.withQualifier() + ".jar";
    }

    private static Promise<Location> toLocation(Artifact artifact, Path path) {
        return Promise.lift(cause -> LOCATION_ERROR.apply(artifact.asString() + ": " + cause.getMessage()),
                            () -> path.toUri().toURL())
        .flatMap(url -> location(artifact, url).async());
    }

    Fn1<Cause, String> LOCATION_ERROR = Causes.forOneValue("Failed to create location for artifact %s");

    /// Errors specific to remote repository operations.
    sealed interface RemoteRepositoryError extends Cause {
        record DownloadFailed(String url, Throwable cause) implements RemoteRepositoryError {
            @Override public String message() {
                if ( cause instanceof RemoteDownloadException rde) {
                return "Download failed: HTTP " + rde.statusCode() + " from " + rde.url();}
                if ( cause instanceof ChecksumMismatchException cme) {
                return "Checksum mismatch for " + cme.artifact() + ": expected " + cme.expected() + ", got " + cme.actual();}
                return "Download failed from " + url + ": " + cause.getMessage();
            }
        }
    }

    /// Internal exception for HTTP download failures.
    final class RemoteDownloadException extends RuntimeException {
        private final String url;
        private final int statusCode;

        RemoteDownloadException(String url, int statusCode) {
            super("HTTP " + statusCode + " from " + url);
            this.url = url;
            this.statusCode = statusCode;
        }

        String url() {
            return url;
        }

        int statusCode() {
            return statusCode;
        }
    }

    /// Internal exception for checksum mismatches.
    final class ChecksumMismatchException extends RuntimeException {
        private final String artifact;
        private final String expected;
        private final String actual;

        ChecksumMismatchException(String artifact, String expected, String actual) {
            super("Checksum mismatch for " + artifact);
            this.artifact = artifact;
            this.expected = expected;
            this.actual = actual;
        }

        String artifact() {
            return artifact;
        }

        String expected() {
            return expected;
        }

        String actual() {
            return actual;
        }
    }
}
