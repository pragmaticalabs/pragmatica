package org.pragmatica.aether.config.cluster;

public record WorkerSpec(int count) {
    public static WorkerSpec workerSpec(int count) {
        return new WorkerSpec(count);
    }

    public static WorkerSpec defaultWorkerSpec() {
        return new WorkerSpec(0);
    }
}
