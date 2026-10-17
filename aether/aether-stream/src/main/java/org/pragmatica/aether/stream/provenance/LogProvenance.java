// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.provenance;

import java.util.Comparator;
import java.util.List;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.lang.Option;


/// What one copy of a partition log says about where its records came from (#1596, spec #1569 §7.5.2):
/// `base` (0, or the start of a first `BASE(d)` entry once operator resolution exists), `low` (the lowest
/// offset the copy still holds), `head` (the highest offset it holds, `-1` for none) and its owner-epoch
/// `history`, oldest first. The input of [ProvenanceComparison] at cold restart (AD7) and at promotion
/// (#1596's gate) alike.
public record LogProvenance(long base, long low, long head, List<ProvenanceEntry> history) {
    /// Below every epoch a history can hold, so a copy without history ranks last.
    private static final Epoch NO_HISTORY = Epoch.epoch(Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE);

    /// Candidate order for choosing a catch-up source among NON-divergent copies: the later last epoch, then
    /// the higher head -- never the head alone (rev1569 F1: a deposed owner's longer unacked tail must not
    /// win). A copy with no history a held record reaches ranks below every copy that has one.
    public static final Comparator<LogProvenance> SOURCE_ORDER = Comparator.comparing(LogProvenance::rankEpoch).thenComparingLong(LogProvenance::head);

    public LogProvenance {
        history = List.copyOf(history);
    }

    public static LogProvenance logProvenance(long base, long low, long head, List<ProvenanceEntry> history) {
        return new LogProvenance(base, low, head, history);
    }

    /// The rank of the last history entry a held record reaches ([ProvenanceEpoch#rank]), if any: an entry starting
    /// above `head` is a ghost -- a crash between its durable record and its first frame, or an install whose apply did
    /// not happen -- and never ranks the copy (#1638 F2), whatever the trims leave. An empty copy ranks by nothing.
    public Option<Epoch> lastEpoch() {
        return lastReached().map(entry -> entry.epoch()
                                               .rank());
    }

    private Option<ProvenanceEntry> lastReached() {
        return Option.from(history.stream().filter(entry -> entry.startOffset() <= head).reduce((_, later) -> later));
    }

    /// The base of a copy with this history (spec §7.5.2): the start of a first `BASE(d)` entry, else 0.
    public static long baseOf(List<ProvenanceEntry> history) {
        return history.stream()
                      .findFirst()
                      .filter(first -> first.epoch() instanceof ProvenanceEpoch.Base)
                      .map(ProvenanceEntry::startOffset)
                      .orElse(0L);
    }

    private Epoch rankEpoch() {
        return lastEpoch().or(NO_HISTORY);
    }
}
