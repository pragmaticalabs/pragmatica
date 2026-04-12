package org.pragmatica.aether.config.cluster;

public record ClusterIdentity(String name, String version) {
    public static ClusterIdentity clusterIdentity(String name, String version) {
        return new ClusterIdentity(name, version);
    }
}
