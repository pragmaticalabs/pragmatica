// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics.observability;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.net.quic.QuicTransportMetrics;

import io.micrometer.core.instrument.Meter;

import static org.assertj.core.api.Assertions.assertThat;


/// Tripwire pair for D-05 (cluster T assessment, rc4 `7c786db0d3`): `QuicTransportMetrics.snapshot()` produces 24 keys
/// and `QuicClusterNetwork.transportMetrics()` adds `boot_token_refusals_total`, but
/// `ObservabilityRegistry.registerTransportMetrics` registers 11 of them on the Prometheus surface. The other 14 (among
/// them `quic_unknown_type_tag_drops_total`, the version-skew signal, and `quic_drop_to_unknown_peer_total`, which the
/// code comments say hid defects for months) are reachable only through the JSON endpoint, so a Prometheus alert cannot
/// see them.
///
/// Level: the registry class itself. The produced key set is read from the real `QuicTransportMetrics`, so a counter
/// added later without a registration turns the real assertion red rather than going unnoticed.
class TransportMetricsExposureTripwireTest {
    /// Added by `QuicClusterNetwork.transportMetrics()` on top of the snapshot (terminal-removal admissions refused).
    private static final String BOOT_TOKEN_REFUSALS = "boot_token_refusals_total";

    private static Set<String> producedKeys() {
        var keys = new TreeSet<>(QuicTransportMetrics.quicTransportMetrics().snapshot().keySet());

        keys.add(BOOT_TOKEN_REFUSALS);

        return keys;
    }

    private static Set<String> exposedKeys() {
        var registry = ObservabilityRegistry.prometheus();

        registry.registerTransportMetrics(Map::of);

        var exposed = new TreeSet<String>();

        for (Meter meter : registry.registry().getMeters()) {
            var name = meter.getId().getName();

            if (name.startsWith("quic_") || name.equals(BOOT_TOKEN_REFUSALS)) {
                exposed.add(name);
            }
        }

        return exposed;
    }

    private static Set<String> unexposedKeys() {
        var missing = new TreeSet<>(producedKeys());

        missing.removeAll(exposedKeys());

        return missing;
    }

    @Test
    void registerTransportMetrics_exposesOnlyElevenOfTheProducedCounters() {
        assertThat(producedKeys()).as("control: the transport produces 27 snapshot keys (24 plus the three #1535 inbound-refusal counters) plus the boot-token refusals counter").hasSize(28);
        assertThat(exposedKeys()).as("TRIPWIRE D-05: exactly these 11 transport counters reach Prometheus; a counter was "
                                     + "registered or removed. Delete this tripwire and enable "
                                     + "registerTransportMetrics_exposesEveryProducedCounter.")
                                 .containsExactlyInAnyOrder("quic_active_connections",
                                                            "quic_handshake_total",
                                                            "quic_handshake_failures_total",
                                                            "quic_dial_failures_total",
                                                            "quic_dial_abandoned_total",
                                                            "quic_messages_sent_total",
                                                            "quic_messages_received_total",
                                                            "quic_bytes_sent_total",
                                                            "quic_bytes_received_total",
                                                            "quic_write_failures_total",
                                                            "quic_backpressure_drops_total");
        assertThat(unexposedKeys()).as("the 17 counters missing from Prometheus (14 plus the three #1535 inbound-refusal counters)").hasSize(17);
    }

    @Disabled("D-05: enable when every transport counter (QuicTransportMetrics.snapshot() plus boot_token_refusals_total) is "
              + "registered on the Prometheus surface. Today red: 14 of 25 are not.")
    @Test
    void registerTransportMetrics_exposesEveryProducedCounter() {
        assertThat(unexposedKeys()).as("transport counters not registered on the Prometheus surface").isEmpty();
    }
}
