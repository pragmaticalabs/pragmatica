// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.metrics;

import java.util.List;

import org.pragmatica.serialization.Codec;


/// What a draining node reports on its pong while its lifecycle state is `HELD`: the departure push has
/// not resolved (not started counts).
///
/// - `episode` — the episode identity `<id>@<drainStartMillis>`.
/// - `owedCount` — entries still owed to other nodes, or `-1` when they are not enumerated.
/// - `undeliverable` — entries that cannot be delivered.
/// - `owedDigest` — hash of the sorted (key, stamp) pairs of the owed entries.
/// - `asOfMillis` — wall-clock time the report was taken.
/// - `sampleKeys` — at most [#MAX_SAMPLE_KEYS] of the owed keys.
/// - `ended` — the end handshake: [HeldEnd#NONE] while the episode is open.
///
/// An rc5 node that has not learned the `HELD` value reads it as `SYNCING` and ignores this field: a wire
/// change inside the rc; a mixed cluster is unsupported.
@Codec
public record HeldReport(String episode,
                         long owedCount,
                         long undeliverable,
                         String owedDigest,
                         long asOfMillis,
                         List<String> sampleKeys,
                         HeldEnd ended) {
    public static final int MAX_SAMPLE_KEYS = 16;

    public HeldReport {
        episode = episode == null
                  ? ""
                  : episode;
        owedDigest = owedDigest == null
                     ? ""
                     : owedDigest;
        sampleKeys = sampleKeys == null
                     ? List.of()
                     : List.copyOf(sampleKeys.subList(0,
                                                      Math.min(sampleKeys.size(), MAX_SAMPLE_KEYS)));
        ended = ended == null
                ? HeldEnd.NONE
                : ended;
    }

    public static HeldReport heldReport(String episode,
                                        long owedCount,
                                        long undeliverable,
                                        String owedDigest,
                                        long asOfMillis,
                                        List<String> sampleKeys,
                                        HeldEnd ended) {
        return new HeldReport(episode, owedCount, undeliverable, owedDigest, asOfMillis, sampleKeys, ended);
    }
}
