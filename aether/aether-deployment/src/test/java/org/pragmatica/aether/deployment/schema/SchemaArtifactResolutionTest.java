// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.schema;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.ResolutionStubs;
import org.pragmatica.aether.deployment.ResolutionStubs.NotHere;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1927: `SchemaOrchestratorService.resolveArtifactBytes` tries the repository with the `blueprint` classifier, then
/// without, then the artifact store. Each step falls through only on a GENUINE not-found; anything else is reported as
/// itself.
class SchemaArtifactResolutionTest {
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:orders-app:1.0.0").unwrap();
    private static final byte[] FROM_STORE = {1, 2, 3};

    @Test
    void aTransientFailureOfTheFirstLocate_isReportedAsItself() throws Exception {
        var store = ResolutionStubs.artifactStoreAnswering(new NotHere("store").promise());
        Repository repository = new Repository() {
            @Override
            public Promise<org.pragmatica.aether.slice.repository.Location> locate(Artifact artifact) {
                return new NotHere("plain").promise();
            }

            @Override
            public Promise<org.pragmatica.aether.slice.repository.Location> locate(Artifact artifact, String classifier) {
                return ResolutionStubs.unreachable().promise();
            }
        };

        resolve(repository, store).await()
                                  .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("must fail"))
                                  .onFailure(cause -> assertThat(cause.message()).contains("remote repository unreachable"));
    }

    @Test
    void aTransientFailureOfTheSecondLocate_isReportedAsItself() throws Exception {
        var store = ResolutionStubs.artifactStoreAnswering(new NotHere("store").promise());
        Repository repository = new Repository() {
            @Override
            public Promise<org.pragmatica.aether.slice.repository.Location> locate(Artifact artifact) {
                return ResolutionStubs.unreachable().promise();
            }

            @Override
            public Promise<org.pragmatica.aether.slice.repository.Location> locate(Artifact artifact, String classifier) {
                return new NotHere("classified").promise();
            }
        };

        resolve(repository, store).await()
                                  .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("must fail"))
                                  .onFailure(cause -> assertThat(cause.message()).contains("remote repository unreachable"));
    }

    @Test
    void genuineNotFoundEverywhereInTheRepository_fallsThroughToTheStore() throws Exception {
        var store = ResolutionStubs.artifactStoreAnswering(Promise.success(FROM_STORE));
        var locates = new AtomicInteger();
        Repository repository = new Repository() {
            @Override
            public Promise<org.pragmatica.aether.slice.repository.Location> locate(Artifact artifact) {
                locates.incrementAndGet();

                return new NotHere("plain").promise();
            }

            @Override
            public Promise<org.pragmatica.aether.slice.repository.Location> locate(Artifact artifact, String classifier) {
                locates.incrementAndGet();

                return new NotHere("classified").promise();
            }
        };

        assertThat(resolve(repository, store).await().or(new byte[0])).isEqualTo(FROM_STORE);
        assertThat(locates.get()).as("both locates were tried before the store").isEqualTo(2);
    }

    @Test
    void aLocatedArtifactWhoseBytesCannotBeRead_isReportedAsItself_notReplacedByTheStoresCopy() throws Exception {
        var store = ResolutionStubs.artifactStoreAnswering(Promise.success(FROM_STORE));
        Repository repository = artifact -> Promise.success(ResolutionStubs.unreadableLocation(artifact));

        resolve(repository, store).await().onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("a read failure must not fall through"));
    }

    @SuppressWarnings("unchecked")
    private static Promise<byte[]> resolve(Repository repository, org.pragmatica.aether.resource.artifact.ArtifactStore store) throws Exception {
        var service = new SchemaOrchestratorServiceInstance(null, null, store, repository, null, null, null, (org.pragmatica.messaging.MessageRouter) null);
        Method resolve = SchemaOrchestratorServiceInstance.class.getDeclaredMethod("resolveArtifactBytes", Artifact.class);

        resolve.setAccessible(true);

        return (Promise<byte[]>) resolve.invoke(service, ARTIFACT);
    }
}
