package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.infra.artifact.ArtifactStore;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Result;

import java.util.function.Supplier;

/**
 * Routes for artifact repository: Maven protocol, artifact info.
 */
public final class RepositoryRoutes implements RouteHandler {
    private final Supplier<AetherNode> nodeSupplier;

    private RepositoryRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static RepositoryRoutes repositoryRoutes(Supplier<AetherNode> nodeSupplier) {
        return new RepositoryRoutes(nodeSupplier);
    }

    @Override
    public boolean handle(RequestContext ctx, ResponseWriter response) {
        var path = ctx.path();
        var method = ctx.method();
        if (!path.startsWith("/repository/")) {
            return false;
        }
        if (method == HttpMethod.GET) {
            if (path.startsWith("/repository/info/")) {
                handleRepositoryInfo(response, path);
            } else {
                handleRepositoryGet(response, path);
            }
            return true;
        }
        if (method == HttpMethod.POST || method == HttpMethod.PUT) {
            handleRepositoryPut(response, path, ctx.body());
            return true;
        }
        return false;
    }

    private void handleRepositoryGet(ResponseWriter response, String uri) {
        var node = nodeSupplier.get();
        node.mavenProtocolHandler()
            .handleGet(uri)
            .onSuccess(r -> sendProtocolResponse(response, r))
            .onFailure(cause -> response.internalError(cause));
    }

    private void handleRepositoryPut(ResponseWriter response, String uri, byte[] content) {
        var node = nodeSupplier.get();
        node.mavenProtocolHandler()
            .handlePut(uri, content)
            .onSuccess(r -> sendProtocolResponse(response, r))
            .onFailure(cause -> response.internalError(cause));
    }

    private void sendProtocolResponse(ResponseWriter response,
                                      org.pragmatica.aether.infra.artifact.MavenProtocolHandler.MavenResponse mavenResponse) {
        var status = HttpStatus.values() [0];
        // Default
        for (var s : HttpStatus.values()) {
            if (s.code() == mavenResponse.statusCode()) {
                status = s;
                break;
            }
        }
        response.write(status,
                       mavenResponse.content(),
                       org.pragmatica.http.ContentType.contentType(mavenResponse.contentType(),
                                                                   org.pragmatica.http.ContentCategory.BINARY));
    }

    private void handleRepositoryInfo(ResponseWriter response, String uri) {
        var node = nodeSupplier.get();
        var infoPath = uri.substring("/repository/info/".length());
        var parts = infoPath.split("/");
        if (parts.length < 3) {
            response.badRequest("Invalid artifact path. Expected: /repository/info/{groupPath}/{artifactId}/{version}");
            return;
        }
        var versionStr = parts[parts.length - 1];
        var artifactIdStr = parts[parts.length - 2];
        var groupPath = new StringBuilder();
        for (int i = 0; i < parts.length - 2; i++) {
            if (i > 0) groupPath.append(".");
            groupPath.append(parts[i]);
        }
        Result.all(GroupId.groupId(groupPath.toString()),
                   ArtifactId.artifactId(artifactIdStr),
                   Version.version(versionStr))
              .map(Artifact::new)
              .async()
              .flatMap(artifact -> node.artifactStore()
                                       .resolveWithMetadata(artifact)
                                       .map(resolved -> buildArtifactInfoResponse(node, artifact, resolved)))
              .onSuccess(response::ok)
              .onFailure(cause -> {
                             if (cause.message()
                                      .contains("not found")) {
                                 response.error(HttpStatus.NOT_FOUND,
                                                cause.message());
                             } else {
                                 response.internalError(cause);
                             }
                         });
    }

    private String buildArtifactInfoResponse(AetherNode node,
                                             Artifact artifact,
                                             ArtifactStore.ResolvedArtifact resolved) {
        var meta = resolved.metadata();
        var isDeployed = node.artifactMetricsCollector()
                             .isDeployed(artifact);
        var sb = new StringBuilder();
        sb.append("{");
        sb.append("\"artifact\":\"")
          .append(artifact.asString())
          .append("\",");
        sb.append("\"size\":")
          .append(meta.size())
          .append(",");
        sb.append("\"chunkCount\":")
          .append(meta.chunkCount())
          .append(",");
        sb.append("\"md5\":\"")
          .append(meta.md5())
          .append("\",");
        sb.append("\"sha1\":\"")
          .append(meta.sha1())
          .append("\",");
        sb.append("\"deployedAt\":")
          .append(meta.deployedAt())
          .append(",");
        sb.append("\"isDeployed\":")
          .append(isDeployed);
        sb.append("}");
        return sb.toString();
    }
}
