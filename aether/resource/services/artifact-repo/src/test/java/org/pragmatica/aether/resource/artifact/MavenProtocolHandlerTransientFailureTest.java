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
