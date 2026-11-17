// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.ember.EmberConfig.EmberConfigError.InvalidValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #3299: the direct library path (`EmberCluster.emberCluster(n, ...)` then `start()`) refuses a cluster below
/// [EmberConfig#MINIMUM_NODES] with the same typed error as the configuration path, before any node is created.
class EmberClusterStartMinimumTest {
    private static final int BASE_PORT = 24_900;

    @Test
    void start_refusesOneAndTwoNodes_withTheConfigPathError_andBootsNothing() {
        for (var size : new int[] {1, 2}) {
            var cluster = EmberCluster.emberCluster(size, BASE_PORT, BASE_PORT + 100, BASE_PORT + 200, "min" + size);

            cluster.start()
                   .await()
                   .onSuccess(_ -> fail("a " + size + "-node cluster must refuse start()"))
                   .onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(InvalidValue.class, invalid -> {
                       assertThat(invalid.field()).isEqualTo("nodes");
                       assertThat(invalid.value()).isEqualTo(size);
                       assertThat(invalid.message()).isEqualTo(EmberConfig.tooFewNodes(size).message());
                   }));

            assertThat(cluster.nodeCount()).isZero();
            assertThat(cluster.allNodes()).isEmpty();
        }
    }

    @Test
    void start_atTheMinimum_isNotRefusedForSize() {
        var cluster = EmberCluster.emberCluster(EmberConfig.MINIMUM_NODES, BASE_PORT + 10, BASE_PORT + 110, BASE_PORT + 210, "min3");

        try {
            cluster.start()
                   .await()
                   .onFailure(cause -> assertThat(cause).isNotInstanceOf(InvalidValue.class));
            assertThat(cluster.nodeCount()).isEqualTo(EmberConfig.MINIMUM_NODES);
        } finally {
            cluster.stop().await();
        }
    }
}
