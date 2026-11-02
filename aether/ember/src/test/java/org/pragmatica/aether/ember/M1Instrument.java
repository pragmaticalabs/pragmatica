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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
        enginePhase().ifPresent(phase -> out.put("engine.phase", phase));
        return out;
    }

    /// The engine's own slot number (`currentPhase`), read by reflection: the independent reading `engine.slots` is checked against. It
    /// advances only in `advancePhase` (which calls `onPhaseCompleted` once) or on a snapshot restore, so outside restores its delta equals
    /// the `engine.slots` delta, up to the one slot either end a non-atomic read can straddle.
    java.util.OptionalLong enginePhase() {
        if (engineRef == null) {
            return java.util.OptionalLong.empty();
        }
        try {
            var method = engineRef.getClass().getDeclaredMethod("currentPhaseForTesting");

            method.setAccessible(true);
            return java.util.OptionalLong.of(((org.pragmatica.consensus.rabia.Phase) method.invoke(engineRef)).value());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("engine phase unreadable on " + nodeId, e);
        }
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
        ALL.add(instrument);
        var store = node.kvStore();

        instrument.store = store;
        instrument.swapRouter(store);
        instrument.swapStateMachine(node, store);
        if (CAPTURE) {
            instrument.wrapSubmitGuard(node);
        }
        return instrument;
    }

    // ---- source capture (opt-in, -Dm1.capture=true) --------------------------------------------------------------------------------
    //
    // Answers "who submits the Noop commands and what opens the empty slots" without touching src/main. Noop SUBMITTERS: the engine's submit
    // guard (called on every locally submitted batch, on the submitting thread) is wrapped; every Noop is counted by key, one in
    // `m1.captureSample` (default 50) also records the first three non-engine stack frames. Slot OPENERS: the state machine proxy sees
    // `onPhaseCompleted` after every slot on the engine's thread; one in `m1.captureSample` slots records the OUTERMOST RabiaEngine method on
    // that stack (the entry that ran the phase), the frame that called it, and the thread, labelled empty when no batch was applied since the
    // previous slot. Counts of the sampled keys are sample counts (multiply by the sample interval); `capture.empty-slots` and the
    // `noop.*` KEY counters are exact.

    static final boolean CAPTURE = Boolean.getBoolean("m1.capture");
    private static final int CAPTURE_SAMPLE = Integer.getInteger("m1.captureSample", 50);
    private static volatile long anchorNanos = System.nanoTime();

    /// Offsets in the capture output are relative to this instant (the arm sets it just before the undeploy request).
    static void anchor() {
        anchorNanos = System.nanoTime();
    }

    private static final class Source {
        final LongAdder count = new LongAdder();
        volatile long firstNanos;
        volatile long lastNanos;
    }

    private final ConcurrentHashMap<String, Source> sources = new ConcurrentHashMap<>();
    private final AtomicBoolean batchSinceSlot = new AtomicBoolean();
    private final AtomicLong slotSerial = new AtomicLong();
    private final AtomicLong noopSerial = new AtomicLong();
    private Object engineRef;
    /// Every installed instrument, so a dump can ask the OTHER cores' ledgers about this core's pending batches.
    static final java.util.List<M1Instrument> ALL = new java.util.concurrent.CopyOnWriteArrayList<>();

    /// Test-only dump of this core's pending batches (reflection on `pendingBatches`, `committedLedger`): the first-choice id (the batch this voter proposes), and per
    /// pending batch its id, correlation ids, the command classes and keys inside, and whether the committed-batch ledger knows the id and ALL of its correlation ids.
    String pendingDump() {
        try {
            var type = engineRef.getClass();
            var pendingField = type.getDeclaredField("pendingBatches");
            var ledgerField = type.getDeclaredField("committedLedger");

            pendingField.setAccessible(true);
            ledgerField.setAccessible(true);
            var pending = new java.util.ArrayList<>(((java.util.Map<?, ?>) pendingField.get(engineRef)).entrySet());
            var ledger = ledgerField.get(engineRef);
            var committedField = ledger.getClass().getDeclaredField("committed");

            committedField.setAccessible(true);
            java.util.Map<Object, java.util.Set<Object>> committed;

            synchronized (ledger) {
                committed = new java.util.HashMap<>();
                for (var e : ((java.util.Map<?, ?>) committedField.get(ledger)).entrySet()) {
                    committed.put(e.getKey(), new java.util.HashSet<>((java.util.Set<?>) e.getValue()));
                }
            }
            var rows = new java.util.ArrayList<String>();

            for (var entry : pending) {
                var batch = entry.getValue();
                var ids = (java.util.List<?>) batch.getClass().getMethod("correlationIds").invoke(batch);
                var commands = (java.util.List<?>) batch.getClass().getMethod("commands").invoke(batch);
                var known = committed.get(entry.getKey());

                rows.add("{\"id\":\"" + esc(entry.getKey()) + "\",\"nCorrelationIds\":" + ids.size() + ",\"correlationIds\":\"" + esc(ids.stream().limit(5).toList())
                         + "\",\"commands\":\"" + esc(commands.stream().limit(6).map(M1Instrument::commandSummary).toList()) + "\",\"nCommands\":" + commands.size()
                         + ",\"ledgerKnowsId\":" + (known != null) + ",\"ledgerKnowsAllCorrelationIds\":" + (known != null && known.containsAll(ids))
                         + ",\"otherCoresLedgerKnowsAll\":\"" + ALL.stream().filter(i -> i != this).filter(i -> i.ledgerKnows(entry.getKey(), ids)).map(i -> i.nodeId).toList() + "\"}");
            }
            return "{\"core\":\"" + nodeId + "\",\"firstChoice\":\"" + (pending.isEmpty() ? "none" : esc(pending.getFirst().getKey())) + "\",\"pendingBatches\":" + pending.size()
                   + ",\"ledgerSize\":" + committed.size() + ",\"pending\":" + rows.stream().collect(java.util.stream.Collectors.joining(",", "[", "]")) + "}";
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "{\"core\":\"" + nodeId + "\",\"error\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }

    /// Whether THIS core's committed-batch ledger holds `batchId` and every one of `correlationIds` (by reflection; a copy taken under the ledger's monitor).
    boolean ledgerKnows(Object batchId, java.util.List<?> correlationIds) {
        try {
            var ledgerField = engineRef.getClass().getDeclaredField("committedLedger");

            ledgerField.setAccessible(true);
            var ledger = ledgerField.get(engineRef);
            var committedField = ledger.getClass().getDeclaredField("committed");

            committedField.setAccessible(true);
            synchronized (ledger) {
                var known = ((java.util.Map<?, ?>) committedField.get(ledger)).get(batchId);

                return known != null && ((java.util.Set<?>) known).containsAll(correlationIds);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static String commandSummary(Object command) {
        try {
            var key = command.getClass().getMethod("key").invoke(command);

            return command.getClass().getSimpleName() + ":" + key.getClass().getSimpleName() + " " + key;
        } catch (ReflectiveOperationException e) {
            return command.getClass().getSimpleName();
        }
    }

    private static String esc(Object value) {
        return String.valueOf(value).replace("\\", "/").replace("\"", "'");
    }

    /// The engine's own view, read by reflection: batches still pending (`pendingBatches`), the age of the oldest one on this node's LOCAL clock
    /// (`oldestPendingBatchAgeMs`, the metric the engine registers) and its current phase. JSON object, or an `error` field when unreadable.
    String engineProbe() {
        try {
            var type = engineRef.getClass();
            var pending = type.getDeclaredMethod("pendingBatchCountForTesting");
            var age = type.getDeclaredMethod("oldestPendingBatchAgeMs");
            var phase = type.getDeclaredField("currentPhase");
            var requested = type.getDeclaredField("requestedConfiguration");
            var config = type.getDeclaredField("currentConfig");

            pending.setAccessible(true);
            age.setAccessible(true);
            phase.setAccessible(true);
            requested.setAccessible(true);
            config.setAccessible(true);
            return "{\"core\":\"" + nodeId + "\",\"pendingBatches\":" + pending.invoke(engineRef) + ",\"oldestPendingBatchAgeMs\":" + age.invoke(engineRef) + ",\"phase\":\""
                   + String.valueOf(phase.get(engineRef)).replace("\"", "'") + "\",\"requestedConfiguration\":\"" + String.valueOf(requested.get(engineRef)).replace("\"", "'")
                   + "\",\"currentConfig\":\"" + String.valueOf(config.get(engineRef)).replace("\"", "'") + "\"}";
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "{\"core\":\"" + nodeId + "\",\"error\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }
    /// Slots per 5 s bucket relative to the anchor (bucket -1 = the 5 s before it), exact; `empty` = no batch applied since the previous slot.
    private final ConcurrentHashMap<Long, LongAdder> slotBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, LongAdder> emptySlotBuckets = new ConcurrentHashMap<>();

    private static String bucketsJson(ConcurrentHashMap<Long, LongAdder> buckets) {
        var out = new java.util.TreeMap<Long, Long>();

        buckets.forEach((bucket, adder) -> out.put(bucket, adder.sum()));
        buckets.clear();
        return out.entrySet().stream().map(e -> "\"" + e.getKey() + "\":" + e.getValue()).collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    /// `{"slots":{bucket:n},"empty":{bucket:n}}` since the previous call, then cleared.
    String drainSlotBuckets() {
        return "{\"slots\":" + bucketsJson(slotBuckets) + ",\"empty\":" + bucketsJson(emptySlotBuckets) + "}";
    }

    static String anchorUtc() {
        return java.time.Instant.now().minusNanos(System.nanoTime() - anchorNanos).toString();
    }

    private void source(String what) {
        var src = sources.computeIfAbsent(what, _ -> new Source());
        var now = System.nanoTime();

        // exact, never drained: `capture.n:noop.committed` is checked against the engine's own `cmd.Noop` per window
        add("capture.n:" + what.substring(0, what.indexOf(' ')), 1);

        src.count.increment();
        if (src.firstNanos == 0) {
            src.firstNanos = now;
        }
        src.lastNanos = now;
    }

    /// The captured sources since the previous drain as a JSON array, largest first (at most `limit`), then cleared.
    String drainSources(int limit) {
        var rows = new java.util.ArrayList<Map.Entry<String, Source>>(sources.entrySet());

        sources.clear();
        rows.sort((a, b) -> Long.compare(b.getValue().count.sum(), a.getValue().count.sum()));
        return rows.stream()
                   .limit(limit)
                   .map(e -> "{\"src\":\"" + e.getKey().replace("\\", "/").replace("\"", "'") + "\",\"n\":" + e.getValue().count.sum() + ",\"firstMs\":"
                             + (e.getValue().firstNanos - anchorNanos) / 1_000_000 + ",\"lastMs\":" + (e.getValue().lastNanos - anchorNanos) / 1_000_000 + "}")
                   .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void wrapSubmitGuard(AetherNode node) throws ReflectiveOperationException {
        var engine = (RabiaEngine) find(node, RabiaEngine.class);
        var field = RabiaEngine.class.getDeclaredField("submitGuard");

        field.setAccessible(true);
        var reference = (AtomicReference<java.util.function.Function<List<?>, Object>>) field.get(engine);
        var original = reference.get();

        reference.set(commands -> {
            observeSubmit(commands);
            return original.apply(commands);
        });
    }

    private void observeSubmit(List<?> commands) {
        add("capture.submit-calls", 1);
        for (var command : commands) {
            if (command instanceof KVCommand.Noop<?> noop) {
                source("noop.submitted key=" + noop.key().getClass().getSimpleName() + " " + noop.key());
                if (noopSerial.incrementAndGet() % CAPTURE_SAMPLE == 1) {
                    source("noop.stack " + stackTop(10));
                }
            }
        }
    }

    private static String stackTop(int frames) {
        return StackWalker.getInstance()
                          .walk(stream -> stream.filter(f -> !f.getClassName().startsWith("org.pragmatica.consensus.rabia") && !f.getClassName().startsWith("org.pragmatica.aether.ember.M1Instrument")
                                                         && !f.getClassName().startsWith("java.") && !f.getClassName().startsWith("jdk."))
                                                .limit(frames)
                                                .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName() + ":" + f.getLineNumber())
                                                .collect(java.util.stream.Collectors.joining(" < ")));
    }

    private void observeSlot() {
        var empty = !batchSinceSlot.getAndSet(false);
        var bucket = Math.floorDiv(System.nanoTime() - anchorNanos, 5_000_000_000L);

        slotBuckets.computeIfAbsent(bucket, _ -> new LongAdder()).increment();
        if (empty) {
            emptySlotBuckets.computeIfAbsent(bucket, _ -> new LongAdder()).increment();
        }

        if (empty) {
            add("capture.empty-slots", 1);
        }
        if (slotSerial.incrementAndGet() % CAPTURE_SAMPLE == 0) {
            source((empty ? "slot.empty " : "slot.batch ") + slotOpener());
        }
    }

    /// The outermost RabiaEngine method on the stack (the entry that ran this phase), the frame that called it, and the thread's name without digits.
    private static String slotOpener() {
        var frames = StackWalker.getInstance().walk(stream -> stream.limit(80).toList());
        int outermost = -1;

        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).getClassName().startsWith("org.pragmatica.consensus.rabia")) {
                outermost = i;
            }
        }
        var chain = new java.util.ArrayList<String>();

        // the outermost three RabiaEngine frames, outermost first: the first is often only the executor wrapper (safeExecute), the next ones name the handler
        for (int i = outermost; i >= 0 && chain.size() < 4; i--) {
            var f = frames.get(i);

            if (f.getClassName().startsWith("org.pragmatica.consensus.rabia")) {
                chain.add(f.getMethodName() + ":" + f.getLineNumber());
            }
        }
        var entry = outermost < 0 ? "none" : String.join(">", chain);
        var via = outermost < 0 || outermost + 1 >= frames.size() ? "none" : frames.get(outermost + 1).getClassName().substring(frames.get(outermost + 1).getClassName().lastIndexOf('.') + 1)
                                                                            + "." + frames.get(outermost + 1).getMethodName();

        return "entry=" + entry + " via=" + via + " thread=" + Thread.currentThread().getName().replaceAll("[0-9]+", "#");
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
            var unchanged = refreshOnly(previous, next);

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
            if (put.cause().value() instanceof AetherValue.NodeArtifactValue artifact) {
                // S6: per-instance state transitions; a FAILED put is a failed (and later retried) load, which changes the count.
                add("nodeArtifact.state:" + artifact.state(), 1);
            }
        }
    }

    /// True when `next` differs from `previous` in the two heartbeat stamps alone, `announcedAt` and `observedCoreEpoch`: the same
    /// equality as `GovernorAnnouncementValue.unchangedFrom` (#2542, know 193c4abe4f), so M-1 and M-1b classify commits alike.
    static boolean refreshOnly(AetherValue.GovernorAnnouncementValue previous, AetherValue.GovernorAnnouncementValue next) {
        return new AetherValue.GovernorAnnouncementValue(next.governorId(),
                                                         next.memberCount(),
                                                         next.members(),
                                                         next.tcpAddress(),
                                                         previous.announcedAt(),
                                                         next.communityTerm(),
                                                         next.communityEpoch(),
                                                         previous.observedCoreEpoch(),
                                                         next.transitionedAt(),
                                                         next.dissolved()).equals(previous);
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
        this.engineRef = engine;
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
            case "processCommitted" -> {
                observeBatch((StateMachine.Batch) args[0], (List) result);
                batchSinceSlot.set(true);
            }
            case "onPhaseCompleted" -> {
                add("engine.slots", 1);
                if (CAPTURE) {
                    observeSlot();
                }
            }
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
            if (command instanceof KVCommand.Noop<?> barrier) {
                add("cmd.Noop:" + barrier.key().getClass().getSimpleName(), 1);
            }
            if (CAPTURE && command instanceof KVCommand.Noop<?> noop) {
                source("noop.committed key=" + noop.key().getClass().getSimpleName() + " " + noop.key());
            }
            if (command instanceof KVCommand.Put<?, ?> put) {
                add("cmd.Put:" + put.key().getClass().getSimpleName(), 1);
            } else if (command instanceof KVCommand.Remove<?> remove) {
                add("cmd.Remove:" + remove.key().getClass().getSimpleName(), 1);
            }
            if (command instanceof KVCommand.LeaderTransaction<?, ?> tx) {
                var accepted = results != null && i < results.size()
                               && results.get(i) instanceof KVCommand.TransactionResult outcome && outcome.accepted();

                add(accepted ? "tx.accepted" : "tx.refused", 1);
                add((accepted ? "tx.accepted.key:" : "tx.refused.key:") + tx.key().getClass().getSimpleName(), 1);
                if (!accepted) {
                    tx.mutations().forEach(m -> add("tx.refused.mut:" + m.key().getClass().getSimpleName(), 1));
                }
                add(accepted ? "tx.accepted.mutations" : "tx.refused.mutations", tx.mutations().size());
                if (accepted) {
                    tx.mutations().forEach(m -> add("tx.mut:" + m.key().getClass().getSimpleName(), 1));
                }
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
