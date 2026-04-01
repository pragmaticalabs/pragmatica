package org.pragmatica.aether.config.cluster;
public record WorkerSpec( int count) {
    /// Factory method.
    public static WorkerSpec workerSpec(int count) {
        return new WorkerSpec(count);
    }

    /// Default: no workers.
    public static WorkerSpec defaultWorkerSpec() {
        return new WorkerSpec(0);
    }
}
