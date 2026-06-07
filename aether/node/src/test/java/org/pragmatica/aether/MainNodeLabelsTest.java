// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MainNodeLabelsTest {

    @Test
    void collectNodeLabels_mapsSourceAndRole_whenEnvPresent() {
        var env = Map.of("AETHER_SOURCE", "replacement", "AETHER_ROLE", "active");
        var labels = Main.collectNodeLabels("host-1", name -> Option.option(env.get(name)));

        assertEquals("host-1", labels.get(NodeInfo.LABEL_HOSTNAME));
        assertEquals("replacement", labels.get(NodeInfo.LABEL_SOURCE));
        assertEquals("active", labels.get(NodeInfo.LABEL_ROLE));
    }

    @Test
    void collectNodeLabels_omitsSourceAndRole_whenEnvAbsent() {
        var labels = Main.collectNodeLabels("host-1", _ -> Option.none());

        assertEquals("host-1", labels.get(NodeInfo.LABEL_HOSTNAME));
        assertFalse(labels.containsKey(NodeInfo.LABEL_SOURCE));
        assertFalse(labels.containsKey(NodeInfo.LABEL_ROLE));
    }

    @Test
    void collectNodeLabels_mapsZoneAlongsideSourceAndRole_whenAllPresent() {
        var env = Map.of("AETHER_ZONE", "eu-1", "AETHER_SOURCE", "seed", "AETHER_ROLE", "active");
        var labels = Main.collectNodeLabels("host-1", name -> Option.option(env.get(name)));

        assertEquals("eu-1", labels.get(NodeInfo.LABEL_ZONE));
        assertEquals("seed", labels.get(NodeInfo.LABEL_SOURCE));
        assertEquals("active", labels.get(NodeInfo.LABEL_ROLE));
    }
}
