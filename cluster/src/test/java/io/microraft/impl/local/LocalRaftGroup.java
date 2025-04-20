/*
 * Original work Copyright (c) 2008-2020, Hazelcast, Inc.
 * Modified work Copyright 2020, MicroRaft.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.microraft.impl.local;

import io.microraft.RaftConfig;
import io.microraft.RaftEndpoint;
import io.microraft.RaftNode;
import io.microraft.RaftNode.RaftNodeBuilder;
import io.microraft.executor.RaftNodeExecutor;
import io.microraft.model.message.RaftMessage;
import io.microraft.persistence.NopRaftStore;
import io.microraft.persistence.RaftStore;
import io.microraft.persistence.RestoredRaftState;
import io.microraft.report.RaftNodeReport;
import io.microraft.report.RaftNodeReportListener;
import io.microraft.statemachine.StateMachine;
import io.microraft.test.util.AssertionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.microraft.RaftConfig.DEFAULT_RAFT_CONFIG;
import static io.microraft.test.util.AssertionUtils.eventually;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * This class is used for running a Raft group with local Raft nodes. It
 * provides methods to access specific Raft nodes, a set of functionalities over
 * them, such as terminations, creating network partitions, dropping or altering
 * network messages.
 *
 * @see LocalRaftEndpoint
 * @see SimpleStateMachine
 * @see Firewall
 */
public final class LocalRaftGroup {

