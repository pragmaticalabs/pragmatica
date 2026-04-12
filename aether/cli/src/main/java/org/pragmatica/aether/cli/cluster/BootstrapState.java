package org.pragmatica.aether.cli.cluster;

import java.util.EnumMap;
import java.util.Map;


/// Bootstrap state for resume support. Section 13.1.
///
/// Persisted as JSON to `~/.aether/clusters/<name>/bootstrap-state.json` so that
/// a failed bootstrap can be resumed from the last completed phase.
public record BootstrapState(String clusterName,
                             String configHash,
                             String startedAt,
                             Map<BootstrapPhase, PhaseStatus> phases) {
    public static BootstrapState bootstrapState(String clusterName,
                                                String configHash,
                                                String startedAt,
                                                Map<BootstrapPhase, PhaseStatus> phases) {
        return new BootstrapState(clusterName, configHash, startedAt, Map.copyOf(phases));
    }

    public static BootstrapState initialState(String clusterName, String configHash, String startedAt) {
        var phases = new EnumMap<BootstrapPhase, PhaseStatus>(BootstrapPhase.class);
        for (var phase : BootstrapPhase.values()) {phases.put(phase, PhaseStatus.PENDING);}
        return new BootstrapState(clusterName, configHash, startedAt, Map.copyOf(phases));
    }

    public enum PhaseStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }
}
