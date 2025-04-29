package org.pragmatica.cluster.consensus.rabia;

public interface RabiaEngineConfig {
    long cleanupInterval(); //10s

    long syncRetryInterval(); //1000ms

    long maxSyncAttempts();

    long removeOlderThan(); // 100 rounds


    static Stage1 builder() {
        record config(long cleanupInterval, long syncRetryInterval, long maxSyncAttempts,
                      long removeOlderThan) implements RabiaEngineConfig {}

        return cleanupInterval -> syncRetryInterval -> maxSyncAttempts -> removeOlderThan -> new config(
                cleanupInterval, syncRetryInterval, maxSyncAttempts, removeOlderThan);
    }

    interface Stage1 {
        Stage2 cleanupInterval(long cleanupInterval);
    }

    interface Stage2 {
        Stage3 syncRetryInterval(long syncRetryInterval);
    }

    interface Stage3 {
        Stage4 maxSyncAttempts(long maxSyncAttempts);
    }

    interface Stage4 {
        RabiaEngineConfig removeOlderThan(long removeOlderThan);
    }

    static RabiaEngineConfig defaultConfig() {
        return builder()
                .cleanupInterval(10)
                .syncRetryInterval(1000)
                .maxSyncAttempts(5)
                .removeOlderThan(500);
    }

    static RabiaEngineConfig testConfig() {
        return builder()
                .cleanupInterval(10)
                .syncRetryInterval(10)
                .maxSyncAttempts(2)
                .removeOlderThan(100);
    }
}
