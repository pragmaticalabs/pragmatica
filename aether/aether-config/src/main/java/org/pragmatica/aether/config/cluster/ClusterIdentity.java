package org.pragmatica.aether.config.cluster;

/// Cluster identity. S3.3
public record ClusterIdentity(String name, String version) {
    public static ClusterIdentity clusterIdentity(String name, String version) {
        return new ClusterIdentity(name, version);
    }
}
