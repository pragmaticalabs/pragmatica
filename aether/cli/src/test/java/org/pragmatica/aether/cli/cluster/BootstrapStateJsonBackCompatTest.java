// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class BootstrapStateJsonBackCompatTest {

    /// JSON shape produced by the previous release (no `sources` field). The cleanup
    /// path must still parse it; the absent field maps to an empty Map and the legacy
    /// fallback resolver kicks in at cleanup time.
    private static final String LEGACY_JSON = """
        {
          "clusterName": "legacy-cluster",
          "configHash": "legacy-hash",
          "startedAt": "2026-04-01T00:00:00Z",
          "phases": {
            "VALIDATE": "COMPLETED",
            "UPLOAD_SSH_KEYS": "COMPLETED",
            "PROVISION": "COMPLETED",
            "COLLECT_ADDRESSES": "COMPLETED",
            "DEPLOY_RUNTIME": "COMPLETED",
            "CLUSTER_FORMATION": "COMPLETED",
            "POST": "COMPLETED"
          },
          "createdResources": [
            {"type": "ProvisionedVm", "provider": "hetzner", "resourceId": "vm-1", "sourceName": "eu-1", "role": "core"}
          ],
          "provisionedNodeIds": ["eu-1-core-0"],
          "collectedAddresses": ["10.0.0.1"],
          "clusterSecret": "legacy-secret"
        }
        """;

    @Test
    void fromJson_legacyShapeWithoutSources_yieldsEmptySourcesMap() {
        var result = BootstrapState.fromJson(LEGACY_JSON);

        result.onFailure(cause -> fail("Legacy JSON must parse: " + cause.message()))
              .onSuccess(state -> {
                  assertNotNull(state.sources(),
                                 "sources() must not be null for legacy JSON missing the field");
                  assertTrue(state.sources().isEmpty(),
                              "Legacy state without sources field must default to empty map");
              });
    }

    @Test
    void fromJson_legacyShape_doesNotCrash_preservesAllOtherFields() {
        var state = BootstrapState.fromJson(LEGACY_JSON).onFailure(cause -> fail(cause.message()))
                                          .unwrap();

        assertEquals("legacy-cluster", state.clusterName());
        assertEquals("legacy-secret", state.clusterSecret());
        assertEquals(1, state.createdResources().size());
        assertEquals("eu-1-core-0", state.provisionedNodeIds().get(0));
    }

    @Test
    void fromJson_explicitNullSources_treatedAsEmpty() {
        var jsonWithNullSources = """
            {
              "clusterName": "x",
              "configHash": "h",
              "startedAt": "2026-04-01T00:00:00Z",
              "phases": {},
              "createdResources": [],
              "provisionedNodeIds": [],
              "collectedAddresses": [],
              "clusterSecret": "",
              "sources": null
            }
            """;

        var state = BootstrapState.fromJson(jsonWithNullSources).onFailure(c -> fail(c.message()))
                                          .unwrap();

        assertTrue(state.sources().isEmpty(),
                   "Explicit null sources field must parse as empty (no NPE)");
    }

    /// T1 — backward compatibility. `firewallId` was a `long` before this widening, so every
    /// existing `bootstrap-state.json` on disk has an UNQUOTED numeric value here (`"firewallId": 12345`).
    /// The reader must still parse it — `JsonNode.asText()` on a numeric node yields its decimal string —
    /// so an existing cluster's firewall is not stranded by the upgrade.
    private static final String LEGACY_JSON_WITH_UNQUOTED_FIREWALL_ID = """
        {
          "clusterName": "legacy-firewall-cluster",
          "configHash": "legacy-hash",
          "startedAt": "2026-04-01T00:00:00Z",
          "phases": {},
          "createdResources": [
            {"type": "CloudFirewall", "provider": "hetzner", "firewallId": 12345, "sourceName": "eu-1", "name": "aether-eu-1"}
          ],
          "provisionedNodeIds": [],
          "collectedAddresses": [],
          "clusterSecret": ""
        }
        """;

    @Test
    void fromJson_legacyUnquotedNumericFirewallId_parsesAsString() {
        var state = BootstrapState.fromJson(LEGACY_JSON_WITH_UNQUOTED_FIREWALL_ID)
                                   .onFailure(cause -> fail("Legacy unquoted firewallId must still parse: " + cause.message()))
                                   .unwrap();

        assertEquals(1, state.createdResources().size());
        var firewall = (CreatedResource.CloudFirewall) state.createdResources().get(0);
        assertEquals("12345", firewall.firewallId(),
                     "An unquoted legacy numeric firewallId must parse to the string \"12345\"");
    }
}
