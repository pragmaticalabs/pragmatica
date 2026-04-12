package org.pragmatica.aether.cli.cluster;

public enum BootstrapPhase {
    VALIDATE,
    PROVISION,
    COLLECT_ADDRESSES,
    DEPLOY_RUNTIME,
    CLUSTER_FORMATION,
    POST_BOOTSTRAP
}
