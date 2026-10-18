// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

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

        assertEquals("legacy-cluster", state.clusterName().value());
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
        assertEquals("12345", firewall.firewallId().value(),
                     "An unquoted legacy numeric firewallId must parse to the string \"12345\"");
        assertEquals("eu-1", firewall.sourceName().value());
        assertEquals("aether-eu-1", firewall.name().value());
    }

    /// A firewall entry with no usable id is the one degradation that must NOT be absorbed: the id is
    /// destroy's only handle on a PAID resource, so dropping the entry would let `aether cluster destroy`
    /// report success while the firewall keeps billing. Fail the load instead, and say what to repair.
    private static final String JSON_WITH_UNUSABLE_FIREWALL_ID = """
        {
          "clusterName": "legacy-firewall-cluster",
          "configHash": "legacy-hash",
          "startedAt": "2026-04-01T00:00:00Z",
          "phases": {},
          "createdResources": [
            {"type": "CloudFirewall", "provider": "hetzner", "sourceName": "eu-1", "name": "aether-eu-1"}
          ],
          "provisionedNodeIds": [],
          "collectedAddresses": [],
          "clusterSecret": ""
        }
        """;

    @Test
    void fromJson_cloudFirewallWithoutFirewallId_failsLoudlyRatherThanDroppingTheLedgerEntry() {
        BootstrapState.fromJson(JSON_WITH_UNUSABLE_FIREWALL_ID)
                      .onSuccess(state -> fail("A firewall entry with no id must not load silently: " + state.createdResources()))
                      .onFailure(cause -> assertTrue(cause.message().contains("Unusable CloudFirewall entry")
                                                     && cause.message().contains("repair the entry's firewallId"),
                                                     () -> "the refusal must name the entry and the repair, was: " + cause.message()));
    }

    /// `name` is DERIVED from (cluster, source), so an unreadable one costs nothing to rebuild — and the
    /// entry, which is how the firewall gets reclaimed, survives. `sourceName` degrades to `default` for
    /// the same reason: neither field selects the resource, the id does.
    private static final String JSON_WITH_UNPARSEABLE_FIREWALL_NAME = """
        {
          "clusterName": "legacy-firewall-cluster",
          "configHash": "legacy-hash",
          "startedAt": "2026-04-01T00:00:00Z",
          "phases": {},
          "createdResources": [
            {"type": "CloudFirewall", "provider": "hetzner", "firewallId": "77", "sourceName": "eu-1", "name": "NOT A LABEL"}
          ],
          "provisionedNodeIds": [],
          "collectedAddresses": [],
          "clusterSecret": ""
        }
        """;

    @Test
    void fromJson_cloudFirewallWithUnparseableName_keepsTheEntryAndReDerivesTheName() {
        var state = BootstrapState.fromJson(JSON_WITH_UNPARSEABLE_FIREWALL_NAME)
                                   .onFailure(cause -> fail("An unreadable display name must not cost the ledger entry: " + cause.message()))
                                   .unwrap();

        assertEquals(1, state.createdResources().size());
        var firewall = (CreatedResource.CloudFirewall) state.createdResources().get(0);

        assertEquals("77", firewall.firewallId().value(),
                     "the id — the only handle destroy has — must survive");
        assertEquals("aether-legacy-firewall-cluster-eu-1", firewall.name().value(),
                     "an unreadable name is re-derived from (cluster, source), the same value the writer produces");
    }

    private static String ledgerWith(String resourceEntry) {
        return """
            {
              "clusterName": "ledger-guard-cluster",
              "configHash": "legacy-hash",
              "startedAt": "2026-04-01T00:00:00Z",
              "phases": {},
              "createdResources": [
                {"type": "ProvisionedVm", "provider": "hetzner", "resourceId": "vm-1", "sourceName": "eu-1", "role": "core"},
                %s
              ],
              "provisionedNodeIds": [],
              "collectedAddresses": [],
              "clusterSecret": ""
            }
            """.formatted(resourceEntry);
    }

    /// A ledger entry of a type this CLI does not know is a handle on a resource it cannot interpret.
    /// Skipping it is invisible (the file still parses) and the next append re-saves the file WITHOUT
    /// the entry, so an older CLI would erase a newer CLI's handle and destroy would report success
    /// while the resource keeps billing — the same reasoning that makes an unusable `CloudFirewall` fail
    /// the load. The failure must name the type so the operator knows which CLI wrote it.
    @Test
    void fromJson_unknownResourceType_failsNamingTheType() {
        BootstrapState.fromJson(ledgerWith("{\"type\": \"FutureThing\", \"resourceId\": \"x-9\"}"))
                      .onSuccess(state -> fail("An unknown ledger entry must not be dropped silently: "
                                               + state.createdResources()))
                      .onFailure(cause -> assertTrue(cause.message().contains("FutureThing"),
                                                     () -> "the refusal must name the type, was: " + cause.message()));
    }

    @Test
    void fromJson_resourceWithoutType_failsRatherThanDroppingTheEntry() {
        BootstrapState.fromJson(ledgerWith("{\"resourceId\": \"x-9\"}"))
                      .onSuccess(state -> fail("A typeless ledger entry must not be dropped silently: "
                                               + state.createdResources()))
                      .onFailure(cause -> assertTrue(cause.message().contains("missing"),
                                                     () -> "the refusal must say the type is missing, was: " + cause.message()));
    }

    /// An absent `resourceId` read by `asText()` becomes `""`, and `""` is what reaches `terminate("")`
    /// at destroy time: the entry parses, names nothing, and destroy cannot tell the operator why.
    @Test
    void fromJson_provisionedVmWithoutResourceId_failsNamingTheField() {
        BootstrapState.fromJson(ledgerWith("{\"type\": \"ProvisionedVm\", \"provider\": \"hetzner\", \"sourceName\": \"eu-1\", \"role\": \"core\"}"))
                      .onSuccess(state -> fail("A VM entry with no id must not load: " + state.createdResources()))
                      .onFailure(cause -> assertTrue(cause.message().contains("ProvisionedVm")
                                                     && cause.message().contains("resourceId"),
                                                     () -> "the refusal must name the type and the field, was: " + cause.message()));
    }

    @Test
    void fromJson_provisionedVmWithBlankResourceId_fails() {
        BootstrapState.fromJson(ledgerWith("{\"type\": \"ProvisionedVm\", \"provider\": \"hetzner\", \"resourceId\": \"  \", \"sourceName\": \"eu-1\", \"role\": \"core\"}"))
                      .onSuccess(state -> fail("A VM entry with a blank id must not load: " + state.createdResources()))
                      .onFailure(cause -> assertTrue(cause.message().contains("resourceId"),
                                                     () -> "the refusal must name the field, was: " + cause.message()));
    }

    @Test
    void fromJson_dockerContainerWithoutContainerId_failsNamingTheField() {
        BootstrapState.fromJson(ledgerWith("{\"type\": \"DockerContainer\", \"sourceName\": \"eu-1\"}"))
                      .onSuccess(state -> fail("A container entry with no id must not load: " + state.createdResources()))
                      .onFailure(cause -> assertTrue(cause.message().contains("DockerContainer")
                                                     && cause.message().contains("containerId"),
                                                     () -> "the refusal must name the type and the field, was: " + cause.message()));
    }

    /// Positive control for the three refusals above: the same fixture with well-formed known entries
    /// loads, so a red in the tests above comes from the entry under test and not from the fixture.
    @Test
    void fromJson_knownEntriesWithIds_loadAllOfThem() {
        var state = BootstrapState.fromJson(ledgerWith("{\"type\": \"DockerContainer\", \"containerId\": \"c-1\", \"sourceName\": \"eu-1\"}"))
                                   .onFailure(cause -> fail("Well-formed entries must load: " + cause.message()))
                                   .unwrap();

        assertEquals(2, state.createdResources().size());
    }

    /// The second consumer of the resource parser: the apply-state ledger shares `parseSingleResourceNode`
    /// and must refuse for the same reason.
    @Test
    void applyStateFromJson_unknownResourceType_fails() {
        var json = """
            {
              "clusterName": "ledger-guard-cluster",
              "configHash": "h",
              "startedAt": "2026-04-01T00:00:00Z",
              "currentWaveIndex": 0,
              "waves": ["PENDING", "PENDING", "PENDING"],
              "createdResources": [{"type": "FutureThing", "resourceId": "x-9"}],
              "destroyedNodeIds": []
            }
            """;

        ApplyState.fromJson(json)
                  .onSuccess(state -> fail("An unknown apply-ledger entry must not be dropped silently: "
                                           + state.createdResources()))
                  .onFailure(cause -> assertTrue(cause.message().contains("FutureThing"),
                                                 () -> "the refusal must name the type, was: " + cause.message()));
    }
}
