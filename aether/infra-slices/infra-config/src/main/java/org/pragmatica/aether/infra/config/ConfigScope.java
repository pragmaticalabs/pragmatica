package org.pragmatica.aether.infra.config;
/**
 * Configuration scopes for hierarchical config lookup.
 * Values at more specific scopes override less specific ones.
 */
public enum ConfigScope {
    /**
     * Cluster-wide configuration shared by all nodes and slices.
     */
    GLOBAL,
    /**
     * Node-specific configuration that overrides global.
     */
    NODE,
    /**
     * Slice-specific configuration that overrides node and global.
     */
    SLICE
}
