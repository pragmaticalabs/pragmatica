// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge.api;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.ember.EmberCluster.EventLogEntry;
import org.pragmatica.aether.forge.api.DeploymentRoutes.RepositoryPutRejected;
import org.pragmatica.aether.forge.api.DeploymentRoutes.RepositoryPutRequest;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler.MavenResponse;

import static org.assertj.core.api.Assertions.assertThat;

/// #1778: the built-in store REFUSES a SNAPSHOT or a conflicting re-put with a status, not a failed promise.
/// Forge's `PUT /api/repository` used to map every answer to "Deployed", so a refused push read as success.
class DeploymentRoutesRepositoryPutTest {
    private static final String PATH = "/repository/org/example/lib/1.0.0-SNAPSHOT/lib-1.0.0-SNAPSHOT.jar";
    private final RepositoryPutRequest request = new RepositoryPutRequest("org.example", "lib", "1.0.0-SNAPSHOT", new byte[]{1, 2, 3});
    private final List<EventLogEntry> events = new ArrayList<>();

    @Test
    void acceptedOrRejected_reportsDeployed_forA2xxAnswer() {
        var answer = MavenResponse.json("{}".getBytes(StandardCharsets.UTF_8));

        DeploymentRoutes.acceptedOrRejected(answer, request, PATH, events::add)
                        .await()
                        .onFailureRun(Assertions::fail)
                        .onSuccess(response -> assertThat(response.success()).isTrue());
        assertThat(events).hasSize(1);
    }

    @Test
    void acceptedOrRejected_failsWithTheRefusal_forA4xxAnswer() {
        var refusal = MavenResponse.badRequest("SNAPSHOT versions are not accepted");

        DeploymentRoutes.acceptedOrRejected(refusal, request, PATH, events::add)
                        .await()
                        .onSuccessRun(Assertions::fail)
                        .onFailure(cause -> assertRejected(cause, "400", "SNAPSHOT versions are not accepted"));
        assertThat(events).as("a refused push is not logged as a deployment").isEmpty();
    }

    private static void assertRejected(org.pragmatica.lang.Cause cause, String status, String detail) {
        assertThat(cause).isInstanceOf(RepositoryPutRejected.class);
        assertThat(cause.message()).contains(status).contains(detail);
    }

    @Test
    void acceptedOrRejected_failsWith409_forAConflictingRePut() {
        DeploymentRoutes.acceptedOrRejected(MavenResponse.conflict("different content"), request, PATH, events::add)
                        .await()
                        .onSuccessRun(Assertions::fail)
                        .onFailure(cause -> assertThat(cause.message()).contains("409"));
    }
}
