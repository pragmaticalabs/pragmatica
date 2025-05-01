package org.pragmatica.cluster.state;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.function.Consumer;

/// Generalized state machine which can be replicated across cluster.
public interface StateMachine<T extends Command> {
    /// Process a command and update the machine's state.
    /// The command must be immutable and its execution must be deterministic.
    ///
    /// @param command The command to process
    void process(T command);

    /// Create a snapshot of the current state machine state.
    /// The snapshot should be serializable and should capture the complete state.
    ///
    /// @return A Result containing the serialized state snapshot
    Result<byte[]> makeSnapshot();

    /// Restore the state machine's state from a snapshot.
    /// This should completely replace the current state with the state from the snapshot.
    ///
    /// @return A Result indicating success or failure of the restoration
    Result<Unit> restoreSnapshot(byte[] snapshot);

    /// Register an observer to be notified of state changes in the state machine.
    /// The observer will be called whenever the state machine's state is modified by a command.
    ///
    /// @param observer Internal state change observer
    void observeStateChanges(Consumer<? super Notification> observer);

    /// Reset state machine to its initial state
    default void reset() {
        // TODO: implement reset method, do not forget to fix the tests.
    }
}
