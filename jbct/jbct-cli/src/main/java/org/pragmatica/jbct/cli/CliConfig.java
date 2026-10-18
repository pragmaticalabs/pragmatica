package org.pragmatica.jbct.cli;

import java.io.PrintStream;
import java.nio.file.Path;

import org.pragmatica.jbct.config.ConfigLoader;
import org.pragmatica.jbct.config.JbctConfig;
import org.pragmatica.lang.Option;


/// Configuration loading shared by the commands (#1455).
///
/// A layer that cannot be used (syntax error, unreadable file, wrong-typed key) is not skipped: the
/// command stops with exit status [#CONFIG_ERROR_EXIT], and stderr names the file and the offending
/// key or line. That is the delivery of the failure to the person running the tool; nothing else
/// consumes it.
public sealed interface CliConfig {
    int CONFIG_ERROR_EXIT = 2;

    /// Load the layered configuration, printing the refusal to `err` when it fails.
    static Option<JbctConfig> load(Option<Path> explicitConfigPath, PrintStream err) {
        return ConfigLoader.load(explicitConfigPath,
                                 Option.none())
                           .onFailure(cause -> err.println("Error: " + cause.message()))
                           .option();
    }

    /// Load the layered configuration, printing the refusal to `System.err` when it fails.
    static Option<JbctConfig> load(Option<Path> explicitConfigPath) {
        return load(explicitConfigPath, System.err);
    }

    record unused() implements CliConfig {}
}
