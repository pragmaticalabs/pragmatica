# Supplemental information for the detailed implementation plan

## Basic Assumptions
- Implementation **MUST** **STRICTLY** follow algorithm description
- Implementation **MUST** be **COMPLETE**, avoid simplifications, omissions or shortcuts 
- Implementation **MUST** consist of only protocol engine and protocol-related data such as messages, states, etc. 
All details like network interaction, serialization, etc. etc. **MUST** be assumed existing.
- Serialization is out of scope
- Network interaction id out of scope
- Each cluster node is identified by `NodeId`  
- Implementation **MUST** be message-driven
- Implementation **MUST** provide necessary safeguards against indefinite waiting. Use existing `SharedScheduler` API for delays and timeouts. 
- Implementation implements `Consensus` interface
- Implementation receives protocol messages via method `processMessage`
- Implementation receives new commands for the state machine in lists and wraps them into internal `Batch` wrapper.
- Implementation sends protocol messages via `ClusterNetwork` interface
- Information about cluster configuration, consensus size, etc. is available via `AddressBook` interface
- State machine is accessible via `StateMachine` interface
- Each state machine command is an implementation of the `Command` marker interface
- Each node starts with state machine in initial state
- Node remains in dormant (follower) state until it successfully synchronizes with the active nodes
- Ensure bootstrapping of the cluster consisting of fresh nodes with state machines in initial state. 
Eventually some node should kick off state synchronization and transition to active mode. 
Observe that this should happen only if enough nodes are available.  

## Files

### Consensus

`Consensus` interface:

```java

public interface Consensus<T extends ProtocolMessage, C extends Command> {
    void processMessage(T message);

    void submitCommands(List<C> commands);
}

```

`ProtocolMessage` marker interface:

```java
public interface ProtocolMessage {
}
```
`Batch` wrapper:
```java
/// Represents a proposal value in the Weak MVC protocol.
public record Batch<C extends Command>(BatchId id, long timestamp, List<C> commands) implements Comparable<Batch<C>> {
    @Override
    public int compareTo(Batch<C> o) {
        return Long.compare(timestamp(), o.timestamp());
    }
}

```

`BatchId`:

```java
public interface BatchId {
    String id();

    static BatchId create(String id) {
        record batchId(String id) implements BatchId {}

        return new batchId(id);
    }

    static BatchId createRandom() {
        return create(ULID.randomULID().encoded());
    }
}

```

### Network Interaction

`ProtocolNetwork` interface:
```java
public interface ProtocolNetwork<T extends ProtocolMessage> {
    <M extends ProtocolMessage> void broadcast(M message);

    <M extends ProtocolMessage> void send(NodeId nodeId, M message);
}
```
`NodeId`:
```java
public interface NodeId {
    String id();

    static NodeId create(String id) {
        record nodeId(String id) implements NodeId {}
        return new nodeId(id);
    }

    static NodeId createRandom() {
        return create(ULID.randomULID().encoded());
    }
}
```
`AddressBoook` interface:
```java
public interface AddressBook {
    Option<NodeInfo> get(NodeId id);

    int clusterSize();

    default int quorumSize() {
        return clusterSize()/2 + 1;
    }

    Option<NodeId> reverseLookup(SocketAddress socketAddress);
}
```

### State Machine
`Command` marker interface:
```java
public interface Command {
}
```

`StateMachine` interface:

```java
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
}
```
### Scheduler
`SharedScheduler`:
```java
public final class SharedScheduler {
    private SharedScheduler() {
    }

    private static final ScheduledExecutorService SCHEDULER = new ScheduledThreadPoolExecutor(2);

    /// Schedule one-time invocation
    public static void schedule(Runnable runnable, TimeSpan interval) {
        SCHEDULER.schedule(runnable, interval.millis(), TimeUnit.MILLISECONDS);
    }

    /// Schedule periodic invocation
    public static void scheduleAtFixedRate(Runnable runnable, TimeSpan interval) {
        SCHEDULER.scheduleAtFixedRate(runnable, interval.millis(), interval.millis(), TimeUnit.MILLISECONDS);
    }
}
```
