package org.pragmatica.cluster.state.kvstore;

import org.pragmatica.lang.Cause;

/// Typed reject cause for a stale-epoch write to an [EpochBearing] Store-A value (#345, piece 1a/1b).
/// A write is stale when its presented epoch is strictly older than the value currently committed
/// under the key — a deposed governor/owner attempting to commit an OLD epoch over a newer one.
///
/// At the Store-A applier ([KVStore]) the rejection is SILENT (no value mutation, no `ValuePut`
/// notification — mirroring `staleLeaderWrite`); this `Cause` is the shared typed vocabulary the
/// data-plane fence (DHT put / stream append, pieces 1b/1e) surfaces to the caller, which re-resolves
/// the current owner and retries against the new owner/epoch.
///
/// @param key       the fenced key whose committed epoch rejected the write
/// @param presented the (stale) epoch the rejected write carried, as text
/// @param current   the committed epoch that fenced it, as text
public record StaleEpoch(StructuredKey key, String presented, String current) implements Cause {
    @Override
    public String message() {
        return "Stale-epoch write to " + key + " rejected: presented epoch " + presented
               + " is older than committed epoch " + current;
    }

    public static StaleEpoch staleEpoch(StructuredKey key, Object presented, Object current) {
        return new StaleEpoch(key, String.valueOf(presented), String.valueOf(current));
    }
}
