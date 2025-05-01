package org.pragmatica.cluster.consensus;

import org.pragmatica.cluster.state.Command;
import org.pragmatica.utility.Sleep;

import java.util.List;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Generalized Consensus API
public interface Consensus<T extends ProtocolMessage, C extends Command> {
    /// Entry point for all protocol messages received from network
    void processMessage(T message);

    /// Attempts to submit a batch of commands for the replicated state machine.
    /// Attempt may fail (return `false`) if node is dormant (not yet active or there is no quorum).
    boolean trySubmitCommands(List<C> commands);

    /// Convenience wrapper for `trySubmitCommands`, which repeats submission until node is ready.
    default void submitCommands(List<C> commands) {
        while (!trySubmitCommands(commands)) {
            Sleep.sleep(timeSpan(100).millis());
        }
    }
}
