package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// Lifecycle status of a compute instance.
public sealed interface InstanceStatus {
    record Provisioning() implements InstanceStatus {
        public static Result<Provisioning> provisioning() {
            return success(new Provisioning());
        }
    }

    record Running() implements InstanceStatus {
        public static Result<Running> running() {
            return success(new Running());
        }
    }

    record Stopping() implements InstanceStatus {
        public static Result<Stopping> stopping() {
            return success(new Stopping());
        }
    }

    record Terminated() implements InstanceStatus {
        public static Result<Terminated> terminated() {
            return success(new Terminated());
        }
    }

    InstanceStatus PROVISIONING = Provisioning.provisioning()
                                             .unwrap();
    InstanceStatus RUNNING = Running.running()
                                   .unwrap();
    InstanceStatus STOPPING = Stopping.stopping()
                                     .unwrap();
    InstanceStatus TERMINATED = Terminated.terminated()
                                         .unwrap();

    record unused() implements InstanceStatus {
        public static Result<unused> unused() {
            return success(new unused());
        }
    }
}
