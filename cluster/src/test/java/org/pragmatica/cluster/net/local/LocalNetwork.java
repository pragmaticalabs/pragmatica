package org.pragmatica.cluster.net.local;

import org.pragmatica.cluster.consensus.ProtocolMessage;
import org.pragmatica.cluster.net.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/// Local network implementation suitable for testing purposes
public class LocalNetwork<T extends ProtocolMessage> implements ClusterNetwork<T> {
    private final Map<NodeId, Consumer<T>> nodes = new ConcurrentHashMap<>();
    private final AddressBook addressBook;
    private Consumer<QuorumState> quorumObserver = _ -> {};

    public List<NodeId> connectedNodes() {
        return List.copyOf(nodes.keySet());
    }

    // Fault injection support
    public enum FaultType {
        MESSAGE_LOSS,
        MESSAGE_DELAY,
        MESSAGE_DUPLICATE,
        MESSAGE_REORDER,
        NODE_CRASH,
        NODE_PARTITION,
        NODE_BYZANTINE
    }
    
    // Fault injection configuration
    private FaultInjector faultInjector;
    private final Map<NodeId, List<NodeId>> partitions = new ConcurrentHashMap<>();

    public LocalNetwork(AddressBook addressBook) {
        this(addressBook, new FaultInjector());
    }
    
    public LocalNetwork(AddressBook addressBook, FaultInjector faultInjector) {
        this.addressBook = addressBook;
        this.faultInjector = faultInjector;
    }

    @Override
    public <M extends ProtocolMessage> void broadcast(M message) {
        nodes.keySet().forEach(nodeId -> send(nodeId, message));
    }

    @SuppressWarnings("unchecked")
    @Override
    public <M extends ProtocolMessage> void send(NodeId nodeId, M message) {
        // For Byzantine behavior - only check if it's a Byzantine node if we can get the sender
        var sender = message.sender();
        if (sender != null && faultInjector.isFaultyNode(sender, FaultType.NODE_BYZANTINE)) {
            // Handle Byzantine behavior - for now, just drop the message
            return;
        }
            
        Thread.ofVirtual().start(() -> {
            if (nodes.containsKey(nodeId)) {
                processWithFaultInjection(nodeId, (T) message);
            }
        });
    }

    @Override
    public void connect(NodeId nodeId) {
    }

    @Override
    public void disconnect(NodeId nodeId) {
        nodes.remove(nodeId);
        if (nodes.size() == addressBook.quorumSize() - 1) {
            quorumObserver.accept(QuorumState.DISAPPEARED);
        }
    }

    @Override
    public void observeViewChanges(Consumer<TopologyChange> observer) {
    }

    @Override
    public void listen(Consumer<T> listener) {
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean quorumConnected() {
        return nodes.size() >= addressBook.quorumSize();
    }

    public void addNode(NodeId nodeId, Consumer<T> listener) {
        // Use processWithFaultInjection to wrap the listener
        nodes.put(nodeId, listener);
        if (nodes.size() == addressBook.quorumSize()) {
            quorumObserver.accept(QuorumState.APPEARED);
        }
    }
    
    // Method to process messages with fault injection
    protected <M extends T> void processWithFaultInjection(NodeId destination, M message) {
        var sender = message.sender();
        
        // Check for node crash
        if (sender != null && faultInjector.isFaultyNode(destination, FaultType.NODE_CRASH)) {
            return; // Dropped
        }
        
        // Check for node partition
        if (sender != null && partitions.containsKey(sender) && 
            partitions.get(sender).contains(destination)) {
            return; // Dropped due to partition
        }
        
        // Check for message loss
        if (faultInjector.shouldDropMessage()) {
            return; // Dropped
        }
        
        // Check for message delay
        if (faultInjector.shouldDelayMessage()) {
            long delay = faultInjector.getMessageDelay();
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // Process the message
        Consumer<T> listener = nodes.get(destination);
        if (listener != null) {
            listener.accept(message);
            
            // Check for message duplication
            if (faultInjector.shouldDuplicateMessage()) {
                listener.accept(message);
            }
        }
    }

    // Network partition management
    public void createPartition(Collection<NodeId> group1, Collection<NodeId> group2) {
        for (var node1 : group1) {
            for (var node2 : group2) {
                partitions.computeIfAbsent(node1, _ -> new ArrayList<>()).add(node2);
                partitions.computeIfAbsent(node2, _ -> new ArrayList<>()).add(node1);
            }
        }
    }
    
    public void healPartitions() {
        partitions.clear();
    }
    
    public FaultInjector getFaultInjector() {
        return faultInjector;
    }
    
    public void setFaultInjector(FaultInjector faultInjector) {
        this.faultInjector = faultInjector;
    }

    @Override
    public void observeQuorumState(Consumer<QuorumState> quorumObserver) {
        this.quorumObserver = quorumObserver;
    }
    
    // Fault injector implementation
    public static class FaultInjector {
        private final Map<FaultType, Boolean> activeFaults = new EnumMap<>(FaultType.class);
        private final Map<NodeId, Set<FaultType>> nodeSpecificFaults = new ConcurrentHashMap<>();
        private final Random random = new Random();
        private double messageLossRate = 0.0;
        private long messageDelayMillis = 0;
        
        public FaultInjector() {
            for (FaultType type : FaultType.values()) {
                activeFaults.put(type, false);
            }
        }
        
        public void setFault(FaultType type, boolean active) {
            activeFaults.put(type, active);
        }
        
        public void setNodeFault(NodeId nodeId, FaultType type, boolean active) {
            nodeSpecificFaults.computeIfAbsent(nodeId, _ -> EnumSet.noneOf(FaultType.class));
            if (active) {
                nodeSpecificFaults.get(nodeId).add(type);
            } else {
                nodeSpecificFaults.get(nodeId).remove(type);
            }
        }
        
        public void setMessageLossRate(double rate) {
            this.messageLossRate = Math.max(0.0, Math.min(1.0, rate));
        }
        
        public void setMessageDelayMillis(long delayMillis) {
            this.messageDelayMillis = Math.max(0, delayMillis);
        }
        
        public boolean shouldDropMessage() {
            return activeFaults.get(FaultType.MESSAGE_LOSS) && random.nextDouble() < messageLossRate;
        }
        
        public boolean shouldDelayMessage() {
            return activeFaults.get(FaultType.MESSAGE_DELAY);
        }
        
        public long getMessageDelay() {
            return messageDelayMillis;
        }
        
        public boolean shouldDuplicateMessage() {
            return activeFaults.get(FaultType.MESSAGE_DUPLICATE);
        }
        
        public boolean isFaultyNode(NodeId nodeId, FaultType type) {
            return nodeSpecificFaults.containsKey(nodeId) && 
                   nodeSpecificFaults.get(nodeId).contains(type);
        }
        
        public void clearAllFaults() {
            for (FaultType type : FaultType.values()) {
                activeFaults.put(type, false);
            }
            nodeSpecificFaults.clear();
            messageLossRate = 0.0;
            messageDelayMillis = 0;
        }
    }
}
