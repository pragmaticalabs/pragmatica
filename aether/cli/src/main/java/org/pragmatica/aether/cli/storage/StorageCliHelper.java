package org.pragmatica.aether.cli.storage;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.lang.Cause;


/// Shared utilities for storage CLI commands.
final class StorageCliHelper {
    private StorageCliHelper() {}

    static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }
}
