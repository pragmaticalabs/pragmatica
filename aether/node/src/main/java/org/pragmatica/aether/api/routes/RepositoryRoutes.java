package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.aether.api.ManagementApiResponses.ArtifactInfoResponse;
import static org.pragmatica.http.routing.PathParameter.aString;


/// Routes for artifact repository: artifact info endpoint.
///
///
/// Maven protocol routes (GET/PUT/POST for binary artifacts) remain in
/// {@link MavenProtocolRoutes} as they require dynamic content types.
public final class RepositoryRoutes implements RouteSource {
    private final Supplier<ManageableNode> nodeSupplier;

    private RepositoryRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static RepositoryRoutes repositoryRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new RepositoryRoutes(nodeSupplier);
    }

    @Override public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<ArtifactInfoResponse>route(ManagementRoute.ARTIFACT_INFO)
                                         .withPath(aString(),
                                                   aString(),
                                                   aString())
                                         .to(this::handleRepositoryInfo)
                                         .asJson());
    }

    private Promise<ArtifactInfoResponse> handleRepositoryInfo(String groupPath,
                                                               String artifactIdStr,
                                                               String versionStr) {
        return parseArtifact(groupPath, artifactIdStr, versionStr).async().flatMap(this::fetchArtifactInfo);
    }

    private Result<Artifact> parseArtifact(String groupPath, String artifactIdStr, String versionStr) {
        var normalizedGroup = groupPath.replace('/', '.');
        return Result.all(GroupId.groupId(normalizedGroup),
                          ArtifactId.artifactId(artifactIdStr),
                          Version.version(versionStr))
        .map(Artifact::new);
    }

    private Promise<ArtifactInfoResponse> fetchArtifactInfo(Artifact artifact) {
        var node = nodeSupplier.get();
        return node.artifactStore().resolveWithMetadata(artifact)
                                 .map(resolved -> buildResponse(node, artifact, resolved));
    }

    private ArtifactInfoResponse buildResponse(ManageableNode node,
                                               Artifact artifact,
                                               ArtifactStore.ResolvedArtifact resolved) {
        var meta = resolved.metadata();
        var isDeployed = node.artifactMetricsCollector().isDeployed(artifact);
        return new ArtifactInfoResponse(artifact.asString(),
                                        meta.size(),
                                        meta.chunkCount(),
                                        meta.md5(),
                                        meta.sha1(),
                                        meta.deployedAt(),
                                        isDeployed);
    }
}
