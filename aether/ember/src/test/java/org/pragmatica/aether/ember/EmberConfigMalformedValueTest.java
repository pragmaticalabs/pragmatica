// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1098: `nodes = "5x"` used to read as absent and take `DEFAULT_NODES`; the load refuses it by name.
class EmberConfigMalformedValueTest {
    @Test
    void loadFromString_malformedNodes_refusesNamingKeyAndValue() {
        EmberConfig.loadFromString("[cluster]\nnodes = \"5x\"\n")
                   .onSuccess(config -> fail("nodes = \"5x\" must refuse the load, loaded " + config))
                   .onFailure(cause -> assertThat(cause.message()).contains("cluster.nodes")
                                                                  .contains("5x"));
    }

    @Test
    void loadFromString_wellFormedNodes_loads() {
        assertThat(EmberConfig.loadFromString("[cluster]\nnodes = 3\n").unwrap().nodes()).isEqualTo(3);
    }
}
