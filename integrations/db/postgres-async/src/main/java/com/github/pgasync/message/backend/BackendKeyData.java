package com.github.pgasync.message.backend;

import com.github.pgasync.message.BackendMessage;

public record BackendKeyData(int pid, int cancelKey) implements BackendMessage {}
