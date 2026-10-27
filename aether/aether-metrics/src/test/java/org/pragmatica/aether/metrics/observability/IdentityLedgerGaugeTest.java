// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics.observability;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2429: the identity ledger's durability gauge reaches the Prometheus surface, so a core whose ledger is lost or cannot be
/// written can be alerted on, not only read from the JSON endpoint.
class IdentityLedgerGaugeTest {
    @Test
    void registerTransportMetrics_exposesTheIdentityLedgerGauge_readingTheSuppliedValue() {
        var registry = ObservabilityRegistry.prometheus();
        var durable = new AtomicInteger(1);

        registry.registerTransportMetrics(() -> Map.of("identity_ledger_durable", durable.get()));

        assertThat(registry.registry().find("identity_ledger_durable").gauge().value()).isEqualTo(1.0);

        durable.set(0);

        assertThat(registry.registry().find("identity_ledger_durable").gauge().value()).isEqualTo(0.0);
    }
}
