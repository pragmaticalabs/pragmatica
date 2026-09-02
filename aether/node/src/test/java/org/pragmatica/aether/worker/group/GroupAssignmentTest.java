// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.group;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// #592 — zone grouping must read the zone a node ADVERTISES, not one parsed out of its name.
///
/// Before this, `GroupAssignment` string-split the `NodeId` at its last dash, so the "zone" of `node-1`
/// was `"node"`. Every test here is written so it would FAIL against that behaviour: the node NAMES are
/// deliberately chosen to split into the same fragment while the advertised zones differ, and vice versa.
/// There was no coverage of this path at all, which is part of why a feature the catalog called
/// "zone-aware" was grouping by naming convention.
class GroupAssignmentTest {
    private static final String GROUP = "workers";
    private static final int MAX_GROUP = 10;

    private static NodeId node(String id) {
        return new NodeId(id);
    }

    private static Map<WorkerGroupId, List<NodeId>> groups(Map<NodeId, String> zones, List<NodeId> members) {
        return GroupAssignment.computeGroups(members, GROUP, MAX_GROUP, id -> zones.getOrDefault(id, "local"));
    }

    @Nested
    class ZoneSource {

        @Test
        void nodesWithIdenticalNamePrefix_butDifferentAdvertisedZones_areGroupedApart() {
            var a = node("node-1");
            var b = node("node-2");

            // Both names split to the same fragment ("node"), so the OLD derivation put them together.
            var result = groups(Map.of(a, "eu-west", b, "us-east"), List.of(a, b));

            assertThat(result.keySet())
                    .as("zone must come from what the node advertises, not from its name")
                    .containsExactlyInAnyOrder(WorkerGroupId.workerGroupId(GROUP, "eu-west"),
                                               WorkerGroupId.workerGroupId(GROUP, "us-east"));
            assertThat(result.get(WorkerGroupId.workerGroupId(GROUP, "eu-west"))).containsExactly(a);
            assertThat(result.get(WorkerGroupId.workerGroupId(GROUP, "us-east"))).containsExactly(b);
        }

        @Test
        void nodesWithUnrelatedNames_butTheSameAdvertisedZone_areGroupedTogether() {
            var a = node("alpha-7");
            var b = node("beta-9");

            // Names split to DIFFERENT fragments ("alpha"/"beta"), so the OLD derivation split these into
            // two zones purely on naming. One advertised zone means one group.
            var result = groups(Map.of(a, "eu-west", b, "eu-west"), List.of(a, b));

            assertThat(result)
                    .as("a shared advertised zone is one zone, however the nodes happen to be named")
                    .hasSize(1);
            assertThat(result.get(WorkerGroupId.workerGroupId(GROUP, "eu-west")))
                    .containsExactlyInAnyOrder(a, b);
        }

        @Test
        void nodesAdvertisingNoZone_collapseToTheSingleDefaultZone() {
            var a = node("node-1");
            var b = node("worker-abc-r7x2");

            // The pre-wiring reality: nothing sets AETHER_ZONE, so every node is zoneless. That must
            // degrade to ONE honest bucket, not to several confident-looking ones derived from names —
            // which is what the old split produced ("node" and "worker-abc").
            var result = groups(Map.of(), List.of(a, b));

            assertThat(result)
                    .as("zoneless nodes form a single default zone rather than name-derived pseudo-zones")
                    .hasSize(1);
            assertThat(result.get(WorkerGroupId.workerGroupId(GROUP, "local")))
                    .containsExactlyInAnyOrder(a, b);
        }
    }

    @Nested
    class Subgrouping {

        @Test
        void aZoneLargerThanMaxGroupSize_splitsWithinThatZoneOnly() {
            var members = List.of(node("n-1"), node("n-2"), node("n-3"), node("n-4"));
            var zones = Map.of(members.get(0), "eu",
                               members.get(1), "eu",
                               members.get(2), "eu",
                               members.get(3), "us");

            var result = GroupAssignment.computeGroups(members, GROUP, 2, id -> zones.getOrDefault(id, "local"));

            // eu has 3 members over a max of 2, so it splits; us has 1 and does not.
            assertThat(result.keySet().stream().filter(id -> id.zone().equals("eu")).toList())
                    .as("an oversized zone splits into subgroups")
                    .hasSize(2);
            assertThat(result.get(WorkerGroupId.workerGroupId(GROUP, "us")))
                    .as("a zone within the limit is untouched by another zone's split")
                    .hasSize(1);
        }
    }
}
