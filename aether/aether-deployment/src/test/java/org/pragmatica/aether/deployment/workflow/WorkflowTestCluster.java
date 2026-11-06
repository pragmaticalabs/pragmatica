// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.workflow;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowRunValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.warning.OperatorWarningSink;

import io.netty.buffer.ByteBuf;


/// A real [KVStore] with a committed leader record, so every transaction a [WorkflowLedger] builds is judged by the applier, not by
/// its shape. `apply` is serialised on the store, as consensus serialises commands: two concurrent submitters are ordered, never
/// interleaved inside one transaction. The store delivers inline to a [WorkflowRunIndex] through the same `WorkflowRunKey` put/remove
/// route production wiring would register ([WorkflowRunIndex#absorbPut], [WorkflowRunIndex#absorbRemove]), so a ledger's `runs()`
/// and every announced transition come from the applied records.
final class WorkflowTestCluster {
    static final NodeId CORE_1 = NodeId.nodeId("core-1").unwrap();
    static final NodeId CORE_2 = NodeId.nodeId("core-2").unwrap();

    private final KVStore<StructuredKey, Object> store;
    private final WorkflowRunIndex index = WorkflowRunIndex.workflowRunIndex();
    private long term = 1;
    private int raws;

    WorkflowTestCluster() {
        var router = MessageRouter.mutable();

        store = new KVStore<>(router,
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
        route(router);
        lead(CORE_1);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void route(MessageRouter.MutableRouter router) {
        var kvRouter = KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                           .onPut(AetherKey.WorkflowRunKey.class,
                                                  (ValuePut<AetherKey.WorkflowRunKey, WorkflowRunValue> put) -> WorkflowRunIndex.absorbPut(index,
                                                                                                                                     store::isReplaying,
                                                                                                                                     put))
                                           .onRemove(AetherKey.WorkflowRunKey.class,
                                                     (ValueRemove<AetherKey.WorkflowRunKey, WorkflowRunValue> remove) -> WorkflowRunIndex.absorbRemove(index,
                                                                                                                                                remove))
                                           .build();

        kvRouter.asRouteEntries()
                .forEach(entry -> entry.entries()
                                       .forEach(route -> router.addRoute((Class) route.first(), (Consumer) route.last())));
    }

    /// The run index the store's route feeds.
    WorkflowRunIndex index() {
        return index;
    }

    /// Announce every applied transition to `warnings`, as the cluster-events owner when `owns` says so.
    Unit announceTo(BooleanSupplier owns, OperatorWarningSink warnings) {
        return index.onTransition(WorkflowRunIndex.announcer(owns, warnings));
    }

    /// Commit `node` as the leader, as an election would.
    void lead(NodeId node) {
        apply(List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(node, term++))));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    WorkflowLedger ledger(NodeId self) {
        return WorkflowLedger.workflowLedger((KVStore<AetherKey, AetherValue>)(KVStore<?, ?>) store,
                                             index,
                                             self,
                                             commands -> Promise.success(apply((List) commands)));
    }

    /// A ledger whose submission path is `apply` (to capture commands or fake consensus answers); reads still hit the real store.
    @SuppressWarnings({"unchecked", "rawtypes"})
    WorkflowLedger ledger(NodeId self, Fn1<Promise<List<Object>>, List<KVCommand<AetherKey>>> apply) {
        return WorkflowLedger.workflowLedger((KVStore<AetherKey, AetherValue>)(KVStore<?, ?>) store, index, self, apply);
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
        mutateRaw(run.id(), Option.some(run));
    }

    /// Deletes a run with a raw leader-transaction removal: test-only, to model a deletion the ledger does not issue yet.
    void removeRaw(String id) {
        mutateRaw(id, Option.none());
    }

    private void mutateRaw(String id, Option<Object> next) {
        var key = new AetherKey.WorkflowRunKey(id);
        var expected = run(id).map(Object.class::cast);

        apply(List.of(new KVCommand.LeaderTransaction<StructuredKey, Object>(key,
                                                                             "raw-" + raws++ + "-" + id,
                                                                             store.getTyped(LeaderKey.INSTANCE,
                                                                                            LeaderValue.class)
                                                                                  .unwrap(),
                                                                             List.of(),
                                                                             List.of(new KVCommand.Mutation<>(key,
                                                                                                              expected,
                                                                                                              next)))));
    }
}
