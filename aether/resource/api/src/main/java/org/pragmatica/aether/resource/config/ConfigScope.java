package org.pragmatica.aether.resource.config;
public enum ConfigScope {
    /// Cluster-wide configuration shared by all nodes and slices.
    GLOBAL,
    /// Node-specific configuration that overrides global.
    NODE,
    /// Slice-specific configuration that overrides node and global.
    SLICE
}
