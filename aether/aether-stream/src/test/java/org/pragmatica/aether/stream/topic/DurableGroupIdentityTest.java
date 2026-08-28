// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.topic;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Pins durable-pubsub-spec §6's version-stable group identity: a slice UPGRADE keeps its cursor
/// (same group id), a different handler method is a different group.
class DurableGroupIdentityTest {
    private static final MethodName METHOD = MethodName.methodName("onOrderCompleted").unwrap();

    @Test
    void groupId_isStable_acrossArtifactVersionUpgrade() {
        var v1 = Artifact.artifact("org.example:orders:1.0.0").unwrap();
        var v2 = Artifact.artifact("org.example:orders:1.1.0").unwrap();

        assertThat(DurableGroupIdentity.groupId(v1, METHOD)).isEqualTo(DurableGroupIdentity.groupId(v2, METHOD));
    }

    @Test
    void groupId_differs_perHandlerMethod() {
        var artifact = Artifact.artifact("org.example:orders:1.0.0").unwrap();
        var other = MethodName.methodName("onOrderCancelled").unwrap();

        assertThat(DurableGroupIdentity.groupId(artifact, METHOD)).isNotEqualTo(DurableGroupIdentity.groupId(artifact,
                                                                                                             other));
    }

    @Test
    void groupId_differs_perArtifactBase() {
        var orders = Artifact.artifact("org.example:orders:1.0.0").unwrap();
        var billing = Artifact.artifact("org.example:billing:1.0.0").unwrap();

        assertThat(DurableGroupIdentity.groupId(orders, METHOD)).isNotEqualTo(DurableGroupIdentity.groupId(billing,
                                                                                                           METHOD));
    }
}