    public static final BiFunction<RaftEndpoint, RaftConfig, RaftStore> IN_MEMORY_RAFT_STATE_STORE_FACTORY = (endpoint,
                                                                                                              config) -> {
        return new InMemoryRaftStore();
    };

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalRaftGroup.class);

    private final RaftConfig config;
    private final boolean newTermEntryEnabled;
    private final List<RaftEndpoint> initialMembers = new ArrayList<>();
    private final Map<RaftEndpoint, RaftNodeContext> nodeContexts = new HashMap<>();
    private final BiFunction<RaftEndpoint, RaftConfig, RaftStore> raftStoreFactory;

    private LocalRaftGroup(int groupSize, int votingMemberCount, RaftConfig config, boolean newTermEntryEnabled,
                           BiFunction<RaftEndpoint, RaftConfig, RaftStore> raftStoreFactory) {
        this.config = config;
        this.newTermEntryEnabled = newTermEntryEnabled;
        this.raftStoreFactory = raftStoreFactory;

        createNodes(groupSize, votingMemberCount, config, raftStoreFactory);
    }

    /**
     * Creates and starts a Raft group for the given number of Raft nodes.
     *
     * @param groupSize the number of Raft nodes to create the Raft group
     *
     * @return the created and started Raft group
     */
    public static LocalRaftGroup start(int groupSize) {
        LocalRaftGroup group = new LocalRaftGroupBuilder(groupSize).build();
        group.start();

        return group;
    }

    public static LocalRaftGroup start(int groupSize, int votingMemberCount) {
        LocalRaftGroup group = new LocalRaftGroupBuilder(groupSize, votingMemberCount).build();
        group.start();

        return group;
    }

    /**
     * Creates and starts a Raft group for the given number of Raft nodes.
     *
     * @param groupSize the number of Raft nodes to create the Raft group
     * @param config    the RaftConfig object to create the Raft nodes with
     *
     * @return the created and started Raft group
     */
    public static LocalRaftGroup start(int groupSize, RaftConfig config) {
        LocalRaftGroup group = new LocalRaftGroupBuilder(groupSize).setConfig(config).build();
        group.start();

        return group;
    }

    public static LocalRaftGroup start(int groupSize, int votingMemberCount, RaftConfig config) {
        LocalRaftGroup group = new LocalRaftGroupBuilder(groupSize, votingMemberCount).setConfig(config).build();
        group.start();

        return group;
    }

    /**
     * Returns a new Raft group builder object
     *
     * @param groupSize the number of Raft nodes to create the Raft group
     *
     * @return a new Raft group builder object
     */
    public static LocalRaftGroupBuilder newBuilder(int groupSize) {
        return new LocalRaftGroupBuilder(groupSize);
    }

    public static LocalRaftGroupBuilder newBuilder(int groupSize, int votingMemberCount) {
        return new LocalRaftGroupBuilder(groupSize, votingMemberCount);
    }

    private void createNodes(int groupSize, int votingMemberCount, RaftConfig config,
                             BiFunction<RaftEndpoint, RaftConfig, RaftStore> raftStoreFactory) {
        for (int i = 0; i < groupSize; i++) {
            RaftEndpoint endpoint = LocalRaftEndpoint.newEndpoint();
            initialMembers.add(endpoint);
        }

        List<RaftEndpoint> initialVotingMembers = initialMembers.subList(0, votingMemberCount);

        for (int i = 0; i < groupSize; i++) {
            RaftEndpoint endpoint = initialMembers.get(i);
            LocalTransport transport = new LocalTransport(endpoint);
            SimpleStateMachine stateMachine = new SimpleStateMachine(newTermEntryEnabled);
            RaftNodeBuilder nodeBuilder = RaftNode.newBuilder()
                                                  .setGroupId("default")
                                                  .setLocalEndpoint(endpoint)
                                                  .setInitialGroupMembers(initialMembers, initialVotingMembers)
                                                  .setConfig(config)
                                                  .setTransport(transport)
                                                  .setStateMachine(stateMachine)
                                                  .setRaftNodeReportListener(new RecordingRaftNodeReportListener());

            if (raftStoreFactory != null) {
                nodeBuilder.setStore(raftStoreFactory.apply(endpoint, config));
            }

            RaftNode node = (RaftNode) nodeBuilder.build();
            var context = new RaftNodeContext(node.executor(), transport, stateMachine, node);
            nodeContexts.put(endpoint, context);
        }
    }

    /**
     * Enables discovery between the created Raft nodes and starts them.
     */
    public void start() {
        initDiscovery();
        startNodes();
    }

    private void initDiscovery() {
        for (var ctx1 : nodeContexts.values()) {
            for (var ctx2 : nodeContexts.values()) {
                if (ctx2.isExecutorRunning() && !ctx1.localEndpoint().equals(ctx2.localEndpoint())) {
                    ctx1.transport().discoverNode(ctx2.node());
                }
            }
        }
    }

    private void startNodes() {
        for (var ctx : nodeContexts.values()) {
            ctx.node().start();
        }
    }

    /**
     * Creates a new Raft node and makes the other Raft nodes discover it.
     *
     * @return the created Raft node.
     */
    public RaftNode createNewNode() {
        var endpoint = LocalRaftEndpoint.newEndpoint();
        var transport = new LocalTransport(endpoint);
        var stateMachine = new SimpleStateMachine(newTermEntryEnabled);
        var raftStore = raftStoreFactory != null ? raftStoreFactory.apply(endpoint, config) : new NopRaftStore();
        var node = RaftNode.newBuilder()
                           .setGroupId("default")
                           .setLocalEndpoint(endpoint)
                           .setInitialGroupMembers(initialMembers)
                           .setConfig(config)
                           .setTransport(transport)
                           .setStateMachine(stateMachine)
                           .setStore(raftStore)
                           .build();

        nodeContexts.put(endpoint, new RaftNodeContext(node.executor(), transport, stateMachine, node));

        node.start();
        initDiscovery();

        return node;
    }

    /**
     * Restores a Raft node with the given {@link RestoredRaftState} object. The
     * Raft node to be restored must be created in this local Raft group.
     * <p>
     * If there exists a running Raft node with the same endpoint, this method fails
     * with {@link IllegalStateException}.
     *
     * @param restoredState the restored Raft state object to start the Raft node
     * @param store         the Raft store object to start the Raft node
     *
     * @return the restored Raft node
     * @throws IllegalStateException if there exists a running Raft node with the same endpoint
     */
    public RaftNode restoreNode(RestoredRaftState restoredState, RaftStore store) {
        boolean exists = nodeContexts.values().stream()
                                     .filter(ctx -> ctx.localEndpoint()
                                                       .equals(restoredState.localEndpointPersistentState()
                                                                            .getLocalEndpoint()))
                                     .anyMatch(RaftNodeContext::isExecutorRunning);

        if (exists) {
            throw new IllegalStateException(
                    restoredState.localEndpointPersistentState().getLocalEndpoint() + " is already running!");
        }

        requireNonNull(restoredState);

        LocalTransport transport = new LocalTransport(
                restoredState.localEndpointPersistentState().getLocalEndpoint());
        SimpleStateMachine stateMachine = new SimpleStateMachine(newTermEntryEnabled);
        var node = RaftNode.newBuilder()
                           .setGroupId("default")
                           .setRestoredState(restoredState)
                           .setConfig(config)
                           .setTransport(transport)
                           .setStateMachine(stateMachine)
                           .setStore(store)
                           .build();
        nodeContexts.put(restoredState.localEndpointPersistentState().getLocalEndpoint(),
                         new RaftNodeContext(node.executor(),
                                             transport,
                                             stateMachine,
                                             node));

        node.start();
        initDiscovery();

        return node;
    }

    /**
     * Returns all Raft nodes currently running in this local Raft group.
     *
     * @return all Raft nodes currently running in this local Raft group
     */
    public List<RaftNode> nodes() {
        return nodeContexts.values()
                           .stream()
                           .map(RaftNodeContext::node)
                           .toList();
    }

    /**
     * Returns all Raft nodes currently running in this local Raft group except the
     * given Raft endpoint.
     *
     * @param endpoint the Raft endpoint to excluded in the returned Raft node list
     *
     * @return all Raft nodes currently running in this local Raft group except the
     * given Raft endpoint
     */
    public List<RaftNode> nodesExcept(RaftEndpoint endpoint) {
        requireNonNull(endpoint);

        var nodes = nodeContexts.values()
                                .stream()
                                .map(RaftNodeContext::node)
                                .filter(node -> !node.localEndpoint().equals(endpoint))
                                .toList();

        if (nodes.size() != nodeContexts.size() - 1) {
            throw new IllegalArgumentException("Unknown endpoint: " + endpoint);
        }

        return nodes;
    }

    /**
     * Returns the currently running Raft node of the given Raft endpoint.
     *
     * @param endpoint the Raft endpoint to return its Raft node
     *
     * @return the currently running Raft node of the given Raft endpoint
     */
    public RaftNode node(RaftEndpoint endpoint) {
        requireNonNull(endpoint);
        return nodeContexts.get(endpoint).node();
    }

    /**
     * Returns the current leader Raft endpoint of the Raft group, or null if there
     * is no elected leader.
     * <p>
     * If different Raft nodes see different leaders, this method fails with
     * {@link AssertionError}.
     *
     * @return the current leader Raft endpoint of the Raft group, or null if there
     * is no elected leader
     * @throws AssertionError if different Raft nodes see different leaders
     */
    public RaftEndpoint leaderEndpoint() {
        RaftEndpoint leaderEndpoint = null;
        int leaderTerm = 0;
        for (RaftNodeContext nodeContext : nodeContexts.values()) {
            if (nodeContext.isExecutorShutdown()) {
                continue;
            }

            var term = nodeContext.node().termState();

            if (term.leaderEndpoint() != null) {
                if (leaderEndpoint == null) {
                    leaderEndpoint = term.leaderEndpoint();
                    leaderTerm = term.term();
                } else if (!(leaderEndpoint.equals(term.leaderEndpoint()) && leaderTerm == term.term())) {
                    leaderEndpoint = null;
                    leaderTerm = 0;
                }
            } else {
                throw new AssertionError("Group doesn't have a single leader endpoint yet!");
            }
        }

        return leaderEndpoint;
    }

    /**
     * Returns the state machine object for the given Raft endpoint.
     *
     * @param endpoint the Raft endpoint to get the state machine object
     *
     * @return the state machine object for the given Raft endpoint
     */
    public SimpleStateMachine stateMachine(RaftEndpoint endpoint) {
        requireNonNull(endpoint);
        return nodeContexts.get(endpoint).stateMachine();
    }

    private Firewall firewall(RaftEndpoint endpoint) {
        requireNonNull(endpoint);
        return nodeContexts.get(endpoint).transport().getFirewall();
    }

    /**
     * Waits until a leader is elected in this Raft group.
     * <p>
     * Fails with {@link AssertionError} if no leader is elected during
     * {@link AssertionUtils#EVENTUAL_ASSERTION_TIMEOUT_SECS}.
     *
     * @return the leader Raft node
     */
    public RaftNode waitUntilLeaderElected() {
        RaftNode[] leaderRef = new RaftNode[1];
        eventually(() -> {
            var leaderNode = leaderNode();
            assertThat(leaderNode).isNotNull();
            leaderRef[0] = leaderNode;
        });

        return leaderRef[0];
    }

    /**
     * Returns the current leader Raft node of the Raft group, or null if there is
     * no elected leader.
     * <p>
     * If different Raft nodes see different leaders or the Raft node of the leader
     * Raft endpoint is not found, this method fails with {@link AssertionError}.
     *
     * @return the current leader Raft endpoint of the Raft group, or null if there
     * is no elected leader
     * @throws AssertionError if different Raft nodes see different leaders or the Raft node of
     *                        the leader Raft endpoint is not found
     */
    public RaftNode leaderNode() {
        var leaderEndpoint = leaderEndpoint();
        if (leaderEndpoint == null) {
            return null;
        }

        var leaderCtx = nodeContexts.get(leaderEndpoint);
        if (leaderCtx == null || leaderCtx.isExecutorShutdown()) {
            throw new AssertionError("Leader endpoint is " + leaderEndpoint + ", but leader node could not be found!");
        }

        return leaderCtx.node();
    }

    /**
     * Returns a random Raft node other than the given Raft endpoint.
     * <p>
     * If no running Raft node is found for given Raft endpoint, then this method
     * fails with {@link NullPointerException}.
     *
     * @param endpoint the endpoint to not to choose for the returned Raft node
     *
     * @return a random Raft node other than the given Raft endpoint
     * @throws NullPointerException if no running Raft node is found for given Raft endpoint
     */
    public RaftNode anyNodeExcept(RaftEndpoint endpoint) {
        requireNonNull(endpoint);
        requireNonNull(nodeContexts.get(endpoint));

        for (var e : nodeContexts.entrySet()) {
            if (!e.getKey().equals(endpoint)) {
                return e.getValue().node();
            }
        }

        throw new IllegalArgumentException("Unknown endpoint: " + endpoint);
    }

    /**
     * Returns all RaftNodeReport objects reported by the given Raft endpoint
     *
     * @param endpoint the Raft endpoint to get the RaftNodeReport objects
     *
     * @return all RaftNodeReport objects reported by the given Raft endpoint
     */
    public List<RaftNodeReport> reports(RaftEndpoint endpoint) {
        requireNonNull(endpoint);
        return nodeContexts.get(endpoint)
                           .node()
                           .raftNodeReportListener()
                           .reports();
    }

    /**
     * Stops all Raft nodes running in the Raft group.
     */
    public void destroy() {
        for (var ctx : nodeContexts.values()) {
            ctx.node().terminate();
        }

        for (var ctx : nodeContexts.values()) {
            ctx.executor().shutdown();
        }

        nodeContexts.clear();
    }

    /**
     * Creates an artificial load on the given Raft node by sleeping its thread for
     * the given duration.
     *
     * @param endpoint the endpoint of the Raft node to slow down
     * @param seconds  the sleep duration in seconds
     */
    public void slowDownNode(RaftEndpoint endpoint, int seconds) {
        nodeContexts.get(endpoint).executor().submit(() -> {
            try {
                LOGGER.info(endpoint.id() + " is under high load for " + seconds + " seconds.");
                Thread.sleep(TimeUnit.SECONDS.toMillis(seconds));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Split the given Raft endpoints from the rest of the Raft group. It means that
     * the communication between the given endpoints and the other Raft nodes will
     * be blocked completely.
     * <p>
     * This method fails with {@link NullPointerException} if no Raft node is found
     * for any of the given endpoints list.
     *
     * @param endpoints the list of Raft endpoints to split from the rest of the Raft
     *                  group
     *
     * @throws NullPointerException if no Raft node is found for any of the given endpoints list
     */
    public void splitMembers(RaftEndpoint... endpoints) {
        splitMembers(List.of(endpoints));
    }

    /**
     * Split the given Raft endpoints from the rest of the Raft group. It means that
     * the communication between the given endpoints and the other Raft nodes will
     * be blocked completely.
     * <p>
     * This method fails with {@link NullPointerException} if no Raft node is found
     * for any of the given endpoints list.
     *
     * @param endpoints the list of Raft endpoints to split from the rest of the Raft
     *                  group
     *
     * @throws NullPointerException if no Raft node is found for any of the given endpoints list
     */
    public void splitMembers(List<RaftEndpoint> endpoints) {
        for (var endpoint : endpoints) {
            requireNonNull(nodeContexts.get(endpoint));
        }

        var side1 = endpoints.stream().map(nodeContexts::get).toList();
        var side2 = new ArrayList<>(nodeContexts.values());

        side2.removeAll(side1);

        for (var ctx1 : side1) {
            for (var ctx2 : side2) {
                ctx1.transport().undiscoverNode(ctx2.node());
                ctx2.transport().undiscoverNode(ctx1.node());
            }
        }
    }

    /**
     * Returns a random set of Raft nodes.
     *
     * @param nodeCount     the number of Raft nodes to return
     * @param includeLeader denotes whether if the leader Raft node can be returned or not
     *
     * @return the randomly selected Raft node set
     */
    public List<RaftEndpoint> randomNodes(int nodeCount, boolean includeLeader) {
        var leaderEndpoint = leaderEndpoint();

        var split = new ArrayList<RaftEndpoint>();

        if (includeLeader) {
            split.add(leaderEndpoint);
        }

        var contexts = new ArrayList<>(nodeContexts.values());
        Collections.shuffle(contexts);

        for (var ctx : contexts) {
            if (leaderEndpoint.equals(ctx.localEndpoint())) {
                continue;
            }

            split.add(ctx.localEndpoint());
            if (split.size() == nodeCount) {
                break;
            }
        }

        return split;
    }

    /**
     * Cancels all network partitions and enables the Raft nodes to reach to each
     * other again.
     */
    public void merge() {
        initDiscovery();
    }

    /**
     * Adds a one-way drop-message rule for the given source and target Raft
     * endpoints and the Raft message type.
     * <p>
     * After this call, Raft messages of the given type sent from the given source
     * Raft endpoint to the given target Raft endpoint are silently dropped.
     *
     * @param source      the source Raft endpoint to drop Raft messages of the given type
     * @param target      the target Raft endpoint to drop Raft messages of the given type
     * @param messageType the type of the Raft messages to be dropped
     */
    public <T extends RaftMessage> void dropMessagesTo(RaftEndpoint source, RaftEndpoint target, Class<T> messageType) {
        firewall(source).dropMessagesTo(target, messageType);
    }

    /**
     * Deletes the one-way drop-message rule for the given source and target Raft
     * endpoints and the Raft message type.
     *
     * @param source      the source Raft endpoint to remove the drop-message rule
     * @param target      the target Raft endpoint to remove the drop-message rule
     * @param messageType the type of the Raft messages
     */
    public <T extends RaftMessage> void allowMessagesTo(RaftEndpoint source, RaftEndpoint target,
                                                        Class<T> messageType) {
        firewall(source).allowMessagesTo(target, messageType);
    }

    /**
     * Adds a one-way drop-all-messages rule for the given source and target Raft
     * endpoints.
     * <p>
     * After this call, all Raft messages sent from the source Raft endpoint to the
     * target Raft endpoint are silently dropped.
     * <p>
     * If there were drop-message rules from the source Raft endpoint to the target
     * Raft endpoint, they are replaced with a drop-all-messages rule.
     *
     * @param source the source Raft endpoint to drop all Raft messages
     * @param target the target Raft endpoint to drop all Raft messages
     */
    public void dropAllMessagesTo(RaftEndpoint source, RaftEndpoint target) {
        firewall(source).dropAllMessagesTo(target);
    }

    /**
     * Deletes all one-way drop-message and drop-all-messages rules created for the
     * source Raft endpoint and the target Raft endpoint.
     *
     * @param source the source Raft endpoint
     * @param target the target Raft endpoint
     */
    public void allowAllMessagesTo(RaftEndpoint source, RaftEndpoint target) {
        firewall(source).allowAllMessagesTo(target);
    }

    /**
     * Adds a one-way drop-message rule for the given Raft message type from the
     * source Raft endpoint to all other Raft endpoints.
     *
     * @param messageType the type of the Raft messages to be dropped
     */
    public <T extends RaftMessage> void dropMessagesToAll(RaftEndpoint source, Class<T> messageType) {
        firewall(source).dropMessagesToAll(messageType);
    }

    /**
     * Deletes the one-way drop-all-messages rule for the given Raft message type
     * from the source Raft endpoint to any other Raft endpoint.
     *
     * @param messageType the type of the Raft message to delete the rule
     */
    public <T extends RaftMessage> void allowMessagesToAll(RaftEndpoint source, Class<T> messageType) {
        firewall(source).allowMessagesToAll(messageType);
    }

    /**
     * Resets all drop rules from the source Raft endpoint.
     */
    public void resetAllRulesFrom(RaftEndpoint source) {
        firewall(source).resetAllRules();
    }

    /**
     * Applies the given function an all Raft messages sent from the source Raft
     * endpoint to the target Raft endpoint.
     * <p>
     * If the given function is not altering a given Raft message, it should return
     * it as it is, instead of returning null.
     * <p>
     * Only a single alter rule can be created in the source Raft endpoint for a
     * given target Raft endpoint and a new alter rule overwrites the previous one.
     *
     * @param source   the source Raft endpoint to apply the alter function
     * @param target   the target Raft endpoint to apply the alter function
     * @param function the alter function to apply to Raft messages
     */
    public void alterMessagesTo(RaftEndpoint source, RaftEndpoint target, Function<RaftMessage, RaftMessage> function) {
        firewall(source).alterMessagesTo(target, function);
    }

    /**
     * Deletes the alter-message rule from the source Raft endpoint to the target
     * Raft endpoint.
     *
     * @param source the source Raft endpoint to delete the alter function
     * @param target the target Raft endpoint to delete the alter function
     */
    void removeAlterMessageRuleTo(RaftEndpoint source, RaftEndpoint target) {
        firewall(source).removeAlterMessageFunctionTo(target);
    }

    /**
     * Terminates the Raft node with the given Raft endpoint and removes it from the
     * discovery state of the other Raft nodes. It means that the other Raft nodes
     * will see the terminated Raft node as unreachable.
     * <p>
     * This method fails with {@link NullPointerException} if there is no running
     * Raft node with the given endpoint.
     *
     * @param endpoint the Raft endpoint to terminate its Raft node
     *
     * @throws NullPointerException if there is no running Raft node with the given endpoint
     */
    public void terminateNode(RaftEndpoint endpoint) {
        RaftNodeContext ctx = nodeContexts.get(requireNonNull(endpoint));
        requireNonNull(ctx);
        ctx.node().terminate().join();
        splitMembers(ctx.localEndpoint());
        ctx.executor().shutdown();
        nodeContexts.remove(endpoint);
    }

    /**
     * Builder for creating and starting Raft groups
     */
    public static final class LocalRaftGroupBuilder {

        private final int groupSize;
        private final int votingMemberCount;
        private RaftConfig config = DEFAULT_RAFT_CONFIG;
        private boolean newTermOperationEnabled;
        private BiFunction<RaftEndpoint, RaftConfig, RaftStore> raftStoreFactory;

        private LocalRaftGroupBuilder(int groupSize) {
            if (groupSize < 1) {
                throw new IllegalArgumentException("Raft groups must have at least 1 Raft node!");
            }
            this.groupSize = groupSize;
            this.votingMemberCount = groupSize;
        }

        private LocalRaftGroupBuilder(int groupSize, int votingMemberCount) {
            if (groupSize < 1) {
                throw new IllegalArgumentException("Raft groups must have at least 1 Raft node!");
            } else if (votingMemberCount < 1) {
                throw new IllegalArgumentException("Raft groups must have at least 1 voting Raft node!");
            } else if (votingMemberCount > groupSize) {
                throw new IllegalArgumentException(
                        "Raft group size must be greater than or equal to voting member count!");
            }
            this.groupSize = groupSize;
            this.votingMemberCount = votingMemberCount;
        }

        /**
         * Sets the RaftConfig object to create Raft nodes.
         *
         * @param config the RaftConfig object to create Raft nodes
         *
         * @return the builder object for fluent calls
         */
        public LocalRaftGroupBuilder setConfig(RaftConfig config) {
            requireNonNull(config);
            this.config = config;
            return this;
        }

        /**
         * @return the builder object for fluent calls
         * @see StateMachine#getNewTermOperation()
         */
        public LocalRaftGroupBuilder enableNewTermOperation() {
            this.newTermOperationEnabled = true;
            return this;
        }

        /**
         * Sets the factory object for creating Raft state stores.
         *
         * @param raftStoreFactory the factory object for creating Raft state stores
         *
         * @return the builder object for fluent calls
         */
        public LocalRaftGroupBuilder setRaftStoreFactory(
                BiFunction<RaftEndpoint, RaftConfig, RaftStore> raftStoreFactory) {
            requireNonNull(raftStoreFactory);
            this.raftStoreFactory = raftStoreFactory;
            return this;
        }

        /**
         * Builds the local Raft group with the configured settings. Please note that
         * the returned Raft group is not started yet.
         *
         * @return the created local Raft group
         */
        public LocalRaftGroup build() {
            return new LocalRaftGroup(groupSize, votingMemberCount, config, newTermOperationEnabled, raftStoreFactory);
        }

        /**
         * Builds and starts the local Raft group with the configured settings.
         *
         * @return the created and started local Raft group
         */
        public LocalRaftGroup start() {
            LocalRaftGroup group = new LocalRaftGroup(groupSize, votingMemberCount, config, newTermOperationEnabled,
                                                      raftStoreFactory);
            group.start();

            return group;
        }
    }

    private record RaftNodeContext(RaftNodeExecutor executor, LocalTransport transport, SimpleStateMachine stateMachine,
                                   RaftNode node) {

        RaftEndpoint localEndpoint() {
            return node.localEndpoint();
        }

        boolean isExecutorRunning() {
            return !isExecutorShutdown();
        }

        boolean isExecutorShutdown() {
            return executor().isShutdown();
        }
    }

    private static class RecordingRaftNodeReportListener implements RaftNodeReportListener {
        private List<RaftNodeReport> reports = new ArrayList<>();

        @Override
        public synchronized List<RaftNodeReport> reports() {
            return List.copyOf(reports);
        }

        @Override
        public synchronized void accept(RaftNodeReport report) {
            reports.add(report);
        }
    }

}
