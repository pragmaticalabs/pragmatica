// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class BootstrapStateJsonRoundTripTest {

    private static BootstrapState baseState() {
        return BootstrapState.initialState("eu-prod", "hash-1", "2026-05-01T00:00:00Z");
    }

    @Test
    void roundTrip_preservesNonEmptySources() {
        var handle = SourceCleanupHandle.sourceCleanupHandle("hetzner",
                                                              Option.some("eu-central"),
                                                              Map.of("api_token", "HCLOUD_TOKEN_PROD"));
        var state = baseState().withSource("hetzner-eu", handle);

        var json = state.toJson();
        var restored = BootstrapState.fromJson(json);

        restored.onFailure(cause -> fail("fromJson failed: " + cause.message()))
                .onSuccess(s -> assertEquals(state, s,
                                              "Round-tripped state must equal original (non-empty sources branch)"));
    }

    @Test
    void roundTrip_preservesMultipleSources_distinctEnvVarNames() {
        var hetz = SourceCleanupHandle.sourceCleanupHandle("hetzner",
                                                            Option.some("eu-central"),
                                                            Map.of("api_token", "HCLOUD_TOKEN_PROD"));
        var aws = SourceCleanupHandle.sourceCleanupHandle("aws",
                                                           Option.some("us-east-1"),
                                                           orderedMap("access_key_id", "AWS_KEY_PROD",
                                                                       "secret_access_key", "AWS_SECRET_PROD"));

        var state = baseState().withSource("hetz-eu", hetz)
                                .withSource("aws-us", aws);

        var restored = BootstrapState.fromJson(state.toJson()).onFailure(c -> fail(c.message()))
                                            .unwrap();

        assertEquals(2, restored.sources().size(),
                     "Both sources must round-trip");
        assertEquals(handleAsMap(hetz), handleAsMap(restored.sources().get("hetz-eu")));
        assertEquals(handleAsMap(aws), handleAsMap(restored.sources().get("aws-us")));
    }

    @Test
    void roundTrip_emptySourcesProducesEmptyMap_notNull() {
        var state = baseState();
        assertTrue(state.sources().isEmpty(), "default state has no sources");

        var restored = BootstrapState.fromJson(state.toJson()).onFailure(c -> fail(c.message()))
                                            .unwrap();

        assertTrue(restored.sources().isEmpty(),
                   "Round-tripped empty sources must remain empty (and non-null)");
    }

    @Test
    void roundTrip_preservesAbsentRegion() {
        var handle = SourceCleanupHandle.sourceCleanupHandle("docker", Option.none(), Map.of());
        var state = baseState().withSource("local", handle);

        var restored = BootstrapState.fromJson(state.toJson()).onFailure(c -> fail(c.message()))
                                            .unwrap();

        assertTrue(restored.sources().get("local").region().isEmpty(),
                   "Empty/absent region must round-trip as Option.none()");
    }

    @Test
    void toJson_emitsSourcesField_evenWhenEmpty() {
        var json = baseState().toJson();
        assertTrue(json.contains("\"sources\""),
                   "Sources field must always be emitted (even when empty) so the format stays uniform");
    }

    private static Map<String, String> orderedMap(String k1, String v1, String k2, String v2) {
        var m = new LinkedHashMap<String, String>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    /// Equality-friendly view: regions as String (Option.equals is well-behaved but
    /// reduces the surface to just provider+region+envVars). The envVars map equality
    /// is order-independent (Map.equals compares entry sets, not iteration order).
    private static Map<String, Object> handleAsMap(SourceCleanupHandle handle) {
        return Map.of("provider", handle.provider(),
                       "region", handle.region().or(""),
                       "envVars", Map.copyOf(handle.credentialEnvVars()));
    }

    /// T2 — the whole point of widening `firewallId` from `long` to `String`: a non-numeric,
    /// provider-shaped id must survive a full serialize/parse round trip unchanged.
    @Test
    void roundTrip_preservesNonNumericAwsShapedFirewallId() {
        var firewall = CreatedResource.CloudFirewall.cloudFirewall("aws", "sg-0abc123def", "us-east-1", "aether-us-east-1");
        var state = baseState().withResource(firewall);

        var restored = BootstrapState.fromJson(state.toJson()).onFailure(c -> fail(c.message()))
                                            .unwrap();

        assertEquals(1, restored.createdResources().size());
        var restoredFirewall = (CreatedResource.CloudFirewall) restored.createdResources().get(0);
        assertEquals("sg-0abc123def", restoredFirewall.firewallId(),
                     "AWS security-group id must round-trip exactly");
    }

    /// T2 — an ARM-shaped id contains `/`, exercising JSON escaping on both the write and read side.
    @Test
    void roundTrip_preservesSlashContainingArmShapedFirewallId() {
        var armId = "/subscriptions/abc-123/resourceGroups/rg1/providers/Microsoft.Network/networkSecurityGroups/nsg1";
        var firewall = CreatedResource.CloudFirewall.cloudFirewall("azure", armId, "westeurope", "aether-westeurope");
        var state = baseState().withResource(firewall);

        var restored = BootstrapState.fromJson(state.toJson()).onFailure(c -> fail(c.message()))
                                            .unwrap();

        assertEquals(1, restored.createdResources().size());
        var restoredFirewall = (CreatedResource.CloudFirewall) restored.createdResources().get(0);
        assertEquals(armId, restoredFirewall.firewallId(),
                     "ARM resource-path id (containing '/') must round-trip exactly");
    }
}
