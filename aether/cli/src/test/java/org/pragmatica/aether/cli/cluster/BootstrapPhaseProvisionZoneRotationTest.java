// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.cluster.BootstrapPhaseProvision.ZoneProvisioner;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Result.success;


/// Unit tests for the per-role-group zone-rotation core of [BootstrapPhaseProvision].
/// These exercise the rotation cursor against a recording [ZoneProvisioner] seam, with no
/// real compute provider, KV-Store, or BootstrapContext — the rotation logic is deterministic.
class BootstrapPhaseProvisionZoneRotationTest {

    private static final List<String> ZONES = List.of("fsn1", "nbg1", "hel1");

    /// Records the zone of every attempt and returns scripted outcomes per zone.
    private static final class RecordingProvisioner implements ZoneProvisioner {
        private final Map<String, Result<ProvisionedNode>> scriptByZone;
        private final List<String> attemptedZones = new ArrayList<>();
        private int callCount;

        private RecordingProvisioner(Map<String, Result<ProvisionedNode>> scriptByZone) {
            this.scriptByZone = scriptByZone;
        }

        @Override
        public Result<ProvisionedNode> provisionInZone(String nodeId, int globalIndex, String zone) {
            callCount++;
            attemptedZones.add(zone);

            return scriptByZone.getOrDefault(zone, success(node(nodeId, zone)));
        }
    }

    private static ProvisionedNode node(String nodeId, String zone) {
        return ProvisionedNode.provisionedNode(nodeId, "server-" + zone, "10.0.0.1");
    }

    private static Cause capacity(String zone) {
        return EnvironmentError.capacityUnavailable(zone, new RuntimeException("error during placement"));
    }

    private static Cause auth() {
        return EnvironmentError.provisionFailed(new RuntimeException("unauthorized"));
    }

    @Nested
    class ZoneRotation {

        @Test
        void rotateZonesForRoleGroup_rotatesToNextZone_whenFirstZoneCapacityUnavailable() {
            var provisioner = new RecordingProvisioner(Map.of("fsn1", capacity("fsn1").result()));

            var result = BootstrapPhaseProvision.rotateZonesForRoleGroup("src", NodeRole.CORE, 1, 0, ZONES, provisioner);

            result.onFailure(cause -> assertThat(cause).as("rotation to nbg1 should succeed").isNull())
                  .onSuccess(nodes -> assertThat(nodes).hasSize(1));
            assertThat(provisioner.attemptedZones)
                    .as("first attempt fsn1 (full), rotated to nbg1 (capacity)")
                    .containsExactly("fsn1", "nbg1");
            result.onSuccess(nodes -> assertThat(nodes.getFirst().serverId()).isEqualTo("server-nbg1"));
        }

        @Test
        void rotateZonesForRoleGroup_nextNodeStartsAtWorkingZone_afterRotation() {
            // fsn1 is permanently full; nbg1 has capacity. Node 0 rotates fsn1->nbg1,
            // node 1 must START at nbg1 (cursor advanced past the known-full fsn1).
            var provisioner = new RecordingProvisioner(Map.of("fsn1", capacity("fsn1").result()));

            var result = BootstrapPhaseProvision.rotateZonesForRoleGroup("src", NodeRole.CORE, 2, 0, ZONES, provisioner);

            result.onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(nodes -> assertThat(nodes).hasSize(2));
            assertThat(provisioner.attemptedZones)
                    .as("node0: fsn1(full)->nbg1(ok); node1 starts at nbg1 directly (fsn1 not re-tried)")
                    .containsExactly("fsn1", "nbg1", "nbg1");
        }
    }

    @Nested
    class Exhaustion {

        @Test
        void rotateZonesForRoleGroup_fails_whenAllZonesCapacityUnavailable() {
            var provisioner = new RecordingProvisioner(Map.of("fsn1", capacity("fsn1").result(),
                                                              "nbg1", capacity("nbg1").result(),
                                                              "hel1", capacity("hel1").result()));

            var result = BootstrapPhaseProvision.rotateZonesForRoleGroup("src", NodeRole.CORE, 1, 0, ZONES, provisioner);

            result.onSuccess(nodes -> assertThat(nodes).as("must not provision when all zones full").isNull())
                  .onFailure(cause -> assertThat(cause.message())
                          .contains("all configured zones exhausted for source src")
                          .contains("fsn1, nbg1, hel1"));
            assertThat(provisioner.callCount)
                    .as("each zone attempted exactly once, then bail (no infinite loop)")
                    .isEqualTo(3);
            assertThat(provisioner.attemptedZones).containsExactly("fsn1", "nbg1", "hel1");
        }
    }

    @Nested
    class NonCapacityErrors {

        @Test
        void rotateZonesForRoleGroup_failsImmediately_onNonCapacityError() {
            var provisioner = new RecordingProvisioner(Map.of("fsn1", auth().result()));

            var result = BootstrapPhaseProvision.rotateZonesForRoleGroup("src", NodeRole.CORE, 1, 0, ZONES, provisioner);

            result.onSuccess(nodes -> assertThat(nodes).isNull())
                  .onFailure(cause -> assertThat(cause).isInstanceOf(EnvironmentError.ProvisionFailed.class));
            assertThat(provisioner.callCount)
                    .as("non-capacity error must NOT rotate — provider called exactly once")
                    .isEqualTo(1);
            assertThat(provisioner.attemptedZones).containsExactly("fsn1");
        }
    }

    @Nested
    class BackwardCompat {

        @Test
        void rotateZonesForRoleGroup_singleZone_attemptsThatZoneOnce() {
            var provisioner = new RecordingProvisioner(Map.of());

            var result = BootstrapPhaseProvision.rotateZonesForRoleGroup("src",
                                                                         NodeRole.CORE,
                                                                         1,
                                                                         0,
                                                                         List.of("nbg1"),
                                                                         provisioner);

            result.onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(nodes -> assertThat(nodes).hasSize(1));
            assertThat(provisioner.attemptedZones)
                    .as("single-zone config behaves exactly as before: one attempt, correct location")
                    .containsExactly("nbg1");
        }

        @Test
        void rotateZonesForRoleGroup_noZones_attemptsOnceWithDefault() {
            var provisioner = new RecordingProvisioner(Map.of());

            var result = BootstrapPhaseProvision.rotateZonesForRoleGroup("src",
                                                                         NodeRole.CORE,
                                                                         1,
                                                                         0,
                                                                         List.of(),
                                                                         provisioner);

            result.onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(nodes -> assertThat(nodes).hasSize(1));
            assertThat(provisioner.attemptedZones)
                    .as("empty zone list → single attempt with empty zone (provider default, no ZoneHint)")
                    .containsExactly("");
            assertThat(provisioner.callCount).isEqualTo(1);
        }
    }
}
