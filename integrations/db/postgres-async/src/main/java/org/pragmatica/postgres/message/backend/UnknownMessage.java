package org.pragmatica.postgres.message.backend;

import org.pragmatica.postgres.message.BackendMessage;

public final class UnknownMessage implements BackendMessage {

    private final byte id;

    public UnknownMessage(byte id) {
        this.id = id;
    }

    public byte getId() {
        return id;
    }

    @Override
    public String toString() {
        return "UnknownMessage(" +
                "id='" + (char) id + "')";
    }
}
