package org.pragmatica.cluster.consensus;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

public interface StateMachine {
    /**
     * Process a command and update the state machine's state.
     * The command must be immutable and its execution must be deterministic.
     *
     * @param command The command to process
     */
    void process(Command command);

    /**
     * Create a snapshot of the current state machine state.
     * The snapshot should be serializable and should capture the complete state.
     *
     * @return A Result containing the serialized state snapshot
     */
    Result<byte[]> makeSnapshot();

    /**
     * Restore the state machine's state from a snapshot.
     * This should completely replace the current state with the state from the snapshot.
     *
     * @return A Result indicating success or failure of the restoration
     */
    Result<Unit> restoreSnapshot(byte[] snapshot);
}
