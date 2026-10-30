// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #774: how the node assembles the config change feed, pinned on the assembly source (the same technique as
/// `AetherNodeBackupCoordinatorStopWiringTest`: the node cannot be started in a unit test, and a wiring line that
/// is deleted or reordered reddens exactly one assertion here).
class ConfigChangeFeedWiringTest {
    private static final String PUBLISH = "dcm.onApplied(configChangeFeed::publish)";
    private static final String FSM_LISTENER = "dcm.onApplied(nodeDeploymentManager::onConfigChanged)";

    @Test
    void theApplierPublishesToTheFeed_beforeTheLegacyFsmListener() {
        var source = AssemblySource.spaced();

        assertThat(source).containsOnlyOnce(PUBLISH).containsOnlyOnce(FSM_LISTENER);
        assertThat(source.indexOf(PUBLISH)).as("the feed is registered first: a Watched subscription is triggered ahead of the legacy push")
                                           .isLessThan(source.indexOf(FSM_LISTENER));
    }

    @Test
    void nodeStop_releasesTheWatchSubscriptions_asTheFirstStep_beforeAnythingTheyReadIsTornDown() {
        var source = AssemblySource.spaced();

        assertThat(source).containsOnlyOnce("configChangeFeed.shutdown()")
                          .containsOnlyOnce("NodeStopSequence.Steps.steps(this::releaseWatchesThenDeactivate,");
        assertThat(source.indexOf("configChangeFeed.shutdown()")).as("shutdown is part of the first stop step, ahead of the deactivation it precedes")
                                                                .isLessThan(source.indexOf("clusterDeploymentManager.deactivate()"));
    }

    @Test
    void theSliceStoreAndTheFeedAreTheSameInstance_andTheFeedIsGivenTheOperatorSink() {
        var source = AssemblySource.spaced();

        assertThat(source).containsOnlyOnce("Option.some(configChangeFeed)")
                          .containsOnlyOnce("configChangeFeed.setOperatorWarningSink(operatorWarningSink)");
    }
}
