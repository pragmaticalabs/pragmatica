package org.pragmatica.aether.infra.artifact;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for Maven repository protocol.
 * Supports GET for artifact resolution and PUT for deployment.
 *
 * <p>URL patterns:
 * <ul>
 *   <li>{@code GET /repository/{groupPath}/{artifactId}/{version}/{file}}</li>
 *   <li>{@code PUT /repository/{groupPath}/{artifactId}/{version}/{file}}</li>
 *   <li>{@code GET /repository/{groupPath}/{artifactId}/maven-metadata.xml}</li>
 * </ul>
 */
public interface MavenProtocolHandler {
    /**
     * Handle a GET request.
     */
    Promise<MavenResponse> handleGet(String path);

    /**
     * Handle a PUT request.
     */
    Promise<MavenResponse> handlePut(String path, byte[] content);

    /**
     * Response from Maven protocol handler.
     */
    record MavenResponse(
    int statusCode,
    String contentType,
    byte[] content) {
        public static MavenResponse ok(byte[] content, String contentType) {
            return new MavenResponse(200, contentType, content);
        }

        public static MavenResponse created() {
            return new MavenResponse(201, "text/plain", new byte[0]);
        }

        public static MavenResponse notFound(String message) {
            return new MavenResponse(404, "text/plain", message.getBytes(StandardCharsets.UTF_8));
        }

        public static MavenResponse badRequest(String message) {
            return new MavenResponse(400, "text/plain", message.getBytes(StandardCharsets.UTF_8));
        }

        public static MavenResponse serverError(String message) {
            return new MavenResponse(500, "text/plain", message.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Parsed Maven path.
     */
    sealed interface ParsedPath {
        record ArtifactPath(Artifact artifact, String classifier, String extension) implements ParsedPath {}

        record MetadataPath(GroupId groupId, ArtifactId artifactId) implements ParsedPath {}

        record ChecksumPath(ParsedPath inner, String algorithm) implements ParsedPath {}
    }

    /**
     * Create a Maven protocol handler.
     */
    static MavenProtocolHandler mavenProtocolHandler(ArtifactStore store) {
        return new MavenProtocolHandlerImpl(store);
    }
}

class MavenProtocolHandlerImpl implements MavenProtocolHandler {
    private static final Logger log = LoggerFactory.getLogger(MavenProtocolHandlerImpl.class);
    private static final String REPOSITORY_PREFIX = "/repository/";

    private final ArtifactStore store;

    MavenProtocolHandlerImpl(ArtifactStore store) {
        this.store = store;
    }

    @Override
    public Promise<MavenResponse> handleGet(String path) {
        log.debug("GET {}", path);
        if (!path.startsWith(REPOSITORY_PREFIX)) {
            return Promise.success(MavenResponse.notFound("Invalid path"));
        }
        var repoPath = path.substring(REPOSITORY_PREFIX.length());
        return parsePath(repoPath)
               .fold(() -> Promise.success(MavenResponse.badRequest("Cannot parse path: " + path)),
                     parsed -> handleGetParsed(parsed));
    }

    private Promise<MavenResponse> handleGetParsed(ParsedPath parsed) {
        return switch (parsed) {
            case ParsedPath.ArtifactPath ap -> handleGetArtifact(ap);
            case ParsedPath.MetadataPath mp -> handleGetMetadata(mp);
            case ParsedPath.ChecksumPath cp -> handleGetChecksum(cp);
        };
    }

    private Promise<MavenResponse> handleGetArtifact(ParsedPath.ArtifactPath ap) {
        return store.resolve(ap.artifact())
                    .map(content -> MavenResponse.ok(content,
                                                     contentTypeFor(ap.extension())))
                    .recover(cause -> {
                                 if (cause instanceof ArtifactStore.ArtifactStoreError.NotFound) {
                                 return MavenResponse.notFound("Artifact not found: " + ap.artifact()
                                                                                         .asString());
                             }
                                 return MavenResponse.serverError(cause.message());
                             });
    }

    private Promise<MavenResponse> handleGetMetadata(ParsedPath.MetadataPath mp) {
        return store.versions(mp.groupId(),
                              mp.artifactId())
                    .map(versions -> {
                             if (versions.isEmpty()) {
                             return MavenResponse.notFound("No versions found");
                         }
                             var xml = generateMavenMetadata(mp.groupId(),
                                                             mp.artifactId(),
                                                             versions);
                             return MavenResponse.ok(xml.getBytes(StandardCharsets.UTF_8),
                                                     "application/xml");
                         });
    }

    private Promise<MavenResponse> handleGetChecksum(ParsedPath.ChecksumPath cp) {
        // For checksums, we need to resolve the inner artifact first
        if (cp.inner() instanceof ParsedPath.ArtifactPath ap) {
            return store.resolve(ap.artifact())
                        .map(content -> {
                                 var checksum = computeChecksum(content,
                                                                cp.algorithm());
                                 return MavenResponse.ok(checksum.getBytes(StandardCharsets.UTF_8),
                                                         "text/plain");
                             })
                        .recover(cause -> MavenResponse.notFound("Artifact not found"));
        }
        return Promise.success(MavenResponse.badRequest("Invalid checksum path"));
    }

    @Override
    public Promise<MavenResponse> handlePut(String path, byte[] content) {
        log.debug("PUT {} ({} bytes)", path, content.length);
        if (!path.startsWith(REPOSITORY_PREFIX)) {
            return Promise.success(MavenResponse.badRequest("Invalid path"));
        }
        var repoPath = path.substring(REPOSITORY_PREFIX.length());
        return parsePath(repoPath)
               .fold(() -> Promise.success(MavenResponse.badRequest("Cannot parse path: " + path)),
                     parsed -> handlePutParsed(parsed, content));
    }

    private Promise<MavenResponse> handlePutParsed(ParsedPath parsed, byte[] content) {
        return switch (parsed) {
            case ParsedPath.ArtifactPath ap when ap.extension()
                                                   .equals("jar") ->
            store.deploy(ap.artifact(),
                         content)
                 .map(_ -> MavenResponse.created())
                 .recover(cause -> MavenResponse.serverError(cause.message()));
            case ParsedPath.ArtifactPath _ ->
            // Accept POM, checksums etc. silently (we don't store them separately)
            Promise.success(MavenResponse.created());
            case ParsedPath.ChecksumPath _ ->
            // Checksums are computed, not stored
            Promise.success(MavenResponse.created());
            case ParsedPath.MetadataPath _ ->
            // Metadata is generated, not stored
            Promise.success(MavenResponse.created());
        };
    }

    private Option<ParsedPath> parsePath(String path) {
        // Check for checksum suffix
        if (path.endsWith(".md5")) {
            return parsePath(path.substring(0,
                                            path.length() - 4))
                   .map(inner -> new ParsedPath.ChecksumPath(inner, "MD5"));
        }
        if (path.endsWith(".sha1")) {
            return parsePath(path.substring(0,
                                            path.length() - 5))
                   .map(inner -> new ParsedPath.ChecksumPath(inner, "SHA-1"));
        }
        var parts = path.split("/");
        if (parts.length < 3) return Option.none();
        // Check for maven-metadata.xml
        if (parts[parts.length - 1].equals("maven-metadata.xml")) {
            return parseMetadataPath(parts);
        }
        // Parse artifact path: groupPath/artifactId/version/file
        return parseArtifactPath(parts);
    }

    private Option<ParsedPath> parseMetadataPath(String[] parts) {
        // groupPath/artifactId/maven-metadata.xml
        if (parts.length < 3) return Option.none();
        var artifactIdStr = parts[parts.length - 2];
        var groupPath = new StringBuilder();
        for (int i = 0; i < parts.length - 2; i++ ) {
            if (i > 0) groupPath.append(".");
            groupPath.append(parts[i]);
        }
        return Result.all(GroupId.groupId(groupPath.toString()),
                          ArtifactId.artifactId(artifactIdStr))
                     .map((groupId, artifactId) -> Option.<ParsedPath>some(new ParsedPath.MetadataPath(groupId,
                                                                                                       artifactId)))
                     .or(Option.none());
    }

    private Option<ParsedPath> parseArtifactPath(String[] parts) {
        // groupPath/artifactId/version/file
        if (parts.length < 4) return Option.none();
        var fileName = parts[parts.length - 1];
        var versionStr = parts[parts.length - 2];
        var artifactIdStr = parts[parts.length - 3];
        var groupPath = new StringBuilder();
        for (int i = 0; i < parts.length - 3; i++ ) {
            if (i > 0) groupPath.append(".");
            groupPath.append(parts[i]);
        }
        // Parse file name: artifactId-version[-classifier].extension
        var extension = extractExtension(fileName);
        var classifier = extractClassifier(fileName, artifactIdStr, versionStr);
        return Result.all(GroupId.groupId(groupPath.toString()),
                          ArtifactId.artifactId(artifactIdStr),
                          Version.version(versionStr))
                     .map((groupId, artifactId, version) -> {
                              var artifact = new Artifact(groupId, artifactId, version);
                              return Option.<ParsedPath>some(new ParsedPath.ArtifactPath(artifact, classifier, extension));
                          })
                     .or(Option.none());
    }

    private String extractExtension(String fileName) {
        var lastDot = fileName.lastIndexOf('.');
        return lastDot > 0
               ? fileName.substring(lastDot + 1)
               : "";
    }

    private String extractClassifier(String fileName, String artifactId, String version) {
        var prefix = artifactId + "-" + version;
        if (!fileName.startsWith(prefix)) return "";
        var remainder = fileName.substring(prefix.length());
        if (remainder.startsWith("-")) {
            var dotIndex = remainder.indexOf('.');
            return dotIndex > 1
                   ? remainder.substring(1, dotIndex)
                   : "";
        }
        return "";
    }

    private String generateMavenMetadata(GroupId groupId, ArtifactId artifactId, List<Version> versions) {
        var latest = versions.getLast();
        var release = versions.stream()
                              .filter(v -> !v.withQualifier()
                                             .contains("SNAPSHOT"))
                              .reduce((a, b) -> b)
                              .orElse(latest);
        var timestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                                         .format(Instant.now()
                                                        .atOffset(ZoneOffset.UTC));
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<metadata>\n");
        sb.append("  <groupId>")
          .append(groupId.id())
          .append("</groupId>\n");
        sb.append("  <artifactId>")
          .append(artifactId.id())
          .append("</artifactId>\n");
        sb.append("  <versioning>\n");
        sb.append("    <latest>")
          .append(latest.withQualifier())
          .append("</latest>\n");
        sb.append("    <release>")
          .append(release.withQualifier())
          .append("</release>\n");
        sb.append("    <versions>\n");
        for (var v : versions) {
            sb.append("      <version>")
              .append(v.withQualifier())
              .append("</version>\n");
        }
        sb.append("    </versions>\n");
        sb.append("    <lastUpdated>")
          .append(timestamp)
          .append("</lastUpdated>\n");
        sb.append("  </versioning>\n");
        sb.append("</metadata>\n");
        return sb.toString();
    }

    private String contentTypeFor(String extension) {
        return switch (extension) {
            case"jar" -> "application/java-archive";
            case"pom" -> "application/xml";
            case"xml" -> "application/xml";
            default -> "application/octet-stream";
        };
    }

    private String computeChecksum(byte[] content, String algorithm) {
        try{
            var md = java.security.MessageDigest.getInstance(algorithm);
            var hash = md.digest(content);
            return java.util.HexFormat.of()
                       .formatHex(hash);
        } catch (Exception e) {
            return "";
        }
    }
}
