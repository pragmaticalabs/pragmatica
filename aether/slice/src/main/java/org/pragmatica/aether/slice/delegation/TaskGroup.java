package org.pragmatica.aether.slice.delegation;

import org.pragmatica.serialization.Codec;


/// Identifies a co-location group of control plane components.
/// Each group is assigned to exactly one core node at a time.
@Codec public enum TaskGroup {
    METRICS,
    SCALING,
    STRATEGIES,
    DEPLOYMENT,
    STORAGE,
    STREAMING
}
