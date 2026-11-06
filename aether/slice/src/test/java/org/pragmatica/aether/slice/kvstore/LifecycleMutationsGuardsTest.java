// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.CapacityLedgerKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeIdentityKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityFacet;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityLedgerValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.FreedCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityFacet;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.cluster.state.kvstore.KVCommand.Mutation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

/// What the first O3-1 verification found unpinned (v-2514): the uncounted hold, the inventory flag on `free` and `observe`, and the
/// `TERMINATED` row of the legal-pair table (q4 7.1.2: a terminated node id holds `FREED` capacity or none). Pure: each builder's mutations are
/// evaluated against the value it was built from, with no store.
class LifecycleMutationsGuardsTest {
    private static final NodeId NODE = NodeId.nodeId("guard-1").unwrap();
    private static final ProcessIncarnation PROCESS = new ProcessIncarnation(1L, 7L);
    private static final String SOURCE = "east";
    private static final String BINDING = "binding";

    private record State(Option<NodeLifecycleValue> record, Option<CapacityLedgerValue> ledger) {
        String pair() {
            return record.flatMap(NodeLifecycleValue::identity).map(facet -> facet.state().name()).or("-")
                   + "+"
                   + record.flatMap(NodeLifecycleValue::capacity).map(facet -> facet.phase().name()).or("-");
        }

        String key() {
            return pair() + "|" + ledger.map(CapacityLedgerValue::allocated).or(-1);
        }
    }

    private static Optional<State> evaluated(State from, Result<List<Mutation<AetherKey, AetherValue>>> built) {
        var next = new ArrayList<State>();

        built.onSuccess(mutations -> next.add(applied(from, mutations)));

        return next.stream().findFirst();
    }

    private static State applied(State from, List<Mutation<AetherKey, AetherValue>> mutations) {
        var record = from.record;
        var ledger = from.ledger;

        for (var mutation : mutations) {
            if (mutation.key() instanceof NodeIdentityKey) {
                record = mutation.replacement().map(NodeLifecycleValue.class::cast);
            } else if (mutation.key() instanceof CapacityLedgerKey) {
                ledger = mutation.replacement().map(CapacityLedgerValue.class::cast);
            } else {
                throw new AssertionError("a builder wrote a foreign key " + mutation.key());
            }
        }

        return new State(record, ledger);
    }

    private static Option<CapacityLedgerValue> ledger(int allocated, long version, boolean complete) {
        return Option.some(new CapacityLedgerValue(allocated, version, complete));
    }

    private static NodeLifecycleValue hold(String binding, CapacityPhase phase) {
        return new NodeLifecycleValue(Option.none(),
                                      Option.some(new CapacityFacet(SOURCE,
                                                                    binding,
                                                                    "worker",
                                                                    phase,
                                                                    phase == CapacityPhase.FREED ? Option.some(FreedCause.REFUSED) : Option.none(),
                                                                    phase == CapacityPhase.FREED ? Option.some(5L) : Option.none())));
    }

    private static NodeLifecycleValue admittedWith(CapacityPhase phase) {
        var capacity = hold(BINDING, phase).capacity();

        return new NodeLifecycleValue(Option.some(new IdentityFacet(IdentityState.ADMITTED, 1L, PROCESS, "core", Option.none(), 10L)), capacity);
    }

    private static NodeLifecycleValue terminated(Option<AetherValue.CapacityFacet> capacity) {
        return new NodeLifecycleValue(Option.some(new IdentityFacet(IdentityState.TERMINATED, 1L, PROCESS, "core", Option.some(IdentityCause.GONE), 10L)), capacity);
    }

    @Test
    void free_ofAnUncountedHold_writesTheTombstone_andMovesNoCount() {
        var current = Option.some(hold(LifecycleMutations.UNCOUNTED_BINDING, CapacityPhase.OBSERVED));
        var counted = ledger(5, 10L, true);
        var built = LifecycleMutations.free(NODE, current, counted, FreedCause.EXTERNAL_RELEASED, 9L);

        assertThat(built.isSuccess()).isTrue();
        built.onSuccess(mutations -> assertThat(mutations).as("the record only: no ledger mutation").hasSize(1));
        var after = evaluated(new State(current, counted), built).orElseThrow();

        assertThat(after.pair()).isEqualTo("-+FREED");
        assertThat(after.ledger).as("the count is untouched").isEqualTo(counted);
        assertThat(LifecycleMutations.free(NODE, current, Option.none(), FreedCause.EXTERNAL_RELEASED, 9L).isSuccess()).as("and needs no ledger, as releaseExternal needs none").isTrue();
    }

