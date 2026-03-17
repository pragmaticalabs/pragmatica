package org.pragmatica.aether.slice;
/// Determines how a scheduled task fires across cluster nodes.
///
/// - `SINGLE` (default): leader fires once, SliceInvoker routes to one instance
/// - `ALL`: every node that has the slice deployed fires independently
public enum ExecutionMode {
    SINGLE,
    ALL
}
