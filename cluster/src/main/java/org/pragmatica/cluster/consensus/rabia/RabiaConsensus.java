package org.pragmatica.cluster.consensus.rabia;

import org.pragmatica.cluster.consensus.Consensus;
import org.pragmatica.cluster.net.ClusterNetwork;
import org.pragmatica.cluster.net.NodeId;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class RabiaConsensus implements Consensus<RabiaMessage> {
    private final NodeId nodeId;
    private final Set<NodeId> clusterNodes;
    private final int quorumSize;
    private final ClusterNetwork<RabiaMessage> network;
    private final Consumer<byte[]> commandExecutor;
    
    // Local state
    private final ConcurrentLinkedQueue<byte[]> commandBuffer = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<UUID, Batch> batches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, VoteSet> voteSets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<NodeId>> receivedVotes = new ConcurrentHashMap<>();
    private final AtomicLong sequenceNumber = new AtomicLong(0);
    private final Random random = new Random();
    
    // Configuration
    private static final int BATCH_SIZE = 100;
    private static final long BATCH_TIMEOUT_MS = 1000;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 100;
    
    // Enhanced state tracking
    private final ConcurrentHashMap<UUID, ProposalState> proposalStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastProposalTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> retryCounts = new ConcurrentHashMap<>();
    
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    
    private final AtomicLong currentView = new AtomicLong(0);
    
    // Enhanced command selection state
    private static class CommandSelection {
        private final List<byte[]> commands;
        private final long selectionTimestamp;
        private final UUID selectionId;
        private volatile boolean selected;
        
        public CommandSelection(List<byte[]> commands) {
            this.commands = commands;
            this.selectionTimestamp = System.currentTimeMillis();
            this.selectionId = UUID.randomUUID();
            this.selected = false;
        }
        
        public List<byte[]> getCommands() {
            return commands;
        }
        
        public long getSelectionTimestamp() {
            return selectionTimestamp;
        }
        
        public UUID getSelectionId() {
            return selectionId;
        }
        
        public boolean isSelected() {
            return selected;
        }
        
        public void setSelected(boolean selected) {
            this.selected = selected;
        }
    }
    
    private final ConcurrentHashMap<UUID, CommandSelection> commandSelections = new ConcurrentHashMap<>();
    private final PriorityQueue<CommandSelection> selectionQueue = new PriorityQueue<>(
        Comparator.comparingLong(CommandSelection::getSelectionTimestamp)
    );
    
    private final StateMachine<StateMachineCommand> stateMachine;
    private final AtomicLong sequenceNumber = new AtomicLong(0);
    private final ConcurrentHashMap<UUID, StateSyncRequest> pendingSyncRequests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService syncScheduler = Executors.newSingleThreadScheduledExecutor();
    
    public RabiaConsensus(NodeId nodeId, 
                         Set<NodeId> clusterNodes, 
                         ClusterNetwork<RabiaMessage> network,
                         Consumer<byte[]> commandExecutor,
                         StateMachine<StateMachineCommand> stateMachine) {
        this.nodeId = nodeId;
        this.clusterNodes = new ConcurrentHashMap.KeySetView<>(clusterNodes, false);
        this.quorumSize = (clusterNodes.size() / 2) + 1;
        this.network = network;
        this.commandExecutor = commandExecutor;
        this.stateMachine = stateMachine;
        
        // Start periodic batching
        scheduler.scheduleAtFixedRate(this::processCommandBuffer, 
                                    BATCH_TIMEOUT_MS, 
                                    BATCH_TIMEOUT_MS, 
                                    TimeUnit.MILLISECONDS);
        
        // Register network listener
        network.listen(this::processMessage);
    }
    
    @Override
    public void processMessage(RabiaMessage message) {
        if (message instanceof StateSyncRequest request) {
            handleStateSyncRequest(request);
        } else if (message instanceof StateSyncResponse response) {
            handleStateSyncResponse(response);
        } else if (message instanceof SelectionProposal proposal) {
            handleSelectionProposal(proposal);
        } else if (message instanceof SelectionVote vote) {
            handleSelectionVote(vote);
        } else if (message instanceof ProposalMessage proposal) {
            handleProposal(proposal);
        } else if (message instanceof VoteMessage vote) {
            handleVote(vote);
        } else if (message instanceof CommitMessage commit) {
            handleCommit(commit);
        }
    }
    
    public void submitCommand(byte[] command) {
        commandBuffer.offer(command);
    }
    
    private void processCommandBuffer() {
        List<byte[]> batchCommands = new ArrayList<>();
        byte[] command;
        
        while (batchCommands.size() < BATCH_SIZE && (command = commandBuffer.poll()) != null) {
            batchCommands.add(command);
        }
        
        if (!batchCommands.isEmpty()) {
            // Create a new command selection
            CommandSelection selection = new CommandSelection(batchCommands);
            commandSelections.put(selection.getSelectionId(), selection);
            selectionQueue.offer(selection);
            
            // Start the selection process
            startSelectionProcess(selection);
        }
    }
    
    private void startSelectionProcess(CommandSelection selection) {
        // Select a random subset of nodes for this selection
        Set<NodeId> selectionNodes = selectRandomRecipients();
        
        // Create and send selection proposal
        SelectionProposal proposal = new SelectionProposal(
            UUID.randomUUID(),
            System.currentTimeMillis(),
            nodeId.id(),
            selection.getSelectionId(),
            selection.getCommands(),
            selection.getSelectionTimestamp()
        );
        
        for (NodeId node : selectionNodes) {
            network.send(node, proposal);
        }
    }
    
    private void handleSelectionProposal(SelectionProposal proposal) {
        CommandSelection selection = commandSelections.computeIfAbsent(
            proposal.selectionId(),
            k -> new CommandSelection(proposal.commands())
        );
        
        // Only process if this is a newer selection
        if (proposal.selectionTimestamp() > selection.getSelectionTimestamp()) {
            selection = new CommandSelection(proposal.commands());
            commandSelections.put(proposal.selectionId(), selection);
        }
        
        // Vote on the selection
        broadcastSelectionVote(proposal.selectionId(), true);
    }
    
    private void handleSelectionVote(SelectionVote vote) {
        CommandSelection selection = commandSelections.get(vote.selectionId());
        if (selection != null && !selection.isSelected()) {
            VoteSet voteSet = voteSets.computeIfAbsent(vote.selectionId(), k -> new VoteSet());
            
            if (vote.accepted()) {
                voteSet.addAcceptedVote(NodeId.create(vote.senderId()));
                
                // Check for quorum
                if (voteSet.hasQuorum(quorumSize)) {
                    selection.setSelected(true);
                    executeCommands(selection.getCommands());
                    cleanupSelection(vote.selectionId());
                }
            }
        }
    }
    
    private void cleanupSelection(UUID selectionId) {
        commandSelections.remove(selectionId);
        selectionQueue.removeIf(s -> s.getSelectionId().equals(selectionId));
    }
    
    private void handleProposal(ProposalMessage proposal) {
        ProposalState state = proposalStates.computeIfAbsent(proposal.batchId(), 
            k -> new ProposalState(proposal.commands()));
            
        // Only process if we haven't seen this proposal before
        if (state.getLastSeenTime() < proposal.timestamp()) {
            state.setLastSeenTime(proposal.timestamp());
            
            // Vote for the proposal if it's the first one we've seen
            if (!votes.containsKey(proposal.batchId())) {
                votes.put(proposal.batchId(), new VoteCount());
                broadcastVote(proposal.batchId(), true);
            }
        }
    }
    
    private void handleVote(VoteMessage vote) {
        VoteSet voteSet = voteSets.computeIfAbsent(vote.batchId(), k -> new VoteSet());
        
        // Check if vote is from current view
        if (vote.viewNumber() != currentView.get()) {
            // If not, we might need to trigger a view change
            handleViewChange(vote.viewNumber());
            return;
        }
        
        NodeId voter = NodeId.create(vote.senderId());
        
        // Only process vote if we haven't seen it before
        if (!voteSet.getAcceptedVotes().contains(voter) && 
            !voteSet.getRejectedVotes().contains(voter)) {
            
            if (vote.accepted()) {
                voteSet.addAcceptedVote(voter);
            } else {
                voteSet.addRejectedVote(voter);
            }
            
            // Check for quorum
            if (voteSet.hasQuorum(quorumSize) && !voteSet.isCommitted()) {
                ProposalState state = proposalStates.get(vote.batchId());
                if (state != null) {
                    // Mark as committed
                    voteSet.setCommitted(true);
                    state.setCommitted(true);
                    
                    // Broadcast commit
                    broadcastCommit(vote.batchId(), state.getCommands());
                    
                    // Execute commands
                    executeCommands(state.getCommands());
                    
                    // Cleanup
                    cleanupProposal(vote.batchId());
                }
            }
        }
    }
    
    private void handleViewChange(long newViewNumber) {
        if (newViewNumber > currentView.get()) {
            // Update current view
            currentView.set(newViewNumber);
            
            // Reset vote sets for new view
            voteSets.clear();
            
            // Broadcast view change notification
            broadcastViewChange(newViewNumber);
        }
    }
    
    private void broadcastViewChange(long newViewNumber) {
        // In a real implementation, this would broadcast to all nodes
        // For now, we'll just log it
        System.out.println("View change to: " + newViewNumber);
    }
    
    private void broadcastVote(UUID batchId, boolean accepted) {
        VoteMessage vote = new VoteMessage(
            UUID.randomUUID(),
            System.currentTimeMillis(),
            nodeId.id(),
            batchId,
            accepted,
            currentView.get()  // Include current view number
        );
        
        network.broadcast(vote);
    }
    
    private void handleCommit(CommitMessage commit) {
        Batch batch = batches.get(commit.batchId());
        if (batch != null && !batch.isCommitted()) {
            batch.setCommitted(true);
            executeCommands(batch.commands());
        }
    }
    
    private void executeCommands(List<byte[]> commands) {
        for (byte[] command : commands) {
            commandExecutor.accept(command);
        }
    }
    
    private void cleanupProposal(UUID batchId) {
        proposalStates.remove(batchId);
        lastProposalTimes.remove(batchId);
        retryCounts.remove(batchId);
        votes.remove(batchId);
        receivedVotes.remove(batchId);
        batches.remove(batchId);
    }
    
    private void handleStateSyncRequest(StateSyncRequest request) {
        // Only respond if we have a more recent state
        if (sequenceNumber.get() > request.lastKnownSequence()) {
            stateMachine.makeSnapshot()
                       .onSuccess(snapshot -> {
                           StateSyncResponse response = new StateSyncResponse(
                               UUID.randomUUID(),
                               System.currentTimeMillis(),
                               nodeId.id(),
                               sequenceNumber.get(),
                               snapshot,
                               true
                           );
                           network.send(NodeId.create(request.senderId()), response);
                       })
                       .onFailure(error -> {
                           // Log error but don't respond
                           System.err.println("Failed to create snapshot: " + error);
                       });
        }
    }
    
    private void handleStateSyncResponse(StateSyncResponse response) {
        // Only process if this is a response to our pending request
        StateSyncRequest request = pendingSyncRequests.get(response.messageId());
        if (request != null && response.sequenceNumber() > sequenceNumber.get()) {
            stateMachine.restoreSnapshot()
                       .onSuccess(unit -> {
                           sequenceNumber.set(response.sequenceNumber());
                           pendingSyncRequests.remove(response.messageId());
                           // Now we can start participating in consensus
                           startConsensusParticipation();
                       })
                       .onFailure(error -> {
                           System.err.println("Failed to restore snapshot: " + error);
                           // Retry state sync after a delay
                           syncScheduler.schedule(() -> requestStateSync(), 5, TimeUnit.SECONDS);
                       });
        }
    }
    
    public void joinCluster() {
        // Request state synchronization from the cluster
        requestStateSync();
    }
    
    private void requestStateSync() {
        StateSyncRequest request = new StateSyncRequest(
            UUID.randomUUID(),
            System.currentTimeMillis(),
            nodeId.id(),
            sequenceNumber.get()
        );
        
        pendingSyncRequests.put(request.messageId(), request);
        network.broadcast(request);
        
        // Set a timeout for the sync request
        syncScheduler.schedule(() -> {
            if (pendingSyncRequests.containsKey(request.messageId())) {
                // Sync request timed out, retry
                pendingSyncRequests.remove(request.messageId());
                requestStateSync();
            }
        }, 30, TimeUnit.SECONDS);
    }
    
    private void startConsensusParticipation() {
        // Start participating in consensus after state sync
        // This could include joining the view change protocol,
        // starting to process commands, etc.
        System.out.println("Node " + nodeId + " joined consensus with sequence number " + sequenceNumber.get());
    }
    
    public void shutdown() {
        scheduler.shutdown();
        syncScheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!syncScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                syncScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            syncScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    // Helper classes
    private static class Batch {
        private final List<byte[]> commands;
        private volatile boolean committed;
        
        public Batch(List<byte[]> commands) {
            this.commands = commands;
            this.committed = false;
        }
        
        public List<byte[]> commands() {
            return commands;
        }
        
        public boolean isCommitted() {
            return committed;
        }
        
        public void setCommitted(boolean committed) {
            this.committed = committed;
        }
    }
    
    private static class VoteCount {
        private final AtomicLong accepted = new AtomicLong(0);
        private final AtomicLong rejected = new AtomicLong(0);
        
        public void incrementAccepted() {
            accepted.incrementAndGet();
        }
        
        public void incrementRejected() {
            rejected.incrementAndGet();
        }
        
        public long getAccepted() {
            return accepted.get();
        }
        
        public long getRejected() {
            return rejected.get();
        }
    }
    
    // Enhanced state tracking class
    private static class ProposalState {
        private final List<byte[]> commands;
        private volatile boolean committed;
        private volatile long lastSeenTime;
        
        public ProposalState(List<byte[]> commands) {
            this.commands = commands;
            this.committed = false;
            this.lastSeenTime = System.currentTimeMillis();
        }
        
        public List<byte[]> getCommands() {
            return commands;
        }
        
        public boolean isCommitted() {
            return committed;
        }
        
        public void setCommitted(boolean committed) {
            this.committed = committed;
        }
        
        public long getLastSeenTime() {
            return lastSeenTime;
        }
        
        public void setLastSeenTime(long lastSeenTime) {
            this.lastSeenTime = lastSeenTime;
        }
    }
    
    // Enhanced voting state
    private static class VoteSet {
        private final Set<NodeId> acceptedVotes = ConcurrentHashMap.newKeySet();
        private final Set<NodeId> rejectedVotes = ConcurrentHashMap.newKeySet();
        private final AtomicLong viewNumber = new AtomicLong(0);
        private volatile boolean committed = false;
        
        public void addAcceptedVote(NodeId nodeId) {
            acceptedVotes.add(nodeId);
        }
        
        public void addRejectedVote(NodeId nodeId) {
            rejectedVotes.add(nodeId);
        }
        
        public boolean hasQuorum(int quorumSize) {
            return acceptedVotes.size() >= quorumSize;
        }
        
        public boolean isCommitted() {
            return committed;
        }
        
        public void setCommitted(boolean committed) {
            this.committed = committed;
        }
        
        public long getViewNumber() {
            return viewNumber.get();
        }
        
        public void incrementViewNumber() {
            viewNumber.incrementAndGet();
        }
        
        public Set<NodeId> getAcceptedVotes() {
            return Collections.unmodifiableSet(acceptedVotes);
        }
        
        public Set<NodeId> getRejectedVotes() {
            return Collections.unmodifiableSet(rejectedVotes);
        }
    }
    
    private Set<NodeId> selectRandomRecipients() {
        List<NodeId> nodes = new ArrayList<>(clusterNodes);
        Collections.shuffle(nodes, random);
        
        // Enhanced selection: ensure at least one node from each region/zone
        // This is a simplified version - in production, you'd want to consider
        // actual network topology and latency
        int subsetSize = Math.min(quorumSize, nodes.size());
        Set<NodeId> selected = new HashSet<>();
        
        // First, ensure we have at least one node from each "region"
        // (In a real implementation, you'd want to group nodes by region/zone)
        int regions = Math.min(3, nodes.size()); // Assuming 3 regions for example
        for (int i = 0; i < regions; i++) {
            selected.add(nodes.get(i));
        }
        
        // Then fill the rest randomly
        while (selected.size() < subsetSize) {
            selected.add(nodes.get(random.nextInt(nodes.size())));
        }
        
        return selected;
    }
} 