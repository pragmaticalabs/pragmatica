// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import org.pragmatica.aether.node.stream.StreamConsumerManager.CommittedEpochSource;
import org.pragmatica.aether.node.stream.StreamConsumerManager.SubscriptionKey;
import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamCursorCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// #3283: the committed cursor checkpoint as a node that does not hold it reads it — the read counterpart of the
/// forwarded commit (#2596). A worker's replica excludes [StreamCursorCheckpointKey] ([org.pragmatica.aether.slice.kvstore.ReplicaFilter]),
/// so its local mirror reads nothing for every key and cannot tell "no checkpoint" from "not replicated here".
///
/// The read is a [KVCommand.Get] submitted through the same cluster `apply` the commit uses; on a worker that is the
/// forwarding transport to one core peer, which runs it through consensus. Outcomes:
/// - FOUND: `some(checkpoint)`.
/// - ABSENT: `none()`, decoded only from the Get's own result. The core computes it when it APPLIES the Get's decision
///   (`KVStore.handleGet`, completed by `RabiaEngine.commitChanges` after `processCommitted`), and Rabia decides one batch per
///   phase and applies phases in order, so every checkpoint decided before the read was submitted is applied by then. A
///   core that lags answers late, never early: its local applied position is not what the answer reflects.
/// - UNREADABLE: a failed promise — no core peer, the forward or apply timeout, an error reply, or a result that is not
///   exactly one Get answer. Never decoded as ABSENT.
///
/// Guarantee: an ABSENT or FOUND answer reflects every checkpoint whose consensus commit completed before the read was
/// sent (the read is ordered after it in the one Rabia log). Not claimed: anything about a commit still in flight while
/// the read runs; it lands in a slot before or after the Get.
///
/// #3285 can reuse the read unchanged: a worker's commit verdict (`ClusterCursorStore.blindVerdict`) becomes FOUND-ours /
/// FOUND-other / ABSENT / UNREADABLE from this read after the barrier, and topic status reads the cursor through it.
@FunctionalInterface
public interface CommittedCursorRead {
    Promise<Option<StreamCursorCheckpointValue>> read(StreamCursorCheckpointKey key);

    Cause NOT_WIRED = Causes.cause("no authoritative cursor read is wired on this node; the committed cursor is unreadable");

    /// The consensus-ordered read: one [KVCommand.Get] through `cluster` (the node's cluster `apply`).
    static CommittedCursorRead consensusRead(Fn1<Promise<List<Object>>, List<KVCommand<AetherKey>>> cluster) {
        var reads = consensusReads(cluster);

        return key -> reads.read(List.of(key))
                           .map(List::getFirst);
    }

    /// The batch form: one command list through `cluster`, one [KVCommand.Get] per key, decoded positionally
    /// ([#getAnswers]). The reconcile pass reads every key it holds in one apply ([#lastReadEpochs]).
    static Batch consensusReads(Fn1<Promise<List<Object>>, List<KVCommand<AetherKey>>> cluster) {
        return keys -> cluster.apply(keys.stream()
                                         .<KVCommand<AetherKey>> map(key -> new KVCommand.Get<AetherKey>(key))
                                         .toList())
                              .flatMap(results -> getAnswers(keys, results).async());
    }

    /// Reads several keys at once. Answers one entry per key, in the order of `keys`, or fails as a whole: no
    /// partial answer is ever returned.
    @FunctionalInterface
    interface Batch {
        Promise<List<Option<StreamCursorCheckpointValue>>> read(List<StreamCursorCheckpointKey> keys);
    }

    /// A read that is never wired: every answer is UNREADABLE, so a node built without the read never starts a worker
    /// consumer at its reset point.
    static CommittedCursorRead unwired() {
        return _ -> NOT_WIRED.promise();
    }

    /// The Gets' answers are exactly one `Option` per key, holding a checkpoint or nothing, in the order the Gets were
    /// sent. Anything else (a list of another size, or any entry of another type) did not come from these Gets applied to
    /// these keys, and the whole batch is UNREADABLE, never ABSENT.
    static Result<List<Option<StreamCursorCheckpointValue>>> getAnswers(List<StreamCursorCheckpointKey> keys,
                                                                        List<?> results) {
        return results.size() == keys.size()
               ? Result.allOf(IntStream.range(0,
                                              keys.size())
                                       .mapToObj(index -> getAnswer(keys.get(index),
                                                                    results.get(index)))
                                       .toList())
               : malformed(keys, results).result();
    }

    private static Result<Option<StreamCursorCheckpointValue>> getAnswer(StreamCursorCheckpointKey key, Object result) {
        return result instanceof Option<?> answer
               ? checkpointOf(key, answer)
               : malformed(List.of(key), List.of(result)).result();
    }

    private static Result<Option<StreamCursorCheckpointValue>> checkpointOf(StreamCursorCheckpointKey key,
                                                                            Option<?> answer) {
        return answer.fold(() -> Result.success(Option.none()),
                           value -> value instanceof StreamCursorCheckpointValue checkpoint
                                    ? Result.success(Option.some(checkpoint))
                                    : malformed(List.of(key), List.of(value)).result());
    }

    private static Cause malformed(List<StreamCursorCheckpointKey> keys, List<?> results) {
        return Causes.cause("cursor read for " + keys
                           + " returned " + results
                           + " instead of one checkpoint answer per key");
    }

