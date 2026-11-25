// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.node.stream.StreamConsumerManager.CommittedEpochSource;
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
        return key -> cluster.apply(List.of(new KVCommand.Get<AetherKey>(key)))
                             .flatMap(results -> getAnswer(key, results).async());
    }

    /// A read that is never wired: every answer is UNREADABLE, so a node built without the read never starts a worker
    /// consumer at its reset point.
    static CommittedCursorRead unwired() {
        return _ -> NOT_WIRED.promise();
    }

    /// The Get's answer is exactly one `Option` holding a checkpoint or nothing. Anything else did not come from a Get
    /// applied to this key and is UNREADABLE, not ABSENT.
    static Result<Option<StreamCursorCheckpointValue>> getAnswer(StreamCursorCheckpointKey key, List<?> results) {
        return results.size() == 1 && results.getFirst() instanceof Option<?> answer
               ? checkpointOf(key, answer)
               : malformed(key, results).result();
    }

    private static Result<Option<StreamCursorCheckpointValue>> checkpointOf(StreamCursorCheckpointKey key,
                                                                            Option<?> answer) {
        return answer.fold(() -> Result.success(Option.none()),
                           value -> value instanceof StreamCursorCheckpointValue checkpoint
                                    ? Result.success(Option.some(checkpoint))
                                    : malformed(key, List.of(value)).result());
    }

    private static Cause malformed(StreamCursorCheckpointKey key, List<?> results) {
        return Causes.cause("cursor read for " + key + " returned " + results + " instead of one checkpoint answer");
    }

    /// The rewind epoch for [StreamConsumerManager]'s reconcile pass, chosen per call by the node's current role: a
    /// forwarding node takes [#lastReadEpochs] over `read`, a core node its own committed state through `localReader`.
    static CommittedEpochSource committedEpochs(BooleanSupplier forwarding,
                                                Fn1<Option<StreamCursorCheckpointValue>, StreamCursorCheckpointKey> localReader,
                                                CommittedCursorRead read) {
        var remote = lastReadEpochs(read);

        return (streamName, partition, consumerGroup) -> forwarding.getAsBoolean()
                                                         ? remote.committedEpoch(streamName, partition, consumerGroup)
                                                         : localReader.apply(StreamCursorCheckpointKey.streamCursorCheckpointKey(streamName,
                                                                                                                                 partition,
                                                                                                                                 consumerGroup))
                                                                      .map(StreamCursorCheckpointValue::rewindEpoch);
    }

    /// The rewind epoch for [StreamConsumerManager]'s reconcile pass on a node whose mirror does not hold checkpoints. The
    /// pass is synchronous, so each call answers with the epoch the PREVIOUS authoritative read found for the key and
    /// starts the next read. The answer is at most one pass old, never invented: it comes only from a FOUND read, an ABSENT
    /// read forgets the key, and an UNREADABLE read keeps the last FOUND. A one-pass-old FOUND is safe to act on because the
    /// applier's rewind fence never lets the committed epoch go back, so "committed is newer than the consumer's" stays
    /// true; it only restarts a rewound consumer one pass later than a core node's put notification does.
    static CommittedEpochSource lastReadEpochs(CommittedCursorRead read) {
        var lastFound = new ConcurrentHashMap<StreamCursorCheckpointKey, RewindEpoch>();

        return (streamName, partition, consumerGroup) -> lastFoundThenRefresh(read,
                                                                              lastFound,
                                                                              StreamCursorCheckpointKey.streamCursorCheckpointKey(streamName,
                                                                                                                                  partition,
                                                                                                                                  consumerGroup));
    }

    private static Option<RewindEpoch> lastFoundThenRefresh(CommittedCursorRead read,
                                                            Map<StreamCursorCheckpointKey, RewindEpoch> lastFound,
                                                            StreamCursorCheckpointKey key) {
        var known = Option.option(lastFound.get(key));

        refresh(read, lastFound, key);

        return known;
    }

    /// Fire-and-forget: the answer lands for the next pass. FER on UNREADABLE — the last FOUND stays, so a rewind already
    /// seen is still acted on, and no restart is claimed from a read that did not answer.
    @Contract
    private static void refresh(CommittedCursorRead read,
                                Map<StreamCursorCheckpointKey, RewindEpoch> lastFound,
                                StreamCursorCheckpointKey key) {
        read.read(key).onSuccess(answer -> remember(lastFound, key, answer));
    }

    @Contract
    private static void remember(Map<StreamCursorCheckpointKey, RewindEpoch> lastFound,
                                 StreamCursorCheckpointKey key,
                                 Option<StreamCursorCheckpointValue> answer) {
        answer.map(StreamCursorCheckpointValue::rewindEpoch)
              .onPresent(epoch -> lastFound.put(key, epoch))
              .onEmpty(() -> lastFound.remove(key));
    }
}
