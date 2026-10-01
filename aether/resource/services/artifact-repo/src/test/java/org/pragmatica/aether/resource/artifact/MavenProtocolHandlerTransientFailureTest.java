// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.artifact;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.dht.DHTError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// A DHT read that FAILS during membership churn (a replacement's "absent" must not vote) is not an
/// answer about the artifact. PUT's existence check is also the write-once guard (#1778): `deploy`
/// overwrites unconditionally, so deploying past a failed check could replace stored content. A
/// transient failure answers 503 (retry) and `deploy` is NOT called; a non-transient one answers 500.
class MavenProtocolHandlerTransientFailureTest {
    private static final String PATH = "/repository/org/example/test/1.0.0/test-1.0.0.jar";
    private static final byte[] CONTENT = "payload".getBytes(StandardCharsets.UTF_8);

    @Test
    void handlePut_answers503WithoutDeploying_whenExistenceCheckFailsTransiently() {
        var deployCalls = new AtomicInteger();
        var store = store(DHTError.quorumNotReached(2, 1).promise(), deploySucceeds(), notFound(), deployCalls);

        MavenProtocolHandler.mavenProtocolHandler(store)
                            .handlePut(PATH, CONTENT)
                            .await()
                            .onFailureRun(Assertions::fail)
                            .onSuccess(response -> assertThat(response.statusCode()).isEqualTo(503));
        assertThat(deployCalls).as("a failed existence check must not overwrite stored content").hasValue(0);
    }

    @Test
    void handlePut_answers500WithoutDeploying_whenExistenceCheckFailsNonTransiently() {
        var deployCalls = new AtomicInteger();
        var store = store(new ArtifactStore.ArtifactStoreError.CorruptedArtifact(file()).promise(),
                          deploySucceeds(),
                          notFound(),
                          deployCalls);

        MavenProtocolHandler.mavenProtocolHandler(store)
                            .handlePut(PATH, CONTENT)
                            .await()
                            .onFailureRun(Assertions::fail)
                            .onSuccess(response -> assertThat(response.statusCode()).isEqualTo(500));
        assertThat(deployCalls).hasValue(0);
    }

    @Test
    void handlePut_deploys_whenExistenceCheckSaysAbsent() {
        var deployCalls = new AtomicInteger();
        var store = store(Promise.success(Option.none()), deploySucceeds(), notFound(), deployCalls);

        MavenProtocolHandler.mavenProtocolHandler(store)
                            .handlePut(PATH, CONTENT)
                            .await()
                            .onFailureRun(Assertions::fail)
                            .onSuccess(response -> assertUploaded(response));
        assertThat(deployCalls).hasValue(1);
    }

    @Test
    void handlePut_answers503_whenDeployFailsTransiently() {
        var store = store(Promise.success(Option.none()), DHTError.OPERATION_TIMEOUT.promise(), notFound());

        MavenProtocolHandler.mavenProtocolHandler(store)
                            .handlePut(PATH, CONTENT)
                            .await()
                            .onFailureRun(Assertions::fail)
                            .onSuccess(response -> assertThat(response.statusCode()).isEqualTo(503));
    }

    @Test
    void handlePut_answers500_whenDeployFailsWithNonTransientCause() {
        var deployFailed = new ArtifactStore.ArtifactStoreError.CorruptedArtifact(file()).<ArtifactStore.DeployResult>promise();
        var store = store(Promise.success(Option.none()), deployFailed, notFound());

        MavenProtocolHandler.mavenProtocolHandler(store)
                            .handlePut(PATH, CONTENT)
                            .await()
                            .onFailureRun(Assertions::fail)
                            .onSuccess(response -> assertThat(response.statusCode()).isEqualTo(500));
    }

    @Test
    void handleGet_answers503_whenResolveFailsTransiently() {
        var store = store(Promise.success(Option.none()), deploySucceeds(), DHTError.quorumNotReached(2, 1).promise());

        MavenProtocolHandler.mavenProtocolHandler(store)
                            .handleGet(PATH)
                            .await()
                            .onFailureRun(Assertions::fail)
                            .onSuccess(response -> assertThat(response.statusCode()).isEqualTo(503));
    }

    @Test
    void handleGet_answers404_whenResolveReportsNotFound() {
        var store = store(Promise.success(Option.none()), deploySucceeds(), notFound());

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
        return ArtifactFile.artifactFile(org.pragmatica.aether.artifact.Artifact.artifact("org.example:test:1.0.0")
                                                                                .unwrap(),
                                         "",
                                         "jar");
    }

    private static Promise<ArtifactStore.DeployResult> deploySucceeds() {
        return Promise.success(new ArtifactStore.DeployResult(file().artifact(), CONTENT.length, "md5", "sha1"));
    }

    private static Promise<byte[]> notFound() {
        return new ArtifactStore.ArtifactStoreError.NotFound(file(), "test-key", 0L).promise();
    }

    private static ArtifactStore store(Promise<Option<ArtifactStore.ArtifactMetadata>> metadata,
                                       Promise<ArtifactStore.DeployResult> deploy,
                                       Promise<byte[]> resolve) {
        return store(metadata, deploy, resolve, new AtomicInteger());
    }

    private static ArtifactStore store(Promise<Option<ArtifactStore.ArtifactMetadata>> metadata,
                                       Promise<ArtifactStore.DeployResult> deploy,
                                       Promise<byte[]> resolve,
                                       AtomicInteger deployCalls) {
        return new ArtifactStore() {
            @Override
            public Promise<DeployResult> deploy(ArtifactFile file, byte[] content) {
                deployCalls.incrementAndGet();

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
                return metadata;
            }

            @Override
            public Promise<List<Version>> versions(GroupId groupId, ArtifactId artifactId) {
                return Promise.success(List.of());
            }

            @Override
            public Promise<Unit> delete(ArtifactFile file) {
                return Promise.success(Unit.unit());
            }

            @Override
            public Metrics metrics() {
                return Metrics.empty();
            }
        };
    }
}
