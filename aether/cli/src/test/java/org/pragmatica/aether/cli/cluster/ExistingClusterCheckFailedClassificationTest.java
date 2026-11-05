// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2103: the existing-cluster guard's refusal reports the classification of the check that failed. A provider that could
/// not be reached is transient, a listing it refused is not; nothing retries on it today, so this is the contract a later
/// retry would rely on.
class ExistingClusterCheckFailedClassificationTest {
    @Test
    void existingClusterCheckFailed_followsItsOrigin() {
        var unreachable = new ClusterBootstrapOrchestrator.BootstrapError.ExistingClusterCheckFailed("c", "cloud listing", Causes.transientCause("unreachable"));
        var refused = new ClusterBootstrapOrchestrator.BootstrapError.ExistingClusterCheckFailed("c", "cloud listing", Causes.cause("forbidden"));

        assertThat(unreachable.isTransient()).isTrue();
        assertThat(unreachable.isTerminal()).isFalse();
        assertThat(refused.isTransient()).isFalse();
    }
}
