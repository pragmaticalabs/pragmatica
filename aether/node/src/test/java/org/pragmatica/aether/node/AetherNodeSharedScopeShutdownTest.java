// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.Map;

import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #903: the shared (plain-overload, unattributed) resource scope has no slice to release it, so only node
/// shutdown can close it. `AetherNode.stop()` never did. The assertion is on the RESOURCE's own close count,
/// never on the promise `stop()` returns, which succeeded before the fix.
class AetherNodeSharedScopeShutdownTest {
    @TempDir
    Path tempDir;

    private AetherNode node;

    @BeforeEach
    void setUp() {
        ReleaseProbeFactory.reset();
    }

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop().await(timeSpan(10).seconds()).onFailure(cause -> fail("stop failed in teardown: " + cause.message()));
        }
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void stop_closesTheSharedScopeResource_exactlyOnce() {
        var configProvider = ConfigurationProvider.builder()
                                                  .withDefaults(Map.of(ReleaseProbeFactory.SECTION + ".enabled", "true"))
                                                  .build();

        node = AetherNode.aetherNode(AetherNodeContentStorageWarnBootTest.minimalConfig(Option.none(),
                                                                                        Option.none(),
                                                                                        configProvider,
                                                                                        tempDir),
                                     () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();

        var provider = ResourceProvider.instance()
                                       .or(() -> fail("the booted node must have installed its ResourceProvider"));

        provider.provide(ReleaseProbeFactory.ProbeResource.class, ReleaseProbeFactory.SECTION)
                .await(timeSpan(10).seconds())
                .onFailure(cause -> fail("shared-scope provisioning must succeed: " + cause.message()));

        assertThat(ReleaseProbeFactory.provisioned()).as("fixture: one shared-scope resource").hasSize(1);
        assertThat(ReleaseProbeFactory.provisioned().getFirst().closeCount()).as("control: open while the node runs")
                                                                          .isZero();

        node.stop().await(timeSpan(10).seconds()).onFailure(cause -> fail("stop must succeed: " + cause.message()));
        node = null;

        assertThat(ReleaseProbeFactory.provisioned().getFirst().closeCount())
                .as("#903: node stop must close the shared scope exactly once")
                .isEqualTo(1);
    }
}
