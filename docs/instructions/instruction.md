## Detailed Implementation Plan for Rabia Consensus Protocol in Java

---

### Protocol Overview
Rabia is a randomized consensus protocol optimized for state machine replication, designed around message-driven state transitions and utilizing randomized leader elections and round-based consensus to maintain strong consistency and high availability.

### Core Components and Structures
Implement the following major classes/structures to strictly adhere to the provided assumptions:

- `RabiaConsensus`: The central consensus engine implementing `Consensus`.
- Messages: strictly defined according to Rabia protocol (Proposal, Vote, Commit, Sync, SyncResponse).
- Internal state management: Round state, commit log, vote tracking, and synchronization status.
- Timers and timeouts managed by the provided `SharedScheduler`.

---

## Implementation Structure

### 1. `RabiaConsensus` Class
Main logic encapsulation; implements provided `Consensus` interface:

```java
import java.util.concurrent.PriorityBlockingQueue;

public class RabiaConsensus implements Consensus<RabiaProtocolMessage, Command> {
    private final NodeId nodeId;
    private final ProtocolNetwork<RabiaProtocolMessage> network;
    private final AddressBook addressBook;
    private final StateMachine<Command> stateMachine;
    private final PriorityBlockingQueue<Batch> pendingBatches;

    private final RabiaRoundState roundState;
    private final RabiaCommitLog commitLog;

    public RabiaConsensus(NodeId nodeId,
                          ProtocolNetwork<RabiaProtocolMessage> network,
                          AddressBook addressBook,
                          StateMachine<Command> stateMachine) {
        this.nodeId = nodeId;
        this.network = network;
        this.addressBook = addressBook;
        this.stateMachine = stateMachine;

        this.roundState = new RabiaRoundState(addressBook);
        this.commitLog = new RabiaCommitLog(stateMachine);

        bootstrapIfNecessary();
        scheduleTimeoutChecks();
    }

    @Override
    public void processMessage(RabiaProtocolMessage message) {
        switch (message) {
            case ProposalMessage p -> handleProposal(p);
            case VoteMessage v -> handleVote(v);
            case CommitMessage c -> handleCommit(c);
            case SyncRequestMessage s -> handleSyncRequest(s);
            case SyncResponseMessage sr -> handleSyncResponse(sr);
        }
    }

    @Override
    public void submitCommands(List<Command> commands) {
        var batch = Batch.create(commands);


        if (roundState.isDormant())
    }

    private void bootstrapIfNecessary() {
        // Initiate synchronization if fresh start.
        if (roundState.isDormant() && enoughNodesAvailable()) {
            broadcastSyncRequest();
        }
    }

    private void scheduleTimeoutChecks() {
        SharedScheduler.scheduleAtFixedRate(this::handleTimeouts, TimeSpan.seconds(1));
    }

    private void handleTimeouts() {
        roundState.handleRoundTimeout(() -> initiateNewRound());
    }

    private boolean enoughNodesAvailable() {
        return addressBook.clusterSize() >= addressBook.quorumSize();
    }

    private void initiateProposal(List<Command> commands) {
        var proposal = new ProposalMessage(nodeId, roundState.currentRound(), commands);
        network.broadcast(proposal);
        roundState.recordProposal(proposal);
    }

    // Message handling methods below
}
```

---

### 2. Rabia Protocol Messages
Strictly defined messages (`RabiaProtocolMessage` implementations):

```java
sealed interface RabiaProtocolMessage extends ProtocolMessage
    permits ProposalMessage, VoteMessage, CommitMessage, SyncRequestMessage, SyncResponseMessage {}

record ProposalMessage(NodeId leaderId, long round, List<Command> commands) implements RabiaProtocolMessage {}

record VoteMessage(NodeId voterId, long round, byte[] proposalHash) implements RabiaProtocolMessage {}

record CommitMessage(long round, byte[] proposalHash) implements RabiaProtocolMessage {}

record SyncRequestMessage(NodeId requesterId) implements RabiaProtocolMessage {}

record SyncResponseMessage(NodeId responderId, long round, byte[] snapshot) implements RabiaProtocolMessage {}
```

