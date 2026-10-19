// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import java.util.Map;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;

/// #689, node side: a node started without `AETHER_ROLE` advertises no role label and is
/// classified CORE by every peer (`MemberDescriptor.isCoreRole`: blank counts as core). That
/// default is deliberate and unchanged; what was missing is the node saying so at boot. The pair
/// below shares one capture: absent role must WARN naming the default, present role must not.
class MainNodeRoleAbsentWarnTest {
    private static final String LOGGER_NAME = "org.pragmatica.aether.Main";
    private static final String MARKER = "AETHER_ROLE";

    private LogCapture capture;

    @BeforeEach
    void setUp() {
        capture = LogCapture.capture(LOGGER_NAME, Level.WARN, false);
    }

    @AfterEach
    void tearDown() {
        capture.close();
    }

    @Test
    void absentRole_warnsAtBoot_namingTheCoreDefault() {
        var labels = Main.collectNodeLabels("host-1", _ -> Option.none());

        assertThat(labels).as("control: the label is genuinely absent").doesNotContainKey(NodeInfo.LABEL_ROLE);
        var warns = capture.messages().stream().filter(msg -> msg.contains(MARKER)).toList();
        assertThat(warns).as("#689: an absent role must be stated at boot, not discovered from a suppressed fence")
                         .hasSize(1);
        assertThat(warns.getFirst()).contains("no role label")
                                    .contains("CORE");
    }

    @Test
    void presentRole_doesNotWarn() {
        var env = Map.of("AETHER_ROLE", "worker");
        var labels = Main.collectNodeLabels("host-1", name -> Option.option(env.get(name)));

        assertThat(labels.get(NodeInfo.LABEL_ROLE)).isEqualTo("worker");
        assertThat(capture.messages()).noneMatch(msg -> msg.contains(MARKER));
    }
}
