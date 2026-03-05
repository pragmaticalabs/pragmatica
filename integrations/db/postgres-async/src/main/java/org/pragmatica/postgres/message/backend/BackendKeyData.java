package org.pragmatica.postgres.message.backend;

import org.pragmatica.postgres.message.BackendMessage;

public record BackendKeyData(int pid, int cancelKey) implements BackendMessage {}
