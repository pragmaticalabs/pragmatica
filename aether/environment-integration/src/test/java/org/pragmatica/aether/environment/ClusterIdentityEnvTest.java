// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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
    void backupVars_areTheFourBackupKeys_andNotIdentity() {
        assertThat(ClusterIdentityEnv.BACKUP_VARS).containsExactly("AETHER_BACKUP_ENABLED",
                                                                   "AETHER_BACKUP_PATH",
                                                                   "AETHER_BACKUP_REMOTE",
                                                                   "AETHER_BACKUP_RESTORE");
        assertThat(ClusterIdentityEnv.IDENTITY_VARS).doesNotContainAnyElementsOf(ClusterIdentityEnv.BACKUP_VARS);
    }

    @Test
    void identityVars_retainExistingClusterIdentityVars() {
        assertThat(ClusterIdentityEnv.IDENTITY_VARS).contains("AETHER_CLUSTER_NAME");
        assertThat(ClusterIdentityEnv.IDENTITY_VARS).contains("AETHER_CLUSTER_SECRET");
        assertThat(ClusterIdentityEnv.IDENTITY_VARS).contains("AETHER_PROVISIONED_BY");
        assertThat(ClusterIdentityEnv.IDENTITY_VARS).contains("AETHER_API_KEY");
    }

    /// #1200: the gossip key is file-delivered like the cluster secret, so it must be on the allow-list that points a node at its file.
    @Test
    void identityVars_includeTheGossipKey_whichIsFileDelivered() {
        assertThat(ClusterIdentityEnv.IDENTITY_VARS).contains("AETHER_GOSSIP_KEY");
        assertThat(ClusterSecretSource.FILE_DELIVERED_ENVS).contains("AETHER_GOSSIP_KEY");
    }
}
