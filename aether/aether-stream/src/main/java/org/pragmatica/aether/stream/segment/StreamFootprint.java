// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.SharedDelete;
import org.pragmatica.storage.StorageInstance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// The owner of a stream's durable footprint in this node's storage: its sealed-segment refs (`streams/`) and its
/// reclaimed-through floors (`stream-floors/`, #1278), both keyed by the stream's INCARNATION
/// ([SegmentIndex#durableName]) — one cluster create of the name.
///
/// **Correctness comes from the key, not from cleanup.** A node serves only the incarnation the committed config
/// names ([#adopt]): the refs of any other life of the name are never installed, never anchor a watermark and are
/// never read, whether the old life was destroyed while this node was down, a destroy crashed half-way, a retention
/// pass or a seal outlived the destroy, or a floor landed after it. Dropping them ([#forget], and the garbage [#adopt]
/// hands back) is best-effort reclamation of space; a failure leaves garbage, never a wrong answer. That is why no
/// tombstone or ordering protocol exists here.
public final class StreamFootprint {
    private static final Logger log = LoggerFactory.getLogger(StreamFootprint.class);
    /// For a manager with no storage of its own (tests, minimal runtimes): there is no footprint.
    public static final StreamFootprint NONE = new StreamFootprint(null, null, null);

    private final StorageInstance storage;
    private final MetadataStore metadata;
    private final SegmentIndex index;

    private StreamFootprint(StorageInstance storage, MetadataStore metadata, SegmentIndex index) {
        this.storage = storage;
        this.metadata = metadata;
        this.index = index;
    }

    /// `metadata` is the store behind `storage`; refs of a forgotten life are enumerated from it, so refs the index
    /// never installed (an older floor, a seal that landed after the destroy) are reclaimed too.
    public static StreamFootprint streamFootprint(StorageInstance storage, MetadataStore metadata, SegmentIndex index) {
        return new StreamFootprint(storage, metadata, index);
    }

    /// Serve `incarnation` of `streamName` from now on — before any of its partitions materializes, so recovery
    /// anchors only at that life's sealed watermark and floor — and report every other life of the name, KEPT.
    ///
    /// Know 6d0c58fd67: nothing here deletes another life. The cluster state records no retirement of a life (a destroy
    /// removes the config and leaves no tombstone), so a life other than the committed one cannot be told apart from the life a STALE
    /// restore (a backup older than the stream's config) left behind, which holds acknowledged sealed data. Its refs and blocks are kept
    /// until an operator removes them or storage-identity adoption (#1569) takes them over. A destroy the node performs itself
    /// ([#forget]) still reclaims the life it served.
    public Promise<List<OtherLife>> adopt(String streamName, long incarnation) {
        if (index == null) {
            return Promise.success(List.of());
        }

        index.adopt(streamName, incarnation);
        // #2258: the adopted life's OWN refs that its own durable reclaim floor already reclaimed (a crash came between the
        // floor write and the release) were not installed; they are released now. No other life's ref is touched: other
        // lives are kept and announced (know 6d0c58fd67, d19f755873).
        index.drainReclaimedBelowFloor().forEach(refName -> storage.deleteRef(refName, SharedDelete.IF_IDENTIFIED));

        return Promise.success(otherLives(streamName, incarnation));
    }

    /// A life of a stream other than the one adopted, as found in the storage metadata: the sealed segments (`streams/` refs) it holds.
    public record OtherLife(long incarnation, long sealedSegments) {
        public static OtherLife otherLife(long incarnation, long sealedSegments) {
            return new OtherLife(incarnation, sealedSegments);
        }
    }

    /// `streamName` was destroyed: stop serving it and reclaim the refs of the life it was. Its sealed segments are released
    /// [SharedDelete#IF_IDENTIFIED] (#2258, `know:` d19f755873): this reads no block inside the destroy's await, and the
    /// collector deletes a block from the shared tier only if it carries the identity header. This explicit destroy of the
    /// applied life is the only path that releases a whole stream; [#adopt] releases nothing.
    public Promise<Unit> forget(String streamName) {
        if (index == null) {
            return Promise.unitPromise();
        }

        var incarnation = index.incarnationOf(streamName);
        var garbage = new ArrayList<>(index.forgetStream(streamName));

        garbage.addAll(refsOfLife(streamName, incarnation));

        return dropAll(streamName, garbage);
    }

    private List<OtherLife> otherLives(String streamName, long incarnation) {
        var sealedByLife = new TreeMap<Long, Long>();

        metadata.listAllRefs().keySet().forEach(ref -> countLife(sealedByLife, ref, streamName, incarnation));

        return sealedByLife.entrySet()
                           .stream()
                           .map(entry -> OtherLife.otherLife(entry.getKey(),
                                                             entry.getValue()))
                           .toList();
    }

    @Contract
    private static void countLife(Map<Long, Long> sealedByLife, String ref, String streamName, long incarnation) {
        SegmentIndex.lifeOf(ref)
                    .filter(life -> life.streamName()
                                        .equals(streamName) && life.incarnation() != incarnation)
                    .onPresent(life -> sealedByLife.merge(life.incarnation(),
                                                          sealedSegmentsIn(ref),
                                                          Long::sum));
    }

    private static long sealedSegmentsIn(String ref) {
        return SegmentIndex.isSealedSegmentRef(ref)
               ? 1L
               : 0L;
    }

    private List<String> refsOfLife(String streamName, long incarnation) {
        return metadata.listAllRefs()
                       .keySet()
                       .stream()
                       .filter(ref -> SegmentIndex.lifeOf(ref)
                                                  .filter(life -> life.streamName()
                                                                      .equals(streamName) && life.incarnation() == incarnation)
                                                  .isPresent())
                       .toList();
    }

    private Promise<Unit> dropAll(String streamName, List<String> refNames) {
        return Promise.allOf(refNames.stream().distinct().map(this::release).toList()).map(results -> logFailures(streamName,
                                                                                                                  results.stream()
                                                                                                                         .filter(result -> result.isFailure())
                                                                                                                         .count()));
    }

    private Promise<Unit> release(String refName) {
        return SegmentIndex.isSealedSegmentRef(refName)
               ? storage.deleteRef(refName, SharedDelete.IF_IDENTIFIED)
               : storage.deleteRef(refName);
    }

    private static Unit logFailures(String streamName, long failed) {
        if (failed > 0) {
            log.warn("{} durable ref(s) of a retired life of stream '{}' could not be dropped; they are garbage nothing reads",
                     failed,
                     streamName);
        }

        return unit();
    }
}
