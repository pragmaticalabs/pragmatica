// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.artifact;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface MavenProtocolHandler {
    Promise<MavenResponse> handleGet(String path);
    Promise<MavenResponse> handlePut(String path, byte[] content);

    record MavenResponse(int statusCode, String contentType, byte[] content) {
        public static MavenResponse ok(byte[] content, String contentType) {
            return new MavenResponse(200, contentType, content);
        }

        public static MavenResponse json(byte[] body) {
            return new MavenResponse(200, "application/json", body);
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

        /// A capability the management API DECLARES but this server does not provide. Distinct from
        /// [#badRequest] on purpose: 400 says the caller sent something malformed, 501 says the
        /// caller was right and the server is incomplete. Answering a declared-but-unbuilt route
        /// with 400 blames the operator for the server's gap.
        public static MavenResponse notImplemented(String message) {
            return new MavenResponse(501, "text/plain", message.getBytes(StandardCharsets.UTF_8));
        }

        public static MavenResponse serverError(String message) {
            return new MavenResponse(500, "text/plain", message.getBytes(StandardCharsets.UTF_8));
        }
    }

    sealed interface ParsedPath {
        record ArtifactPath(Artifact artifact, String classifier, String extension) implements ParsedPath {}

        record MetadataPath(GroupId groupId, ArtifactId artifactId) implements ParsedPath {}

        record ChecksumPath(ParsedPath inner, String algorithm) implements ParsedPath {}
    }

    static MavenProtocolHandler mavenProtocolHandler(ArtifactStore store) {
        return new MavenProtocolHandlerImpl(store);
    }
}

class MavenProtocolHandlerImpl implements MavenProtocolHandler {
    private static final Logger log = LoggerFactory.getLogger(MavenProtocolHandlerImpl.class);
    private static final String REPOSITORY_PREFIX = "/repository/";
    /// `ManagementRoute.REPOSITORY_ARTIFACTS_LIST` declares `GET /repository/artifacts` inside the
    /// namespace the maven protocol owns, so this handler receives it before any management route
    /// can. It is not a maven coordinate, so the coordinate parser rejected it and the caller got
    /// `400 Cannot parse path` — a parse error for a request that was never malformed, blaming the
    /// operator for a route the server simply never implemented (#523).
    ///
    /// `/repository/info/...` (`ARTIFACT_INFO`) is the other non-coordinate route under this prefix;
    /// it is excluded upstream in `MavenProtocolRoutes`. Both exclusions are hand-maintained, so a
    /// THIRD non-coordinate route declared under `/repository/` would reintroduce this bug — see the
    /// note on #525.
    private static final String ARTIFACTS_LIST_PATH = "/repository/artifacts";

    /// Names the missing capability, why it is missing, where it is tracked, and every
    /// listing-adjacent surface that DOES work today, so an operator who hits it learns both what to
    /// use right now and that the real thing is coming.
    private static final String ARTIFACTS_LIST_UNSUPPORTED = "Repository-wide artifact listing is not implemented. GET /repository/artifacts is declared "
                                                           + "in the management API but has no server-side implementation: artifacts are content-addressed "
                                                           + "in the DHT, which exposes no scan or prefix-iteration primitive, so a listing needs an index "
                                                           + "that does not exist yet (tracked by issue #527). Supported today: "
                                                           + "GET /repository/{groupPath}/{artifactId}/maven-metadata.xml "
                                                           + "('aether artifacts versions <group:artifact>') lists the versions of a known artifact; "
                                                           + "GET /repository/info/{groupPath}/{artifactId}/{version} ('aether artifacts info <coords>') "
                                                           + "describes one artifact; GET /api/v1/artifacts/metrics ('aether artifacts metrics') reports "
                                                           + "repository totals.";

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

        if (isArtifactsListPath(path)) {
            return Promise.success(MavenResponse.notImplemented(ARTIFACTS_LIST_UNSUPPORTED));
        }

        var repoPath = path.substring(REPOSITORY_PREFIX.length());

