// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.deployment.cluster.HierarchyStateWriter;
import org.pragmatica.aether.deployment.cluster.IdentityRegistrar;
import org.pragmatica.aether.node.IdentityRecordPersister;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeIdentityKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeIdentityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.consensus.net.IdentityFacts;
import org.pragmatica.consensus.net.IdentityLedger;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;

/// ID-1b wiring: the identity record's two routes, as `membershipEdges` registers them, reach their handlers THROUGH the router. A unit test
/// of the handler alone cannot pin this (a route that is never registered, or registered for the wrong message class, leaves the handler
/// green), so these tests deliver the real notification and the real membership decision to the entries.
class MembershipAssemblyIdentityRecordTest {
    private static final NodeId NODE = NodeId.nodeId("heal-7").unwrap();
    private static final NodeIdentityKey KEY = NodeIdentityKey.nodeIdentityKey(NODE);
    private static final ProcessIncarnation PROCESS = new ProcessIncarnation(1L, 0x5EEDFACE0BADL);

    @TempDir
    Path directory;

    private static MessageRouter.MutableRouter router(List<MessageRouter.Entry<?>> entries) {
        var router = MessageRouter.mutable();

        entries.forEach(entry -> entry.entries().forEach(tuple -> router.addRoute(tuple.first(), tuple.last())));

        return router;
    }

    @Test
    void identityRecordEntries_deliversAnAppliedRetirementToTheLedger() throws IOException {
        var file = directory.resolve("identity").resolve("ledger");
        var tokens = BootTokens.bootTokens(0L);
        var ledger = IdentityLedger.identityLedger(file, IdentityLedger.DurableWriter.fileOps());

        tokens.attachLedger(ledger);
        tokens.preload(ledger.open(true).facts(), false);
        var entries = MembershipAssembly.identityRecordEntries(Option.some(IdentityRecordPersister.identityRecordPersister(ledger, tokens)), Option.none(), () -> true);
        var value = new NodeIdentityValue(IdentityState.TERMINATED, 1L, Option.some(PROCESS), "core", Option.none(), Option.some(IdentityCause.REPLACED), 5L, Option.none());

        router(entries).route(new ValuePut<AetherKey, AetherValue>(new KVCommand.Put<>(KEY, value), Option.none()));

        assertThat(IdentityFacts.parse(Files.readString(file)).unwrap().terminated()).containsEntry(NODE, "REPLACED");
    }

    @Test
    void identityRecordEntries_routesTheAdmissionSignalToTheRegistrar_onlyOnTheActiveLeader() {
        var commits = new ArrayList<KVCommand<AetherKey>>();
        var writer = HierarchyStateWriter.hierarchyStateWriter(Option::none, _ -> Option.none(), commands -> {
            commits.addAll(commands);

            return Promise.success(List.of());
        });
        var registrar = IdentityRegistrar.identityRegistrar(writer, _ -> 0x5EEDFACE0BADL, () -> 1L);
        var leader = new AtomicBoolean(false);
        var router = router(MembershipAssembly.identityRecordEntries(Option.none(), Option.some(registrar), leader::get));
        var joined = new MembershipDecision.NodeJoined(NODE, List.of(), 1L, HlcTimestamp.ZERO);

        router.route(joined);
        assertThat(registrar.failures()).as("a follower does not record the signal").isZero();

        leader.set(true);
        router.route(joined);
        assertThat(registrar.failures()).as("the leader reached the registrar (this writer has no authority, so the commit is refused and counted)").isEqualTo(1L);
    }

    @Test
    void identityRecordEntries_registersNothing_whenNeitherEdgeExists() {
        assertThat(MembershipAssembly.identityRecordEntries(Option.none(), Option.none(), () -> true)).isEmpty();
    }
}
