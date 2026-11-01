// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueGet;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.rabia.RabiaEngine;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;

/// M-1 counting instrument (#2512), test-only. Two independent readings of one core's committed KV stream:
///
/// 1. the NOTIFICATION reading: the store's `router` field is replaced by a mutable router that counts every `ValuePut` / `ValueRemove` the
///    store publishes (not while it replays a snapshot) and forwards it to the original router. One notification is one committed mutation
///    of one key; a refused write publishes nothing. Counted by the key's class, i.e. by family.
/// 2. the ENGINE reading: the consensus engine's state machine is replaced by a proxy that counts every committed batch, every command by
///    kind, every leader transaction accepted or refused (from the result the store returns), and every completed slot
///    (`onPhaseCompleted`, which the engine calls after EVERY slot, empty ones included).
///
/// Nothing in `src/main` is touched; the swap is by reflection on final fields of objects the node built.
final class M1Instrument {
    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final String nodeId;
    private KVStore<?, ?> store;
    /// One entry per committed mutation notification (outside replay): when, and which op on which family. Used only to find when a
    /// window's activity ended and which families are idle background; every COUNT comes from the counters.
    private final java.util.concurrent.ConcurrentLinkedQueue<Commit> log = new java.util.concurrent.ConcurrentLinkedQueue<>();

    record Commit(long nanos, String op) {}

    List<Commit> logSince(long nanos) {
        return log.stream().filter(c -> c.nanos() >= nanos).toList();
    }

    private M1Instrument(String nodeId) {
        this.nodeId = nodeId;
    }

    String nodeId() {
        return nodeId;
    }

    void add(String name, long amount) {
        counters.computeIfAbsent(name, _ -> new LongAdder()).add(amount);
    }

    Map<String, Long> snapshot() {
        var out = new TreeMap<String, Long>();

        counters.forEach((name, adder) -> out.put(name, adder.sum()));
        return out;
    }

