// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.testlogging.LogCapture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2825 (D7), through the REAL boot path: `AetherNode.aetherNode` over a data root that holds a directory of another NodeId
/// under `stream-segments/` logs `[stream-data-abandoned]` naming it, and leaves it in place. Red-before: removing the
/// `AbandonedStreamData.check` call in `AetherNode.assembleNode` turns the test red. The capture is on the logger of
/// [AbandonedStreamData], which is the log line [org.pragmatica.utility.warning.OperatorWarnings] writes before it offers the
/// warning to the event sink.
class AetherNodeAbandonedStreamDataBootTest {
    private LogCapture capture;
    private AetherNode node;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        capture = LogCapture.capture(AbandonedStreamData.class.getName(), Level.INFO, false);
    }

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> {});
        }

        ConfigService.clear();
        ResourceProvider.clear();
        capture.close();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void boot_overAnotherNodesStreamDirectory_logsTheAbandonedWarning_andKeepsTheDirectory() throws Exception {
        var config = AetherNodeContentStorageWarnBootTest.minimalConfig(Option.none(),
                                                                         Option.none(),
                                                                         ConfigurationProvider.builder().build(),
                                                                         tempDir);
        var foreign = AetherNode.streamDataDir(config).getParent().resolve("node-from-the-previous-run").resolve("segments");

        Files.createDirectories(foreign);
        Files.write(foreign.resolve("a.blk"), new byte[321]);

        node = AetherNode.aetherNode(config, () -> {})
                          .onFailure(cause -> fail("boot must succeed over abandoned data: " + cause.message()))
                          .unwrap();

        assertThat(capture.messages()).anyMatch(msg -> msg.contains("[stream-data-abandoned]")
                                                       && msg.contains("node-from-the-previous-run")
                                                       && msg.contains("321 bytes"));
        assertThat(foreign.resolve("a.blk")).exists();
    }
}
