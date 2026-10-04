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
import java.util.stream.Stream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.StorageError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface MavenProtocolHandler {
    Promise<MavenResponse> handleGet(String path);
    Promise<MavenResponse> handlePut(String path, byte[] content);
    /// Archives the version named by `/repository/<groupPath>/<artifactId>/<version>` (#1778). The store
    /// never deletes: the version stops resolving and is delisted, its keys are kept.
    Promise<MavenResponse> handleDelete(String path);

    /// `<groupPath>/<artifactId>/<version>`: the group is every segment before the last two, the same
    /// reading as `GET`/`PUT` coordinates. Shared by every route that carries a bare version coordinate
    /// (`DELETE`, and `GET /repository/info/...`, #1102) so none of them re-derives the group
    /// positionally.
    static Option<Artifact> parseVersionPath(String path) {
        var parts = path.split("/");

        if (parts.length < 3) return Option.none();

        var groupPath = String.join(".",
                                    List.of(parts).subList(0, parts.length - 2));

        return Result.all(GroupId.groupId(groupPath),
                          ArtifactId.artifactId(parts[parts.length - 2]),
                          Version.version(parts[parts.length - 1]))
                     .map(Artifact::new)
                     .option();
    }

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

        /// The request is well-formed but contradicts the store's state: a coordinate already holds different
        /// content, the version is archived, or it is too young to archive. Retrying the same request cannot
        /// succeed, unlike [#unavailable].
        public static MavenResponse conflict(String message) {
            return new MavenResponse(409, "text/plain", message.getBytes(StandardCharsets.UTF_8));
        }

        /// The artifact existed and was archived: unavailable on purpose, distinct from [#notFound]
        /// ("never written").
        public static MavenResponse gone(String message) {
            return new MavenResponse(410, "text/plain", message.getBytes(StandardCharsets.UTF_8));
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

        /// A passing condition (DHT churn, quorum or peer loss, timeout): the same request may succeed
        /// if retried. The route layer adds `Retry-After` to every 503 it writes, so callers back off
        /// instead of reading the answer as a server defect the way they read 500.
        public static MavenResponse unavailable(String message) {
            return new MavenResponse(503, "text/plain", message.getBytes(StandardCharsets.UTF_8));
        }
    }

    sealed interface ParsedPath {
        /// `fileName` is the exact path segment: the store keys the file by it, never by a parsed reading of it.
        record ArtifactPath(Artifact artifact, String fileName) implements ParsedPath {
            ArtifactFile file() {
                return ArtifactFile.named(artifact, fileName);
            }
        }

        record MetadataPath(GroupId groupId, ArtifactId artifactId) implements ParsedPath {}

        /// `<group>/<artifact>/<version>/maven-metadata.xml`: what Maven writes for a SNAPSHOT version. The built-in
        /// store holds no SNAPSHOTs, so this is recognised in order to be refused accurately, never served (#1919).
        record VersionMetadataPath(String path) implements ParsedPath {}

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

    private static final String DERIVED_METADATA_JSON = "{\"status\":\"derived\",\"detail\":\"maven-metadata.xml and its checksums are computed by the repository; the uploaded bytes were not stored\"}";

    private static final String METADATA_FILE = "maven-metadata.xml";
    private static final DateTimeFormatter LAST_UPDATED_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

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
            case ParsedPath.VersionMetadataPath vp -> Promise.success(MavenResponse.notFound(versionMetadataRefusal(vp)));
            case ParsedPath.ChecksumPath cp -> handleGetChecksum(cp);
        };
    }

    /// FER: a failed read is reported, not dropped: it becomes the status the caller acts on (503 asks it to
    /// retry); nothing is retried or compensated here.
    private Promise<MavenResponse> handleGetArtifact(ParsedPath.ArtifactPath ap) {
        return store.resolve(ap.file())
                    .map(content -> MavenResponse.ok(content,
                                                     contentTypeFor(ap.fileName())))
                    .recover(cause -> getFailureResponse(ap, cause));
    }

    /// Absent is 404 ("never written"); archived is 410 ("was written, retired on purpose").
    private static MavenResponse getFailureResponse(ParsedPath.ArtifactPath ap, Cause cause) {
        return switch (cause) {
            case ArtifactStore.ArtifactStoreError.NotFound _ -> MavenResponse.notFound("Artifact not found: " + ap.file().asString());
            case ArtifactStore.ArtifactStoreError.Archived archived -> MavenResponse.gone(archived.message());
            default -> failureResponse("GET",
                                       ap.file().asString(),
                                       cause);
        };
    }

    /// Transient causes answer 503 (retry), everything else 500 (defect). Every 5xx is WARN-logged
    /// with its cause: the response body is routinely discarded by clients (`curl -o /dev/null`),
    /// and a 500 that left no log line once cost a diagnosis its only evidence.
    private static MavenResponse failureResponse(String operation, String subject, Cause cause) {
        if (isTransientFailure(cause)) {
            log.warn("{} {} unavailable (answering 503, retryable): {}", operation, subject, cause.message());

            return MavenResponse.unavailable(cause.message());
        }

        log.warn("{} {} failed (answering 500): {}", operation, subject, cause.message());

        return MavenResponse.serverError(cause.message());
    }

    private static boolean isTransientFailure(Cause cause) {
        return cause.isTransient() || cause instanceof StorageError.TierNotAdmitted;
    }

    private Promise<MavenResponse> handleGetMetadata(ParsedPath.MetadataPath mp) {
        return renderMetadata(mp).map(rendered -> rendered.fold(() -> MavenResponse.notFound("No versions found"),
                                                                xml -> MavenResponse.ok(xml, "application/xml")))
                             .recover(cause -> metadataFailureResponse(mp, cause));
    }

    /// The render reads the stored metadata of EVERY listed version (for `<lastUpdated>`), so one transient
    /// read failure must not turn into a 500 for the whole listing and its checksums: it answers 503 so the
    /// client retries, exactly like a failed artifact read.
    private static MavenResponse metadataFailureResponse(ParsedPath.MetadataPath mp, Cause cause) {
        return failureResponse("GET",
                               mp.groupId().id() + ":" + mp.artifactId().id() + " maven-metadata.xml",
                               cause);
    }

    /// The exact bytes `GET .../maven-metadata.xml` returns, or empty when nothing is deployed. Every
    /// checksum of the metadata (#1833) is computed from THIS, so a client that fetches the metadata and
    /// then its sidecar compares like with like.
    private Promise<Option<byte[]>> renderMetadata(ParsedPath.MetadataPath mp) {
        return store.versions(mp.groupId(),
                              mp.artifactId())
                    .flatMap(versions -> versions.isEmpty()
                                         ? Promise.success(Option.<byte[]> none())
                                         : lastDeployedAt(mp, versions).map(deployedAt -> Option.some(generateMavenMetadata(mp.groupId(),
                                                                                                                            mp.artifactId(),
                                                                                                                            versions,
                                                                                                                            deployedAt).getBytes(StandardCharsets.UTF_8))));
    }

    /// The newest deploy time among the listed versions, read from the metadata the store persisted when each
    /// version's primary file was written (write-once, #1778), so it only changes when a version is added. A
    /// read that FAILS fails the render: omitting the field on a transient error would make the bytes, and so
    /// their checksums, depend on the failure. A version with no stored metadata contributes nothing.
    private Promise<Long> lastDeployedAt(ParsedPath.MetadataPath mp, List<Version> versions) {
        var reads = versions.stream()
                            .map(version -> store.metadata(new Artifact(mp.groupId(),
                                                                        mp.artifactId(),
                                                                        version))
                                                 .map(stored -> stored.map(ArtifactStore.ArtifactMetadata::deployedAt)
                                                                      .or(0L)))
                            .toList();

        return Promise.allOf(reads).flatMap(MavenProtocolHandlerImpl::newestOrFirstFailure);
    }

    private static Promise<Long> newestOrFirstFailure(List<Result<Long>> results) {
        return results.stream()
                      .flatMap(result -> result.fold(Stream::of,
                                                     _ -> Stream.<Cause> empty()))
                      .findFirst()
                      .<Promise<Long>> map(Cause::promise)
                      .orElseGet(() -> Promise.success(results.stream()
                                                              .mapToLong(result -> result.fold(_ -> 0L,
                                                                                               deployedAt -> deployedAt))
                                                              .max()
                                                              .orElse(0L)));
    }

    private Promise<MavenResponse> handleGetChecksum(ParsedPath.ChecksumPath cp) {
        return switch (cp.inner()) {
            case ParsedPath.ArtifactPath ap -> handleGetArtifactChecksum(ap, cp.algorithm());
            case ParsedPath.MetadataPath mp -> handleGetMetadataChecksum(mp, cp.algorithm());
            case ParsedPath.VersionMetadataPath vp -> Promise.success(MavenResponse.notFound(versionMetadataRefusal(vp)));
            case ParsedPath.ChecksumPath _ -> Promise.success(MavenResponse.badRequest("Invalid checksum path"));
        };
    }

    private Promise<MavenResponse> handleGetArtifactChecksum(ParsedPath.ArtifactPath ap, String algorithm) {
        return store.resolve(ap.file())
                    .map(content -> checksumResponse(content, algorithm))
                    .recover(MavenProtocolHandlerImpl::checksumFailureResponse);
    }

    private Promise<MavenResponse> handleGetMetadataChecksum(ParsedPath.MetadataPath mp, String algorithm) {
        return renderMetadata(mp).map(rendered -> rendered.fold(() -> MavenResponse.notFound("No versions found"),
                                                                xml -> checksumResponse(xml, algorithm)))
                             .recover(cause -> metadataFailureResponse(mp, cause));
    }

    private MavenResponse checksumResponse(byte[] content, String algorithm) {
        return MavenResponse.ok(computeChecksum(content, algorithm).getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    /// A sidecar of an archived file is as gone as the file (410, #1778); every other failure keeps answering 404,
    /// as it always has.
    private static MavenResponse checksumFailureResponse(Cause cause) {
        return cause instanceof ArtifactStore.ArtifactStoreError.Archived archived
               ? MavenResponse.gone(archived.message())
               : MavenResponse.notFound("Artifact not found");
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
            case ParsedPath.ChecksumPath cp when cp.inner() instanceof ParsedPath.VersionMetadataPath vp -> Promise.success(MavenResponse.badRequest(versionMetadataRefusal(vp)));
            case ParsedPath.VersionMetadataPath vp -> Promise.success(MavenResponse.badRequest(versionMetadataRefusal(vp)));
            case ParsedPath.ChecksumPath cp when cp.inner() instanceof ParsedPath.MetadataPath -> Promise.success(derivedMetadataResponse());
            case ParsedPath.ChecksumPath _ -> Promise.success(MavenResponse.created());
            case ParsedPath.MetadataPath _ -> Promise.success(derivedMetadataResponse());
        };
    }

    /// `maven-metadata.xml` and its checksums are DERIVED from the version index, never stored: a client's
    /// uploaded copy describes its own view of the versions, not ours. The upload is accepted (a standard
    /// `mvn deploy` PUTs the metadata and its checksums and must not fail on them) but the answer says it
    /// was not stored, instead of the contentless 201 that used to read as "stored" (#1833).
    private static MavenResponse derivedMetadataResponse() {
        return MavenResponse.json(DERIVED_METADATA_JSON.getBytes(StandardCharsets.UTF_8));
    }

    /// Write-once PUT (#1778); the decisions live in `ArtifactStore.deploy`, which this maps to HTTP:
    ///
    ///   - new coordinate: 200 `{"status":"uploaded", ...}`;
    ///   - same content already stored (idempotent re-put): 200 `{"status":"already-present", ...}`;
    ///   - different content already stored: 409 naming both SHA-1 digests, stored content kept;
    ///   - archived version: 409, coordinates of an archived version are never reused;
    ///   - SNAPSHOT version: 400, the built-in store holds immutable coordinates only;
    ///   - the existence check or the write fails transiently: 503 + Retry-After (a failed check never
    ///     deploys, #1795); any other failure: 500.
    ///
    /// The body is JSON so clients can rely on the exit code + status field instead of grepping error strings.
    ///
    /// FER: a refused or failed PUT is reported, not dropped: it becomes the status above; nothing is retried
    /// or compensated here.
    private Promise<MavenResponse> handlePutArtifact(ParsedPath.ArtifactPath ap, byte[] content) {
        return store.deploy(ap.file(),
                            content)
                    .map(this::buildPushResponse)
                    .recover(cause -> putFailureResponse(ap.file(),
                                                         cause));
    }

    private MavenResponse buildPushResponse(ArtifactStore.DeployResult result) {
        return MavenResponse.json(renderPushJson(result.alreadyPresent()
                                                 ? "already-present"
                                                 : "uploaded",
                                                 result.artifact(),
                                                 result.size(),
                                                 result.md5(),
                                                 result.sha1()));
    }

    /// Refusals are the caller's to act on, so they are logged at INFO and carry the store's own message.
    private static MavenResponse putFailureResponse(ArtifactFile file, Cause cause) {
        return switch (cause) {
            case ArtifactStore.ArtifactStoreError.ContentConflict conflict -> refusal(file,
                                                                                      MavenResponse.conflict(conflict.message()));
            case ArtifactStore.ArtifactStoreError.Archived archived -> refusal(file,
                                                                               MavenResponse.conflict(archived.message()));
            case ArtifactStore.ArtifactStoreError.VersionLimitReached full -> refusal(file,
                                                                                      MavenResponse.conflict(full.message()));
            case ArtifactStore.ArtifactStoreError.SnapshotRefused refused -> refusal(file,
                                                                                     MavenResponse.badRequest(refused.message()));
            default -> failureResponse("PUT", file.asString(), cause);
        };
    }

    private static MavenResponse refusal(ArtifactFile file, MavenResponse response) {
        log.info("PUT {} refused (answering {}): {}",
                 file.asString(),
                 response.statusCode(),
                 new String(response.content(), StandardCharsets.UTF_8));

        return response;
    }

    @Override
    public Promise<MavenResponse> handleDelete(String path) {
        log.debug("DELETE {}", path);
        if (!path.startsWith(REPOSITORY_PREFIX)) {
            return Promise.success(MavenResponse.badRequest("Invalid path"));
        }

        return MavenProtocolHandler.parseVersionPath(path.substring(REPOSITORY_PREFIX.length())).fold(() -> Promise.success(MavenResponse.badRequest("Cannot parse path: " + path)),
                                                                                                      this::archiveVersion);
    }

    /// FER: a refused or failed archive is reported, not dropped: it becomes a status the caller acts on;
    /// nothing is retried or compensated here.
    private Promise<MavenResponse> archiveVersion(Artifact artifact) {
        return store.archive(artifact)
                    .map(_ -> MavenResponse.json(renderArchiveJson(artifact)))
                    .recover(cause -> archiveFailureResponse(artifact, cause));
    }

    /// Nothing stored is 404; a version younger than the retention period is 409.
    private static MavenResponse archiveFailureResponse(Artifact artifact, Cause cause) {
        return switch (cause) {
            case ArtifactStore.ArtifactStoreError.VersionNotFound missing -> MavenResponse.notFound(missing.message());
            case ArtifactStore.ArtifactStoreError.RetentionNotElapsed young -> MavenResponse.conflict(young.message());
            default -> failureResponse("DELETE", artifact.asString(), cause);
        };
    }

    private byte[] renderArchiveJson(Artifact artifact) {
        var sb = new StringBuilder(96);

        sb.append('{');
        appendJsonField(sb, "status", "archived");
        sb.append(',');
        appendJsonField(sb, "coords", artifact.asString());
        sb.append('}');

        return sb.toString()
                 .getBytes(StandardCharsets.UTF_8);
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

        var metadataChecksum = metadataSidecar(path, ".sha256", "SHA-256").orElse(() -> metadataSidecar(path,
                                                                                                        ".sha512",
                                                                                                        "SHA-512"));

        if (metadataChecksum.isPresent()) {
            return metadataChecksum;
        }

        var parts = path.split("/");

        if (parts.length < 3) return Option.none();

        if (parts[parts.length - 1].equals(METADATA_FILE)) {
            return parseMetadataPath(parts).orElse(() -> parseVersionMetadataPath(path, parts));
        }

        return parseArtifactPath(parts);
    }

    /// Why a version-level `maven-metadata.xml` is not served or accepted (#1919): Maven writes it only for SNAPSHOT
    /// versions, which the built-in store does not hold (#1778), so there is nothing to serve and nothing to accept.
    private static String versionMetadataRefusal(ParsedPath.VersionMetadataPath refused) {
        return "Version-level maven-metadata.xml is not supported: it exists only for SNAPSHOT versions, which the "
             + "built-in artifact store does not accept (" + refused.path()
             + "); use the artifact-level "
             + "maven-metadata.xml, or publish a release version";
    }

    /// `<group>/<artifact>/<version>/maven-metadata.xml`: tried only when the artifact-level reading fails, i.e. the
    /// segment before the file is a version, not an artifact id (a version's dots are not valid in one).
    private Option<ParsedPath> parseVersionMetadataPath(String path, String[] parts) {
        if (parts.length < 4) return Option.none();

        var groupPath = String.join(".",
                                    List.of(parts).subList(0, parts.length - 3));

        return Result.all(GroupId.groupId(groupPath),
                          ArtifactId.artifactId(parts[parts.length - 3]),
                          Version.version(parts[parts.length - 2]))
                     .map((_, _, _) -> Option.<ParsedPath> some(new ParsedPath.VersionMetadataPath(path)))
                     .or(Option.none());
    }

    /// SHA-256 and SHA-512 of `maven-metadata.xml` (#1833). For every other file those suffixes name a
    /// stored sidecar file in its own right (#1778), so they are claimed here ONLY when the stripped path
    /// is the metadata file; the MD5 and SHA-1 suffixes are always checksums and are handled above.
    private Option<ParsedPath> metadataSidecar(String path, String suffix, String algorithm) {
        if (!path.endsWith("/" + METADATA_FILE + suffix)) {
            return Option.none();
        }

        return parsePath(path.substring(0,
                                        path.length() - suffix.length())).map(inner -> new ParsedPath.ChecksumPath(inner,
                                                                                                                   algorithm));
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

    /// The version segment must be written canonically (`Version#withQualifier`): the store keys a file by its exact
    /// name, while a coordinate resolves its primary file as `<artifactId>-<canonical version>.jar`. A non-canonical
    /// segment (`1.0.0.Final`, read as `1.0.0-Final`) would store a jar that its own coordinate never finds, so the
    /// path does not parse and answers 400.
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

        return Result.all(GroupId.groupId(groupPath.toString()),
                          ArtifactId.artifactId(artifactIdStr),
                          Version.version(versionStr))
                     .map((groupId, artifactId, version) -> toArtifactPath(groupId,
                                                                           artifactId,
                                                                           version,
                                                                           versionStr,
                                                                           fileName))
                     .or(Option.none());
    }

    private Option<ParsedPath> toArtifactPath(GroupId groupId,
                                              ArtifactId artifactId,
                                              Version version,
                                              String versionStr,
                                              String fileName) {
        var artifact = new Artifact(groupId, artifactId, version);

        return version.withQualifier()
                      .equals(versionStr)
               ? Option.some(new ParsedPath.ArtifactPath(artifact, fileName))
               : Option.none();
    }

    /// `<latest>` is the highest version by [VersionOrder], `<release>` the highest non-SNAPSHOT
    /// one (falling back to `<latest>` when every version is a snapshot); `<versions>` lists them
    /// ascending. `<lastUpdated>` is the newest deploy time of the listed versions, from stored state and not
    /// the wall clock: a clock value made every render differ, so a checksum fetched after the metadata (#1833)
    /// could never match it. The output is a pure function of the stored versions. The versions list is stored in deploy order, so "last deployed" used to be
    /// reported as latest (#281).
    private String generateMavenMetadata(GroupId groupId,
                                         ArtifactId artifactId,
                                         List<Version> unordered,
                                         long lastDeployedAtMillis) {
        var versions = unordered.stream().sorted(VersionOrder.INSTANCE).toList();
        var latest = versions.getLast();
        var release = versions.stream().filter(v -> !VersionOrder.isSnapshot(v)).reduce((a, b) -> b).orElse(latest);
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
        if (lastDeployedAtMillis > 0) {
            sb.append("    <lastUpdated>")
              .append(LAST_UPDATED_FORMAT.format(Instant.ofEpochMilli(lastDeployedAtMillis).atOffset(ZoneOffset.UTC)))
              .append("</lastUpdated>\n");
        }

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

    /// Content type is the one thing read from the file name's tail; identity never is.
    private String contentTypeFor(String fileName) {
        return switch (fileName.substring(fileName.lastIndexOf('.') + 1)) {
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
