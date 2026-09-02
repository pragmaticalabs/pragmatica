package org.pragmatica.jbct.derive.model;

import java.util.List;

import org.pragmatica.lang.Option;

/// A verification floor (SPEC.md §3, `[[floors]]`): the physical hop budget for one path, the
/// arithmetic the verification step needs. A floor the sheet does not supply produces an explicit
/// `UNVERIFIED`, never a silently assumed default — but verification itself is a Phase-B concern;
/// Phase A only parses and carries floors.
public record Floor(Scope path, List<Hop> hops) {
    public Floor {
        hops = List.copyOf(hops);
    }

    /// A single hop on a path with its optional p50 latency in milliseconds.
    ///
    /// Only `p50_ms` is modeled in schema v0.1; richer hop budgets are a Phase-B extension.
    public record Hop(String name, Option<Long> p50Ms) {}
}
