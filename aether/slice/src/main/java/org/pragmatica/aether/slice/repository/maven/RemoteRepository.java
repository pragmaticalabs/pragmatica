// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.repository.maven;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.FileOps.createDirectories;
import static org.pragmatica.lang.io.FileOps.createTempFile;
import static org.pragmatica.lang.io.FileOps.deleteIfExists;
import static org.pragmatica.lang.io.FileOps.exists;
import static org.pragmatica.lang.io.FileOps.moveReplace;
import static org.pragmatica.lang.io.FileOps.writeBytes;
import static org.pragmatica.aether.slice.repository.Location.location;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-ZONE-02", "JBCT-EX-01"})
public interface RemoteRepository extends Repository {
    Logger log = LoggerFactory.getLogger(RemoteRepository.class);
    Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(30);

    static RemoteRepository remoteRepository(String repoId, String baseUrl) {
        return remoteRepository(repoId, baseUrl, DEFAULT_HTTP_TIMEOUT);
    }

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

        if (exists(cachedPath)) {
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
        var sha256Url = jarUrl + ".sha256";
        var sha1Url = jarUrl + ".sha1";

        return downloadJar(httpOps, jarUrl, credentials, artifact, httpTimeout).flatMap(jarBytes -> verifyChecksumAndCache(httpOps,
                                                                                                                           sha256Url,
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

        return httpOps.sendBytes(jarRequest)
                      .flatMap(result -> handleJarResponse(result, jarUrl, artifact));
    }

    private static Promise<byte[]> handleJarResponse(HttpResult<byte[]> result, String jarUrl, Artifact artifact) {
        if (result.statusCode() != 200) {
            return new RemoteRepositoryError.DownloadFailed(jarUrl,
                                                            new RemoteDownloadException(jarUrl, result.statusCode())).promise();
        }

        var jarBytes = result.body();

        log.info("Downloaded {} ({} bytes) from {}", artifact.asString(), jarBytes.length, jarUrl);

        return Promise.success(jarBytes);
    }

    private static Promise<Path> verifyChecksumAndCache(HttpOperations httpOps,
                                                        String sha256Url,
                                                        String sha1Url,
                                                        Option<MavenSettingsCredentials.Credentials> credentials,
                                                        byte[] jarBytes,
                                                        Artifact artifact,
                                                        Path targetPath,
                                                        Duration httpTimeout) {
        var sha256Request = buildRequest(sha256Url, credentials, httpTimeout);

        return httpOps.sendString(sha256Request)
                      .flatMap(result -> handleChecksumResponse(result,
                                                                "SHA-256",
                                                                httpOps,
                                                                sha1Url,
                                                                credentials,
                                                                jarBytes,
                                                                artifact,
                                                                httpTimeout))
                      .flatMap(_ -> cacheAndReturn(targetPath, jarBytes, artifact));
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private static Promise<byte[]> handleChecksumResponse(HttpResult<String> result,
                                                          String algorithm,
                                                          HttpOperations httpOps,
                                                          String sha1Url,
                                                          Option<MavenSettingsCredentials.Credentials> credentials,
                                                          byte[] jarBytes,
                                                          Artifact artifact,
                                                          Duration httpTimeout) {
        if (result.statusCode() == 200) {
            return verifyChecksum(result.body(), algorithm, jarBytes, artifact);
        }

        log.debug("No {} checksum available for {} ({}), trying fallback",
                  algorithm,
                  artifact.asString(),
                  result.statusCode());

        return fallbackToSha1(httpOps, sha1Url, credentials, jarBytes, artifact, httpTimeout);
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private static Promise<byte[]> fallbackToSha1(HttpOperations httpOps,
                                                  String sha1Url,
                                                  Option<MavenSettingsCredentials.Credentials> credentials,
                                                  byte[] jarBytes,
                                                  Artifact artifact,
                                                  Duration httpTimeout) {
        var sha1Request = buildRequest(sha1Url, credentials, httpTimeout);

        return httpOps.sendString(sha1Request)
                      .flatMap(sha1Result -> handleSha1FallbackResponse(sha1Result, jarBytes, artifact));
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private static Promise<byte[]> handleSha1FallbackResponse(HttpResult<String> result,
                                                              byte[] jarBytes,
                                                              Artifact artifact) {
        if (result.statusCode() == 200) {
            return verifyChecksum(result.body(), "SHA-1", jarBytes, artifact);
        }

        log.warn("No checksum available for {} — neither SHA-256 nor SHA-1", artifact.asString());

        return new RemoteRepositoryError.ChecksumUnavailable(artifact.asString()).promise();
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private static Promise<byte[]> verifyChecksum(String checksumBody,
                                                  String algorithm,
                                                  byte[] jarBytes,
                                                  Artifact artifact) {
        var expectedChecksum = checksumBody.trim().split("\\s") [0];

        return Promise.lift(cause -> new RemoteRepositoryError.DownloadFailed(artifact.asString(),
                                                                              cause),
                            () -> computeChecksum(jarBytes, algorithm))
                      .flatMap(actualChecksum -> matchChecksum(expectedChecksum,
                                                               actualChecksum,
                                                               algorithm,
                                                               jarBytes,
                                                               artifact));
    }

    private static Promise<byte[]> matchChecksum(String expected,
                                                 String actual,
                                                 String algorithm,
                                                 byte[] jarBytes,
                                                 Artifact artifact) {
        if (!expected.equalsIgnoreCase(actual)) {
            return new RemoteRepositoryError.DownloadFailed(artifact.asString(),
                                                            new ChecksumMismatchException(artifact.asString(),
                                                                                          expected,
                                                                                          actual)).promise();
        }

        log.debug("{} verified for {}: {}", algorithm, artifact.asString(), actual);

        return Promise.success(jarBytes);
    }

    private static Promise<Path> cacheAndReturn(Path targetPath, byte[] jarBytes, Artifact artifact) {
        return Promise.lift(cause -> new RemoteRepositoryError.DownloadFailed(artifact.asString(), cause),
                            () -> cacheAndReturnPath(targetPath, jarBytes, artifact));
    }

    private static Path cacheAndReturnPath(Path targetPath, byte[] jarBytes, Artifact artifact) {
        var result = cacheArtifact(targetPath, jarBytes);

        log.info("Cached {} to {}", artifact.asString(), targetPath);

        return result;
    }

    private static HttpRequest buildRequest(String url,
                                            Option<MavenSettingsCredentials.Credentials> credentials,
                                            Duration httpTimeout) {
        var builder = HttpRequest.newBuilder().uri(URI.create(url)).GET().timeout(httpTimeout);

        credentials.onPresent(creds -> builder.header("Authorization", creds.toBasicAuthHeader()));

        return builder.build();
    }

    private static String computeChecksum(byte[] data, String algorithm) throws Exception {
        var digest = MessageDigest.getInstance(algorithm);
        var hash = digest.digest(data);
        var sb = new StringBuilder();

        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }

        return sb.toString();
    }

    private static Path cacheArtifact(Path targetPath, byte[] content) {
        return createDirectories(targetPath.getParent()).flatMap(_ -> createTempFile(".download-", ".tmp"))
                                .flatMap(tempFile -> writeBytes(tempFile, content).flatMap(_ -> moveReplace(tempFile,
                                                                                                            targetPath))
                                                               .onFailure(_ -> deleteIfExists(tempFile)))
                                .unwrap();
    }

    private static Path localPath(Artifact artifact, Path localRepo) {
        var version = artifact.version();
        var artifactId = artifact.artifactId().id();

        return localRepo.resolve(artifact.groupId().id().replace('.', '/'))
                        .resolve(artifactId)
                        .resolve(version.withQualifier())
                        .resolve(artifactId + "-" + version.withQualifier() + ".jar");
    }

    private static String remotePath(Artifact artifact) {
        var version = artifact.version();
        var artifactId = artifact.artifactId().id();

        return artifact.groupId()
                       .id()
                       .replace('.', '/')
             + "/" + artifactId
             + "/" + version.withQualifier()
             + "/" + artifactId
             + "-" + version.withQualifier()
             + ".jar";
    }

    private static Promise<Location> toLocation(Artifact artifact, Path path) {
        return Promise.lift(cause -> LOCATION_ERROR.apply(artifact.asString() + ": " + cause.getMessage()),
                            () -> path.toUri()
                                      .toURL())
                      .flatMap(url -> location(artifact, url).async());
    }

    Fn1<Cause, String> LOCATION_ERROR = Causes.forOneValue("Failed to create location for artifact %s");

    sealed interface RemoteRepositoryError extends Cause {
        record DownloadFailed(String url, Throwable cause) implements RemoteRepositoryError {
            @Override
            public String message() {
                if (cause instanceof RemoteDownloadException rde) {
                    return "Download failed: HTTP " + rde.statusCode() + " from " + rde.url();
                }

                if (cause instanceof ChecksumMismatchException cme) {
                    return "Checksum mismatch for " + cme.artifact()
                         + ": expected " + cme.expected()
                         + ", got " + cme.actual();
                }

                return "Download failed from " + url + ": " + cause.getMessage();
            }
        }

        record ChecksumUnavailable(String artifact) implements RemoteRepositoryError {
            @Override
            public String message() {
                return "No checksum available for artifact " + artifact + " — neither SHA-256 nor SHA-1";
            }
        }
    }

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
