package org.pragmatica.aether.config;
/// Placement policy for slice deployment — determines where slices run.
public enum PlacementPolicy {
    /// Slices run only on core consensus nodes (default, backward-compatible).
    CORE_ONLY,
    /// Slices prefer worker nodes; fall back to core if no workers available.
    WORKERS_PREFERRED,
    /// Slices run exclusively on workers; fail if no workers available.
    WORKERS_ONLY,
    /// Slices may run on any node (core or worker).
    ALL
}
