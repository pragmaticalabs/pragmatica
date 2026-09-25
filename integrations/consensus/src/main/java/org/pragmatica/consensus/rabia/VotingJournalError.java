package org.pragmatica.consensus.rabia;

import org.pragmatica.lang.Cause;


public enum VotingJournalError implements Cause {
    IN_USE("Consensus directory is already owned by a running process"),
    CLOSED("Consensus persistence is closed"),
    UNSUPPORTED("Persistence adapter does not preserve voting history"),
    CORRUPT("Consensus journal checksum, schema, or sequence is invalid"),
    TORN_TAIL("Consensus journal contains an incomplete frame; explicit recovery is required"),
    CAPACITY("Consensus journal record or retained history exceeds its bounded capacity"),
    CONFLICT("A durable proposal, ballot, or decision conflicts with an earlier promise"),
    WRONG_NODE("Consensus journal belongs to a different node identity"),
    GAP("Consensus journal does not contain a contiguous applied prefix");
    private final String message;
    VotingJournalError(String message) {
        this.message = message;
    }
    @Override
    public String message() {
        return message;
    }
}
