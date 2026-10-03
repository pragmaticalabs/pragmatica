// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageInstance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// The single owner of a stream's durable footprint in this node's storage: its sealed-segment refs (`streams/`) and
/// its reclaimed-through floors (`stream-floors/`, #1278). A destroyed stream drops all of it, so a stream later
/// created under the same name starts from nothing — it must not inherit a floor or a sealed watermark it never had,
/// which would make recovery treat its own WAL records at or below that watermark as already sealed and drop them.
///
/// **Crash ordering.** A destroy first writes a `stream-tombstones/<stream>` ref and makes it durable, then drops the
/// segment refs, then the floors, then the tombstone. A crash anywhere in between leaves the tombstone on disk, and a
/// rebuild ignores EVERY surviving ref of a tombstoned stream ([SegmentIndex#destroyedStreams]) — so it matters not
/// which refs were already gone: nothing of the old stream anchors anything. [#completePending] finishes such a destroy
/// at boot, and [#ensureForgotten] before a stream of that name materializes again.
///
/// The tombstone, not an order between floors and segment refs, is what makes this crash-safe: dropping floors last
/// leaves a floor licensing segments that are gone (a recreate would anchor at it), and dropping them first leaves
/// segment refs that rebuild into a watermark above a recreated stream's own WAL. Either order has a bad
/// intermediate state; the tombstone hides all of them.
public final class StreamFootprint {
    private static final Logger log = LoggerFactory.getLogger(StreamFootprint.class);

    /// For a manager with no storage of its own (tests, minimal runtimes): there is no footprint to drop.
    public static final StreamFootprint NONE = new StreamFootprint(null,
                                                                   null,
                                                                   null,
                                                                   RetentionEnforcer.FloorDurability.LIVE);

    private final StorageInstance storage;
    private final MetadataStore metadata;
    private final SegmentIndex index;
    private final RetentionEnforcer.FloorDurability durability;

    private StreamFootprint(StorageInstance storage,
                            MetadataStore metadata,
                            SegmentIndex index,
                            RetentionEnforcer.FloorDurability durability) {
        this.storage = storage;
        this.metadata = metadata;
        this.index = index;
        this.durability = durability;
    }

    /// `metadata` is the store behind `storage`: the refs to drop are enumerated from it, not from `index`, so a
    /// destroy finished at boot — whose refs the rebuilt index ignored — still finds every one, an older floor
    /// included.
    public static StreamFootprint streamFootprint(StorageInstance storage,
                                                  MetadataStore metadata,
                                                  SegmentIndex index,
                                                  RetentionEnforcer.FloorDurability durability) {
        return new StreamFootprint(storage, metadata, index, durability);
    }

    /// Drop every durable ref of `streamName` (see the class doc for the ordering). A tombstone that cannot be made
    /// durable is withdrawn and nothing is dropped: the footprint then survives as before, logged at ERROR.
    public Promise<Unit> forget(String streamName) {
        if (storage == null) {
            return Promise.unitPromise();
        }

        var tombstone = SegmentIndex.tombstoneRefName(streamName);

        return storage.putRef(tombstone,
                              streamName.getBytes(StandardCharsets.UTF_8))
                      .flatMap(_ -> durable(streamName, tombstone))
                      .flatMap(_ -> dropAll(refsUnder(SegmentIndex.segmentRefPrefix(streamName))))
                      .flatMap(_ -> dropAll(refsUnder(SegmentIndex.floorRefPrefix(streamName))))
                      .map(_ -> forgetIndexed(streamName))
                      .flatMap(_ -> storage.deleteRef(tombstone));
    }

    /// Before `streamName` materializes: if a destroy of that name did not finish (its tombstone is still here),
    /// finish it now, so the new stream's own refs are never hidden by the old tombstone.
    public Promise<Unit> ensureForgotten(String streamName) {
        return storage != null && storage.resolveRef(SegmentIndex.tombstoneRefName(streamName))
                                         .isPresent()
               ? forget(streamName)
               : Promise.unitPromise();
    }

    /// At boot: finish every destroy a crash interrupted.
    public Promise<Unit> completePending(java.util.Collection<String> refNames) {
        var pending = SegmentIndex.destroyedStreams(refNames).stream().map(this::forget).toList();

        return Promise.allOf(pending).map(_ -> unit());
    }

    private Promise<Unit> durable(String streamName, String tombstone) {
        return durability.persist()
                         .onFailure(cause -> log.error("Destroy of stream '{}' left its durable refs in place: its tombstone could not"
                                                      + " be made durable: {}",
                                                       streamName,
                                                       cause.message()))
                         .async()
                         .onFailure(_ -> storage.deleteRef(tombstone));
    }

    private Promise<Unit> dropAll(List<String> refNames) {
        return Promise.allOf(refNames.stream().map(storage::deleteRef).toList())
                      .map(results -> Result.allOf(results))
                      .flatMap(Result::async)
                      .map(_ -> unit());
    }

    private List<String> refsUnder(String prefix) {
        return metadata.listAllRefs()
                       .keySet()
                       .stream()
                       .filter(ref -> ref.startsWith(prefix))
                       .toList();
    }

    private Unit forgetIndexed(String streamName) {
        index.forgetStream(streamName);

        return unit();
    }
}