    /// The rewind epochs for [StreamConsumerManager]'s reconcile pass, chosen per pass by the node's current role: a
    /// forwarding node takes [#lastReadEpochs] over `reads`, a core node its own committed state through `localReader`.
    static CommittedEpochSource committedEpochs(BooleanSupplier forwarding,
                                                Fn1<Option<StreamCursorCheckpointValue>, StreamCursorCheckpointKey> localReader,
                                                Batch reads) {
        var remote = lastReadEpochs(reads);

        return keys -> forwarding.getAsBoolean()
                       ? remote.forPass(keys)
                       : localPass(remote, localReader);
    }

    /// A core pass holds no key for the remote read: it forgets every held key and sends nothing, so a later forwarding
    /// pass never acts on an answer from before the node became a core.
    private static Fn1<Option<RewindEpoch>, SubscriptionKey> localPass(CommittedEpochSource remote,
                                                                       Fn1<Option<StreamCursorCheckpointValue>, StreamCursorCheckpointKey> localReader) {
        remote.forPass(List.of());

        return key -> localReader.apply(checkpointKey(key))
                                 .map(StreamCursorCheckpointValue::rewindEpoch);
    }

    /// The rewind epochs for [StreamConsumerManager]'s reconcile pass on a node whose mirror does not hold checkpoints.
    ///
    /// Cost: one cluster apply per pass at most, carrying one Get per key the pass holds, and at most one such read
    /// outstanding per node: a pass that finds the previous read unanswered sends none, so answers never arrive out of
    /// order. The read is freed when it settles, answered or failed (the cluster apply's own timeout bounds it).
    ///
    /// The pass is synchronous, so it acts on what EARLIER reads answered, never on its own read. Only the keys of the
    /// latest pass are kept (a core pass keeps none): a key the pass no longer holds is forgotten, and an answer is taken
    /// only for a key held continuously since its read was sent. So an epoch acted on is as old as the last ANSWERED read
    /// for that key among the reads sent while it has been held, and no older: an UNREADABLE read keeps it (FER: no
    /// restart is claimed from a read that did not answer), an ABSENT one clears it, a FOUND one replaces it.
    ///
    /// It can be stale: a slow answer, a failing read, or a checkpoint deleted and recreated at a lower epoch since, which
    /// the applier's rewind fence does not cover. A stale FOUND newer than the consumer's epoch is harmless to the
    /// position: the restart abandons the consumer without a flush, the new one re-fetches its cursor through the
    /// authoritative read, and the pass skips it while that fetch is pending ([StreamConsumerManager]'s `rewoundBehind`).
    /// Its cost is at most one restart per pass, and none once a read for the key sent after the change has answered. A
    /// core node acts on its own committed state at once, through its put notification.
    static CommittedEpochSource lastReadEpochs(Batch reads) {
        /// A key the latest pass holds: the pass that first held it in its current run, and what the reads answered.
        record Held(long since, Option<RewindEpoch> found) {
            /// The answer of a read sent at `readPass`, taken only if the key was held when it was sent.
            Held answered(long readPass, Option<StreamCursorCheckpointValue> answer) {
                return since <= readPass
                       ? new Held(since, answer.map(StreamCursorCheckpointValue::rewindEpoch))
                       : this;
            }
        }
        /// One node's pass state: the held keys and the slot of the one outstanding read.
        record LastReadEpochs(Batch reads,
                              AtomicLong passes,
                              AtomicBoolean outstanding,
                              ConcurrentHashMap<StreamCursorCheckpointKey, Held> held) implements CommittedEpochSource {
            @Override
            public Fn1<Option<RewindEpoch>, SubscriptionKey> forPass(List<SubscriptionKey> keys) {
                var pass = passes.incrementAndGet();
                var passKeys = keys.stream().map(CommittedCursorRead::checkpointKey).toList();

                held.keySet().retainAll(Set.copyOf(passKeys));
                passKeys.forEach(key -> held.putIfAbsent(key, new Held(pass, Option.none())));
                var known = Map.copyOf(held);

                sendIfFree(passKeys, pass);

                return key -> Option.option(known.get(checkpointKey(key))).flatMap(Held::found);
            }

            @Contract
            private void sendIfFree(List<StreamCursorCheckpointKey> passKeys, long pass) {
                if (!passKeys.isEmpty() && outstanding.compareAndSet(false, true)) {
                    reads.read(passKeys).onResult(result -> settle(passKeys, pass, result));
                }
            }

            /// Fire-and-forget: the answers land for a later pass; the slot is freed after they land, either way.
            @Contract
            private void settle(List<StreamCursorCheckpointKey> passKeys,
                                long pass,
                                Result<List<Option<StreamCursorCheckpointValue>>> result) {
                result.onSuccess(answers -> remember(passKeys, pass, answers));
                outstanding.set(false);
            }

            @Contract
            private void remember(List<StreamCursorCheckpointKey> passKeys,
                                  long pass,
                                  List<Option<StreamCursorCheckpointValue>> answers) {
                IntStream.range(0,
                                passKeys.size())
                         .forEach(index -> held.computeIfPresent(passKeys.get(index),
                                                                 (_, current) -> current.answered(pass,
                                                                                                  answers.get(index))));
            }
        }

        return new LastReadEpochs(reads, new AtomicLong(), new AtomicBoolean(), new ConcurrentHashMap<>());
    }

    private static StreamCursorCheckpointKey checkpointKey(SubscriptionKey key) {
        return StreamCursorCheckpointKey.streamCursorCheckpointKey(key.streamName(),
                                                                   key.partition(),
                                                                   key.consumerGroup());
    }
}
