// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProviderDefaults;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;


/// Drift guard coupling [ProvisionRequest#resolve]'s private `SPOT_ROLE` literal to
/// [NodeRole#value] for `SPOT`. `environment-integration` is a leaf module and cannot import
/// `NodeRole`, so resolve() compares the context role by string. This test lives in a module
/// where BOTH types are visible and fails if `NodeRole.SPOT.value()` ever drifts from that
/// literal — a drift would resolve a `spot` role to an ON_DEMAND market, silently downgrading
/// spot on every provider (the class this epic eliminates).
class SpotRoleConstantDriftTest {
    @Test
    void resolve_roleFromNodeRoleSpotValue_mapsToSpotMarket() {
        var context = ProvisionContext.provisionContext("cluster",
                                                        NodeRole.SPOT.value(),
                                                        sourceNameOrDefault("src"),
                                                        ProvisionContext.PROVISIONED_BY_CTM);
        var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "cx22", NodeRole.SPOT.value(), context).unwrap();

        var request = ProvisionRequest.resolve(spec, defaults()).unwrap();

        assertThat(request.market()).isEqualTo(InstanceType.SPOT);
    }

    private static ProviderDefaults defaults() {
        return ProviderDefaults.providerDefaults("cx22", "img", "img", "zone", Option.empty(), true);
    }
}
