// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Regression test for the DHT operation timeout default.
///
/// The deploy chain `/api/blueprints/deploy` →
/// `BlueprintService.publishFromArtifact` → `BuiltinRepository` →
/// `ArtifactStore.resolveWithMetadata` → `dht.get(metaKey)` bottlenecks on
/// `DHTConfig.operationTimeout()`. Validation #7 (post NodeId-as-container-name
/// migration) surfaced sporadic `Promise timed out after 10000ms` HTTP 500
/// failures during parallel-suite-load cluster bootstrap. Default raised to
/// 30s to give the deploy pipeline headroom; this test pins that contract so
/// the value isn't silently regressed.
class TimeoutsConfigDhtDefaultTest {
    @Test
    void dhtTimeouts_default_operationIs30Seconds() {
        var dhtTimeouts = TimeoutsConfig.DhtTimeouts.dhtTimeouts();

        assertThat(dhtTimeouts.operation().millis()).isEqualTo(30_000L);
    }

    @Test
    void dhtTimeouts_default_antiEntropyIntervalIs30Seconds() {
        var dhtTimeouts = TimeoutsConfig.DhtTimeouts.dhtTimeouts();

        assertThat(dhtTimeouts.antiEntropyInterval().millis()).isEqualTo(30_000L);
    }
}