    @Test
    void free_ofACountedHold_keepsTheInventoryFlagItRead() {
        var current = Option.some(hold(BINDING, CapacityPhase.OBSERVED));
        var incomplete = ledger(3, 4L, false);
        var after = evaluated(new State(current, incomplete), LifecycleMutations.free(NODE, current, incomplete, FreedCause.TERMINATED, 9L)).orElseThrow();

        assertThat(after.ledger).isEqualTo(ledger(2, 5L, false));
    }

    @Test
    void observe_confirmingAHold_keepsTheInventoryFlagItRead() {
        var current = Option.some(hold(BINDING, CapacityPhase.DISPATCHED));
        var incomplete = ledger(3, 4L, false);
        var after = evaluated(new State(current, incomplete), LifecycleMutations.observe(NODE, current, incomplete, SOURCE, BINDING)).orElseThrow();

        assertThat(after.pair()).isEqualTo("-+OBSERVED");
        assertThat(after.ledger).isEqualTo(ledger(3, 5L, false));
    }

    @Test
    void terminate_isRefused_whileTheCapacityIsLive_andAllowedOnceFreedOrAbsent() {
        for (var phase : new CapacityPhase[] {CapacityPhase.DISPATCHED, CapacityPhase.OBSERVED, CapacityPhase.RELEASED, CapacityPhase.UNKNOWN}) {
            assertThat(LifecycleMutations.terminate(NODE, Option.some(admittedWith(phase)), IdentityCause.GONE, 20L).isFailure()).as("capacity " + phase).isTrue();
        }

        assertThat(LifecycleMutations.terminate(NODE, Option.some(admittedWith(CapacityPhase.FREED)), IdentityCause.GONE, 20L).isSuccess()).isTrue();
        var identityOnly = new NodeLifecycleValue(admittedWith(CapacityPhase.FREED).identity(), Option.none());

        assertThat(LifecycleMutations.terminate(NODE, Option.some(identityOnly), IdentityCause.GONE, 20L).isSuccess()).isTrue();
    }

    @Test
    void aTerminatedNodeId_isNeverReservedOrObserved() {
        var identityOnly = Option.some(terminated(Option.none()));
        var freed = Option.some(terminated(hold(BINDING, CapacityPhase.FREED).capacity()));
        var room = ledger(0, 1L, true);

        assertThat(LifecycleMutations.reserve(NODE, identityOnly, room, 5, SOURCE, BINDING, "worker").isFailure()).as("reserve over identity only").isTrue();
        assertThat(LifecycleMutations.observe(NODE, identityOnly, room, SOURCE, BINDING).isFailure()).as("observe over identity only").isTrue();
        assertThat(LifecycleMutations.observe(NODE, freed, room, SOURCE, BINDING).isFailure()).as("observe over a tombstone").isTrue();
        assertThat(LifecycleMutations.reserve(NODE, freed, room, 5, SOURCE, BINDING, "worker").isFailure()).as("reserve over a tombstone").isTrue();
    }

    @Test
    void noPairReachableByTheBuilders_isTerminatedWithLiveCapacity() {
        var reached = new TreeSet<String>();
        var visited = new HashSet<String>();
        var frontier = new ArrayDeque<State>();

        frontier.add(new State(Option.none(), ledger(0, 1L, true)));

        while (!frontier.isEmpty()) {
            var from = frontier.poll();

            if (!visited.add(from.key())) {
                continue;
            }

            reached.add(from.pair());
            var built = new ArrayList<Result<List<Mutation<AetherKey, AetherValue>>>>();

            built.add(LifecycleMutations.reserve(NODE, from.record, from.ledger, 100, SOURCE, BINDING, "worker"));
            built.add(LifecycleMutations.observe(NODE, from.record, from.ledger, SOURCE, BINDING));
            built.add(LifecycleMutations.refuse(NODE, from.record, SOURCE, BINDING));
            built.add(LifecycleMutations.admit(NODE, from.record, PROCESS, "core", 9L));
            built.add(LifecycleMutations.readmit(NODE, from.record, PROCESS, 9L));
            built.add(LifecycleMutations.depart(NODE, from.record, 9L));

            for (var cause : FreedCause.values()) {
                built.add(LifecycleMutations.free(NODE, from.record, from.ledger, cause, 9L));
            }

            for (var cause : IdentityCause.values()) {
                built.add(LifecycleMutations.terminate(NODE, from.record, cause, 9L));
            }

            built.forEach(result -> evaluated(from, result).filter(next -> next.ledger.map(CapacityLedgerValue::allocated).or(0) <= 3)
                                                           .ifPresent(frontier::add));
        }

        assertThat(reached).as("control: the walk reaches the terminated rows and the live pairs").contains("TERMINATED+FREED", "TERMINATED+-", "ADMITTED+OBSERVED", "-+DISPATCHED");
        assertThat(reached).as("pairs reachable by the builders").noneMatch(pair -> pair.startsWith("TERMINATED+") && !pair.equals("TERMINATED+FREED") && !pair.equals("TERMINATED+-"));
    }
}
