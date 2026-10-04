// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.node.ProvisioningContextCaptureFactory.CapturedProvisioningContext;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.resource.interceptor.CacheDhtClient;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.lang.Option;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1777 (CTO ruling R2 / Q4), pinned at the node's REAL assembly: the `DHTClient` extension a slice resource — idempotency
/// among them — provisions through is the replicated DHT client, and the cache namespace gets the `[cache]` client as
/// `CacheDhtClient`. `registerDhtExtensions` is pinned in isolation by `AetherNodeDhtReplicationTest`; this pins the call
/// site in `AetherNode`'s assembly, where swapping the two clients left the whole node suite green (v1882, round 2).
///
/// The fixture's DHT is FULL replication (the harness declaration) while the cache keeps its own RF 1 / CF 1, so the two
/// clients are distinguishable: a swap hands idempotency a single-copy client.
class AetherNodeIdempotencyDhtWiringBootTest {
    private static final String CAPTURE_SECTION = "context_capture";

    private AetherNode node;

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> {});
        }

        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void assembledNode_registersTheReplicatedClientAsDhtClient_andTheCacheClientSeparately() {
        var configProvider = ConfigurationProvider.builder()
                                                  .withDefaults(Map.of(CAPTURE_SECTION + ".enabled", "true"))
                                                  .build();

        node = AetherNode.aetherNode(AetherNodeContentStorageWarnBootTest.minimalConfig(Option.none(),
                                                                                       Option.none(),
                                                                                       configProvider,
                                                                                       tempDir),
                                     () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();

        var spi = ResourceProvider.instance()
                                  .fold(() -> fail("the node installs its SPI provider when a configProvider is present"),
                                        provider -> provider);
        var context = spi.provide(CapturedProvisioningContext.class, CAPTURE_SECTION, ProvisioningContext.provisioningContext())
                         .await()
                         .onFailure(cause -> fail("provisioning through the node's SPI must succeed: " + cause.message()))
                         .unwrap()
                         .context();
        var dhtClient = context.extension(DHTClient.class)
                               .onFailure(cause -> fail("no DHTClient extension: " + cause.message()))
                               .unwrap();
        var cacheClient = context.extension(CacheDhtClient.class)
                                 .onFailure(cause -> fail("no CacheDhtClient extension: " + cause.message()))
                                 .unwrap()
                                 .client();

        assertThat(cacheClient.config().isFullReplication()).as("fixture: the cache client is distinguishable (RF 1)")
                                                            .isFalse();
        assertThat(dhtClient.config().isFullReplication()).as("the DHTClient idempotency resolves is the replicated DHT, "
                                                              + "not the [cache] client")
                                                          .isTrue();
        assertThat(dhtClient).isNotSameAs(cacheClient);
    }
}
