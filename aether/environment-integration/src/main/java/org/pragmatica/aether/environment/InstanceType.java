package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// Type of instance to provision. Supports on-demand and spot instance models.
public sealed interface InstanceType {
    record OnDemand() implements InstanceType {
        public static Result<OnDemand> onDemand() {
            return success(new OnDemand());
        }
    }

    record Spot() implements InstanceType {
        public static Result<Spot> spot() {
            return success(new Spot());
        }
    }

    InstanceType ON_DEMAND = OnDemand.onDemand()
                                    .unwrap();
    InstanceType SPOT = Spot.spot()
                           .unwrap();

    record unused() implements InstanceType {
        public static Result<unused> unused() {
            return success(new unused());
        }
    }
}
