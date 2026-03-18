package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.slice.kvstore.AetherKey.*;

import java.util.Set;

/// Identifies ephemeral KV-Store key types that should be excluded from backup/restore.
///
/// Ephemeral keys represent transient control plane state that is automatically rebuilt
/// when nodes join the cluster. Backing them up causes stale references to nodes that
/// may no longer exist on restore.
///
/// Persistent keys (blueprints, slice targets, scheduled tasks, config, etc.) represent
/// operator-defined desired state that must survive cluster restarts.
@SuppressWarnings("JBCT-UTIL-02")
public sealed interface EphemeralKeys {
    /// Key types that are rebuilt automatically and must NOT be backed up.
    Set<Class<? extends AetherKey>> EPHEMERAL_KEY_TYPES = Set.of(NodeArtifactKey.class,
                                                                 NodeRoutesKey.class,
                                                                 NodeLifecycleKey.class,
                                                                 EndpointKey.class,
                                                                 ActivationDirectiveKey.class,
                                                                 GovernorAnnouncementKey.class,
                                                                 SliceNodeKey.class,
                                                                 HttpNodeRouteKey.class);

    /// TOML section names corresponding to ephemeral key types.
    Set<String> EPHEMERAL_SECTIONS = Set.of("node-artifact",
                                            "node-routes",
                                            "node-lifecycle",
                                            "endpoints",
                                            "activation",
                                            "governor-announcement",
                                            "slices",
                                            "http-node-routes");

    /// Returns true if the given key is ephemeral and should be excluded from backup.
    static boolean isEphemeral(AetherKey key) {
        return EPHEMERAL_KEY_TYPES.contains(key.getClass());
    }

    /// Returns true if the given TOML section name corresponds to ephemeral keys.
    static boolean isEphemeralSection(String section) {
        return EPHEMERAL_SECTIONS.contains(section);
    }

    record unused() implements EphemeralKeys {}
}
