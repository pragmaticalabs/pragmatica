package org.pragmatica.aether.environment.docker;

import org.pragmatica.lang.Promise;

import java.util.List;


/// Abstraction for executing Docker CLI commands.
/// Enables testing by allowing injection of a stub implementation.
public interface DockerCommandRunner {
    Promise<String> execute(List<String> command);
}
