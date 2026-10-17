// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.repository;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.resource.artifact.ArtifactStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1778: the archive retention the operator configures is the one the built-in store runs under.
class RepositoryFactoryArchivePolicyTest {
    @Test
    void archivePolicy_carriesTheConfiguredRetention() {
        var config = SliceConfig.sliceConfig().withArtifactArchiveRetention(timeSpan(36).hours());

        assertThat(RepositoryFactory.archivePolicy(config).minimumRetention().millis()).isEqualTo(36L * 60 * 60 * 1000);
    }

    @Test
    void archivePolicy_defaultsToTheStoreDefaultOfSevenDays() {
        assertThat(RepositoryFactory.archivePolicy(SliceConfig.sliceConfig()))
            .as("SliceConfig's default mirrors ArchivePolicy.DEFAULT")
            .isEqualTo(ArtifactStore.ArchivePolicy.DEFAULT);
    }
}
