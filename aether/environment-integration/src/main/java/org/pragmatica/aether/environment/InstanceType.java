package org.pragmatica.aether.environment;

/// Type of instance to provision. Supports on-demand and spot instance models.
public sealed interface InstanceType {
    record OnDemand() implements InstanceType {}

    record Spot() implements InstanceType {}

    InstanceType ON_DEMAND = new OnDemand();
    InstanceType SPOT = new Spot();
}
