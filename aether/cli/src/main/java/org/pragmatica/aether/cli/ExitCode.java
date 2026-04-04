package org.pragmatica.aether.cli;

public sealed interface ExitCode {
    int SUCCESS = 0;

    int ERROR = 1;

    int TIMEOUT = 2;

    int NOT_FOUND = 3;

    record unused() implements ExitCode{}
}
