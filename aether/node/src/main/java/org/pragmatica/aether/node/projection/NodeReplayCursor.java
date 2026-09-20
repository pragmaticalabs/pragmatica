// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.projection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.pragmatica.aether.node.projection.PartitionBounds.Bounds;
import org.pragmatica.aether.node.stream.ClusterCursorStore;
import org.pragmatica.aether.resource.projection.Projection.ReplayCursor;
import org.pragmatica.aether.resource.projection.ProjectionStore.PartitionRange;
import org.pragmatica.aether.resource.projection.ProjectionStore.ReplayRange;
import org.pragmatica.aether.resource.projection.ProjectionStore.RewindToken;
import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamCursorCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// The node's [ReplayCursor] for one attached projection (#1333): the seam `Projection.rebuild` drives.
///
/// **capture** — one [PartitionRange] per partition of the topic stream, `earliestRetained` through
/// `visibleHead`, from [PartitionBounds]; a partition with nothing visible is left out (LIVE from the
/// start). Moves nothing; a refused bound refuses the whole capture before the store is touched.
///
/// **rewind** — for every captured partition, PUT the group's `StreamCursorCheckpointKey` to
/// `(fromOffset, epoch = token)`. The value is `EpochBearing`, so the applier refuses every later put
/// stamped with an older epoch — the zombie fence. The group id is resolved NOW (an inferred subscriber
/// that turned out ambiguous refuses the rewind rather than rewinding a guess). Then the committed value
/// is READ BACK and the rewind fails unless it carries the token: a put the applier refused — the
/// projection store's generation slot is behind the cluster (an in-memory store after a restart) — would
/// otherwise leave the store REBUILDING forever with nothing saying why. The consumer restart is
/// level-triggered from the committed epoch (`StreamConsumerManager`), so the rewind is complete when the
/// puts are committed; the rewound consumer resumes at `fromOffset` under the token.
public record NodeReplayCursor(String topicStream,
                               Supplier<Result<String>> groupId,
                               Supplier<Option<Integer>> partitionCount,
                               PartitionBounds bounds,
                               Fn1<Promise<Unit>, KVCommand<AetherKey>> commandWriter,
                               Fn1<Option<StreamCursorCheckpointValue>, StreamCursorCheckpointKey> committedReader) implements ReplayCursor {
    private static final Logger log = LoggerFactory.getLogger(NodeReplayCursor.class);

    sealed interface RewindError extends Cause {
        record StreamUnknown(String topicStream) implements RewindError {
            @Override
            public String message() {
                return "Topic stream " + topicStream + " is not known to this node yet, so its partitions cannot be captured";
            }
        }

        record RewindNotCommitted(String topicStream, String groupId, int partition, RewindToken token, Option<RewindEpoch> committed) implements RewindError {
            @Override
            public String message() {
                return "Rewind of group " + groupId + " on " + topicStream + "[" + partition + "] to token " + token
                     + " was not committed: the cluster cursor carries epoch " + committed.map(RewindEpoch::toString).or("<absent>")
                     + ". The applier refuses an older epoch, so the projection store's generation is behind the"
                     + " cluster's — its generation slot must survive restarts (durable store), or the group's"
                     + " checkpoint must be reset by an operator";
            }
        }
    }

    @Override
    public Promise<ReplayRange> capture() {
        return partitionCount.get()
                             .map(this::captureAll)
                             .or(() -> new RewindError.StreamUnknown(topicStream).promise());
    }

    private Promise<ReplayRange> captureAll(int count) {
        var captures = IntStream.range(0, count)
                                .mapToObj(partition -> bounds.bounds(topicStream, partition)
                                                             .map(range -> Map.entry(partition, range)))
                                .toList();

        return Promise.allOf(captures)
                      .flatMap(results -> Result.allOf(results).async())
                      .map(NodeReplayCursor::toRange);
    }

    private static ReplayRange toRange(List<Map.Entry<Integer, Option<Bounds>>> captured) {
        var partitions = new HashMap<Integer, PartitionRange>();

        captured.forEach(entry -> entry.getValue()
                                       .onPresent(range -> partitions.put(entry.getKey(),
                                                                          new PartitionRange(range.earliestRetained(),
                                                                                             range.visibleHead()))));

        return new ReplayRange(Map.copyOf(partitions));
    }

    @Override
    public Promise<Unit> rewind(ReplayRange range, RewindToken token) {
        return groupId.get()
                      .async()
                      .flatMap(group -> rewindAll(group, range, token));
    }

    private Promise<Unit> rewindAll(String group, ReplayRange range, RewindToken token) {
        var epoch = epochOf(token);
        var puts = range.partitions()
                        .entrySet()
                        .stream()
                        .map(entry -> commandWriter.apply(ClusterCursorStore.checkpointCommand(group,
                                                                                               topicStream,
                                                                                               entry.getKey(),
                                                                                               entry.getValue().fromOffset(),
                                                                                               epoch)))
                        .toList();

        return Promise.allOf(puts)
                      .flatMap(results -> Result.allOf(results).async())
                      .flatMap(_ -> verifyCommitted(group, range, token, epoch))
                      .onSuccess(_ -> log.info("Rewound group {} on {} to {} under epoch {}", group, topicStream, range.partitions(), epoch));
    }

    /// The put resolved, which says the command was APPLIED, not that it was ACCEPTED — a fenced refusal is
    /// silent. Reading back what the applier committed is the only way to know.
    private Promise<Unit> verifyCommitted(String group, ReplayRange range, RewindToken token, RewindEpoch epoch) {
        var refused = range.partitions()
                           .keySet()
                           .stream()
                           .map(partition -> checkCommitted(group, partition, token, epoch))
                           .toList();

        return Result.allOf(refused)
                     .mapToUnit()
                     .async();
    }

    private Result<Unit> checkCommitted(String group, int partition, RewindToken token, RewindEpoch epoch) {
        var committed = committedReader.apply(StreamCursorCheckpointKey.streamCursorCheckpointKey(topicStream, partition, group))
                                       .map(StreamCursorCheckpointValue::rewindEpoch);

        return committed.filter(epoch::equals)
                        .isPresent()
               ? Result.unitResult()
               : new RewindError.RewindNotCommitted(topicStream, group, partition, token, committed).result();
    }

    public static RewindEpoch epochOf(RewindToken token) {
        return RewindEpoch.rewindEpoch(token.generation(), token.rewind());
    }

    public static RewindToken tokenOf(RewindEpoch epoch) {
        return new RewindToken(epoch.generation(), epoch.rewind());
    }
}
