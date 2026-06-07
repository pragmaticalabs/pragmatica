// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterIdentityEnvTest {

    @Test
    void identityVars_includeSourceAndRole_forSelfDescribedMembership() {
        assertThat(ClusterIdentityEnv.IDENTITY_VARS).contains("AETHER_SOURCE");
        assertThat(ClusterIdentityEnv.IDENTITY_VARS).contains("AETHER_ROLE");
    }

    @Test
    void identityVars_retainExistingClusterIdentityVars() {
        assertThat(ClusterIdentityEnv.IDENTITY_VARS).contains("AETHER_CLUSTER_NAME");
        assertThat(ClusterIdentityEnv.IDENTITY_VARS).contains("AETHER_CLUSTER_SECRET");
        assertThat(ClusterIdentityEnv.IDENTITY_VARS).contains("AETHER_PROVISIONED_BY");
        assertThat(ClusterIdentityEnv.IDENTITY_VARS).contains("AETHER_API_KEY");
    }
}