    /// `after - before`, zero entries dropped.
    static Map<String, Long> delta(Map<String, Long> before, Map<String, Long> after) {
        var out = new TreeMap<String, Long>();

        after.forEach((name, value) -> {
            var diff = value - before.getOrDefault(name, 0L);

            if (diff != 0) {
                out.put(name, diff);
            }
        });
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static M1Instrument install(AetherNode node) throws ReflectiveOperationException {
        var instrument = new M1Instrument(node.self().id());
        var store = node.kvStore();

        instrument.store = store;
        instrument.swapRouter(store);
        instrument.swapStateMachine(node, store);
        return instrument;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void swapRouter(KVStore<?, ?> kv) throws ReflectiveOperationException {
        var field = KVStore.class.getDeclaredField("router");

        field.setAccessible(true);
        var original = (MessageRouter) field.get(kv);
        var counting = MessageRouter.mutable();

        counting.addRoute((Class) ValuePut.class, (Consumer) (Object message) -> {
            countPut((ValuePut) message);
            original.route((ValuePut) message);
        });
        counting.addRoute((Class) ValueRemove.class, (Consumer) (Object message) -> {
            countRemove((ValueRemove) message);
            original.route((ValueRemove) message);
        });
        counting.addRoute((Class) ValueGet.class, (Consumer) (Object message) -> {
            add("notification.get", 1);
            original.route((ValueGet) message);
        });
        field.set(kv, counting);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void countPut(ValuePut<?, ?> put) {
        var replaying = store.isReplaying();
        var family = put.cause().key().getClass().getSimpleName();
        var prefix = replaying ? "replay.put:" : "put:";

        add(prefix + family, 1);
        if (!replaying && put.cause().value() instanceof AetherValue.GovernorAnnouncementValue next
            && put.oldValue().isPresent() && put.oldValue().unwrap() instanceof AetherValue.GovernorAnnouncementValue previous) {
            var unchanged = previous.governorId().equals(next.governorId())
                            && previous.members().equals(next.members())
                            && previous.tcpAddress().equals(next.tcpAddress())
                            && previous.communityTerm() == next.communityTerm()
                            && previous.communityEpoch().equals(next.communityEpoch())
                            && previous.observedCoreEpoch().equals(next.observedCoreEpoch())
                            && previous.dissolved() == next.dissolved();

            add(unchanged ? "gov.refresh-only" : "gov.changed", 1);
        }
        if (!replaying) {
            log.add(new Commit(System.nanoTime(), "put:" + family));
            add("put.total", 1);
            add(put.oldValue().isPresent() ? "put.update" : "put.create", 1);
            if (put.oldValue().isPresent() && put.oldValue().unwrap().equals(put.cause().value())) {
                add("put.same-value", 1);
            }
            add("put.bytes", sizeOf(put.cause().value()));
        }
    }

    private void countRemove(ValueRemove<?, ?> remove) {
        var replaying = store.isReplaying();
        var family = remove.cause().key().getClass().getSimpleName();

        add((replaying ? "replay.remove:" : "remove:") + family, 1);
        if (!replaying) {
            log.add(new Commit(System.nanoTime(), "remove:" + family));
            add("remove.total", 1);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private long sizeOf(Object value) {
        try {
            return ((org.pragmatica.serialization.Serializer) store.serializer()).encode(value).length;
        } catch (RuntimeException e) {
            add("put.bytes-unencodable", 1);
            return 0;
        }
    }

    // ---- engine reading ------------------------------------------------------------------------------------------------------------

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void swapStateMachine(AetherNode node, KVStore<?, ?> kv) throws ReflectiveOperationException {
        var engine = (RabiaEngine) find(node, RabiaEngine.class);

        if (engine == null) {
            throw new IllegalStateException("no RabiaEngine reachable from node " + nodeId);
        }

        var field = RabiaEngine.class.getDeclaredField("stateMachine");

        field.setAccessible(true);
        var real = (StateMachine) field.get(engine);

        if (real != kv) {
            throw new IllegalStateException("engine state machine is not the node's KVStore on " + nodeId);
        }

        var proxy = Proxy.newProxyInstance(StateMachine.class.getClassLoader(),
                                           new Class<?>[]{StateMachine.class},
                                           (_, method, args) -> {
                                               var result = invoke(real, method, args);

                                               observe(method.getName(), args, result);
                                               return result;
                                           });

        field.set(engine, proxy);
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void observe(String method, Object[] args, Object result) {
        switch (method) {
            case "processCommitted" -> observeBatch((StateMachine.Batch) args[0], (List) result);
            case "onPhaseCompleted" -> add("engine.slots", 1);
            case "restoreCommittedSnapshot", "restoreSnapshot" -> add("engine.snapshot-restores", 1);
            default -> {}
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void observeBatch(StateMachine.Batch<?> batch, List<?> results) {
        add("engine.batches", 1);
        add("engine.commands", batch.commands().size());
        for (int i = 0; i < batch.commands().size(); i++) {
            var command = (KVCommand) batch.commands().get(i);
            var kind = command.getClass().getSimpleName();

            add("cmd." + kind, 1);
            if (command instanceof KVCommand.LeaderTransaction<?, ?> tx) {
                var accepted = results != null && i < results.size()
                               && results.get(i) instanceof KVCommand.TransactionResult outcome && outcome.accepted();

                add(accepted ? "tx.accepted" : "tx.refused", 1);
                add((accepted ? "tx.accepted.key:" : "tx.refused.key:") + tx.key().getClass().getSimpleName(), 1);
                if (!accepted) {
                    tx.mutations().forEach(m -> add("tx.refused.mut:" + m.key().getClass().getSimpleName(), 1));
                }
                add(accepted ? "tx.accepted.mutations" : "tx.refused.mutations", tx.mutations().size());
            }
        }
    }

    // ---- object-graph search -------------------------------------------------------------------------------------------------------

    /// Breadth-first search from `root` through instance fields of `org.pragmatica` objects, atomic references, options, collections and map
    /// values, for the first instance of `target`.
    static Object find(Object root, Class<?> target) {
        var seen = new IdentityHashMap<Object, Boolean>();
        var queue = new ArrayDeque<Object>();

        queue.add(root);
        seen.put(root, true);
        int visited = 0;

        while (!queue.isEmpty() && visited++ < 400_000) {
            var current = queue.poll();

            if (target.isInstance(current)) {
                return current;
            }
            for (var child : children(current)) {
                if (child != null && seen.put(child, true) == null) {
                    queue.add(child);
                }
            }
        }
        return null;
    }

    /// The graph is live (a node being added mutates collections while this walks them), so a failed read is retried.
    private static List<Object> children(Object object) {
        for (int attempt = 0; ; attempt++) {
            try {
                return childrenOnce(object);
            } catch (RuntimeException e) {
                if (attempt >= 20) {
                    throw e;
                }
            }
        }
    }

    private static List<Object> childrenOnce(Object object) {
        var out = new java.util.ArrayList<Object>();

        if (object instanceof AtomicReference<?> ref) {
            out.add(ref.get());
            return out;
        }
        if (object instanceof Option<?> option) {
            option.onPresent(out::add);
            return out;
        }
        if (object instanceof Collection<?> collection) {
            new java.util.ArrayList<>(collection).stream().limit(2_000).forEach(out::add);
            return out;
        }
        if (object instanceof Map<?, ?> map) {
            new java.util.ArrayList<>(map.values()).stream().limit(2_000).forEach(out::add);
            return out;
        }
        var type = object.getClass();

        if (!type.getName().startsWith("org.pragmatica") && !type.isHidden() && !type.getName().contains("$$Lambda")) {
            return out;
        }
        for (var c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    out.add(field.get(object));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // not readable: skip
                }
            }
        }
        return out;
    }
}
