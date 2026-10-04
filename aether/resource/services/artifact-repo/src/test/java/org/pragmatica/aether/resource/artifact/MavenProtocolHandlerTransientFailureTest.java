// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.artifact;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.dht.DHTError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// A DHT failure during membership churn is not an answer about the artifact: the handler maps a TRANSIENT
/// store failure to 503 + retry and any other to 500, never to "absent". The write-once guard itself (a failed
/// existence check never deploys, #1795) lives in `ArtifactStore.deploy` and is pinned by
/// `ArtifactStoreWriteOnceTest#deploy_failsWithoutWriting_whenTheExistenceCheckFailsTransiently`.
class MavenProtocolHandlerTransientFailureTest {
    private static final String PATH = "/repository/org/example/test/1.0.0/test-1.0.0.jar";
    private static final byte[] CONTENT = "payload".getBytes(StandardCharsets.UTF_8);

    @Test
    void handlePut_answers200Uploaded_whenDeploySucceeds() {
        var store = store(deploySucceeds(), notFound());

        MavenProtocolHandler.mavenProtocolHandler(store)
                            .handlePut(PATH, CONTENT)
                            .await()
                            .onFailureRun(Assertions::fail)
                            .onSuccess(response -> assertUploaded(response));
    }

    @Test
    void handlePut_answers503_whenDeployFailsTransiently() {
        var store = store(DHTError.OPERATION_TIMEOUT.promise(), notFound());

        MavenProtocolHandler.mavenProtocolHandler(store)
                            .handlePut(PATH, CONTENT)
                            .await()
                            .onFailureRun(Assertions::fail)
                            .onSuccess(response -> assertThat(response.statusCode()).isEqualTo(503));
    }

    @Test
    void handlePut_answers500_whenDeployFailsWithNonTransientCause() {
        var deployFailed = new ArtifactStore.ArtifactStoreError.CorruptedArtifact(file()).<ArtifactStore.DeployResult>promise();
        var store = store(deployFailed, notFound());

        MavenProtocolHandler.mavenProtocolHandler(store)
                            .handlePut(PATH, CONTENT)
                            .await()
                            .onFailureRun(Assertions::fail)
                            .onSuccess(response -> assertThat(response.statusCode()).isEqualTo(500));
    }

    @Test
    void handleGet_answers503_whenResolveFailsTransiently() {
        var store = store(deploySucceeds(), DHTError.quorumNotReached(2, 1).promise());

        MavenProtocolHandler.mavenProtocolHandler(store)
                            .handleGet(PATH)
                            .await()
                            .onFailureRun(Assertions::fail)
                            .onSuccess(response -> assertThat(response.statusCode()).isEqualTo(503));
    }

    @Test
    void handleGet_answers404_whenResolveReportsNotFound() {
        var store = store(deploySucceeds(), notFound());

        MavenProtocolHandler.mavenProtocolHandler(store)
                            .handleGet(PATH)
                            .await()
                            .onFailureRun(Assertions::fail)
                            .onSuccess(response -> assertThat(response.statusCode()).isEqualTo(404));
    }

    private static final String METADATA = "/repository/org/example/test/maven-metadata.xml";

    /// `<lastUpdated>` is derived from the stored metadata of EVERY listed version, so a transient read failure on
    /// any one of them must not become a 500 for the listing or its checksums: it answers 503 + retry.
    @Test
    void handleGetMetadata_andItsChecksums_answer503_whenOneVersionsMetadataReadFailsTransiently() {
        var handler = MavenProtocolHandler.mavenProtocolHandler(versionsStore(version -> version.equals("2.0.0")
                                                                                       ? DHTError.OPERATION_TIMEOUT.promise()
                                                                                       : Promise.success(Option.none())));

        for (var suffix : List.of("", ".md5", ".sha1", ".sha256", ".sha512")) {
            handler.handleGet(METADATA + suffix)
                   .await()
                   .onFailureRun(() -> Assertions.fail("maven-metadata.xml" + suffix + " must answer a status, not fail the promise"))
                   .onSuccess(response -> assertThat(response.statusCode()).as("maven-metadata.xml%s", suffix).isEqualTo(503));
        }
    }

