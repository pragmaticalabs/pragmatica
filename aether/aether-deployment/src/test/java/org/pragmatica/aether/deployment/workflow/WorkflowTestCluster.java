// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.workflow;

import java.util.List;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowRunValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;


/// A real [KVStore] with a committed leader record, so every transaction a [WorkflowLedger] builds is judged by the applier, not by
/// its shape. `apply` is serialised on the store, as consensus serialises commands: two concurrent submitters are ordered, never
/// interleaved inside one transaction.
final class WorkflowTestCluster {
    static final NodeId CORE_1 = NodeId.nodeId("core-1").unwrap();
    static final NodeId CORE_2 = NodeId.nodeId("core-2").unwrap();

    private final KVStore<StructuredKey, Object> store;
    private long term = 1;
    private int raws;

    WorkflowTestCluster() {
        store = new KVStore<>(MessageRouter.mutable(),
                              new Serializer() {
            @Override
            public <T> void write(ByteBuf buffer, T value) {}
        },
                              new Deserializer() {
            @Override
            public <T> T read(ByteBuf buffer) {
                                  return null;
                              }
        });
        lead(CORE_1);
    }

    /// Commit `node` as the leader, as an election would.
    void lead(NodeId node) {
        apply(List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(node, term++))));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    WorkflowLedger ledger(NodeId self) {
        return WorkflowLedger.workflowLedger((KVStore<AetherKey, AetherValue>)(KVStore<?, ?>) store,
                                             self,
                                             commands -> Promise.success(apply((List) commands)));
    }

    /// A ledger whose submission path is `apply` (to capture commands or fake consensus answers); reads still hit the real store.
    @SuppressWarnings({"unchecked", "rawtypes"})
    WorkflowLedger ledger(NodeId self, Fn1<Promise<List<Object>>, List<KVCommand<AetherKey>>> apply) {
        return WorkflowLedger.workflowLedger((KVStore<AetherKey, AetherValue>)(KVStore<?, ?>) store, self, apply);
    }

    List<Object> apply(List<KVCommand<StructuredKey>> commands) {
        synchronized (store) {
            return store.process(store.createBatch(commands));
        }
    }

    Option<WorkflowRunValue> run(String id) {
        return store.getTyped(new AetherKey.WorkflowRunKey(id), WorkflowRunValue.class);
    }

    Option<AetherValue.WorkflowClaimValue> claim(String key) {
        return store.getTyped(new AetherKey.WorkflowClaimKey(key), AetherValue.WorkflowClaimValue.class);
    }

    /// Overwrites a run with a raw put: test-only, to model a run committed by a node of another version.
    void putRaw(WorkflowRunValue run) {
        var key = new AetherKey.WorkflowRunKey(run.id());
        var expected = run(run.id()).map(Object.class::cast);

        apply(List.of(new KVCommand.LeaderTransaction<StructuredKey, Object>(key,
                                                                             "raw-" + raws++ + "-" + run.id(),
                                                                             store.getTyped(LeaderKey.INSTANCE,
                                                                                            LeaderValue.class)
                                                                                  .unwrap(),
                                                                             List.of(),
                                                                             List.of(new KVCommand.Mutation<>(key,
                                                                                                              expected,
                                                                                                              Option.some(run))))));
    }
}
