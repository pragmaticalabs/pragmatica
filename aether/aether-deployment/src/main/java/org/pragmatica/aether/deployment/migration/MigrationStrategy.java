package org.pragmatica.aether.deployment.migration;


/// Strategy for executing a cross-environment migration.
public enum MigrationStrategy {
    /// Migrate one node at a time: provision target, sync, drain source, repeat.
    ROLLING,

    /// Provision all target nodes first, sync, then switch DNS and drain all source nodes.
    BLUE_GREEN
}
