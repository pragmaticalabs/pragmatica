package org.pragmatica.aether.update;

/// Deployment strategy type for version transitions.
///
/// Each strategy controls how traffic is shifted from old to new version:
///   - CANARY: Progressive multi-stage traffic shifting with observation periods
///   - BLUE_GREEN: Atomic traffic switch between two environments
///   - ROLLING: Simple two-phase deploy-then-route update
public enum DeploymentStrategy {
    CANARY,
    BLUE_GREEN,
    ROLLING
}
