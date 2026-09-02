package org.pragmatica.jbct.derive.result;

import java.util.List;

import org.pragmatica.jbct.derive.model.Axis;

/// A combination check (SPEC.md §4 press, F24/F26): two or more moving pressures from *different*
/// questions that converge on the same axis. Combinations are evaluated after singles and are
/// first-class output — convergence is itself a finding (the "own shape diverges" trigger is a
/// combination, not a single answer).
public record Combination(Axis axis, List<Pressure> members, String note) {
    public Combination {
        members = List.copyOf(members);
    }
}
