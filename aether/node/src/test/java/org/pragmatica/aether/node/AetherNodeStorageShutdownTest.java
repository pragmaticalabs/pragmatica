// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.testlogging.LogCapture;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1078: `AetherNode.stop()` never called `StorageInstance.shutdown()` on the three node-owned
/// instances (`content`, `artifacts`, `streams`), so a stop was not graceful for storage — the path
/// that drains a write-behind queue was never taken. Every node instance is write-through today,
/// so the observable of `shutdown()` on a real node is its own INFO line,
/// `Storage instance '<name>' shut down`, captured here through log4j on the instance's logger:
/// three names after `stop()`, none before. The write-behind drain itself is pinned in
/// `integrations/storage` (`WriteBehindTest`); this pins that the node REACHES it.
class AetherNodeStorageShutdownTest {
    private static final String STORAGE_LOGGER = "org.pragmatica.storage.DefaultStorageInstance";

    private AetherNode node;

    @TempDir
    Path tempDir;
    private LogCapture capture;

    @BeforeEach
    void setUp() {
        capture = LogCapture.capture(STORAGE_LOGGER, Level.ALL, false);
    }

    @AfterEach
    void tearDown() {
        // The test itself stops the node and nulls the field; this runs only when it failed before
        // reaching that line, and a stop failure then is reported rather than swallowed.
        if (node != null) {
            var stopped = node.stop().await(timeSpan(10).seconds());

            stopped.onFailure(cause -> fail("stop failed in teardown: " + cause.message()));
        }

        capture.close();
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void stop_shutsDown_everyNodeOwnedStorageInstance() {
        node = AetherNode.aetherNode(AetherNodeContentStorageWarnBootTest.minimalConfig(Option.none(),
                                                                                        Option.none(),
                                                                                        ConfigurationProvider.builder().build(),
                                                                                        tempDir),
                                     () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        var names = node.storageSetups().keySet();

        assertThat(names).as("fixture: the node owns the three storage instances the ticket names")
                  .containsExactlyInAnyOrder("content", "artifacts", "streams");
        assertThat(shutDownNames()).as("nothing is shut down before stop()").isEmpty();
        node.stop().await(timeSpan(10).seconds()).onFailure(cause -> fail("stop must succeed: " + cause.message()));
        node = null;
        assertThat(shutDownNames()).as("stop() must shut down each node-owned storage instance exactly once")
                  .containsExactlyInAnyOrder("content", "artifacts", "streams");
    }

    private List<String> shutDownNames() {
        return capture.messages()
                       .stream()
                       .filter(message -> message.startsWith("Storage instance '") && message.endsWith("' shut down"))
                       .map(message -> message.substring("Storage instance '".length(),
                                                         message.length() - "' shut down".length()))
                       .toList();
    }
}