    @Test
    void handleGetMetadata_answers500_whenOneVersionsMetadataReadFailsWithANonTransientCause() {
        var handler = MavenProtocolHandler.mavenProtocolHandler(versionsStore(_ -> new ArtifactStore.ArtifactStoreError.CorruptedArtifact(file()).promise()));

        handler.handleGet(METADATA)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(response -> assertThat(response.statusCode()).isEqualTo(500));
    }

    /// The control: versions whose per-file metadata is simply absent (legacy or never written) are not an error,
    /// and neither is a version listing with nothing stored.
    @Test
    void handleGetMetadata_answers200WithoutLastUpdated_whenNoVersionHasStoredMetadata() {
        var handler = MavenProtocolHandler.mavenProtocolHandler(versionsStore(_ -> Promise.success(Option.none())));

        handler.handleGet(METADATA)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(response -> {
                   assertThat(response.statusCode()).isEqualTo(200);
                   assertThat(new String(response.content(), StandardCharsets.UTF_8)).contains("<version>2.0.0</version>")
                                                                                     .doesNotContain("lastUpdated");
               });
    }

    private static void assertUploaded(MavenProtocolHandler.MavenResponse response) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.content(), StandardCharsets.UTF_8)).contains("\"status\":\"uploaded\"");
    }

    private static ArtifactFile file() {
        return ArtifactFile.artifactFile(Artifact.artifact("org.example:test:1.0.0").unwrap(), "", "jar");
    }

    private static Promise<ArtifactStore.DeployResult> deploySucceeds() {
        return Promise.success(new ArtifactStore.DeployResult(file().artifact(), CONTENT.length, "md5", "sha1"));
    }

    private static Promise<byte[]> notFound() {
        return new ArtifactStore.ArtifactStoreError.NotFound(file(), "test-key", 0L).promise();
    }

    /// A store listing 1.0.0 and 2.0.0 whose per-version metadata read behaves as the test says, keyed by version.
    private static ArtifactStore versionsStore(java.util.function.Function<String, Promise<Option<ArtifactStore.ArtifactMetadata>>> metadataOf) {
        var base = store(deploySucceeds(), notFound());

        return new ArtifactStore() {
            @Override
            public Promise<DeployResult> deploy(ArtifactFile file, byte[] content) {
                return base.deploy(file, content);
            }

            @Override
            public Promise<byte[]> resolve(ArtifactFile file) {
                return base.resolve(file);
            }

            @Override
            public Promise<ResolvedArtifact> resolveWithMetadata(ArtifactFile file) {
                return base.resolveWithMetadata(file);
            }

            @Override
            public Promise<Boolean> exists(ArtifactFile file) {
                return base.exists(file);
            }

            @Override
            public Promise<Option<ArtifactMetadata>> metadata(ArtifactFile file) {
                return metadataOf.apply(file.artifact().version().withQualifier());
            }

            @Override
            public Promise<List<Version>> versions(GroupId groupId, ArtifactId artifactId) {
                return Promise.success(List.of(Version.version("1.0.0").unwrap(), Version.version("2.0.0").unwrap()));
            }

            @Override
            public Promise<Unit> archive(Artifact artifact) {
                return base.archive(artifact);
            }

            @Override
            public Metrics metrics() {
                return base.metrics();
            }
        };
    }

    private static ArtifactStore store(Promise<ArtifactStore.DeployResult> deploy, Promise<byte[]> resolve) {
        return new ArtifactStore() {
            @Override
            public Promise<DeployResult> deploy(ArtifactFile file, byte[] content) {
                return deploy;
            }

            @Override
            public Promise<byte[]> resolve(ArtifactFile file) {
                return resolve;
            }

            @Override
            public Promise<ResolvedArtifact> resolveWithMetadata(ArtifactFile file) {
                return DHTError.OPERATION_TIMEOUT.promise();
            }

            @Override
            public Promise<Boolean> exists(ArtifactFile file) {
                return Promise.success(false);
            }

            @Override
            public Promise<Option<ArtifactMetadata>> metadata(ArtifactFile file) {
                return Promise.success(Option.none());
            }

            @Override
            public Promise<List<Version>> versions(GroupId groupId, ArtifactId artifactId) {
                return Promise.success(List.of());
            }

            @Override
            public Promise<Unit> archive(Artifact artifact) {
                return Promise.success(Unit.unit());
            }

            @Override
            public Metrics metrics() {
                return Metrics.empty();
            }
        };
    }
}
