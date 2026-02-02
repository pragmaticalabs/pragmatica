package org.pragmatica.aether.infra.artifact;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

class MavenProtocolHandlerTest {

    @Test
    void handleGet_returns_notFound_for_missing_artifact() {
        var store = testStore();
        var handler = MavenProtocolHandler.mavenProtocolHandler(store);

        handler.handleGet("/repository/org/example/test/1.0.0/test-1.0.0.jar")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(response -> {
                   assertThat(response.statusCode()).isEqualTo(404);
               });
    }

    @Test
    void handleGet_returns_badRequest_for_invalid_path() {
        var store = testStore();
        var handler = MavenProtocolHandler.mavenProtocolHandler(store);

        handler.handleGet("/invalid/path")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(response -> {
                   assertThat(response.statusCode()).isEqualTo(404);
               });
    }

    @Test
    void handleGet_returns_badRequest_for_unparseable_path() {
        var store = testStore();
        var handler = MavenProtocolHandler.mavenProtocolHandler(store);

        handler.handleGet("/repository/invalid")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(response -> {
                   assertThat(response.statusCode()).isEqualTo(400);
               });
    }

    @Test
    void handlePut_accepts_jar_file() {
        var store = testStore();
        var handler = MavenProtocolHandler.mavenProtocolHandler(store);

        handler.handlePut("/repository/org/example/test/1.0.0/test-1.0.0.jar", new byte[]{1, 2, 3})
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(response -> {
                   assertThat(response.statusCode()).isEqualTo(201);
               });
    }

    @Test
    void handlePut_accepts_pom_file() {
        var store = testStore();
        var handler = MavenProtocolHandler.mavenProtocolHandler(store);

        handler.handlePut("/repository/org/example/test/1.0.0/test-1.0.0.pom", "<project/>".getBytes())
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(response -> {
                   assertThat(response.statusCode()).isEqualTo(201);
               });
    }

    @Test
    void handlePut_accepts_checksum_file() {
        var store = testStore();
        var handler = MavenProtocolHandler.mavenProtocolHandler(store);

        handler.handlePut("/repository/org/example/test/1.0.0/test-1.0.0.jar.sha1", "abc123".getBytes())
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(response -> {
                   assertThat(response.statusCode()).isEqualTo(201);
               });
    }

    private ArtifactStore testStore() {
        return new ArtifactStore() {
            @Override
            public Promise<DeployResult> deploy(Artifact artifact, byte[] content) {
                return Promise.success(new DeployResult(artifact, content.length, "md5", "sha1"));
            }

            @Override
            public Promise<byte[]> resolve(Artifact artifact) {
                return new ArtifactStoreError.NotFound(artifact).promise();
            }

            @Override
            public Promise<ResolvedArtifact> resolveWithMetadata(Artifact artifact) {
                return new ArtifactStoreError.NotFound(artifact).promise();
            }

            @Override
            public Promise<Boolean> exists(Artifact artifact) {
                return Promise.success(false);
            }

            @Override
            public Promise<java.util.List<org.pragmatica.aether.artifact.Version>> versions(
                    org.pragmatica.aether.artifact.GroupId groupId,
                    org.pragmatica.aether.artifact.ArtifactId artifactId) {
                return Promise.success(java.util.List.of());
            }

            @Override
            public Promise<Unit> delete(Artifact artifact) {
                return Promise.success(Unit.unit());
            }

            @Override
            public Metrics metrics() {
                return Metrics.empty();
            }
        };
    }
}
