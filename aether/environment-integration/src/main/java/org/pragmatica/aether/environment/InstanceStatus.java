package org.pragmatica.aether.environment;

/// Lifecycle status of a compute instance.
public sealed interface InstanceStatus {
    record Provisioning() implements InstanceStatus {}

    record Running() implements InstanceStatus {}

    record Stopping() implements InstanceStatus {}

    record Terminated() implements InstanceStatus {}

    InstanceStatus PROVISIONING = new Provisioning();
    InstanceStatus RUNNING = new Running();
    InstanceStatus STOPPING = new Stopping();
    InstanceStatus TERMINATED = new Terminated();
}
