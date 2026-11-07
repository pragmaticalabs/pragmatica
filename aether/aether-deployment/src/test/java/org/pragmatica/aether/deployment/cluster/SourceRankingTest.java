// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.CommunityPlacement.Location;
import org.pragmatica.aether.config.cluster.CostClass;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.SourceFallback;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class SourceRankingTest {
    private static final long NOW = 1_000_000L;
    private static final Option<Map<String, Integer>> NO_COUNTS = Option.none();

    private static final List<String> POOL = List.of("a-hetzner", "b-docker", "c-aws", "d-ssh", "e-hetzner", "f-forge");

    private static final List<String> ZONES = Arrays.asList(null, "z1", "z2", "z3");

    // ---- identity: ranking with no new field set is exactly today's locationOrder() (review F3) ----
    @Test
    void rank_defaultProfiles_ordersExactlyAsLocationOrder() {
        var random = new Random(42);
        var sources = defaultSources();
        var mixedSeen = false;

        for (int i = 0; i < 300; i++) {
            var candidates = randomLocations(random, POOL);
            var expected = new ArrayList<>(candidates);

            expected.sort(PlacementReconciler.locationOrder());
            var literal = new ArrayList<>(candidates);

            literal.sort(Comparator.comparing(Location::source).thenComparing(location -> location.zone()
                                                                                                  .or("")));
            assertEquals(expected, literal, "oracle disagrees with the literal comparator");
            assertEquals(expected, SourceRanking.rank(candidates, sources, NodeRole.WORKER, none(), NO_COUNTS));
            mixedSeen |= candidates.stream().anyMatch(l -> l.source()
                                                            .equals("b-docker")) && candidates.stream()
                                                                                              .anyMatch(l -> l.source()
                                                                                                              .equals("a-hetzner"));
        }

        assertTrue(mixedSeen, "generator never produced a docker+cloud set");
    }

    @Test
    void rank_defaultProfiles_matchesGoldenLists() {
        var sources = defaultSources();
        // (1) name order puts the hetzner source first, lead-time order would put docker first
        assertEquals(List.of(loc("a-hetzner", null), loc("b-docker", null)),
                     SourceRanking.rank(List.of(loc("b-docker", null), loc("a-hetzner", null)),
                                        sources,
                                        NodeRole.WORKER,
                                        none(),
                                        NO_COUNTS));
        // (2) one source in zones z2, z1 plus an unzoned location of another source: source, then zone
        assertEquals(List.of(loc("a-hetzner", "z1"), loc("a-hetzner", "z2"), loc("b-docker", null)),
                     SourceRanking.rank(List.of(loc("a-hetzner", "z2"), loc("b-docker", null), loc("a-hetzner", "z1")),
                                        sources,
                                        NodeRole.WORKER,
                                        none(),
                                        NO_COUNTS));
        // (3) two cloud types with different lead times plus docker, names ordered against lead time
        assertEquals(List.of(loc("a-hetzner", null), loc("b-docker", null), loc("c-aws", null)),
                     SourceRanking.rank(List.of(loc("c-aws", null), loc("b-docker", null), loc("a-hetzner", null)),
                                        sources,
                                        NodeRole.WORKER,
                                        none(),
                                        NO_COUNTS));
    }

    // ---- INV-08: never outside the bindings ----
    @Test
    void rank_randomInputs_isSubsetOfCandidates() {
        var random = new Random(7);

        for (int i = 0; i < 300; i++) {
            var names = new ArrayList<>(POOL);

            names.add("x-gone");
            var candidates = randomLocations(random, names);
            var sources = randomSources(random);
            var ranked = SourceRanking.rank(candidates, sources, NodeRole.WORKER, none(), NO_COUNTS);

            assertTrue(candidates.containsAll(ranked));
            assertTrue(ranked.stream().allMatch(l -> sources.containsKey(l.source())));
        }
    }

    @Test
    void rank_undeclaredSource_isDroppedNeverDefaulted() {
        var ranked = SourceRanking.rank(List.of(loc("x-gone", null)),
                                        defaultSources(),
                                        NodeRole.WORKER,
                                        none(),
                                        NO_COUNTS);

        assertTrue(ranked.isEmpty());
    }

    @Test
    void rank_nothingEligible_isEmptyNotOtherDeclaredSources() {
        var ranked = SourceRanking.rank(List.of(loc("x-gone", null)),
                                        Map.of("b-docker", docker("b-docker")),
                                        NodeRole.WORKER,
                                        none(),
                                        NO_COUNTS);

        assertTrue(ranked.isEmpty());
    }

    // ---- INV-09: deterministic, no input-order dependence ----
    @Test
    void rank_shuffledInput_yieldsIdenticalOrder() {
        var random = new Random(11);
        var sources = randomSources(random);
        var candidates = randomLocations(random, POOL);
        var request = SelectionRequest.selectionRequest(Option.some(NOW + 100_000L), NOW);
        var first = SourceRanking.rank(candidates, sources, NodeRole.WORKER, request, NO_COUNTS);

        for (int i = 0; i < 100; i++) {
            var shuffled = new ArrayList<>(candidates);

            Collections.shuffle(shuffled, random);
            assertEquals(first, SourceRanking.rank(shuffled, sources, NodeRole.WORKER, request, NO_COUNTS));
        }
    }

    @Test
    void rank_sameSourceDifferentZones_orderedByZoneRegardlessOfInput() {
        var sources = defaultSources();
        var forward = List.of(loc("a-hetzner", "z1"), loc("a-hetzner", "z2"), loc("a-hetzner", "z3"));
        var backward = List.of(loc("a-hetzner", "z3"), loc("a-hetzner", "z2"), loc("a-hetzner", "z1"));

        assertEquals(forward, SourceRanking.rank(backward, sources, NodeRole.WORKER, none(), NO_COUNTS));
        assertEquals(forward, SourceRanking.rank(forward, sources, NodeRole.WORKER, none(), NO_COUNTS));
    }

    // ---- key 1: deadline ----
    @Test
    void rank_deadline_beatsPreferenceAndName() {
        var sources = Map.of("a-cloud", with(hetzner("a-cloud"), 1, CostClass.ONDEMAND), "b-docker", docker("b-docker"));
        var candidates = List.of(loc("a-cloud", null), loc("b-docker", null));
        var tight = SelectionRequest.selectionRequest(Option.some(NOW + 40_000L), NOW);

        assertEquals(List.of(loc("a-cloud", null), loc("b-docker", null)),
                     SourceRanking.rank(candidates, sources, NodeRole.WORKER, none(), NO_COUNTS));
        assertEquals(List.of(loc("b-docker", null), loc("a-cloud", null)),
                     SourceRanking.rank(candidates, sources, NodeRole.WORKER, tight, NO_COUNTS));
    }

    @Test
    void rank_deadlineExactlyAtLeadTime_meetsDeadline() {
        var sources = Map.of("a-cloud", hetzner("a-cloud"), "b-docker", docker("b-docker"));
        var candidates = List.of(loc("b-docker", null), loc("a-cloud", null));
        var boundary = SelectionRequest.selectionRequest(Option.some(NOW + 65_000L), NOW);
        var oneShort = SelectionRequest.selectionRequest(Option.some(NOW + 64_999L), NOW);

        assertEquals(loc("a-cloud", null),
                     SourceRanking.rank(candidates, sources, NodeRole.WORKER, boundary, NO_COUNTS).getFirst());
        assertEquals(loc("b-docker", null),
                     SourceRanking.rank(candidates, sources, NodeRole.WORKER, oneShort, NO_COUNTS).getFirst());
    }

    @Test
    void rank_everyCandidateMissesDeadline_stillRanksAll() {
        var sources = defaultSources();
        var candidates = List.of(loc("c-aws", null), loc("a-hetzner", null));
        var impossible = SelectionRequest.selectionRequest(Option.some(NOW), NOW);

        assertEquals(List.of(loc("a-hetzner", null), loc("c-aws", null)),
                     SourceRanking.rank(candidates, sources, NodeRole.WORKER, impossible, NO_COUNTS));
    }

    @Test
    void rank_noDeadline_ignoresLeadTime() {
        var sources = Map.of("a-cloud", hetzner("a-cloud"), "b-docker", docker("b-docker"));

        assertEquals(List.of(loc("a-cloud", null), loc("b-docker", null)),
                     SourceRanking.rank(List.of(loc("b-docker", null), loc("a-cloud", null)),
                                        sources,
                                        NodeRole.WORKER,
                                        none(),
                                        NO_COUNTS));
    }

    // ---- key 2: preference ----
    @Test
    void rank_lowerPreference_beatsCostClassAndName() {
        var sources = Map.of("a-reserved",
                             with(docker("a-reserved"), 100, CostClass.RESERVED),
                             "b-spot",
                             with(hetzner("b-spot"), 1, CostClass.SPOT));

        assertEquals(List.of(loc("b-spot", null), loc("a-reserved", null)),
                     SourceRanking.rank(List.of(loc("a-reserved", null), loc("b-spot", null)),
                                        sources,
                                        NodeRole.WORKER,
                                        none(),
                                        NO_COUNTS));
    }

    // ---- key 3: cost class ----
    @Test
    void rank_costClass_reservedThenOnDemandThenSpot() {
        var sources = Map.of("a-spot",
                             with(hetzner("a-spot"), 100, CostClass.SPOT),
                             "b-ondemand",
                             with(hetzner("b-ondemand"), 100, CostClass.ONDEMAND),
                             "c-reserved",
                             with(hetzner("c-reserved"), 100, CostClass.RESERVED));

        assertEquals(List.of(loc("c-reserved", null), loc("b-ondemand", null), loc("a-spot", null)),
                     SourceRanking.rank(List.of(loc("a-spot", null), loc("b-ondemand", null), loc("c-reserved", null)),
                                        sources,
                                        NodeRole.WORKER,
                                        none(),
                                        NO_COUNTS));
    }

    @Test
    void rank_free_sharesReservedRank_ownerQ4() {
        // Owner ruling Q4, know ea915292bf: FREE and RESERVED both rank 0, so name decides between them.
        var sources = Map.of("a-ondemand",
                             with(hetzner("a-ondemand"), 100, CostClass.ONDEMAND),
                             "b-free",
                             with(docker("b-free"), 100, CostClass.FREE),
                             "c-reserved",
                             with(docker("c-reserved"), 100, CostClass.RESERVED),
                             "d-spot",
                             with(hetzner("d-spot"), 100, CostClass.SPOT));
        var all = List.of(loc("d-spot", null), loc("c-reserved", null), loc("b-free", null), loc("a-ondemand", null));
        var reservedFirstByName = Map.of("a-x",
                                         with(docker("a-x"), 100, CostClass.RESERVED),
                                         "b-x",
                                         with(docker("b-x"), 100, CostClass.FREE));
        var freeFirstByName = Map.of("a-x",
                                     with(docker("a-x"), 100, CostClass.FREE),
                                     "b-x",
                                     with(docker("b-x"), 100, CostClass.RESERVED));
        var pair = List.of(loc("b-x", null), loc("a-x", null));

        assertEquals(List.of(loc("b-free", null), loc("c-reserved", null), loc("a-ondemand", null), loc("d-spot", null)),
                     SourceRanking.rank(all, sources, NodeRole.WORKER, none(), NO_COUNTS));
        assertEquals(List.of(loc("a-x", null), loc("b-x", null)),
                     SourceRanking.rank(pair, reservedFirstByName, NodeRole.WORKER, none(), NO_COUNTS));
        assertEquals(List.of(loc("a-x", null), loc("b-x", null)),
                     SourceRanking.rank(pair, freeFirstByName, NodeRole.WORKER, none(), NO_COUNTS));
    }

    // ---- key 4: source name, then zone ----
    @Test
    void rank_allElseEqual_sourceNameDecides() {
        var sources = Map.of("a-docker", docker("a-docker"), "b-docker", docker("b-docker"));

        assertEquals(List.of(loc("a-docker", "z2"), loc("b-docker", "z1")),
                     SourceRanking.rank(List.of(loc("b-docker", "z1"), loc("a-docker", "z2")),
                                        sources,
                                        NodeRole.WORKER,
                                        none(),
                                        NO_COUNTS));
    }

    @Test
    void rank_sameSource_unzonedBeforeZoned() {
        var sources = Map.of("a-docker", docker("a-docker"));

        assertEquals(List.of(loc("a-docker", null), loc("a-docker", "z1")),
                     SourceRanking.rank(List.of(loc("a-docker", "z1"), loc("a-docker", null)),
                                        sources,
                                        NodeRole.WORKER,
                                        none(),
                                        NO_COUNTS));
    }

    // ---- filters ----
    @Test
    void rank_sourceWithoutRole_isDropped() {
        var workerOnly = withRoles(docker("a-docker"), Set.of(NodeRole.CORE));
        var sources = Map.of("a-docker", workerOnly, "b-docker", docker("b-docker"));

        assertEquals(List.of(loc("b-docker", null)),
                     SourceRanking.rank(List.of(loc("a-docker", null), loc("b-docker", null)),
                                        sources,
                                        NodeRole.WORKER,
                                        none(),
                                        NO_COUNTS));
    }

    @Test
    void rank_maxNodesReached_dropsSourceBelowCapKeeps() {
        var capped = withMaxNodes(docker("a-docker"), 3);
        var sources = Map.of("a-docker", capped, "b-docker", docker("b-docker"));
        var candidates = List.of(loc("a-docker", null), loc("b-docker", null));

        assertEquals(List.of(loc("b-docker", null)),
                     SourceRanking.rank(candidates,
                                        sources,
                                        NodeRole.WORKER,
                                        none(),
                                        Option.some(Map.of("a-docker", 3))));
        assertEquals(List.of(loc("a-docker", null), loc("b-docker", null)),
                     SourceRanking.rank(candidates,
                                        sources,
                                        NodeRole.WORKER,
                                        none(),
                                        Option.some(Map.of("a-docker", 2))));
    }

    @Test
    void rank_maxNodesSetButNoCountsSupplied_filterNotApplied() {
        var sources = Map.of("a-docker", withMaxNodes(docker("a-docker"), 0));

        assertEquals(List.of(loc("a-docker", null)),
                     SourceRanking.rank(List.of(loc("a-docker", null)), sources, NodeRole.WORKER, none(), NO_COUNTS));
    }

    @Test
    void rank_maxNodesSetCountMissing_countsAsZero() {
        var sources = Map.of("a-docker", withMaxNodes(docker("a-docker"), 1));

        assertEquals(List.of(loc("a-docker", null)),
                     SourceRanking.rank(List.of(loc("a-docker", null)),
                                        sources,
                                        NodeRole.WORKER,
                                        none(),
                                        Option.some(Map.of())));
    }

    @Test
    void rank_maxNodesAbsent_isUnbounded() {
        var sources = Map.of("a-docker", docker("a-docker"));

        assertEquals(List.of(loc("a-docker", null)),
                     SourceRanking.rank(List.of(loc("a-docker", null)),
                                        sources,
                                        NodeRole.WORKER,
                                        none(),
                                        Option.some(Map.of("a-docker", 1_000_000))));
    }

    @Test
    void rank_doesNotMutateCandidates() {
        var candidates = new ArrayList<>(List.of(loc("b-docker", null), loc("a-hetzner", null)));
        var before = List.copyOf(candidates);

        SourceRanking.rank(candidates, defaultSources(), NodeRole.WORKER, none(), NO_COUNTS);
        assertEquals(before, candidates);
    }

    // ---- fixtures ----
    private static SelectionRequest none() {
        return SelectionRequest.selectionRequest(Option.none(), NOW);
    }

    private static Location loc(String source, String zone) {
        return new Location(source, Option.option(zone), 0, 1);
    }

    private static List<Location> randomLocations(Random random, List<String> names) {
        var all = new ArrayList<Location>();

        for (var name : names) {
            for (var zone : ZONES) {
                all.add(loc(name, zone));
            }
        }

        Collections.shuffle(all, random);

        return new ArrayList<>(all.subList(0, 1 + random.nextInt(8)));
    }

    private static Map<String, SourceProfile> defaultSources() {
        var sources = new HashMap<String, SourceProfile>();

        sources.put("a-hetzner", hetzner("a-hetzner"));
        sources.put("b-docker", docker("b-docker"));
        sources.put("c-aws", aws("c-aws"));
        sources.put("d-ssh", ssh("d-ssh"));
        sources.put("e-hetzner", hetzner("e-hetzner"));
        sources.put("f-forge", forge("f-forge"));

        return sources;
    }

    private static Map<String, SourceProfile> randomSources(Random random) {
        var sources = new HashMap<String, SourceProfile>();
        var costs = CostClass.values();

        for (var entry : defaultSources().entrySet()) {
            if (random.nextInt(5) > 0) {
                var profile = with(entry.getValue(), 1 + random.nextInt(3), costs[random.nextInt(costs.length)]);

                sources.put(entry.getKey(),
                            random.nextInt(4) == 0
                            ? withRoles(profile, Set.of(NodeRole.CORE))
                            : profile);
            }
        }

        return sources;
    }

    private static SourceProfile hetzner(String name) {
        return profile(name, SourceType.CLOUD, Option.some(CloudProviderName.HETZNER));
    }

    private static SourceProfile aws(String name) {
        return profile(name, SourceType.CLOUD, Option.some(CloudProviderName.AWS));
    }

    private static SourceProfile docker(String name) {
        return profile(name, SourceType.DOCKER, Option.none());
    }

    private static SourceProfile ssh(String name) {
        return profile(name, SourceType.SSH, Option.none());
    }

    private static SourceProfile forge(String name) {
        return profile(name, SourceType.FORGE, Option.none());
    }

    private static SourceProfile profile(String name, SourceType type, Option<CloudProviderName> provider) {
        return build(name,
                     type,
                     provider,
                     rolesOf(Set.of(NodeRole.CORE, NodeRole.WORKER, NodeRole.SPOT)),
                     Option.none(),
                     SourceProfile.DEFAULT_PREFERENCE,
                     CostClass.ONDEMAND);
    }

    private static SourceProfile with(SourceProfile base, int preference, CostClass costClass) {
        return build(base.name().value(),
                     base.type(),
                     base.provider(),
                     base.roles(),
                     base.maxNodes(),
                     preference,
                     costClass);
    }

    private static SourceProfile withRoles(SourceProfile base, Set<NodeRole> roles) {
        return build(base.name().value(),
                     base.type(),
                     base.provider(),
                     rolesOf(roles),
                     base.maxNodes(),
                     base.preference(),
                     base.costClass());
    }

    private static SourceProfile withMaxNodes(SourceProfile base, int maxNodes) {
        return build(base.name().value(),
                     base.type(),
                     base.provider(),
                     base.roles(),
                     Option.some(maxNodes),
                     base.preference(),
                     base.costClass());
    }

    private static Map<NodeRole, RoleSubTable> rolesOf(Set<NodeRole> roles) {
        var map = new EnumMap<NodeRole, RoleSubTable>(NodeRole.class);

        roles.forEach(role -> map.put(role,
                                      RoleSubTable.roleSubTable(Option.none(),
                                                                Option.none(),
                                                                Option.none(),
                                                                Option.none(),
                                                                "runtime")));

        return map;
    }

    private static SourceProfile build(String name,
                                       SourceType type,
                                       Option<CloudProviderName> provider,
                                       Map<NodeRole, RoleSubTable> roles,
                                       Option<Integer> maxNodes,
                                       int preference,
                                       CostClass costClass) {
        Map<NodeRole, TimeSpan> noLeadTimes = Map.of();

        return new SourceProfile(new SourceName(name),
                                 type,
                                 provider,
                                 Option.none(),
                                 Option.none(),
                                 Option.none(),
                                 List.of(),
                                 Option.none(),
                                 Option.none(),
                                 Option.none(),
                                 LoadBalancerMode.NONE,
                                 List.of(),
                                 Option.none(),
                                 Map.of(),
                                 roles,
                                 List.of(),
                                 Option.none(),
                                 Option.none(),
                                 noLeadTimes,
                                 maxNodes,
                                 preference,
                                 costClass,
                                 SourceFallback.ALLOW);
    }
}
