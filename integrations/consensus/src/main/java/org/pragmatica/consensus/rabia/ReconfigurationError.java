package org.pragmatica.consensus.rabia;

import org.pragmatica.lang.Cause;


public enum ReconfigurationError implements Cause {
    STATE_TRANSFER_TOO_LARGE("Checkpoint cannot be transferred within the bounded transport frame; reduce state or provision a supported transfer mechanism"),
    INVALID_BARRIER("An electorate barrier must not contain application commands"),
    PARTICIPATION_ALREADY_STARTED("Participation role is immutable once consensus startup or message handling begins"),
    NOT_PASSIVE_CLIENT("Core routing directory updates require a passive client and must exclude self"),
    NOT_ACTIVE("Only an active voter can propose an electorate change"),
    UNKNOWN_VOTER("The target electorate contains an unadmitted or non-core identity"),
    BOOTSTRAP_ALREADY_STARTED("Bootstrap electorate cannot change after synchronization starts"),
    AUTHORITY_PERSISTENCE_UNSUPPORTED("Persistence cannot store voter authority and handoff atomically"),
    INCOMPATIBLE_EPOCH("Electorate epoch or predecessor does not match current authority"),
    SUPERSEDED("A different electorate change won the consensus slot");
    private final String message;
    ReconfigurationError(String message) {
        this.message = message;
    }
    @Override
    public String message() {
        return message;
    }
}
