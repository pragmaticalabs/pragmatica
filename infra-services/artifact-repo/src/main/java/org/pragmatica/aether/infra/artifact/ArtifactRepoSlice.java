package org.pragmatica.aether.infra.artifact;

import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.SliceRoute;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;

import java.util.List;

/**
 * Artifact Repository Slice - provides Maven-compatible artifact storage.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /repository/**} - Resolve artifacts</li>
 *   <li>{@code PUT /repository/**} - Deploy artifacts</li>
 * </ul>
 */
public record ArtifactRepoSlice(
 MavenProtocolHandler mavenHandler) implements Slice {
    public static ArtifactRepoSlice artifactRepoSlice(ArtifactStore store) {
        return new ArtifactRepoSlice(MavenProtocolHandler.mavenProtocolHandler(store));
    }

    @Override
    public List<SliceMethod< ? , ? >> methods() {
        return List.of(
        new SliceMethod<>(
        MethodName.methodName("get")
                  .unwrap(),
        this::handleGet,
        new TypeToken<MavenProtocolHandler.MavenResponse>() {},
        new TypeToken<RepositoryRequest>() {}),
        new SliceMethod<>(
        MethodName.methodName("put")
                  .unwrap(),
        this::handlePut,
        new TypeToken<MavenProtocolHandler.MavenResponse>() {},
        new TypeToken<RepositoryRequest>() {}));
    }

    @Override
    public List<SliceRoute> routes() {
        return List.of(
        SliceRoute.get("/repository/{path}", "get")
                  .withPathVar("path")
                  .build(),
        SliceRoute.put("/repository/{path}", "put")
                  .withPathVar("path")
                  .withBody()
                  .build());
    }

    private Promise<MavenProtocolHandler.MavenResponse> handleGet(RepositoryRequest request) {
        return mavenHandler.handleGet("/repository/" + request.path());
    }

    private Promise<MavenProtocolHandler.MavenResponse> handlePut(RepositoryRequest request) {
        return mavenHandler.handlePut("/repository/" + request.path(), request.content());
    }

    /**
     * Request for repository operations.
     */
    public record RepositoryRequest(
    String path,
    byte[] content) {}
}