---

### 3. Internal Round State (`RabiaRoundState`)
Tracks rounds, leadership, and votes:

```java
public class RabiaRoundState {
    private final AddressBook addressBook;
    private long currentRound;
    private NodeId leader;
    private final Map<Long, Set<NodeId>> votesReceived = new HashMap<>();
    private Optional<ProposalMessage> currentProposal = Optional.empty();
    private boolean dormant = true;

    public RabiaRoundState(AddressBook addressBook) {
        this.addressBook = addressBook;
        this.currentRound = 0;
    }

    public boolean isLeader(NodeId nodeId) {
        return Objects.equals(leader, nodeId);
    }

    public boolean isDormant() {
        return dormant;
    }

    public void recordProposal(ProposalMessage proposal) {
        currentProposal = Optional.of(proposal);
        dormant = false;
    }

    public void handleVote(VoteMessage vote, Runnable onQuorumReached) {
        votesReceived.computeIfAbsent(vote.round(), k -> new HashSet<>()).add(vote.voterId());
        if (votesReceived.get(vote.round()).size() >= addressBook.quorumSize()) {
            onQuorumReached.run();
        }
    }

    public long currentRound() {
        return currentRound;
    }

    public void handleRoundTimeout(Runnable onTimeout) {
        currentRound++;
        leader = selectLeaderRandomly();
        currentProposal = Optional.empty();
        votesReceived.clear();
        onTimeout.run();
    }

    private NodeId selectLeaderRandomly() {
        var ids = addressBook.allNodeIds();
        return ids.get(ThreadLocalRandom.current().nextInt(ids.size()));
    }
}
```

---

### 4. Commit Log Management (`RabiaCommitLog`)
Handles command commits and state-machine updates:

```java
public class RabiaCommitLog {
    private final StateMachine<Command> stateMachine;
    private final Set<byte[]> committedProposals = new HashSet<>();

    public RabiaCommitLog(StateMachine<Command> stateMachine) {
        this.stateMachine = stateMachine;
    }

    public void commitProposal(ProposalMessage proposal) {
        var hash = hash(proposal);
        if (committedProposals.add(hash)) {
            proposal.commands().forEach(stateMachine::process);
        }
    }

    private byte[] hash(ProposalMessage proposal) {
        return MessageDigest.getInstance("SHA-256").digest(proposal.toString().getBytes(StandardCharsets.UTF_8));
    }
}
```

---

### 5. Synchronization Logic
Handles cluster bootstrapping and late-node synchronization:

- `SyncRequest` initiates synchronization.
- `SyncResponse` returns a snapshot to synchronize dormant nodes.

```java
private void handleSyncRequest(SyncRequestMessage request) {
    var snapshot = stateMachine.makeSnapshot().getOrThrow();
    network.send(request.requesterId(), new SyncResponseMessage(nodeId, roundState.currentRound(), snapshot));
}

private void handleSyncResponse(SyncResponseMessage response) {
    stateMachine.restoreSnapshot(response.snapshot());
    roundState.syncToRound(response.round());
    roundState.wakeFromDormant();
}
```

---

### 6. Timeouts and Safeguards
Using `SharedScheduler` to enforce bounded delays on message handling and round initiation:

- Proposal timeout handling.
- Round timeout handling.

---

### Bootstrapping Behavior
- Node starts dormant.
- Node attempts synchronization upon availability of quorum-sized cluster.
- Node transitions to active after successful synchronization.

---

### Execution Guarantees
- **Message-Driven:** All behavior triggered via incoming `processMessage` calls.
- **Determinism:** State machine commands deterministically executed.
- **Quorum Enforcement:** All state changes require quorum agreement.

---

## Deliverables
- Complete Java implementation according to Rabia protocol specifications and the supplemental assumptions.
- Thorough encapsulation of consensus logic strictly separate from networking and serialization layers.
- Timed and guarded state transitions to avoid indefinite waits and ensure liveness.

---

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
