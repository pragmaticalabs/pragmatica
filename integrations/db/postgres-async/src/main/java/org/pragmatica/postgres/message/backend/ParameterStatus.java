package org.pragmatica.postgres.message.backend;

import org.pragmatica.postgres.message.BackendMessage;

public final class ParameterStatus implements BackendMessage {

    private final String name;
    private final String value;

    public ParameterStatus(String name, String value) {
        this.name = name;
        this.value = value;
    }

    @Override
    public String toString() {
        return "ParameterStatus(" +
                "name='" + name + '\'' +
                ", value='" + value + '\'' +
                ')';
    }
}