        return parsePath(repoPath).fold(() -> Promise.success(MavenResponse.badRequest("Cannot parse path: " + path)),
                                        parsed -> handleGetParsed(parsed));
    }

    /// Whole-path equality, tolerating one trailing slash — deliberately NOT a prefix test.
    /// A groupId may begin with the segment `artifacts` (`GROUP_ID_PATTERN` only requires a dotted
    /// name), so `/repository/artifacts/demo/lib/1.0.0/lib-1.0.0.jar` is a real coordinate for group
    /// `artifacts.demo` that must keep resolving; a `startsWith` here would silently 501 every
    /// artifact published under such a group. Everything else that fails to parse keeps its 400.
    private static boolean isArtifactsListPath(String path) {
        return ARTIFACTS_LIST_PATH.equals(path) || (ARTIFACTS_LIST_PATH + "/").equals(path);
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
                                 return MavenResponse.notFound("Artifact not found: " + ap.artifact().asString());
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

        return parsePath(repoPath).fold(() -> Promise.success(MavenResponse.badRequest("Cannot parse path: " + path)),
                                        parsed -> handlePutParsed(parsed, content));
    }

    private Promise<MavenResponse> handlePutParsed(ParsedPath parsed, byte[] content) {
        return switch (parsed) {
            // Every artifact is durable, not just .jar: store.deploy is byte-oriented and
            // extension-blind. Previously non-jar artifact PUTs (e.g. .bin/.pom) returned 201 with
            // the content DISCARDED, so a subsequent GET 404'd — silent data loss (the GET path
            // resolves any extension). Sidecars stay contentless 201s (separate ParsedPath cases).
            case ParsedPath.ArtifactPath ap -> handlePutArtifact(ap, content);
            case ParsedPath.ChecksumPath _ -> Promise.success(MavenResponse.created());
            case ParsedPath.MetadataPath _ -> Promise.success(MavenResponse.created());
        };
    }

    /// Idempotent PUT semantics: if the artifact already exists in the store, return
    /// `{"status":"already-present", ...}` with the existing size/md5/sha1 from KV metadata.
    /// Otherwise call `store.deploy` and return `{"status":"uploaded", ...}` with the
    /// metrics computed from the deploy result. Both paths emit HTTP 200 OK with a
    /// JSON body so clients can rely on the exit code + status field for idempotence
    /// instead of grepping error strings.
    ///
    /// Single DHT round-trip for the existence check: `metadata()` returns
    /// `Option.none()` when the meta key is absent, otherwise the parsed record. We
    /// deliberately do NOT call `resolveWithMetadata` here — that reads all chunks and
    /// verifies SHA1 integrity, which is needed for GET semantics but is wasteful for
    /// a duplicate-PUT response. Race: two concurrent PUTs both see `none` and both
    /// call `deploy`; the second overwrites the metadata key. This is acceptable —
    /// each client's "uploaded" semantics still hold (the PUT they sent did write
    /// content to the store) and `ArtifactStore.deploy` is idempotent at the chunk
    /// level (content-addressed BlockIds).
    private Promise<MavenResponse> handlePutArtifact(ParsedPath.ArtifactPath ap, byte[] content) {
        return store.metadata(ap.artifact())
                    .flatMap(metaOpt -> metaOpt.map(meta -> buildAlreadyPresentResponse(ap.artifact(),
                                                                                        meta))
                                               .or(() -> deployAndBuildResponse(ap.artifact(),
                                                                                content)))
                    .recover(cause -> MavenResponse.serverError(cause.message()));
    }

    private Promise<MavenResponse> buildAlreadyPresentResponse(Artifact artifact, ArtifactStore.ArtifactMetadata meta) {
        return Promise.success(MavenResponse.json(renderPushJson("already-present",
                                                                 artifact,
                                                                 meta.size(),
                                                                 meta.md5(),
                                                                 meta.sha1())));
    }

    private Promise<MavenResponse> deployAndBuildResponse(Artifact artifact, byte[] content) {
        return store.deploy(artifact, content)
                    .map(result -> MavenResponse.json(renderPushJson("uploaded",
                                                                     result.artifact(),
                                                                     result.size(),
                                                                     result.md5(),
                                                                     result.sha1())));
    }

    /// Hand-rolled JSON renderer: the artifact-repo module deliberately has no Jackson
    /// dependency (keeps the resource layer free of JSON-mapper transitive weight). The
    /// fields are primitives + URL-safe strings (artifact coordinates, hex digests),
    /// so escaping is limited to backslash and double-quote in the status/coords
    /// values. If the JSON shape ever grows nested objects, promote this to a shared
    /// serialization helper instead of expanding the inline writer.
    private byte[] renderPushJson(String status, Artifact artifact, long size, String md5, String sha1) {
        var sb = new StringBuilder(160);

        sb.append('{');
        appendJsonField(sb, "status", status);
        sb.append(',');
        appendJsonField(sb, "coords", artifact.asString());
        sb.append(',');
        sb.append("\"size\":").append(size);
        sb.append(',');
        appendJsonField(sb, "md5", md5);
        sb.append(',');
        appendJsonField(sb, "sha1", sha1);
        sb.append('}');

        return sb.toString()
                 .getBytes(StandardCharsets.UTF_8);
    }

    private static void appendJsonField(StringBuilder sb, String name, String value) {
        sb.append('"').append(name).append("\":\"").append(escapeJson(value)).append('"');
    }

    private static String escapeJson(String s) {
        if (Option.option(s).isEmpty()) {
            return "";
        }

        var sb = new StringBuilder(s.length() + 8);

        for (int i = 0; i < s.length(); i++) {
            var c = s.charAt(i);

            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }

        return sb.toString();
    }

    private Option<ParsedPath> parsePath(String path) {
        if (path.endsWith(".md5")) {
            return parsePath(path.substring(0, path.length() - 4)).map(inner -> new ParsedPath.ChecksumPath(inner, "MD5"));
        }

        if (path.endsWith(".sha1")) {
            return parsePath(path.substring(0, path.length() - 5)).map(inner -> new ParsedPath.ChecksumPath(inner,
                                                                                                            "SHA-1"));
        }

        var parts = path.split("/");

        if (parts.length < 3) return Option.none();

        if (parts[parts.length - 1].equals("maven-metadata.xml")) {
            return parseMetadataPath(parts);
        }

        return parseArtifactPath(parts);
    }

    private Option<ParsedPath> parseMetadataPath(String[] parts) {
        if (parts.length < 3) return Option.none();

        var artifactIdStr = parts[parts.length - 2];
        var groupPath = new StringBuilder();

        for (int i = 0; i < parts.length - 2; i++) {
            if (i > 0) groupPath.append(".");

            groupPath.append(parts[i]);
        }

        return Result.all(GroupId.groupId(groupPath.toString()),
                          ArtifactId.artifactId(artifactIdStr))
                     .map((groupId, artifactId) -> Option.<ParsedPath> some(new ParsedPath.MetadataPath(groupId,
                                                                                                        artifactId)))
                     .or(Option.none());
    }

    private Option<ParsedPath> parseArtifactPath(String[] parts) {
        if (parts.length < 4) return Option.none();

        var fileName = parts[parts.length - 1];
        var versionStr = parts[parts.length - 2];
        var artifactIdStr = parts[parts.length - 3];
        var groupPath = new StringBuilder();

        for (int i = 0; i < parts.length - 3; i++) {
            if (i > 0) groupPath.append(".");

            groupPath.append(parts[i]);
        }

        var extension = extractExtension(fileName);
        var classifier = extractClassifier(fileName, artifactIdStr, versionStr);

        return Result.all(GroupId.groupId(groupPath.toString()),
                          ArtifactId.artifactId(artifactIdStr),
                          Version.version(versionStr))
                     .map((groupId, artifactId, version) -> toArtifactPath(groupId,
                                                                           artifactId,
                                                                           version,
                                                                           classifier,
                                                                           extension))
                     .or(Option.none());
    }

    private Option<ParsedPath> toArtifactPath(GroupId groupId,
                                              ArtifactId artifactId,
                                              Version version,
                                              String classifier,
                                              String extension) {
        var artifact = new Artifact(groupId, artifactId, version);

        return Option.some(new ParsedPath.ArtifactPath(artifact, classifier, extension));
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
        var timestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(Instant.now().atOffset(ZoneOffset.UTC));
        var sb = new StringBuilder();

        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<metadata>\n");
        sb.append("  <groupId>").append(escapeXml(groupId.id())).append("</groupId>\n");
        sb.append("  <artifactId>").append(escapeXml(artifactId.id())).append("</artifactId>\n");
        sb.append("  <versioning>\n");
        sb.append("    <latest>").append(escapeXml(latest.withQualifier())).append("</latest>\n");
        sb.append("    <release>").append(escapeXml(release.withQualifier())).append("</release>\n");
        sb.append("    <versions>\n");
        for (var v : versions) {
            sb.append("      <version>").append(escapeXml(v.withQualifier())).append("</version>\n");
        }

        sb.append("    </versions>\n");
        sb.append("    <lastUpdated>").append(timestamp).append("</lastUpdated>\n");
        sb.append("  </versioning>\n");
        sb.append("</metadata>\n");

        return sb.toString();
    }

    private static String escapeXml(String s) {
        if (Option.option(s).isEmpty()) return "";

        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private String contentTypeFor(String extension) {
        return switch (extension) {
            case "jar" -> "application/java-archive";
            case "pom" -> "application/xml";
            case "xml" -> "application/xml";
            default -> "application/octet-stream";
        };
    }

    private String computeChecksum(byte[] content, String algorithm) {
        try {
            var md = java.security.MessageDigest.getInstance(algorithm);
            var hash = md.digest(content);

            return java.util.HexFormat.of()
                                      .formatHex(hash);
        } catch (Exception e) {
            return "";
        }
    }
}
