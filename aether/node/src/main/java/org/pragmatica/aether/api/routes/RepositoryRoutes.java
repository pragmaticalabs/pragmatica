package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.infra.artifact.ArtifactStore;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.aether.api.ManagementApiResponses.ArtifactInfoResponse;

/**
 * Routes for artifact repository: artifact info endpoint.
 *
 * <p>Maven protocol routes (GET/PUT/POST for binary artifacts) remain in
 * {@link MavenProtocolRoutes} as they require dynamic content types.
 */
public final class RepositoryRoutes implements RouteSource {
    private static final Cause INVALID_PATH = Causes.cause("Invalid artifact path. Expected: /repository/info/{groupPath}/{artifactId}/{version}");

    private final Supplier<AetherNode> nodeSupplier;

    private RepositoryRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static RepositoryRoutes repositoryRoutes(Supplier<AetherNode> nodeSupplier) {
        return new RepositoryRoutes(nodeSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(// Artifact info endpoint - captures everything after /repository/info/
        Route.<ArtifactInfoResponse> get("/repository/info")
             .withoutParameters()
             .to(ctx -> handleRepositoryInfo(ctx.pathParams()))
             .asJson());
    }

    private Promise<ArtifactInfoResponse> handleRepositoryInfo(List<String> pathSegments) {
        // Path segments: [groupPart1, groupPart2, ..., artifactId, version]
        // Need at least 3 parts: one group segment + artifactId + version
        if (pathSegments.size() < 3) {
            return INVALID_PATH.promise();
        }
        return parseArtifact(pathSegments).async()
                            .flatMap(this::fetchArtifactInfo);
    }

    private Result<Artifact> parseArtifact(List<String> parts) {
        var versionStr = parts.getLast();
        var artifactIdStr = parts.get(parts.size() - 2);
        var groupPath = String.join(".",
                                    parts.subList(0, parts.size() - 2));
        return Result.all(GroupId.groupId(groupPath),
                          ArtifactId.artifactId(artifactIdStr),
                          Version.version(versionStr))
                     .map(Artifact::new);
    }

    private Promise<ArtifactInfoResponse> fetchArtifactInfo(Artifact artifact) {
        var node = nodeSupplier.get();
        return node.artifactStore()
                   .resolveWithMetadata(artifact)
                   .map(resolved -> buildResponse(node, artifact, resolved));
    }

    private ArtifactInfoResponse buildResponse(AetherNode node,
                                               Artifact artifact,
                                               ArtifactStore.ResolvedArtifact resolved) {
        var meta = resolved.metadata();
        var isDeployed = node.artifactMetricsCollector()
                             .isDeployed(artifact);
        return new ArtifactInfoResponse(artifact.asString(),
                                        meta.size(),
                                        meta.chunkCount(),
                                        meta.md5(),
                                        meta.sha1(),
                                        meta.deployedAt(),
                                        isDeployed);
    }
}
