package org.pragmatica.kafka;

/// Producer acknowledgement level. Kafka has no per-event confirmation factor: acks and ISR are broker-level.
public enum Acks {
    NONE("0"),
    LEADER("1"),
    ALL("all");
    private final String wire;
    Acks(String wire) {
        this.wire = wire;
    }
    String wire() {
        return wire;
    }
}
