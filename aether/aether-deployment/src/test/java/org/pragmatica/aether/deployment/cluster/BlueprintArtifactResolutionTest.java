// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.lang.reflect.Method;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.ResolutionStubs;
import org.pragmatica.aether.deployment.ResolutionStubs.NotHere;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1927: `BlueprintService.resolveArtifactBytes` tries the repository, then the artifact store. Only a GENUINE not-found
/// may fall through to the store; a repository that could not answer, or a located artifact whose bytes cannot be read,
/// is reported as itself. Falling through on any failure made the caller see the store's "not found" for what was an
/// outage, or even succeed from the store with an artifact the repository holds a different copy of.
class BlueprintArtifactResolutionTest {
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:orders-app:1.0.0").unwrap();
    private static final byte[] FROM_STORE = {1, 2, 3};

    @Test
    void aTransientRepositoryFailure_isReportedAsItself_notAsTheStoresNotFound() throws Exception {
        var store = ResolutionStubs.artifactStoreAnswering(new NotHere("store").promise());
        Repository repository = _ -> ResolutionStubs.unreachable().promise();

        resolve(repository, store).await()
                                  .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("must fail"))
                                  .onFailure(cause -> assertThat(cause.message()).contains("remote repository unreachable"));
    }

    @Test
    void aGenuineNotFound_fallsThroughToTheStore() throws Exception {
        var store = ResolutionStubs.artifactStoreAnswering(Promise.success(FROM_STORE));
        Repository repository = _ -> new NotHere("repository").promise();

        assertThat(resolve(repository, store).await().or(new byte[0])).isEqualTo(FROM_STORE);
    }

    @Test
    void bothNotFound_reportsTheStoresNotFound() throws Exception {
        var store = ResolutionStubs.artifactStoreAnswering(new NotHere("store").promise());
        Repository repository = _ -> new NotHere("repository").promise();

        resolve(repository, store).await().onFailure(cause -> assertThat(cause.message()).contains("not here: store"));
    }

    @Test
    void aLocatedArtifactWhoseBytesCannotBeRead_isReportedAsItself_notReplacedByTheStoresCopy() throws Exception {
        var store = ResolutionStubs.artifactStoreAnswering(Promise.success(FROM_STORE));
        Repository repository = artifact -> Promise.success(ResolutionStubs.unreadableLocation(artifact));

        resolve(repository, store).await().onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("a read failure must not fall through to the store"));
    }

    @SuppressWarnings("unchecked")
    private static Promise<byte[]> resolve(Repository repository, org.pragmatica.aether.resource.artifact.ArtifactStore store) throws Exception {
        var service = new BlueprintServiceInstance(null, null, repository, Option.some(store), Option.empty(), OperatorWarningSink.logOnly());
        Method resolve = BlueprintServiceInstance.class.getDeclaredMethod("resolveArtifactBytes", Artifact.class, String.class);

        resolve.setAccessible(true);

        return (Promise<byte[]>) resolve.invoke(service, ARTIFACT, "blueprint");
    }
}
