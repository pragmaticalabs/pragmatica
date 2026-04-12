package org.pragmatica.aether.config.cluster;

public record TimeoutsConfig(String healthCheck, String quorumFormation, String drain) {
    public static TimeoutsConfig timeoutsConfig(String healthCheck, String quorumFormation, String drain) {
        return new TimeoutsConfig(healthCheck, quorumFormation, drain);
    }

    public static TimeoutsConfig defaultTimeoutsConfig() {
        return new TimeoutsConfig("300s", "600s", "120s");
    }
}
