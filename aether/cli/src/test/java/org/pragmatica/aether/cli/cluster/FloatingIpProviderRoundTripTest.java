// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.ClusterName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// `FloatingIpAssignment.provider()` used to be a constant `"cloud"` overriding the record's own component,
/// so the provider a caller passed was written to the ledger as `"cloud"` and read back as `"cloud"`. Both
/// ledgers (`bootstrap-state.json`, `apply-state.json`) must carry the real provider through a round trip.
class FloatingIpProviderRoundTripTest {
    private static final ClusterName CLUSTER = ClusterName.clusterName("fip-round-trip").unwrap();
    private static final List<String> PROVIDERS = List.of("hetzner", "aws", "azure", "gcp", "docker");

    private static CreatedResource.FloatingIpAssignment floatingIp(String provider) {
        return CreatedResource.FloatingIpAssignment.floatingIpAssignment(provider, "203.0.113.9", "eu-1-core-0");
    }

    @Test
    void floatingIp_reportsTheProviderItWasBuiltWith() {
        for (var provider : PROVIDERS) {
            assertThat(floatingIp(provider).provider()).isEqualTo(provider);
        }
    }

    @Test
    void bootstrapState_roundTrip_preservesEachProvider() {
        for (var provider : PROVIDERS) {
            var state = BootstrapState.initialState(CLUSTER, "h", "2026-10-18T00:00:00Z").withResource(floatingIp(provider));

            BootstrapState.fromJson(state.toJson())
                          .onFailure(cause -> fail(cause.message()))
                          .onSuccess(restored -> assertThat(restored.createdResources()).as("provider " + provider)
                                                                                         .containsExactly(floatingIp(provider)));
        }
    }

    @Test
    void applyState_roundTrip_preservesEachProvider() {
        for (var provider : PROVIDERS) {
            var state = ApplyStateFixtures.state(CLUSTER).withResource(floatingIp(provider));

            ApplyState.fromJson(ApplyState.toJson(state))
                      .onFailure(cause -> fail(cause.message()))
                      .onSuccess(restored -> assertThat(restored.createdResources()).as("provider " + provider)
                                                                                     .containsExactly(floatingIp(provider)));
        }
    }

    /// A ledger written by an older CLI carries `"provider": "cloud"` for every floating IP; it still loads,
    /// as the provider `"cloud"`.
    @Test
    void legacyLedgerWithCloudProvider_stillLoads() {
        var state = BootstrapState.initialState(CLUSTER, "h", "2026-10-18T00:00:00Z").withResource(floatingIp("cloud"));

        BootstrapState.fromJson(state.toJson())
                      .onFailure(cause -> fail(cause.message()))
                      .onSuccess(restored -> assertThat(((CreatedResource.FloatingIpAssignment) restored.createdResources().getFirst()).provider())
                              .isEqualTo("cloud"));
    }
}
