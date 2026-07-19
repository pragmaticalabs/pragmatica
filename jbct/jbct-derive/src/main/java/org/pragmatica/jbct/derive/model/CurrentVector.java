package org.pragmatica.jbct.derive.model;

import java.util.List;

/// The current position of a living system on the six vector axes (SPEC.md §3,
/// `[current_vector]`), each entry scoped, so an audit run can start from where the system
/// already is rather than from nothing.
///
/// Recovery is expressed per effectful operation with its recovery class, rather than as an
/// axis value, matching the schema's `recovery = [{ operation = "...", class = "..." }]` shape.
public record CurrentVector(List<AxisPosition> topology,
                            List<AxisPosition> substrate,
                            List<AxisPosition> readWrite,
                            List<AxisPosition> state,
                            List<AxisPosition> persistence,
                            List<RecoveryPosition> recovery) {
    public CurrentVector {
        topology = List.copyOf(topology);
        substrate = List.copyOf(substrate);
        readWrite = List.copyOf(readWrite);
        state = List.copyOf(state);
        persistence = List.copyOf(persistence);
        recovery = List.copyOf(recovery);
    }

    /// A scoped position on a single axis.
    public record AxisPosition(String value, Scope scope) {}

    /// A recovery posture for one effectful operation.
    public record RecoveryPosition(String operation, String recoveryClass) {}
}
