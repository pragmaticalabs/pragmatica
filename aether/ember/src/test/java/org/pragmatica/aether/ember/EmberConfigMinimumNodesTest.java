// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.ClusterSizeGate;
import org.pragmatica.aether.ember.EmberConfig.EmberConfigError.InvalidValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #3299: a one- or two-node Ember/Forge cluster is refused at configuration with a typed error that states the
/// minimum, the same floor as production's [ClusterSizeGate] (#782). A one-node cluster reports ready but never
/// elects a leader. The check is reached through the factory, the TOML loader and the [Ember] builder.
class EmberConfigMinimumNodesTest {
    @Test
    void minimum_isTheProductionFloor() {
        assertThat(EmberConfig.MINIMUM_NODES).isEqualTo(ClusterSizeGate.MINIMUM_SUPPORTED_CLUSTER_SIZE).isEqualTo(3);
    }

    @Test
    void emberConfig_refusesBelowMinimum_withTypedErrorNamingTheMinimum() {
        for (var nodes : new int[] {0, 1, 2}) {
            EmberConfig.emberConfig(nodes, 5150, 8888)
                       .onSuccess(config -> fail("nodes " + nodes + " must be refused, got " + config))
                       .onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(InvalidValue.class, invalid -> {
                           assertThat(invalid.field()).isEqualTo("nodes");
                           assertThat(invalid.value()).isEqualTo(nodes);
                           assertThat(invalid.message()).contains("must be at least 3");
                       }));
        }
    }

    @Test
    void emberConfig_acceptsTheMinimum() {
        assertThat(EmberConfig.emberConfig(EmberConfig.MINIMUM_NODES, 5150, 8888).isSuccess()).isTrue();
    }

    @Test
    void loadFromString_refusesOneNode_acceptsThree() {
        assertThat(EmberConfig.loadFromString("[cluster]\nnodes = 1\n").isFailure()).isTrue();
        assertThat(EmberConfig.loadFromString("[cluster]\nnodes = 3\n").unwrap().nodes()).isEqualTo(3);
    }

    @Test
    void ember_cluster_start_refusesOneNode() {
        Ember.cluster(1)
             .start()
             .onSuccess(instance -> fail("a one-node cluster must refuse the start, started " + instance))
             .onFailure(cause -> assertThat(cause.message()).contains("nodes").contains("at least 3"));
    }
}
