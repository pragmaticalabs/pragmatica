package org.pragmatica.aether.slice;

/// Consistency mode for stream event publishing.
///
/// Controls whether events are sequenced locally (governor-local ordering)
/// or go through Rabia consensus for total order across all producers.
public enum ConsistencyMode {
    /// Governor-local sequencing (default). Events are ordered per-partition
    /// on the owning node. This is the Phase 1 behavior.
    EVENTUAL,

    /// Rabia consensus path. Events are proposed through Rabia and applied
    /// on all nodes in the same total order.
    STRONG
}
