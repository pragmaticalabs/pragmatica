package org.pragmatica.aether.cli.cluster;


/// Bootstrap phases in the 6-phase cluster bootstrap model. Section 8.
public enum BootstrapPhase {
    VALIDATE,
    PROVISION,
    COLLECT_ADDRESSES,
    DEPLOY_RUNTIME,
    CLUSTER_FORMATION,
    POST_BOOTSTRAP
}
