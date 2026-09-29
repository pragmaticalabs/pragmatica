/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.consensus.rabia;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.ConsensusError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.StateMachine.Batch.Id;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineIO.SubmitCommands;
import org.pragmatica.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.*;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.RoundRequest;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.*;
import org.pragmatica.consensus.rabia.ConsensusEvent.ConsensusActive;
import org.pragmatica.consensus.rabia.ConsensusEvent.ConsensusPassive;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.concurrent.AtomicHolder;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageReceiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.consensus.rabia.RabiaPersistence.SavedState.savedState;
import static org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;


/// Implementation of the Rabia consensus protocol.
///
/// Rabia is a crash-fault-tolerant (CFT) consensus algorithm that provides:
///
///   - No persistent event log required
///   - Batch-based command processing
///   - Automatic state synchronization
///   - Deterministic decision-making with coin-flip fallback
///
/// @param <C> Command type
public class RabiaEngine<C extends Command> {
    private static final Logger log = LoggerFactory.getLogger(RabiaEngine.class);
    /// Light jitter scale: ±20% around the configured sync retry interval.
    /// Smaller than the default (±50%) to avoid disrupting protocol timing while still
    /// breaking thundering-herd patterns when many nodes resync after a quorum hiccup.
    private static final double SCALE = 0.2d;
    /// Default phase stall check interval.
    public static final TimeSpan DEFAULT_PHASE_STALL_CHECK = TimeSpan.timeSpan(500).millis();
    /// Apply-task duration probe threshold (5 ms). Diagnostic only — tasks at or above this on
    /// the single consensus apply worker are logged as `SLOW-APPLY` with the executor queue depth.
    private static final long SLOW_APPLY_THRESHOLD_NANOS = 5_000_000L;
    /// One stuck-in-`Syncing` WARN per this many unsatisfied sync rounds (#660) — roughly every 30s at
    /// the default 5s `syncRetryInterval`.
    private static final int WARN_EVERY_N_SYNC_ROUNDS = 6;

    /// Greater is preferred: higher epoch; at an equal epoch, the lexicographically LOWER member-id list
    /// (members are kept sorted, joined with a separator below every id character).
    private static final Comparator<VoterConfiguration> FORMED_PREFERENCE = Comparator.comparingLong(VoterConfiguration::epoch).thenComparing(RabiaEngine::memberIds,
                                                                                                                                              Comparator.reverseOrder());

    private volatile boolean passiveClient;
    private boolean participationStarted;
    private volatile boolean passiveClientReady;

    /// Configure immutable WORKER behavior before transport startup. Scoped projection is external.
    public synchronized Result<Unit> configurePassiveClient() {
        if (participationStarted || stopping.get() || !(engineState.get() instanceof EngineState.Stopped)) {
            return ReconfigurationError.PARTICIPATION_ALREADY_STARTED.result();
        }

        passiveClient = true;
        startPromise.get().succeed(Unit.unit());

        return Result.success(Unit.unit());
    }

    public org.pragmatica.lang.Unit authorizePassiveClient() {
        passiveClientReady = passiveClient;

        return org.pragmatica.lang.Unit.unit();
    }

    public boolean isPassiveClientReady() {
        return passiveClient && passiveClientReady;
    }

    /// The installed voter configuration: epoch plus complete roster. Changes only through
    /// [#installVoters] — genesis, a Rabia §4 command applied at its slot, or a sync adoption of a newer
    /// epoch. Empty while genesis is pending ([#deferGenesis]).
    private volatile Option<VoterConfiguration> voters = Option.none();
    /// The configuration this engine was bootstrapped with — the anchor a replacement's genesis roster
    /// is rendered from. Not a history: later epochs are learned from the log, never from this value.
    private volatile Option<VoterConfiguration> genesis = Option.none();
    private volatile boolean genesisPending;
    private volatile Option<Supplier<Set<NodeId>>> genesisDiscovery = Option.none();
    private volatile Set<NodeId> genesisConfiguredCores = Set.of();
    /// Executor-confined. The newest formed configuration seen since the last genesis round.
    private Option<VoterConfiguration> newestFormed = Option.none();
    /// Executor-confined after [#start]. Genesis view agreement while genesis is pending.
    private volatile Option<GenesisViewAgreement> genesisAgreement = Option.none();
    private final AtomicReference<ScheduledFuture<?>> genesisTimer = new AtomicReference<>();

    /// Genesis round interval: starts here and doubles while the view is unchanged, up to the sync retry
    /// interval, so a waiting cluster does not flood the network and a changing one reacts quickly.
    private static final TimeSpan GENESIS_ROUND_BASE = TimeSpan.timeSpan(250).millis();
    private static final int GENESIS_BACKOFF_STEPS = 5;
    private static final int GENESIS_WARN_EVERY_ROUNDS = 10;

    private volatile Option<Cause> authorityFailure = Option.none();
    private final List<Consumer<VoterConfiguration>> voterListeners = new CopyOnWriteArrayList<>();
    private final Map<Set<NodeId>, Promise<Unit>> reconfigurationPromises = new ConcurrentHashMap<>();
    /// The §4 command this voter currently carries on its proposals. Always of the current base epoch:
    /// [#settleRequests] drops it once the epoch moves.
    private volatile Option<ReconfigurationCommand> requestedConfiguration = Option.none();
    /// Every identity this engine has installed as a voter during its lifetime.
    private final Set<NodeId> voterHistory = ConcurrentHashMap.newKeySet();

    /// A change this replica applied itself: agreed at `slot` (R), governing from R+1.
    private record AppliedChange(Phase slot, VoterConfiguration installed, Set<NodeId> added) {}

    private volatile Option<AppliedChange> lastChange = Option.none();
    /// Members added by [#lastChange] from which a ballot past R has been accepted.
    private final Set<NodeId> caughtUp = ConcurrentHashMap.newKeySet();

    private static final int MAX_DEFERRED_BALLOTS = 256;

    /// Executor-confined. Ballots of a newer epoch than this replica has applied; see [#deferIfNewerEpoch].
    private final ArrayDeque<ProtocolMessage> deferredBallots = new ArrayDeque<>();
    /// Executor-confined. The slot this replica last asked each peer to repair; one request per slot.
    private final Map<NodeId, Phase> repairRequests = new HashMap<>();
    private volatile Option<Cause> stateTransferFailure = Option.none();

    public Option<Cause> stateTransferFailure() {
        return stateTransferFailure;
    }

    /// Read immutable values only; never inspect executor-confined maps.
    public VoterReconfigurationStatus voterReconfigurationStatus() {
        var failure = authorityFailure.map(Cause::message).or(stateTransferFailure.map(Cause::message).or(""));

        return voters.map(configuration -> describeReconfiguration(configuration, failure))
                     .or(() -> unresolvedStatus(failure));
    }

    private VoterReconfigurationStatus unresolvedStatus(String failure) {
        var stage = genesisPending
                    ? "GENESIS_PENDING"
                    : "UNAVAILABLE";

        return new VoterReconfigurationStatus(stage,
                                              Option.none(),
                                              List.of(),
                                              List.of(),
                                              Option.none(),
                                              List.of(),
                                              failure);
    }

    private VoterReconfigurationStatus describeReconfiguration(VoterConfiguration configuration, String failure) {
        var requested = requestedConfiguration;
        var change = changeGoverning(configuration);
        var awaiting = change.map(this::awaitingCatchUp).or(List.of());

        return new VoterReconfigurationStatus(reconfigurationStage(requested, awaiting),
                                              Option.some(configuration.epoch()),
                                              identities(configuration.members()),
                                              requested.map(command -> identities(command.target().members()))
                                                       .or(List.of()),
                                              change.map(applied -> applied.slot()
                                                                           .successor()
                                                                           .value()),
                                              identities(awaiting),
                                              failure);
    }

    private static String reconfigurationStage(Option<ReconfigurationCommand> requested, List<NodeId> awaiting) {
        if (requested.isPresent()) {
            return "REQUESTED";
        }

        return awaiting.isEmpty()
               ? "STABLE"
               : "CATCHING_UP";
    }

    private static List<String> identities(Collection<NodeId> nodes) {
        return nodes.stream()
                    .map(NodeId::id)
                    .sorted()
                    .toList();
    }

    private Option<AppliedChange> changeGoverning(VoterConfiguration configuration) {
        return lastChange.filter(change -> change.installed()
                                                 .equals(configuration));
    }

    private List<NodeId> awaitingCatchUp(AppliedChange change) {
        return change.added()
                     .stream()
                     .filter(node -> !caughtUp.contains(node))
                     .sorted(Comparator.comparing(NodeId::id))
                     .toList();
    }

    /// Installs the complete bootstrap electorate, never a discovery seed subset. Allowed before
    /// participation starts, or once while genesis is pending ([#deferGenesis]). A voter configuration
    /// persisted with a backup is deliberately NOT consulted: a cold restart forms a fresh genesis and
    /// restores backup data under it (owner ruling, #1526).
    public synchronized Result<Unit> initializeVoters(VoterConfiguration initial) {
        if (!genesisPending && (participationStarted || stopping.get() || !(engineState.get() instanceof EngineState.Stopped))) {
            return ReconfigurationError.BOOTSTRAP_ALREADY_STARTED.result();
        }

        return Result.success(installGenesis(initial));
    }

    /// Genesis wait-and-retry: withdraws the provisional roster so this engine neither votes nor
    /// adopts state until [#initializeVoters] supplies the complete one. Ballots that arrive meanwhile
    /// are held (bounded) and re-delivered once the roster is installed.
    public synchronized Result<Unit> deferGenesis() {
        if (participationStarted || stopping.get() || !(engineState.get() instanceof EngineState.Stopped)) {
            return ReconfigurationError.BOOTSTRAP_ALREADY_STARTED.result();
        }

        voters = Option.none();
        genesis = Option.none();
        currentConfig.set(Option.none());
        genesisPending = true;

        return Result.success(Unit.unit());
    }

    /// Genesis by view agreement (#1526): as [#deferGenesis], and from [#start] on this engine runs
    /// [GenesisViewAgreement] rounds — merging what `discovered` sees with every view announced to it —
    /// and installs epoch 0 once the agreement rules hold. `fixedView` (`cluster.genesis_voters`) replaces
    /// discovery: it is the view and nothing is merged. A core whose electorate has already formed answers
    /// an announcement with its configuration, and a pending node installs that instead, so a late or
    /// replacement core joins the running cluster rather than starting a second one.
    ///
    /// `configuredCores` names the cores the configuration lists; it is used only to tell the operator
    /// which of them have not appeared while genesis waits.
    public synchronized Result<Unit> deferGenesis(Supplier<Set<NodeId>> discovered,
                                                  int configuredCount,
                                                  Option<ClusterConfig> fixedView,
                                                  Set<NodeId> configuredCores) {
        return deferGenesis().onSuccess(_ -> armGenesisAgreement(discovered, configuredCount, fixedView))
                           .onSuccess(_ -> genesisConfiguredCores = Set.copyOf(configuredCores));
    }

    private void armGenesisAgreement(Supplier<Set<NodeId>> discovered,
                                     int configuredCount,
                                     Option<ClusterConfig> fixedView) {
        genesisDiscovery = Option.some(discovered);
        genesisAgreement = Option.some(GenesisViewAgreement.genesisViewAgreement(self,
                                                                                 configuredCount,
                                                                                 fixedView.map(roster -> Set.copyOf(roster.members()))));
    }

    /// Test hook: one genesis round on the apply executor, as the round timer would run it.
    Unit runGenesisRoundForTesting() {
        safeExecute(this::genesisRound);

        return Unit.unit();
    }

    private void genesisRound() {
        if (!genesisPending) {
            cancelGenesisTimer();

            return;
        }

        if (newestFormed.isPresent()) {
            newestFormed.onPresent(this::joinFormedElectorate);

            return;
        }

        genesisAgreement.onPresent(agreement -> announceGenesisRound(agreement, agreement.tick(authenticatedDiscovery())));
    }

    /// The cores discovery reports that also authenticate for this cluster: QUIC peers are admitted only
    /// through the cluster's TLS authority, so a discovered id counts only once it is a connected peer.
    /// A role label alone never puts a node in the view.
    private Set<NodeId> authenticatedDiscovery() {
        var authenticated = new HashSet<>(network.connectedPeers());

        authenticated.add(self);

        return genesisDiscovery.map(Supplier::get)
                               .or(Set.of())
                               .stream()
                               .filter(authenticated::contains)
                               .collect(Collectors.toUnmodifiableSet());
    }

    private void announceGenesisRound(GenesisViewAgreement agreement, GenesisViewAgreement.Report report) {
        var announcement = new GenesisAnnouncement(self,
                                                   report.round(),
                                                   Option.some(new ClusterConfig(List.copyOf(report.view()))),
                                                   Option.none());
        var recipients = new HashSet<>(network.connectedPeers());

        recipients.addAll(report.view());
        recipients.stream().filter(node -> !node.equals(self)).forEach(node -> network.send(node, announcement));
        logGenesisWait(agreement.status(), report.round());
        completeGenesisIfAgreed(agreement);
    }

    /// Every [#GENESIS_WARN_EVERY_ROUNDS] rounds a pending node says why it waits and what clears it.
    private void logGenesisWait(GenesisViewAgreement.Status status, long round) {
        if (round % GENESIS_WARN_EVERY_ROUNDS != 1) {
            return;
        }

        if (status.stage() == GenesisViewAgreement.Stage.EXCEEDS_COUNT) {
            log.warn("Node {} will NOT start genesis: {} core candidates are visible, more than the configured core count: {}. "
                    + "A running pending core never forgets a candidate it saw, so stopping candidates alone does not clear this. "
                    + "Operator action: stop the extra candidates, then STOP every pending core and every worker or governor "
                    + "connected to them, THEN start the cores; or start them under fresh identities; or set "
                    + "cluster.genesis_voters to the intended roster and do the same stop-all-then-start. Restarting cores "
                    + "one at a time while a peer that saw them keeps running is refused (#1545).",
                     self,
                     status.view().size(),
                     status.view());

            return;
        }

        if (status.stage() == GenesisViewAgreement.Stage.WAITING) {
            log.warn("Node {} genesis pending: view {} ({} cores); configured cores not yet visible: {}; members not yet "
                    + "reporting this view stably: {}. Genesis needs every configured core. Operator action: start the "
                    + "missing configured cores. If a core this node has seen is lost for good, nothing done while the "
                    + "pending cores keep running clears this: STOP every pending core and every worker or governor "
                    + "connected to them, THEN start the cores with the replacement; or start them under fresh identities; "
                    + "or set cluster.genesis_voters to the intended roster and do the same stop-all-then-start. Restarting "
                    + "cores one at a time while a peer that saw them keeps running is refused (#1545).",
                     self,
                     status.view(),
                     status.view().size(),
                     missingConfiguredCores(status.view()),
                     status.missing());
        }
    }

    private Set<NodeId> missingConfiguredCores(Set<NodeId> view) {
        return genesisConfiguredCores.stream()
                                     .filter(core -> !view.contains(core))
                                     .collect(Collectors.toUnmodifiableSet());
    }

    /// Invariant (#1554): a node that has observed a formed electorate never forms a new one. Once
    /// [#newestFormed] holds a configuration, completing the view agreement joins it instead of installing
    /// a fresh epoch 0 — otherwise the peers this node agreed with and the formed electorate it has seen
    /// would be two electorates.
    private void completeGenesisIfAgreed(GenesisViewAgreement agreement) {
        newestFormed.onPresent(this::joinFormedElectorate)
                    .onEmpty(() -> agreement.agreed()
                                            .onPresent(view -> installAgreedGenesis(new VoterConfiguration(0,
                                                                                                           new ClusterConfig(List.copyOf(view))))));
    }

    private void installAgreedGenesis(VoterConfiguration configuration) {
        log.info("Node {} installs genesis roster {}: every member reported the identical view in two consecutive rounds",
                 self,
                 configuration.members());
        installGenesis(configuration);
    }

    @Contract
    @MessageReceiver
    public void genesisAnnouncement(GenesisAnnouncement announcement) {
        safeExecute(() -> handleGenesisAnnouncement(announcement));
    }

    private void handleGenesisAnnouncement(GenesisAnnouncement announcement) {
        if (announcement.formed().isPresent()) {
            announcement.formed()
                        .filter(formed -> formed.contains(announcement.sender()))
                        .onPresent(this::noteFormedElectorate);

            return;
        }

        if (genesisPending) {
            genesisAgreement.onPresent(agreement -> receiveGenesisView(agreement, announcement));

            return;
        }

        voters.filter(configuration -> configuration.contains(self))
              .onPresent(configuration -> network.send(announcement.sender(),
                                                       new GenesisAnnouncement(self,
                                                                               0,
                                                                               Option.some(configuration.roster()),
                                                                               Option.some(configuration))));
    }

    private void receiveGenesisView(GenesisViewAgreement agreement, GenesisAnnouncement announcement) {
        announcement.view()
                    .onPresent(view -> agreement.receive(announcement.sender(),
                                                         announcement.round(),
                                                         Set.copyOf(view.members())));
        completeGenesisIfAgreed(agreement);
    }

    /// Rule for joining a formed electorate (#1526): a pending node gathers every `formed` configuration
    /// answered by one of that configuration's own members — and every LIVE sync response carrying one —
    /// until its next genesis round, then installs the NEWEST. It never installs an epoch older than one
    /// a live responder has shown it, so a lagging member's older answer cannot win over a current one
    /// that arrived in the same round.
    ///
    /// "Newest" is the total order [#FORMED_PREFERENCE]: higher epoch, then, at an equal epoch, the
    /// lexicographically lowest member list. Two different rosters at one epoch exist only as two epoch-0
    /// electorates (the documented residual); the tiebreak makes every observer pick the same one whatever
    /// the arrival order. Under a total order the only tie is an identical configuration, so keeping the
    /// seen one on a tie and replacing it are the same.
    private void noteFormedElectorate(VoterConfiguration formed) {
        if (genesisPending) {
            newestFormed = Option.some(newestFormed.map(seen -> preferredFormed(seen, formed)).or(formed));
        }
    }

    private static VoterConfiguration preferredFormed(VoterConfiguration seen, VoterConfiguration formed) {
        return FORMED_PREFERENCE.compare(formed, seen) > 0
               ? formed
               : seen;
    }

    private static String memberIds(VoterConfiguration configuration) {
        return configuration.members()
                            .stream()
                            .map(NodeId::id)
                            .collect(Collectors.joining("\u0000"));
    }

    private void joinFormedElectorate(VoterConfiguration formed) {
        log.info("Node {} joins the formed electorate: epoch {} {}", self, formed.epoch(), formed.members());
        installGenesis(formed);
    }

    private void cancelGenesisTimer() {
        Option.option(genesisTimer.getAndSet(null)).onPresent(task -> task.cancel(false));
    }

    public boolean isGenesisPending() {
        return genesisPending;
    }

    private Unit installGenesis(VoterConfiguration configuration) {
        var resolvesPending = genesisPending;

        cancelGenesisTimer();
        genesis = Option.some(configuration);
        authorityFailure = Option.none();
        voterHistory.clear();
        installVoters(configuration);
        if (resolvesPending) {
            observerMode = !configuration.contains(self);
            genesisPending = false;
            safeExecute(this::drainDeferredBallots);
            safeExecute(this::resynchronizeAfterGenesis);
        }

        return Unit.unit();
    }

    /// Responses collected while genesis was pending were all ineligible, so a syncing engine asks
    /// again at once instead of waiting a full retry interval.
    private void resynchronizeAfterGenesis() {
        if (engineState.get() instanceof EngineState.Syncing) {
            doSynchronize();
        }
    }

    private void installVoters(VoterConfiguration configuration) {
        voters = Option.some(configuration);
        voterHistory.addAll(configuration.members());
        currentConfig.set(Option.some(configuration.roster()));
        voterListeners.forEach(listener -> listener.accept(configuration));
    }

    /// The installed roster once nothing here can still change it: no §4 command is requested or
    /// awaiting its slot, and every member added by the last change this replica applied has been
    /// observed voting past that change's slot R. Resources outside it may then be retired.
    ///
    /// A replica that reached its epoch through a snapshot has no catch-up evidence of its own and
    /// reports the roster as soon as nothing is pending — retirement only ever concerns identities
    /// that are already outside the governing roster.
    public Option<VoterConfiguration> retirementSafeVoters() {
        return voters.filter(_ -> requestedConfiguration.isEmpty() && reconfigurationPromises.isEmpty())
                     .filter(this::addedMembersCaughtUp);
    }

    private boolean addedMembersCaughtUp(VoterConfiguration configuration) {
        return changeGoverning(configuration).map(this::awaitingCatchUp)
                              .map(List::isEmpty)
                              .or(true);
    }

    public Option<VoterConfiguration> genesisVoters() {
        return genesis;
    }

    public Set<NodeId> verifiedVoterHistoryIds() {
        return Set.copyOf(voterHistory);
    }

    /// Directory hints affect passive leader routing only; this method never installs voters.
    public Result<Unit> installPassiveCoreDirectory(List<NodeId> members, Consumer<List<NodeId>> installRouting) {
        if (!passiveClient || members.contains(self)) {
            return ReconfigurationError.NOT_PASSIVE_CLIENT.result();
        }

        return ClusterConfig.clusterConfig(members)
                            .map(ClusterConfig::members)
                            .onSuccess(installRouting)
                            .mapToUnit();
    }

    public Option<VoterConfiguration> voterConfiguration() {
        return voters;
    }

    public org.pragmatica.lang.Unit onVoterConfiguration(Consumer<VoterConfiguration> listener) {
        voterListeners.add(listener);
        voterConfiguration().onPresent(listener);

        return org.pragmatica.lang.Unit.unit();
    }

    private long voterEpoch() {
        return voterConfiguration().map(VoterConfiguration::epoch)
                                 .or(-1L);
    }

    private int voterCount() {
        return voterConfiguration().map(v -> v.members()
                                              .size())
                                 .or(Integer.MAX_VALUE);
    }

    private int voterQuorum() {
        return voterConfiguration().map(VoterConfiguration::quorumSize)
                                 .or(Integer.MAX_VALUE);
    }

    private int voterFPlusOne() {
        return voterConfiguration().map(VoterConfiguration::fPlusOne)
                                 .or(Integer.MAX_VALUE);
    }

    private boolean isVoter(NodeId node) {
        return voterConfiguration().map(v -> v.contains(node))
                                 .or(false);
    }

    /// Ballots count only from installed voters at the current epoch. An old-epoch ballot for a slot at
    /// or past a change's R+1 therefore never counts, and neither does any ballot of a removed voter.
    private boolean acceptsBallot(NodeId node, long epoch) {
        return authorityFailure.isEmpty()
               && isVoter(node)
               && epoch == voterEpoch();
    }

    /// Peers whose committed history this replica relays or repairs: installed voters and admitted
    /// consensus members. Wider than [#acceptsBallot] on purpose — a lagging or removed voter must still
    /// be able to learn the slot that changed the roster.
    private boolean isTrustedPeer(NodeId node) {
        return isVoter(node) || topologyManager.isConsensusMember(node);
    }

    private boolean broadcastVoters(org.pragmatica.consensus.ProtocolMessage message) {
        if (passiveClient) {
            return false;
        }

        voterConfiguration().onPresent(v -> v.members()
                                             .stream()
                                             .filter(node -> !node.equals(self))
                                             .forEach(node -> network.send(node, message)));

        return true;
    }

    private void broadcastCoreObservers(org.pragmatica.consensus.ProtocolMessage message) {
        if (passiveClient) {
            return;
        }

        var recipients = new java.util.HashSet<>(voterConfiguration().map(VoterConfiguration::members).or(List.of()));

        network.connectedPeers()
               .stream()
               .filter(message instanceof SyncRequest
                       ? topologyManager::isStateTransferPeer
                       : topologyManager::isConsensusMember)
               .forEach(recipients::add);
        recipients.stream().filter(node -> !node.equals(self)).forEach(node -> network.send(node, message));
    }

    /// A genesis-pending engine has no voter configuration yet; its state is saved without one rather
    /// than failing (a pending node must still stop cleanly).
    private Result<Unit> saveState() {
        if (passiveClient) {
            return Result.success(Unit.unit());
        }

        return voters.fold(() -> persistence.save(stateMachine, currentPhase.get(), pendingBatches.values()),
                           configuration -> persistence.save(stateMachine,
                                                             currentPhase.get(),
                                                             pendingBatches.values(),
                                                             configuration));
    }

    private final NodeId self;
    private final TopologyManager topologyManager;
    private final ClusterNetwork network;
    private final StateMachine<C> stateMachine;
    private final ProtocolConfig config;
    /// #1212 — this node's durable first-boot marker. Defaults to [ParticipationMarker#unknown] when
    /// the deployment supplies none, which denies the relaxation: absence is WIPED, never NEW.
    private final ParticipationMarker participationMarker;
    private final ConsensusMetrics metrics;
    private final boolean activationGated;
    private final TimeSpan phaseStallCheck;
    private volatile boolean activationAuthorized;
    private volatile boolean observerMode;
    private final AtomicHolder<ClusterStateNotification> pendingQuorum = AtomicHolder.atomicHolder();
    /// Sink for engine-level `ConsensusEvent` emissions. The default no-op consumer keeps
    /// existing test constructors working; production wiring (`RabiaNode`) injects
    /// `ConsensusBridge.consensusBridge(router)` so the consensus engine becomes the
    /// authoritative source for `ClusterStateNotification` (E2 Phase 2c.0, 2026-05-28).
    private final Consumer<ConsensusEvent> consensusEventListener;

    /// Single-fire-per-transition gate for `ConsensusActive` / `ConsensusPassive`. Tracks the
    /// last published "active" sense; the consumer fires only on true edges
    /// (false → true emits `ConsensusActive`, true → false emits `ConsensusPassive`).
    /// Initialized `false` because every engine starts in `Stopped` (not active).
    private final java.util.concurrent.atomic.AtomicBoolean lastPublishedActive = new java.util.concurrent.atomic.AtomicBoolean(false);

    // Single-thread executor with DiscardPolicy to silently drop tasks after shutdown
    private final ExecutorService executor = new ThreadPoolExecutor(1,
                                                                    1,
                                                                    0L,
                                                                    TimeUnit.MILLISECONDS,
                                                                    new LinkedBlockingQueue<>(),
                                                                    new ThreadPoolExecutor.DiscardPolicy());

    private final ConcurrentNavigableMap<Id, Batch<C>> pendingBatches = new ConcurrentSkipListMap<>();
    private final Map<NodeId, SyncResponse<C>> syncResponses = new ConcurrentHashMap<>();
    private final RabiaPersistence<C> persistence;
    /// Consecutive sync rounds that failed to reach the response threshold, driving the periodic
    /// stuck-in-`Syncing` WARN (#660). Reset when a sync round starts fresh, when state is adopted, and
    /// when the engine activates, so the reported count is the length of the current stall.
    private final AtomicInteger syncRounds = new AtomicInteger();

    @SuppressWarnings("rawtypes")
    private final Map<CorrelationId, Promise> correlationMap = new ConcurrentHashMap<>();

    /// Decisions delivered to a node whose engine is `Stopped` / `Syncing` are buffered here
    /// instead of applied immediately. Applying them in those states causes asymmetric apply:
    /// the Decision mutates KV state via `commitDecision → advancePhase`, then the imminent
    /// `restoreSnapshot` wipes the mutation, and `applyRestoredState` regresses
    /// `currentPhase`. Net effect: KV writes from the live phase silently disappear from the
    /// rejoiner's local state machine. Buffer is drained at the tail of `activate()` once the
    /// engine is `Idle`, in phase-ascending order, filtered to phases at or above the
    /// post-restore `currentPhase` (older Decisions are safely discarded — they're already
    /// captured in the restored snapshot).
    private static final int MAX_BUFFERED_DECISIONS = 256;

    private final java.util.concurrent.ConcurrentLinkedDeque<Decision<C>> bufferedDecisions = new java.util.concurrent.ConcurrentLinkedDeque<>();

    private final java.util.concurrent.atomic.AtomicInteger bufferedDecisionCount = new java.util.concurrent.atomic.AtomicInteger();

    //--------------------------------- Node State Start
    private final Map<Phase, PhaseData<C>> phases = new ConcurrentHashMap<>();
    private final AtomicReference<Phase> currentPhase = new AtomicReference<>(Phase.ZERO);
    /// Highest Rabia phase this node has ever observed the cluster reference, across any inbound
    /// peer message (Propose / VoteRound1 / VoteRound2 / Decision) and any sync candidate's
    /// `lastCommittedPhase`. Advance-only (never regresses). This is the engine's view of the
    /// cluster's committed frontier; `currentPhase` is the locally-applied frontier. When the
    /// former exceeds the latter the node is committed-ahead-of-applied — i.e. lagging — which
    /// [#isPendingCatchUp] reports so leadership-pinned ownership can be withheld from a
    /// not-yet-caught-up replacement leader (#329).
    private final AtomicReference<Phase> highestObservedClusterPhase = new AtomicReference<>(Phase.ZERO);
    private final AtomicReference<EngineState> engineState = new AtomicReference<>(new EngineState.Stopped());
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final Promise<Unit> stoppedCompletion = Promise.promise();
    private final AtomicReference<Promise<Unit>> startPromise = new AtomicReference<>(Promise.promise());
    /// The old-phase sweep, armed on ACTIVATION rather than in the constructor (#714).
    ///
    /// Constructor-arming leaked this task forever on the failed-boot path: it is cancelled only by
    /// [#stop], reached via `clusterNode.stop()`, and `AetherNode.cancelArmedWork` deliberately does
    /// not attempt a teardown of a cluster stack that never started. An engine that was constructed
    /// and then refused therefore kept ticking for the life of the JVM.
    ///
    /// Deferring costs nothing, because it is BEHAVIOUR-PRESERVING by construction rather than a
    /// trade-off: [#doCleanupOldPhases] already returns immediately unless the engine is active or
    /// observing, so an unarmed pre-activation engine and an armed one do exactly the same thing —
    /// nothing. Arming at activation only stops burning a scheduler slot to reach that early return.
    /// `phases` cannot grow unswept either: entries are created on the consensus path, which the
    /// same state guard gates.
    private final AtomicReference<ScheduledFuture<?>> cleanupTask = new AtomicReference<>();
    private final AtomicLong quorumSequence = new AtomicLong();

    /// Current cluster membership (consensus-level view).
    /// Tracked locally so [#reconfigure] can detect a true membership change vs a no-op
    /// replay of the same config. `none` until the first explicit reconfigure (or the
    /// first quorum activation observed against a known TopologyManager.topology()).
    private final AtomicReference<Option<ClusterConfig>> currentConfig = new AtomicReference<>(Option.none());

    /// Wave-1 §6.4 detect-only boot future-history check (cluster-topology-overhaul spec):
    /// one-shot gate so the persisted-vs-cluster phase comparison runs exactly once per process,
    /// at the FIRST sync restore (the moment the node first learns the cluster's reported state).
    private final AtomicBoolean bootFutureHistoryChecked = new AtomicBoolean(false);

    /// Listener invoked (persistedPhaseValue, clusterReportedPhaseValue) when the §6.4
    /// future-history hazard is detected — this node's persisted Rabia phase EXCEEDS what the
    /// joined cluster reports (mixed-wipe / `down -v` hazard). Detect-only: the engine logs WARN
    /// and notifies; recovery design is explicitly deferred (RC2). Default no-op; AetherNode
    /// wires the per-node transition journal.
    private volatile BiConsumer<Long, Long> onBootFutureHistory = (persisted, cluster) -> {};

    /// Listeners invoked after EVERY successful sync restore, once the restored state has been
    /// applied and the state machine content is queryable (and the engine re-activated).
    /// Late-joiner seam: state distributed via live notifications (e.g. KV `ValuePut`-derived
    /// state such as the cluster gossip-encryption key) is re-readable from the restored store
    /// at this point. Widened from a single slot to a list (Wave 8.6 / M5) so the gossip-key
    /// replay and the post-activation action-log drain can both register; invoked in
    /// registration order. See [#onStateRestored(Runnable)].
    private final List<Runnable> onStateRestored = new CopyOnWriteArrayList<>();

    //--------------------------------- Node State End
    /// Creates a new Rabia consensus engine without metrics or activation gating.
    ///
    /// @param topologyManager The topology manager for node communication
    /// @param network         The network implementation
    /// @param stateMachine    The state machine to apply commands to
    /// @param config          Configuration for the consensus engine
    public RabiaEngine(TopologyManager topologyManager,
                       ClusterNetwork network,
                       StateMachine<C> stateMachine,
                       ProtocolConfig config) {
        this(topologyManager,
             network,
             stateMachine,
             config,
             ConsensusMetrics.noop(),
             false,
             RabiaPersistence.inMemory(),
             DEFAULT_PHASE_STALL_CHECK);
    }

    /// Creates a new Rabia consensus engine with metrics but without activation gating.
    ///
    /// @param topologyManager The topology manager for node communication
    /// @param network         The network implementation
    /// @param stateMachine    The state machine to apply commands to
    /// @param config          Configuration for the consensus engine
    /// @param metrics         Metrics collector for observability
    public RabiaEngine(TopologyManager topologyManager,
                       ClusterNetwork network,
                       StateMachine<C> stateMachine,
                       ProtocolConfig config,
                       ConsensusMetrics metrics) {
        this(topologyManager,
             network,
             stateMachine,
             config,
             metrics,
             false,
             RabiaPersistence.inMemory(),
             DEFAULT_PHASE_STALL_CHECK);
    }

    /// Creates a new Rabia consensus engine with metrics and activation gating but in-memory persistence.
    ///
    /// When `activationGated` is true, the engine will not start consensus on quorum ESTABLISHED
    /// until `authorizeActivation()` is called. This allows the CDM to decide whether a joining
    /// node should participate in consensus or become a worker.
    ///
    /// @param topologyManager The topology manager for node communication
    /// @param network         The network implementation
    /// @param stateMachine    The state machine to apply commands to
    /// @param config          Configuration for the consensus engine
    /// @param metrics         Metrics collector for observability
    /// @param activationGated Whether consensus activation requires explicit authorization
    public RabiaEngine(TopologyManager topologyManager,
                       ClusterNetwork network,
                       StateMachine<C> stateMachine,
                       ProtocolConfig config,
                       ConsensusMetrics metrics,
                       boolean activationGated) {
        this(topologyManager,
             network,
             stateMachine,
             config,
             metrics,
             activationGated,
             RabiaPersistence.inMemory(),
             DEFAULT_PHASE_STALL_CHECK);
    }

    /// Creates a new Rabia consensus engine with all parameters except phaseStallCheck.
    /// Uses the default phase stall check interval.
    ///
    /// @param topologyManager The topology manager for node communication
    /// @param network         The network implementation
    /// @param stateMachine    The state machine to apply commands to
    /// @param config          Configuration for the consensus engine
    /// @param metrics         Metrics collector for observability
    /// @param activationGated Whether consensus activation requires explicit authorization
    /// @param persistence     The persistence implementation for state backup
    public RabiaEngine(TopologyManager topologyManager,
                       ClusterNetwork network,
                       StateMachine<C> stateMachine,
                       ProtocolConfig config,
                       ConsensusMetrics metrics,
                       boolean activationGated,
                       RabiaPersistence<C> persistence) {
        this(topologyManager,
             network,
             stateMachine,
             config,
             metrics,
             activationGated,
             persistence,
             DEFAULT_PHASE_STALL_CHECK);
    }

    /// Creates a new Rabia consensus engine with all parameters including persistence and phase stall check.
    /// Equivalent to the full-arity constructor with a no-op `consensusEventListener`.
    public RabiaEngine(TopologyManager topologyManager,
                       ClusterNetwork network,
                       StateMachine<C> stateMachine,
                       ProtocolConfig config,
                       ConsensusMetrics metrics,
                       boolean activationGated,
                       RabiaPersistence<C> persistence,
                       TimeSpan phaseStallCheck) {
        this(topologyManager,
             network,
             stateMachine,
             config,
             metrics,
             activationGated,
             persistence,
             phaseStallCheck,
             RabiaEngine::ignoreConsensusEvent);
    }

    /// Default no-op consumer used by legacy constructors that do not wire a
    /// `consensusEventListener`. Marked `@Contract` because the void return is intentional
    /// (sink semantics).
    @Contract
    private static void ignoreConsensusEvent(ConsensusEvent event) {}

    /// Creates a new Rabia consensus engine with all parameters including persistence,
    /// phase stall check, and a `consensusEventListener` for engine-level state transitions.
    ///
    /// @param topologyManager          The topology manager for node communication
    /// @param network                  The network implementation
    /// @param stateMachine             The state machine to apply commands to
    /// @param config                   Configuration for the consensus engine
    /// @param metrics                  Metrics collector for observability
    /// @param activationGated          Whether consensus activation requires explicit authorization
    /// @param persistence              The persistence implementation for state backup
    /// @param phaseStallCheck          Interval for checking phase stalls
    /// @param consensusEventListener   Sink for `ConsensusActive` / `ConsensusPassive` events;
    ///                                 typically `ConsensusBridge.consensusBridge(router)` in
    ///                                 production wiring
    public RabiaEngine(TopologyManager topologyManager,
                       ClusterNetwork network,
                       StateMachine<C> stateMachine,
                       ProtocolConfig config,
                       ConsensusMetrics metrics,
                       boolean activationGated,
                       RabiaPersistence<C> persistence,
                       TimeSpan phaseStallCheck,
                       Consumer<ConsensusEvent> consensusEventListener) {
        this.self = topologyManager.self().id();
        this.topologyManager = topologyManager;
        this.network = network;
        this.stateMachine = stateMachine;
        this.config = config;
        this.participationMarker = config.participationMarker().or(ParticipationMarker::unknown);
        this.metrics = Option.option(metrics).or(ConsensusMetrics.noop());
        this.activationGated = activationGated;
        this.activationAuthorized = !activationGated;
        this.persistence = persistence;
        this.phaseStallCheck = phaseStallCheck;
        this.consensusEventListener = Option.option(consensusEventListener).or(RabiaEngine::ignoreConsensusEvent);
        VoterConfiguration.voterConfiguration(0,
                                              topologyManager.coreNodes().stream().toList())
                          .flatMap(this::initializeVoters)
                          .onFailure(cause -> authorityFailure = Option.some(cause));
    }

    @Contract
    @MessageReceiver
    public void clusterState(ClusterStateNotification clusterStateNotification) {
        if (passiveClient) {
            return;
        }

        if (!clusterStateNotification.advanceSequence(quorumSequence)) {
            log.debug("Ignoring stale ClusterStateNotification: {}", clusterStateNotification);

            return;
        }

        log.trace("Node {} received cluster state {}", self, clusterStateNotification);
        switch (clusterStateNotification.state()) {
            case ACTIVE -> handleClusterActive(clusterStateNotification);
            case PASSIVE -> pauseForQuorumLoss();
        }
    }

    private void handleClusterActive(ClusterStateNotification notification) {
        if (authorityFailure.isPresent()) {
            return;
        }

        if (activationGated && !activationAuthorized) {
            log.info("Node {}: cluster active but activation gated, storing notification", self);
            pendingQuorum.set(notification);

            return;
        }
        // Membership-architecture-spec §4.5 / §7.3: distinguish quorum-resume (Paused → Idle,
        // no state reset) from cold-start (Stopped → Syncing). The Paused branch keeps the
        // engine's existing currentPhase / phases / pendingBatches intact;
        // any Decisions delivered during the pause have already been applied, so we just
        // re-arm phase processing.
        var current = engineState.get();

        if (current.isPaused()) {
            resumeFromPause();
        } else if (current.isActive() || current instanceof EngineState.Syncing) {
            // Redundant ACTIVE while already active/syncing — this is the ConsensusBridge echo
            // of our own ConsensusActive (ClusterStateNotification is engine-derived in steady
            // state). Ignore it: re-running clusterConnected() here would force a spurious
            // re-sync and, via the Active→Syncing transition, emit ConsensusPassive and flap.
            // TopologyObserver is the cold-start/resume originator; RabiaEngine owns every
            // steady-state transition.
            log.debug("Node {}: ignoring redundant cluster ACTIVE while {}", self, current);
        } else {
            // Stopped (cold start) or Observing — begin a sync round to catch up, then Active.
            clusterConnected();
        }
    }

    /// Authorize a gated engine to start consensus participation.
    /// If a quorum ESTABLISHED notification was received while gated, it is replayed.
    /// When promoting from observer mode, transitions directly to active without re-sync.
    @Contract
    public void authorizeActivation() {
        if (passiveClient) {
            return;
        }

        if (!topologyManager.isConsensusMember(self)) {
            log.warn("Node {} cannot authorize voting without admitted CORE identity", self);

            return;
        }
        // A pending genesis decides the role later ([#installGenesis]); until then authorize as a voter.
        if (voters.isPresent() && !isVoter(self)) {
            authorizeObservation();

            return;
        }

        log.info("Node {}: consensus activation authorized", self);
        if (observerMode) {
            log.info("Node {}: promoting from observer to full consensus", self);
            observerMode = false;
        }

        activationAuthorized = true;
        var pending = pendingQuorum.getAndClear();

        if (pending.isPresent()) {
            log.info("Node {}: replaying stored cluster-state notification", self);
            clusterConnected();
        } else if (engineState.get().isObserving()) {
            safeExecute(this::promoteObserverToActive);
        }
    }

    private void promoteObserverToActive() {
        var oldState = engineState.getAndSet(new EngineState.Idle());

        exitState(oldState);
        notifyConsensusStateTransition();
        log.info("Node {} promoted from observer to active in phase {}", self, currentPhase.get());
        safeExecute(this::startPhase);
    }

    /// Authorize a gated engine to enter observer mode.
    /// The node will receive and apply committed Decisions but will not propose or vote.
    /// If a quorum ESTABLISHED notification was received while gated, it is replayed.
    @Contract
    public void authorizeObservation() {
        if (passiveClient) {
            return;
        }

        log.info("Node {}: consensus observation authorized (observer mode)", self);
        activationAuthorized = true;
        observerMode = true;
        pendingQuorum.getAndClear().onPresent(this::replayQuorumForObserver);
    }

    /// Returns true if the engine is currently in observer mode.
    public boolean isObserving() {
        return engineState.get()
                          .isObserving();
    }

    // Side-effect callback for `Option.onPresent` — void inherent. Triggered by
    // `pendingQuorum.getAndClear()` consumer chain in `authorizeObservation()`.
    @Contract
    private void replayQuorumForObserver(ClusterStateNotification notification) {
        log.info("Node {}: replaying stored cluster-state notification for observer mode", self);
        clusterConnected();
    }

    /// Cold-start path: engine is `Stopped`, quorum has just been established for the
    /// first time (or after a full reset via [#reconfigure]). Initiates a sync round to
    /// catch up state from peers, then transitions to `Active`.
    ///
    /// This is NOT used for quorum-return after a transient pause — that path goes through
    /// [#resumeFromPause] which preserves all in-memory state.
    private void clusterConnected() {
        log.info("Node {}: quorum connected. Starting synchronization attempts", self);
        safeExecute(this::doClusterConnected);
    }

    private void doClusterConnected() {
        syncResponses.clear();
        syncRounds.set(0);
        // Catch-up race fix: broadcast the first SyncRequest IMMEDIATELY instead of waiting a full
        // syncRetryInterval for the timer below. A replacement that joins a cluster hundreds of
        // phases ahead must start its snapshot-install round at once — otherwise it sits silently in
        // Syncing (triggerResync is a no-op while Syncing) and can be drained/killed as a not-ready
        // node before the first request ever goes out. The scheduled `synchronize` remains the retry;
        // doSynchronize processes accumulated responses (>= quorum) or re-broadcasts.
        broadcastCoreObservers(new SyncRequest(self));
        var task = SharedScheduler.schedule(this::synchronize,
                                            config.syncRetryInterval().randomize(SCALE));
        var oldState = engineState.getAndSet(new EngineState.Syncing(task));

        exitState(oldState);
        notifyConsensusStateTransition();
    }

    /// Membership-architecture-spec §4.5 / §7.3 — quorum-loss handler.
    ///
    /// Transitions Active (Idle/InPhase) or Observing engines to `Paused`, retaining ALL
    /// in-memory protocol state: `phases`, `currentPhase`, `pendingBatches`,
    /// `correlationMap`, `bufferedDecisions`. The state machine is NOT reset.
    ///
    /// On the subsequent quorum `ESTABLISHED` notification, [#resumeFromPause] re-arms
    /// phase processing without a sync round — Decisions delivered during the pause are
    /// applied directly by [#handleDecision], keeping the engine current.
    ///
    /// In-flight stall/sync timers are cancelled. State is also persisted to durable
    /// storage so that a crash during the pause leaves a recoverable snapshot.
    private void pauseForQuorumLoss() {
        safeExecute(this::doPauseForQuorumLoss);
    }

    private void doPauseForQuorumLoss() {
        var current = engineState.get();

        if (!current.isActive() && !current.isObserving() && !current.isPaused() && !(current instanceof EngineState.Syncing)) {
            // Stopped engines stay Stopped — nothing to pause.
            return;
        }

        if (current.isPaused()) {
            return;
        }

        var oldState = engineState.getAndSet(new EngineState.Paused());

        exitState(oldState);
        notifyConsensusStateTransition();
        saveState().onSuccessRun(() -> log.info("Node {} paused (quorum lost). State retained, snapshot persisted. currentPhase={}, pendingBatches={}",
                                                self,
                                                currentPhase.get(),
                                                pendingBatches.size()))
                 .onFailure(cause -> log.error("Node {} failed to persist state on pause: {}", self, cause));
    }

    /// Membership-architecture-spec §4.5 / §7.3 — quorum-return handler when previously paused.
    ///
    /// Transitions `Paused` → `Idle`, preserving `currentPhase`, `phases`, `pendingBatches`,
    /// and binary-round ballots. Re-arms processing if pending batches remain. No sync round —
    /// Decisions delivered during the pause have already been applied.
    private void resumeFromPause() {
        safeExecute(this::doResumeFromPause);
    }

    private void doResumeFromPause() {
        var current = engineState.get();

        if (!current.isPaused()) {
            return;
        }

        engineState.set(new EngineState.Idle());
        notifyConsensusStateTransition();
        log.info("Node {} resumed from pause (quorum returned). currentPhase={}, pendingBatches={}",
                 self,
                 currentPhase.get(),
                 pendingBatches.size());
        // Replay through the live ordering guard. A remaining gap starts synchronization;
        // only a decision at the applied frontier can mutate the state machine.
        drainBufferedDecisions();
        if (!pendingBatches.isEmpty() || requestedConfiguration.isPresent()) {
            safeExecute(this::startPhase);
        }
    }

    /// Requests a Rabia §4 voter reconfiguration to the complete roster `target`.
    ///
    /// The request becomes a special command carried out-of-batch on this voter's proposals, with the
    /// current epoch as its base. Weak-MVC agrees the slot R that carries it, and from R+1 the target
    /// governs on every replica ([#applyReconfiguration]). A single add, a single remove and a one-slot
    /// replacement are all expressible; a change is refused unless the voters it retains are a majority
    /// of the target. The promise completes when this replica applies the agreed command, and fails with
    /// [ReconfigurationError#SUPERSEDED] when a different change is applied first. Retirement of removed
    /// resources waits for [#retirementSafeVoters], not for this promise.
    public synchronized Promise<Unit> reconfigure(ClusterConfig target) {
        if (stopping.get()) {
            return new ConsensusError.NodeInactive(self).promise();
        }

        var promise = Promise.<Unit> promise();

        safeExecute(() -> proposeReconfiguration(target, promise),
                    () -> promise.fail(new ConsensusError.NodeInactive(self)));

        return promise;
    }

    private void proposeReconfiguration(ClusterConfig target, Promise<Unit> promise) {
        var valid = ClusterConfig.clusterConfig(target.members()).flatMap(this::admissibleCommand);

        if (valid.isFailure()) {
            promise.resolve(valid.mapToUnit());

            return;
        }

        var command = valid.unwrap();

        if (voters.map(current -> current.roster()
                                         .sameMembership(command.target())).or(false)) {
            promise.succeed(Unit.unit());

            return;
        }

        if (!trackReconfiguration(command.target(), promise)) {
            return;
        }

        var request = new ReconfigurationRequest(self, command.baseEpoch(), command.target());

        handleReconfigurationRequest(request);
        broadcastVoters(request);
    }

    /// Local admission of a requested change. Every check here is re-evaluated deterministically at the
    /// agreed slot as well ([ReconfigurationCommand#successorOf]); refusing early only spares the log a
    /// slot and gives the caller a precise cause.
    private Result<ReconfigurationCommand> admissibleCommand(ClusterConfig target) {
        if (!engineState.get().isActive() || !isVoter(self)) {
            return ReconfigurationError.NOT_ACTIVE.result();
        }

        if (target.members().stream().anyMatch(node -> !isVoter(node) && !topologyManager.isConsensusMember(node))) {
            return ReconfigurationError.UNKNOWN_VOTER.result();
        }

        var command = ReconfigurationCommand.reconfigurationCommand(voterEpoch(), target);

        if (!voters.map(command::retainsTargetMajority).or(false)) {
            return ReconfigurationError.INSUFFICIENT_RETAINED_VOTERS.result();
        }

        return addsMembers(command)
               ? transferableState().map(_ -> command)
                                  .onFailure(cause -> stateTransferFailure = Option.some(cause))
               : Result.success(command);
    }

    private boolean addsMembers(ReconfigurationCommand command) {
        return command.target()
                      .members()
                      .stream()
                      .anyMatch(node -> !isVoter(node));
    }

    /// An added voter catches up through sync, so the size bound #1390 applied to its handoff checkpoint
    /// now applies to the sync response it will be sent. Checked before proposing, so an untransferable
    /// state is a visible refusal rather than an added voter that can never catch up.
    private Result<Unit> transferableState() {
        return stateMachine.makeSnapshot()
                           .map(snapshot -> new SyncResponse<C>(self,
                                                                new SavedState<C>(snapshot,
                                                                                  currentPhase.get(),
                                                                                  List.of(),
                                                                                  voters),
                                                                ResponderState.LIVE))
                           .flatMap(network::validateOutboundMessage)
                           .mapError(_ -> ReconfigurationError.STATE_TRANSFER_TOO_LARGE);
    }

    private boolean trackReconfiguration(ClusterConfig target, Promise<Unit> promise) {
        var previous = Option.option(reconfigurationPromises.putIfAbsent(Set.copyOf(target.members()),
                                                                         promise));

        previous.onPresent(existing -> existing.onResult(promise::resolve));

        return previous.isEmpty();
    }

    @Contract
    @MessageReceiver
    public void reconfigurationRequest(ReconfigurationRequest request) {
        safeExecute(() -> handleReconfigurationRequest(request));
    }

    private void handleReconfigurationRequest(ReconfigurationRequest request) {
        if (ClusterConfig.clusterConfig(request.target().members()).isFailure()) {
            return;
        }

        if (!acceptsBallot(request.sender(), request.epoch()) || !isVoter(self)) {
            return;
        }

        var command = ReconfigurationCommand.reconfigurationCommand(request.epoch(), request.target());

        if (!voters.map(command::appliesTo).or(false)) {
            return;
        }

        adoptRequested(command);
        if (engineState.get().isInPhase()) {
            broadcastOwnProposalIfNeeded();
        } else {
            startPhase();
        }
    }

    /// Carries `command` on this voter's next proposals. Competing commands of one base epoch resolve by
    /// [ReconfigurationCommand#preferredOver], so every voter converges on the same proposal; a command
    /// of another base epoch is never adopted.
    private void adoptRequested(ReconfigurationCommand command) {
        if (command.baseEpoch() != voterEpoch()) {
            return;
        }

        requestedConfiguration = Option.some(requestedConfiguration.filter(current -> current.preferredOver(command))
                                                                   .or(command));
    }

    /// Rabia §4 effective point. The command decided at `slot` (R) installs its successor roster in
    /// memory here; [#advancePhase] then moves this replica to R+1, the first slot the new roster
    /// governs. Slots are strictly sequential — a replica votes only in its current phase
    /// ([#canVoteRound1], [#canVoteRound2], [#canMakeDecision]) — so no replica casts a ballot for R+1
    /// before applying R, and every replica (a lagging one, an observer, a removed voter) switches
    /// through this same path. A command whose base epoch is not the current one is a deterministic
    /// no-op that still consumes its slot.
    private void applyReconfiguration(Phase slot, ReconfigurationCommand command) {
        voters.flatMap(command::successorOf)
              .onPresent(next -> installAppliedChange(slot, next))
              .onEmpty(() -> log.info("Node {} applied voter reconfiguration at slot {} as a no-op: base epoch {}, current epoch {}",
                                      self,
                                      slot,
                                      command.baseEpoch(),
                                      voterEpoch()));
        settleRequests();
    }

    private void installAppliedChange(Phase slot, VoterConfiguration next) {
        var previous = voters.map(VoterConfiguration::members).or(List.of());
        var added = next.members()
                        .stream()
                        .filter(node -> !previous.contains(node))
                        .collect(Collectors.toUnmodifiableSet());

        caughtUp.clear();
        if (added.contains(self)) {
            caughtUp.add(self);
        }

        lastChange = Option.some(new AppliedChange(slot, next, added));
        installVoters(next);
        observerMode = !next.contains(self);
        log.info("Node {} applied voter reconfiguration at slot {}: epoch {} {} governs from slot {}",
                 self,
                 slot,
                 next.epoch(),
                 next.members(),
                 slot.successor());
        safeExecute(this::drainDeferredBallots);
    }

    /// Settles local requests against the installed roster: a request whose base epoch has passed is
    /// dropped, and each caller's promise succeeds when its target is installed or fails as superseded
    /// once its target can no longer be proposed.
    private void settleRequests() {
        var epoch = voterEpoch();

        requestedConfiguration = requestedConfiguration.filter(command -> command.baseEpoch() == epoch);
        voters.onPresent(this::settlePromises);
    }

    private void settlePromises(VoterConfiguration installed) {
        var requested = requestedConfiguration.map(command -> Set.copyOf(command.target().members()));

        for (var target : List.copyOf(reconfigurationPromises.keySet())) {
            if (target.equals(Set.copyOf(installed.members()))) {
                Option.option(reconfigurationPromises.remove(target)).onPresent(this::completeRequestedChange);
            } else if (!requested.map(target::equals).or(false)) {
                Option.option(reconfigurationPromises.remove(target)).onPresent(promise -> promise.fail(ReconfigurationError.SUPERSEDED));
            }
        }
    }

    /// The requester of an applied change — the leader's reconciler — completes its promise and opens slot
    /// R+1 with an empty proposal. Every voter answers it, so the first ballots past R, which are the
    /// catch-up evidence [#retirementSafeVoters] waits for, arrive even in a cluster with no traffic.
    /// An added voter does the same when it joins ([#promoteAddedVoter], [#adoptSyncedConfiguration]):
    /// if the slot the leader opened completed before it joined, its own proposal past R is still
    /// recorded as evidence by the leader, which answers it with the completed slot's decision.
    private void completeRequestedChange(Promise<Unit> promise) {
        promise.succeed(Unit.unit());
        safeExecute(this::openSlotAfterChange);
    }

    private void openSlotAfterChange() {
        var current = engineState.get();

        if (current instanceof EngineState.Idle && isVoter(self) && pendingBatches.isEmpty() && requestedConfiguration.isEmpty()) {
            startPhaseWithBatch(current, Batch.emptyBatch());
        }
    }

    /// Adopts the configuration a sync source carried when it is newer than the installed one. The
    /// epoch never regresses; an equal epoch is already installed ([#eligibleSyncResponse] refuses a
    /// different roster at the same epoch).
    private void adoptSyncedConfiguration(VoterConfiguration configuration) {
        if (configuration.epoch() <= voterEpoch()) {
            return;
        }

        var joins = configuration.contains(self) && !isVoter(self);

        installVoters(configuration);
        observerMode = !configuration.contains(self);
        settleRequests();
        if (joins) {
            safeExecute(this::openSlotAfterChange);
        }

        log.info("Node {} adopted voter epoch {} {} from synchronized state",
                 self,
                 configuration.epoch(),
                 configuration.members());
        safeExecute(this::drainDeferredBallots);
    }

    /// Rabia §4 slot boundary. A ballot of a NEWER epoch belongs to a slot after a change this replica
    /// has not applied yet, so it cannot be judged against the current roster. It is held (bounded,
    /// oldest dropped) and re-delivered once the epoch advances, and the sender is asked to repair this
    /// replica's current slot so the change is learned without waiting for a stall re-broadcast.
    private boolean deferIfNewerEpoch(NodeId sender, long epoch, ProtocolMessage message) {
        if (epoch <= voterEpoch()) {
            return false;
        }

        deferredBallots.offer(message);
        if (deferredBallots.size() > MAX_DEFERRED_BALLOTS) {
            deferredBallots.pollFirst();
        }

        requestSlotRepair(sender);

        return true;
    }

    private void requestSlotRepair(NodeId peer) {
        var phase = currentPhase.get();

        if (voters.isEmpty() || phase.equals(repairRequests.get(peer))) {
            return;
        }

        repairRequests.put(peer, phase);
        network.send(peer, new RoundRequest(self, voterEpoch(), phase, 0));
    }

    private void drainDeferredBallots() {
        var deferred = List.copyOf(deferredBallots);

        deferredBallots.clear();
        deferred.forEach(this::redeliverBallot);
    }

    @SuppressWarnings("unchecked")
    private void redeliverBallot(ProtocolMessage message) {
        switch (message) {
            case Propose<?> propose -> handlePropose((Propose<C>) propose);
            case VoteRound1 vote -> handleVoteRound1(vote);
            case VoteRound2 vote -> handleVoteRound2(vote);
            default -> log.warn("Node {} dropped unexpected deferred message {}", self, message);
        }
    }

    /// Repairs a peer that sent a ballot for a slot this replica has completed: it replays the decision,
    /// or a snapshot when the slot's data is gone. Keyed on trust rather than on the current epoch, since
    /// the peer is typically a replica that has not yet applied the slot that changed the roster.
    private void repairPastSlot(NodeId peer, Phase phase) {
        if (isTrustedPeer(peer)) {
            replayCompletedSlot(peer, phase);
        }
    }

    /// Records that a member added by the last applied change has voted past its slot R. A ballot of the
    /// current epoch for any slot after R — even one that arrives after that slot completed — proves
    /// the member applied R, so it is recorded before the past-slot repair branch.
    private void recordCatchUp(NodeId sender, long epoch, Phase phase) {
        voters.filter(configuration -> configuration.epoch() == epoch)
              .flatMap(this::changeGoverning)
              .filter(change -> change.added()
                                      .contains(sender) && phase.compareTo(change.slot()) > 0)
              .onPresent(_ -> caughtUp.add(sender));
    }

    /// Hard-shutdown path used only by [#stop]. Performs the full state-clearing reset that
    /// `clusterDisconnected()` used to do under quorum-loss; in the new design, quorum-loss
    /// goes through [#pauseForQuorumLoss] and only `stop()` (and [#reconfigure]) reset state.
    private void shutdownAndReset() {
        var current = engineState.get();

        if (current instanceof EngineState.Stopped) {
        // Already stopped via performStop's pre-set; just clear state.
        }

        phases.clear();
        currentPhase.set(Phase.ZERO);
        highestObservedClusterPhase.set(Phase.ZERO);
        stateMachine.reset();
        startPromise.set(Promise.promise());
        pendingBatches.clear();
        bufferedDecisions.clear();
        bufferedDecisionCount.set(0);
        correlationMap.forEach((_, promise) -> promise.fail(new ConsensusError.NodeInactive(self)));
        correlationMap.clear();
    }

    public boolean isActive() {
        return engineState.get()
                          .isActive();
    }

    /// Returns true when the engine is in the `Paused` state — quorum is currently
    /// unavailable, in-memory protocol state is retained, and new `apply()` submissions
    /// are rejected with [ConsensusError.QuorumPaused]. Mutually exclusive with `isActive`.
    public boolean isPaused() {
        return engineState.get()
                          .isPaused();
    }

    /// Returns true when this node's locally-applied frontier trails the cluster's observed
    /// committed frontier — i.e. the engine is still draining the consensus log and has NOT
    /// caught up. Distinct from [#isActive], which only reports protocol participation
    /// (Idle/InPhase) and stays true for a freshly-elected replacement leader whose log is
    /// still draining.
    ///
    /// Pending-catch-up is true when either:
    ///   - the engine is mid-resync (`Syncing`) — by construction it is behind and pulling state, or
    ///   - the highest cluster phase observed from any peer message exceeds the locally-applied
    ///     `currentPhase` (the committed-ahead-of-applied gap).
    ///
    /// A genuinely caught-up active node (no higher cluster phase seen, not syncing) reports
    /// false. Used by the leader-pinned task-group owner resolver (#329) to withhold ownership
    /// from a lagging replacement leader so synchronous leader-local commits do not stall and
    /// 503; the resolver bounds the suppression in time so this can never wedge ownership.
    public boolean isPendingCatchUp() {
        var state = engineState.get();

        if (state instanceof EngineState.Syncing) {
            return true;
        }

        return highestObservedClusterPhase.get()
                                          .compareTo(currentPhase.get()) > 0;
    }

    /// Advance-only update of the cluster's observed committed frontier. Records the highest
    /// phase any inbound peer message has referenced so [#isPendingCatchUp] can compare it to
    /// the locally-applied `currentPhase`. Never regresses.
    private void observeClusterPhase(Phase phase) {
        highestObservedClusterPhase.updateAndGet(existing -> existing.compareTo(phase) >= 0
                                                             ? existing
                                                             : phase);
    }

    /// Package-private test hook: highest observed cluster phase (the committed frontier).
    Phase highestObservedClusterPhaseForTesting() {
        return highestObservedClusterPhase.get();
    }

    /// Package-private test hook: current Rabia phase.
    /// Used by R1 unit tests to verify state retention across pause/resume.
    Promise<Unit> settleForTesting() {
        if (stopping.get()) {
            return stoppedCompletion;
        }

        var settled = Promise.<Unit> promise();

        executor.execute(() -> settled.succeed(Unit.unit()));

        return settled;
    }

    Phase currentPhaseForTesting() {
        return currentPhase.get();
    }

    /// Package-private test hook: number of pending batches awaiting consensus.
    /// Used by R1 unit tests to verify state retention across pause/resume.
    int pendingBatchCountForTesting() {
        return pendingBatches.size();
    }

    /// Package-private test hook: current cluster config (set by [#reconfigure]).
    Option<ClusterConfig> currentConfigForTesting() {
        return currentConfig.get();
    }

    public <R> Promise<List<R>> apply(List<C> commands) {
        var pendingAnswer = Promise.<List<R>> promise();

        return submitCommands(commands,
                              batch -> correlationMap.put(batch.correlationIds().getFirst(),
                                                          pendingAnswer),
                              pendingAnswer::fail).async()
                             .flatMap(_ -> pendingAnswer.timeout(config.applyTimeout())
                                                        .mapError(this::toApplyTimeout));
    }

    /// Replaces the generic `CoreError.Timeout` produced by `Promise.timeout` with the
    /// domain-specific `ConsensusError.ApplyTimeout`, leaving every other cause untouched.
    private Cause toApplyTimeout(Cause cause) {
        return cause instanceof CoreError.Timeout
               ? new ConsensusError.ApplyTimeout(config.applyTimeout().millis())
               : cause;
    }

    @Contract
    @MessageReceiver
    public void handleSubmit(SubmitCommands<C> submitCommands) {
        submitCommands(submitCommands.commands(),
                       _ -> {},
                       _ -> {});
    }

    private synchronized Result<Batch<C>> submitCommands(List<C> commands,
                                                         Consumer<Batch<C>> onBatchPrepared,
                                                         Consumer<Cause> onRejected) {
        if (stopping.get()) {
            return new ConsensusError.NodeInactive(self).result();
        }

        if (log.isDebugEnabled()) {
            var caller = Thread.currentThread().getStackTrace();
            var callerInfo = caller.length > 3
                             ? caller[3].toString()
                             : "unknown";

            log.debug("Node {} submitting {} command(s): {} [caller: {}]", self, commands.size(), commands, callerInfo);
        }

        return validateSubmission(commands).map(_ -> prepareBatch(commands))
                                 .onSuccess(batch -> safeExecute(() -> registerBatch(batch, onBatchPrepared),
                                                                 () -> onRejected.accept(new ConsensusError.NodeInactive(self))))
                                 .onSuccess(batch -> safeExecute(() -> broadcastBatch(batch)));
    }

    private Result<List<C>> validateSubmission(List<C> commands) {
        if (commands.isEmpty()) {
            return new ConsensusError.CommandBatchIsEmpty().result();
        }

        var state = engineState.get();

        if (state.isPaused()) {
            return new ConsensusError.QuorumPaused(self).result();
        }

        if (!state.isActive()) {
            return new ConsensusError.NodeInactive(self).result();
        }

        var pending = pendingBatches.size();

        if (pending >= config.maxPendingBatches()) {
            return new ConsensusError.BackpressureExceeded(pending, config.maxPendingBatches()).result();
        }

        return Result.success(commands);
    }

    private Batch<C> prepareBatch(List<C> commands) {
        var batch = stateMachine.createBatch(commands);

        log.trace("Node {}: client submitted {} command(s). Prepared batch: {}", self, commands.size(), batch);

        return batch;
    }

    private void registerBatch(Batch<C> batch, Consumer<Batch<C>> onBatchPrepared) {
        // #958: the id is a content hash, so a second local submission of identical commands
        // must merge its correlationId into the already-pending batch, exactly as
        // doHandleNewBatch does for a remote one. A plain put() replaced the pending batch,
        // dropping the first caller's correlationId; commitChanges() then completed only the
        // survivor and the first caller saw ApplyTimeout although its command had applied.
        mergePending(batch);
        metrics.updatePendingBatches(self, pendingBatches.size());
        onBatchPrepared.accept(batch);
        triggerPhaseIfNeeded();
    }

    /// How a SUBMITTED or broadcast batch enters `pendingBatches` while live: merge by content-derived
    /// id. (Proposals learned from peers and restored pending batches enter through
    /// [#learnProposedBatch], which additionally de-duplicates correlation ids.)
    /// `compute()` makes the merge atomic; the lambda routes through `Option.option(existing)`
    /// so the absent case is expressed via `fold` rather than a raw `existing == null` sentinel.
    /// Same id ⟹ same commands, so only correlationIds are combined, via the state machine.
    private void mergePending(Batch<C> incoming) {
        pendingBatches.compute(incoming.id(),
                               (_, existing) -> Option.option(existing).fold(() -> incoming,
                                                                             current -> stateMachine.merge(current,
                                                                                                           incoming)));
    }

    private void broadcastBatch(Batch<C> batch) {
        broadcastCoreObservers(new NewBatch<>(self, batch));
    }

    private void triggerPhaseIfNeeded() {
        if (engineState.get() instanceof EngineState.Idle) {
            safeExecute(this::startPhase);
        }
    }

    public synchronized Promise<Unit> start() {
        participationStarted = true;
        armGenesisTimer();

        return startPromise.get();
    }

    /// Genesis agreement runs before consensus can start, so it owns its own round timer: armed at
    /// [#start] while genesis is pending with a view agreement, rescheduled after every round with
    /// backoff, and cancelled once genesis is installed or on stop.
    private void armGenesisTimer() {
        if (!genesisPending || genesisAgreement.isEmpty()) {
            return;
        }

        scheduleGenesisRound(GENESIS_ROUND_BASE);
    }

    private void scheduleGenesisRound(TimeSpan delay) {
        if (!genesisPending || stopping.get()) {
            return;
        }

        var task = SharedScheduler.schedule(() -> safeExecute(this::timedGenesisRound), delay);

        Option.option(genesisTimer.getAndSet(task)).onPresent(previous -> previous.cancel(false));
    }

    private void timedGenesisRound() {
        genesisRound();
        genesisAgreement.onPresent(agreement -> scheduleGenesisRound(genesisRoundDelay(agreement.roundsSinceChange())));
    }

    private TimeSpan genesisRoundDelay(long roundsSinceChange) {
        var steps = Math.min(roundsSinceChange, GENESIS_BACKOFF_STEPS);
        var delay = GENESIS_ROUND_BASE.millis() << steps;

        return TimeSpan.timeSpan(Math.min(delay,
                                          Math.max(GENESIS_ROUND_BASE.millis(),
                                                   config.syncRetryInterval().millis()))).millis();
    }

    public synchronized Promise<Unit> stop() {
        if (stopping.compareAndSet(false, true)) {
            submitStop();
        }

        return stoppedCompletion;
    }

    /// #1442: the submission's own `Result` is consumed here instead of being dropped in a statement
    /// inside [#stop]. Nothing about the shutdown changes — the executor refuses the task once it is
    /// itself shutting down, and routing that refusal into `stoppedCompletion` is what makes `stop()`
    /// settle rather than hang; on success there is nothing to carry, because completion arrives from
    /// [#performStop].
    private Unit submitStop() {
        return Result.lift(Causes::fromThrowable,
                           () -> executor.execute(() -> performStop(stoppedCompletion)))
                     .onFailure(stoppedCompletion::fail)
                     .or(Unit.unit());
    }

    private void performStop(Promise<Unit> promise) {
        cancelGenesisTimer();
        Option.option(cleanupTask.getAndSet(null)).onPresent(task -> task.cancel(false));
        var oldState = engineState.getAndSet(new EngineState.Stopped());

        exitState(oldState);
        notifyConsensusStateTransition();
        // Backstop sweep (rc4, #1341), kept alongside the rejection path (#1390) — a union, ruling
        // 151b0edfe. Admission is closed, and every task admitted before `stop()` has already run or
        // been refused through its `onStopped` callback in [#safeExecute], which fails a refused
        // request at the point of refusal; what remains here is a request whose batch was registered
        // and is still awaiting a decision, which only this sweep can settle. Snapshot contents and
        // the phase frontier therefore describe the same state.
        correlationMap.forEach((_, pending) -> pending.fail(new ConsensusError.NodeInactive(self)));
        correlationMap.clear();
        reconfigurationPromises.values().forEach(pending -> pending.fail(new ConsensusError.NodeInactive(self)));
        reconfigurationPromises.clear();
        var persisted = passiveClient
                        ? Result.success(Unit.unit())
                        : saveState();
        var reset = Result.lift(org.pragmatica.lang.utils.Causes::fromThrowable,
                                () -> {
                                    shutdownAndReset();

                                    return Unit.unit();
                                });

        executor.shutdown();
        promise.resolve(persisted.flatMap(_ -> reset));
    }

    /// Containment boundary for the single consensus apply worker (7c). Every task submitted to
    /// {@link #executor} runs through here so a handler that throws — on the live-apply path OR the
    /// snapshot/resync restore path — is caught and logged instead of escaping the worker's run(),
    /// terminating the thread, dropping the in-flight consensus round, and leaving the node
    /// permanently un-converged (topology never applied -> stuck behind -> infinite resync). Mirrors
    /// the swallow semantics the live KV dispatch already has (MessageRouter.dispatchOne): the worker
    /// survives to process subsequent rounds; the failed round is abandoned and re-driven by the
    /// sender's retry. Errors (non-RuntimeException Throwable) are intentionally left to propagate.
    private void safeExecute(Runnable task) {
        safeExecute(task,
                    () -> {});
    }

    private synchronized void safeExecute(Runnable task, Runnable onStopped) {
        if (stopping.get()) {
            onStopped.run();

            return;
        }

        participationStarted = true;
        executor.execute(() -> {
            if (stopping.get()) {
                onStopped.run();

                return;
            }

            if (passiveClient) {
                return;
            }

            var start = System.nanoTime();

            Result.lift(org.pragmatica.lang.utils.Causes::fromThrowable,
                        () -> {
                            task.run();

                            return Unit.unit();
                        })
                  .onFailure(cause -> log.error("Consensus apply boundary failed: {}", cause));
            var elapsed = System.nanoTime() - start;

            if (elapsed >= SLOW_APPLY_THRESHOLD_NANOS) {
                log.warn("SLOW-APPLY ms={} queueDepth={}", elapsed / 1_000_000, applyQueueDepth());
            }
        });
    }

    /// Diagnostic helper: backlog of the single-thread apply executor. The executor is always a
    /// `ThreadPoolExecutor` (see field construction); returns -1 if that ever changes so the probe
    /// never throws.
    private int applyQueueDepth() {
        return executor instanceof ThreadPoolExecutor pool
               ? pool.getQueue()
                     .size()
               : -1;
    }

    @Contract
    @MessageReceiver
    public void processPropose(Propose<C> propose) {
        safeExecute(() -> handlePropose(propose));
    }

    @Contract
    @MessageReceiver
    public void processVoteRound1(VoteRound1 voteRound1) {
        safeExecute(() -> handleVoteRound1(voteRound1));
    }

    @Contract
    @MessageReceiver
    public void processVoteRound2(VoteRound2 voteRound2) {
        safeExecute(() -> handleVoteRound2(voteRound2));
    }

    @Contract
    @MessageReceiver
    public void processDecision(Decision<C> decision) {
        safeExecute(() -> handleDecision(decision));
    }

    @Contract
    @MessageReceiver
    public void processSyncResponse(SyncResponse<C> syncResponse) {
        safeExecute(() -> handleSyncResponse(syncResponse));
    }

    @Contract
    @SuppressWarnings("unchecked")
    @MessageReceiver
    public void handleNewBatch(NewBatch<?> newBatch) {
        safeExecute(() -> doHandleNewBatch((Batch<C>) newBatch.batch()));
    }

    private void doHandleNewBatch(Batch<C> incoming) {
        mergePending(incoming);
        if (engineState.get().isInPhase()) {
            // Already in phase - broadcast our proposal for this batch if not already proposed
            broadcastOwnProposalIfNeeded();
        } else {
            triggerPhaseIfNeeded();
        }
    }

    /// Proposal retransmission must not duplicate request correlations in the pending queue.
    private Unit learnProposedBatch(Batch<C> incoming) {
        pendingBatches.compute(incoming.id(),
                               (_, existing) -> Option.option(existing).fold(() -> incoming,
                                                                             current -> new Batch<>(current.id(),
                                                                                                    java.util.stream.Stream.concat(current.correlationIds()
                                                                                                                                          .stream(),
                                                                                                                                   incoming.correlationIds()
                                                                                                                                           .stream())
                                                                                                                           .distinct()
                                                                                                                           .toList(),
                                                                                                    Math.min(current.timestamp(),
                                                                                                             incoming.timestamp()),
                                                                                                    current.commands())));

        return Unit.unit();
    }

    /// Broadcasts own proposal for pending batch if not already proposed in current phase.
    private void broadcastOwnProposalIfNeeded() {
        var phase = currentPhase.get();
        var phaseData = getOrCreatePhaseData(phase);

        if (phaseData.hasProposal(self)) {
            return;
        }

        if (requestedConfiguration.isPresent()) {
            broadcastOwnProposal(phase, phaseData, Batch.emptyBatch());

            return;
        }

        broadcastOwnProposal(phase, phaseData, nextProposal());
    }

    /// The batch this voter proposes when it joins a slot: its oldest pending batch, or an empty one. An
    /// empty proposal still counts toward the slot's proposal quorum, so a slot another voter opened with
    /// nothing to carry (see [#openSlotAfterChange]) decides V0 instead of stalling.
    private Batch<C> nextProposal() {
        return Option.option(pendingBatches.firstEntry())
                     .map(Map.Entry::getValue)
                     .or(Batch::emptyBatch);
    }

    /// Starts a new phase with pending commands.
    /// Dormant nodes must not enter phases -- they accumulate batches in pendingBatches
    /// and process them after activation. Without this guard, dormant nodes would broadcast
    /// Propose messages but ignore incoming votes, creating an unrecoverable phase deadlock.
    private void startPhase() {
        var current = engineState.get();

        if (!current.isActive() || !isVoter(self) || authorityFailure.isPresent()) {
            return;
        }

        if (! (current instanceof EngineState.Idle)) {
            return;
        }

        if (requestedConfiguration.isPresent()) {
            startPhaseWithBatch(current, Batch.emptyBatch());

            return;
        }

        Option.option(pendingBatches.firstEntry())
              .onEmpty(this::reExecuteStartPhaseIfBatchPending)
              .onPresent(batchEntry -> startPhaseWithBatch(current,
                                                           batchEntry.getValue()));
    }

    private void reExecuteStartPhaseIfBatchPending() {
        // Re-check after — a batch may have been added during the window
        if (!pendingBatches.isEmpty() || requestedConfiguration.isPresent()) {
            safeExecute(this::startPhase);
        }
    }

    private void startPhaseWithBatch(EngineState current, Batch<C> batch) {
        var phase = currentPhase.get();

        log.trace("Node {} starting phase {} with batch {}", self, phase, batch.id());
        var stallDetector = createStallDetector();

        if (!engineState.compareAndSet(current, new EngineState.InPhase(stallDetector))) {
            stallDetector.cancel(false);

            return;
        }

        notifyConsensusStateTransition();
        var phaseData = getOrCreatePhaseData(phase);

        broadcastOwnProposal(phase, phaseData, batch);
        driveBinaryRound(phaseData);
    }

    /// Synchronizes with other nodes to catch up if needed.
    private void synchronize() {
        safeExecute(this::doSynchronize);
    }

    private void doSynchronize() {
        if (engineState.get().isActive()) {
            return;
        }
        // Check if we already have enough responses from previous attempt
        var adopted = adoptIfThresholdMet();
        // #1447: a round is stuck by its OUTCOME, not by whether adoption ran. An adoption that did not
        // activate (a restore that failed, an activation that was refused) retries and counts like any
        // other round; at clusterSize 1 adoption runs on every round, so this is the only way it counts.
        if (engineState.get().isActive()) {
            return;
        }

        warnIfSyncStuck(adopted);
        // Only clear and restart if we don't have enough responses
        syncResponses.clear();
        var request = new SyncRequest(self);

        log.trace("Node {}: requesting phase synchronization {}", self, request);
        broadcastCoreObservers(request);
        var task = SharedScheduler.schedule(this::synchronize,
                                            config.syncRetryInterval().randomize(SCALE));
        var oldState = engineState.getAndSet(new EngineState.Syncing(task));

        exitState(oldState);
        notifyConsensusStateTransition();
    }

    /// #660 observability half: the retry loop logged only at TRACE, so a node deadlocked in `Syncing`
    /// produced tens of megabytes of log with no indication at INFO that anything was wrong. A cluster
    /// that cannot adopt state is dead — no leader, no reconciler — while every link and SWIM view looks
    /// healthy, which is exactly the silent failure the silence-killer doctrine exists to prevent. The
    /// line carries the arithmetic that decides the gate so an operator can tell "waiting for peers"
    /// from "threshold can never be met".
    ///
    /// Periodic rather than per-round: at the default 5s `syncRetryInterval` this is roughly every 30s.
    /// The counter resets whenever a sync episode starts ([#doClusterConnected]) or the engine activates,
    /// so the round number in the line is the length of the CURRENT stall, not a process-lifetime total.
    /// Adoption does NOT reset it (#1447): a node whose adoption keeps failing is exactly the stall this
    /// line exists to report.
    private void warnIfSyncStuck(boolean adopted) {
        var round = syncRounds.incrementAndGet();

        if (round % WARN_EVERY_N_SYNC_ROUNDS != 0) {
            return;
        }

        if (adopted) {
            log.warn("Node {} still SYNCING after {} rounds: the adoption threshold is met, but the node did not "
                    + "activate on the adopted state (restore or activation refused: {}). This node has no leader "
                    + "and runs no reconciler while this persists.",
                     self,
                     round,
                     authorityFailure.map(Cause::message)
                                     .or("see the preceding ERROR"));

            return;
        }

        if (genesisPending) {
            log.warn("Node {} still SYNCING after {} rounds: the complete genesis voter roster is not resolved yet, "
                    + "so no state can be adopted. This node has no leader and runs no reconciler while this persists.",
                     self,
                     round);

            return;
        }

        log.warn("Node {} still SYNCING after {} rounds: {} responses of which {} live, from {} "
                + "(clusterSize={}). Adoption needs {} responses while any responder is live — self {} "
                + "toward that majority — or {} responses when none is live. This node has no leader "
                + "and runs no reconciler while this persists.",
                 self,
                 round,
                 syncResponses.size(),
                 liveResponseCount(),
                 syncResponses.keySet(),
                 voterCount(),
                 responsesRequiredWithALiveResponder(voterCount()),
                 selfCanVouchForItsOwnHistory()
                 ? "counts (it holds durable state)"
                 : selfProvablyNeverVoted()
                   ? "counts (marker proves it never participated, #1212)"
                   : "does NOT count (no durable state, and no marker proving it is new)",
                 syncPeerResponsesRequired());
    }

    /// The state this node adopts: the most advanced state among the peer sync responses AND this
    /// The phase below which this node must never be pulled — self's weight in the adoption decision.
    ///
    /// [#syncPeerResponsesRequired] counts self toward the majority, so the responses by themselves are a
    /// minority (`clusterSize / 2`) and the intersection argument that makes adoption safe holds only
    /// over `{self} ∪ responders`. Self therefore has to be able to REFUSE a response set that is behind
    /// it: if this node is the only member of the majority that witnessed a commit, adopting a staler
    /// state would silently discard it. Self participates as a FLOOR rather than as an adoption
    /// candidate — see [#activateWithoutAdoption] for why the distinction matters.
    ///
    /// The floor is the more advanced of the persisted and the LIVE phase. `persistence.save` runs only
    /// at pause, reconfigure, stop and restore — never on commit — so the persisted snapshot lags the
    /// live state machine by an unbounded amount, and a resync triggered from an ACTIVE engine
    /// ([#triggerResync], reachable from a far-future Propose or Decision) has live state that the
    /// persisted snapshot does not describe.
    private Phase ownStateFloor(Option<SavedState<C>> persisted) {
        var persistedPhase = persisted.map(SavedState::lastCommittedPhase).or(Phase.ZERO);
        var livePhase = currentPhase.get();

        return persistedPhase.compareTo(livePhase) > 0
               ? persistedPhase
               : livePhase;
    }

    /// Adopts the collected state once the response threshold is met. Shared by the retry path
    /// ([#doSynchronize]) and the arrival path ([#handleSyncResponse]), which previously carried
    /// separate copies of the candidate selection.
    ///
    /// The candidate is the most advanced state the PEERS report, which is deliberately a different
    /// quantity from what this node ends up holding: [#detectBootFutureHistory] compares self against
    /// what the CLUSTER reports, and folding self into the candidate would make its predicate
    /// unfireable and silently retire the §6.4 mixed-wipe detector.
    private void adoptCollectedState(List<SyncResponse<C>> candidates) {
        var persisted = persistence.load();
        var responses = candidates.stream()
                                  .map(SyncResponse::state)
                                  .sorted(Comparator.comparing(SavedState::lastCommittedPhase))
                                  .toList();

        if (responses.isEmpty()) {
            // Only reachable at clusterSize 1, where the requirement is zero responses: self is the
            // whole majority and there is no peer to adopt from.
            activateWithoutAdoption(persisted, "no peers to adopt from");

            return;
        }

        var candidate = responses.getLast();

        detectBootFutureHistory(persisted, candidate);
        if (candidate.lastCommittedPhase().compareTo(ownStateFloor(persisted)) < 0) {
            activateWithoutAdoption(persisted, "every response is behind this node's own state");

            return;
        }

        log.trace("Node {} uses {} as synchronization candidate out of {} responses", self, candidate, responses.size());
        restoreState(candidate);
    }

    /// Activates on this node's OWN state, installing no RESPONSE.
    ///
    /// Reached when the response threshold is met but no response carries a state more advanced than
    /// this node already holds. Self is part of the majority, so the majority's most advanced state is
    /// already self's and there is nothing to fetch from a peer.
    ///
    /// This deliberately does NOT route a response through [#restoreState]: that would call
    /// `stateMachine.restoreSnapshot` with a state that is BEHIND the live one, overwriting a live state
    /// machine with a staler snapshot while `applyRestoredState`'s advance-only `currentPhase` kept the
    /// counter where it was — committed writes gone with no phase to indicate it. That is precisely the
    /// state loss this gate exists to prevent, so self is a floor and never an adopted candidate.
    ///
    /// Mirrors the tail of [#restoreState]'s empty-snapshot branch — activate, then replay, then notify —
    /// so post-restore listeners still fire exactly once, as they did when an empty response was adopted.
    ///
    /// #1020 — "already here" is true of the LIVE state machine only when self's history is in it.
    /// A process restarted from disk holds its history in `persistence.load()` and nothing else:
    /// `load()` fed the sync-response payload, the adoption floor and the future-history detector,
    /// and never the state machine. Activating bare here left such a node ACTIVE with an EMPTY store
    /// at phase 0 — an API key it had committed and acknowledged answered 403 after a full-cluster
    /// stop with `[backup]` enabled, on exactly the node whose snapshot was the most advanced. So
    /// when the persisted phase is ahead of the live one, the persisted state IS the own state and is
    /// installed through [#restoreState] (phase advance-only, pending batches, re-persist, activate,
    /// replay, notify). A live phase at or past the persisted one means the history is already in
    /// the process — a resync from ACTIVE — and installing the older snapshot would regress it.
    ///
    /// **This arm is the only path by which a node installs its own persisted state.** Cores run
    /// in-memory Rabia (owner ruling, session 28: #1390's vote WAL and boot recovery are removed), so
    /// only a `[backup]` node reaches it. A failed [#restoreState] skips activation — the node stays
    /// fail-closed in `Syncing` and never serves the empty store (#1468) — and [#logRestoreFailure]
    /// reports it. Pinned by `RabiaOwnRestoreFailureTest`.
    private void activateWithoutAdoption(Option<SavedState<C>> persisted, String reason) {
        persisted.filter(state -> state.lastCommittedPhase()
                                       .compareTo(currentPhase.get()) > 0)
                 .onPresent(state -> restoreOwnState(state, reason))
                 .onEmpty(() -> activateOnLiveState(reason));
    }

    private void restoreOwnState(SavedState<C> state, String reason) {
        log.info("Node {} activating on its own persisted state ({}); persisted phase {}, live phase {}",
                 self,
                 reason,
                 state.lastCommittedPhase(),
                 currentPhase.get());
        restoreState(withoutConfiguration(state), this::recordOwnRestoreFailure);
    }

    /// #1468 — a node that cannot restore its OWN persisted history reports it as the authority
    /// failure (visible through [#voterReconfigurationStatus]), not only in the log, and the
    /// `authorityFailure` fence keeps it from activating on a later sync round. A responder-snapshot
    /// restore failure is NOT recorded here: another sync round may adopt a readable snapshot.
    private void recordOwnRestoreFailure(Cause cause) {
        authorityFailure = Option.some(cause);
        logRestoreFailure(cause);
    }

    /// A backup's voter configuration is not authority: a cold restart forms a fresh genesis and restores
    /// only the data under it (owner ruling, #1526). Own restores and COLD sync answers therefore carry
    /// no configuration.
    private static <C extends Command> SavedState<C> withoutConfiguration(SavedState<C> state) {
        return new SavedState<>(state.snapshot(), state.lastCommittedPhase(), state.pendingBatches(), Option.none());
    }

    private void activateOnLiveState(String reason) {
        log.debug("Node {} activating on its own state ({}); own phase {}", self, reason, currentPhase.get());
        syncResponses.clear();
        activate();
        replayStateNotifications();
        notifyStateRestored();
    }

    @Contract
    @MessageReceiver
    public void handleSyncRejected(RabiaProtocolMessage.Asynchronous.SyncRejected rejected) {
        safeExecute(() -> {
            if (!isVoter(rejected.sender()) && !topologyManager.isStateTransferPeer(rejected.sender())) {
                return;
            }

            if (engineState.get()
                           .isActive() || rejected.epoch() < voterEpoch()) {
                return;
            }

            stateTransferFailure = Option.some(ReconfigurationError.STATE_TRANSFER_TOO_LARGE);
            // Other peers may have a smaller or newer valid checkpoint; keep synchronization live.
            log.error("Node {} state transfer refused by {}: {}",
                      self,
                      rejected.sender(),
                      ReconfigurationError.STATE_TRANSFER_TOO_LARGE.message());
        });
    }

    private void sendSyncResponse(NodeId peer, SyncResponse<C> response) {
        var emptyPending = new SyncResponse<C>(self,
                                               new SavedState<C>(response.state().snapshot(),
                                                                 response.state().lastCommittedPhase(),
                                                                 List.of(),
                                                                 response.state().configuration()),
                                               response.responder());
        var bounded = network.validateOutboundMessage(response)
                             .fold(_ -> network.validateOutboundMessage(emptyPending)
                                               .map(_ -> emptyPending),
                                   _ -> Result.success(response));

        bounded.onSuccess(value -> network.send(peer, value))
               .onFailure(cause -> {
                              stateTransferFailure = Option.some(ReconfigurationError.STATE_TRANSFER_TOO_LARGE);
                              log.error("Node {} cannot send bounded checkpoint to {}: {}",
                                        self,
                                        peer,
                                        cause.message());
                              network.send(peer,
                                           new RabiaProtocolMessage.Asynchronous.SyncRejected(self,
                                                                                              voterEpoch()));
                          });
    }

    /// Whether a sync response may count toward adoption. The responder must belong to the configuration
    /// it claims, and that configuration must be either this replica's own or of a NEWER epoch; a lower
    /// epoch is a lagging responder and is ignored. A different roster at the SAME epoch is a genesis
    /// disagreement: it is refused and reported, because adopting it would join a second electorate.
    /// A response without a configuration is legacy evidence for the genesis epoch only.
    private boolean eligibleSyncResponse(SyncResponse<C> response) {
        return response.state()
                       .configuration()
                       .fold(() -> voterEpoch() == 0 && isVoter(response.sender()),
                             claimed -> claimed.contains(response.sender()) && comparableConfiguration(response.sender(),
                                                                                                       claimed));
    }

    private boolean comparableConfiguration(NodeId sender, VoterConfiguration claimed) {
        var own = voters;
        var disagrees = own.filter(configuration -> configuration.epoch() == claimed.epoch() && !configuration.equals(claimed));

        disagrees.onPresent(configuration -> log.warn("Node {} refuses sync state from {}: epoch {} roster {} differs from this node's {}. "
                                                     + "The genesis rosters disagree; this node will not adopt state from another electorate.",
                                                      self,
                                                      sender,
                                                      claimed.epoch(),
                                                      claimed.members(),
                                                      configuration.members()));

        return own.map(configuration -> claimed.epoch() > configuration.epoch() || claimed.equals(configuration))
                  .or(false);
    }

    /// Handles a synchronization response from another node.
    private void handleSyncResponse(SyncResponse<C> response) {
        if (genesisPending && response.responder() == ResponderState.LIVE) {
            response.state()
                    .configuration()
                    .filter(configuration -> configuration.contains(response.sender()))
                    .onPresent(this::noteFormedElectorate);
        }

        if (!eligibleSyncResponse(response) || response.sender().equals(self)) {
            return;
        }

        if (engineState.get().isActive()) {
            log.trace("Node {} ignoring synchronization response {}. Node is active", self, response);

            return;
        }

        syncResponses.put(response.sender(), response);
        if (!adoptIfThresholdMet()) {
            log.trace("Node {} received {} responses {}, not enough to proceed (live responders = {})",
                      self,
                      syncResponses.size(),
                      syncResponses.keySet(),
                      liveResponseCount());
        }
    }

    private void restoreState(SavedState<C> state) {
        restoreState(state, this::logRestoreFailure);
    }

    private void restoreState(SavedState<C> state, Consumer<Cause> onRestoreFailure) {
        observeClusterPhase(state.lastCommittedPhase());
        syncResponses.clear();
        // Always carry forward the source's lastCommittedPhase + pendingBatches even when
        // the state-machine snapshot is empty. V0 decisions advance phase without touching
        // the state machine, so a syncing node that ignores the empty-snapshot phase ends
        // up perpetually `MAX_PHASE_AHEAD` behind and burns its retry budget on resyncs.
        if (state.snapshot().length == 0) {
            applyRestoredState(state);
            activate();
            replayStateNotifications();
            notifyStateRestored();

            return;
        }
        // sync → activate → replay (cluster-topology-overhaul §5.8, AMENDED 2026-06-11):
        // restoreSnapshot installs the synced state SILENTLY (no notifications); activate() flips
        // the engine ACTIVE; replayStateNotifications() then fires the synthetic notification burst
        // as the FIRST work after activation, so a KV notification structurally implies ACTIVE.
        stateMachine.restoreCommittedSnapshot(state.snapshot(),
                                              state.lastCommittedPhase().value())
                    .onSuccess(_ -> applyRestoredState(state))
                    .onSuccessRun(this::activate)
                    .onSuccessRun(this::replayStateNotifications)
                    .onSuccessRun(this::notifyStateRestored)
                    .onFailure(cause -> onRestoreFailure.accept(cause));
    }

    /// #1020 — the ONE operator signal on a failed restore, so it names the CONSEQUENCE and not only
    /// the cause.
    ///
    /// A `restoreSnapshot` that fails skips `activate()`, so the engine stays `Syncing` and the retry
    /// tick re-enters the same branch. This is the per-attempt line; the periodic stuck-in-`Syncing`
    /// WARN ([#warnIfSyncStuck]) also counts these rounds since #1447, so the stall is reported by its
    /// length too. It spells out that the node is NOT active rather than logging a bare cause.
    ///
    /// It covers every failed restore that reaches [#restoreState]: a responder's snapshot, or the
    /// own-restore arm of [#activateWithoutAdoption] (fail-closed per #1468).
    private void logRestoreFailure(Cause cause) {
        log.error("Node {} FAILED to restore state and is NOT active: {}. It stays in sync/retry and serves no "
                 + "requests; every retry re-enters this same branch until the snapshot can be read.",
                  self,
                  cause.message());
    }

    /// #1020 (rc4) — this re-persist is what makes the restored state durable for the NEXT restart, and
    /// its failure used to be silent end to end. #1390 routes the save through the authority snapshot;
    /// rc4's ERROR is kept alongside (union, merge of #1390 into rc4).
    ///
    /// FER (degrade forward): the failure is absorbed here, not propagated — the restored state stays
    /// in memory, [#recordRestoredStateSaveFailure] sets `authorityFailure` (#1390), which keeps
    /// [#activate] from activating the node, and names the stale-disk consequence at ERROR (#1020).
    /// The `Unit` fallback only supplies the return value; the refusal itself is routed by
    /// `onFailure`. The fence is currently PERMANENT (nothing on this path clears it) and later sync
    /// rounds re-adopt and re-fire the restore hooks on the inactive node — open in #1516.
    private Unit persistRestoredState() {
        return saveState().onFailure(this::recordRestoredStateSaveFailure)
                        .or(Unit.unit());
    }

    /// #1020 — a failed re-persist after a restore. #1390's `authorityFailure` keeps the node from
    /// activating; rc4's ERROR names the consequence, because `GitBackedPersistence` carries no logger
    /// of its own and this is the only place the failure is heard. Pinned by
    /// `RabiaRestoredStateSaveFailureLogTest#syncAdoptionSaveFails_nodeDoesNotActivate_pinsCurrentBehaviour`
    /// and `#syncAdoptionSaveFails_logsFailedToPersistAtError`.
    private void recordRestoredStateSaveFailure(Cause cause) {
        authorityFailure = Option.some(cause);
        log.error("Node {} restored state but FAILED to persist it: {}. The restore is "
                 + "in memory ONLY — this node's disk still holds its previous checkpoint, "
                 + "so a restart will lose the restored history and serve a stale store.",
                  self,
                  cause);
    }

    /// Fire the state machine's deferred notification burst (cluster-topology-overhaul §5.8,
    /// AMENDED 2026-06-11). Called AFTER [#activate()] on the apply thread, so the synthetic puts
    /// land before any live apply queued behind this restore — consumers observe a totally-ordered
    /// "world-as-of-N, then N+1 onward" stream. Mutation-free notification synthesis only.
    @Contract
    private void replayStateNotifications() {
        stateMachine.replayNotifications();
    }

    /// Wave-1 §6.4 boot-time future-history sanity check (DETECT-ONLY, ratified D9). Runs ONCE
    /// per process, at the first sync restore — the moment this node first learns the cluster's
    /// reported Rabia phase (`candidate.lastCommittedPhase()`, the max among the quorum of sync
    /// responses). Compares it against this node's OWN persisted phase (`persistence.load()`).
    /// Persisted phase EXCEEDING the cluster-reported phase means "I have a future this cluster
    /// never saw" — the mixed-wipe / `down -v` hazard. Emits WARN + notifies the
    /// [#onBootFutureHistory] listener (journal feed); changes NO control flow — restore
    /// proceeds exactly as before. Rabia is leaderless: the phase counter is the log index and
    /// there is no separate persisted term, so the phase pair is precisely what is comparable.
    /// With the in-memory persistence (no snapshot survives restart) `load()` is empty and the
    /// check is trivially silent.
    ///
    /// The candidate passed here MUST be the max over peer RESPONSES ONLY. Comparing self against a
    /// candidate that already includes self makes `persisted > candidate` unsatisfiable, which retires
    /// this detector without removing it — and burns its one-shot latch while doing so. The persisted
    /// state is passed in rather than re-loaded because `gitBacked` persistence shells out to `git`, and
    /// the adoption path must not pay that twice on the consensus apply thread.
    @Contract
    private void detectBootFutureHistory(Option<SavedState<C>> persisted, SavedState<C> candidate) {
        if (!bootFutureHistoryChecked.compareAndSet(false, true)) {
            return;
        }

        persisted.map(SavedState::lastCommittedPhase)
                 .filter(phase -> phase.compareTo(candidate.lastCommittedPhase()) > 0)
                 .onPresent(phase -> warnBootFutureHistory(phase,
                                                           candidate.lastCommittedPhase()));
    }

    @Contract
    private void warnBootFutureHistory(Phase persisted, Phase clusterReported) {
        log.warn("Node {} BOOT FUTURE-HISTORY detected (§6.4, detect-only): persisted Rabia phase {} exceeds "
                + "cluster-reported sync phase {} — this node carries history the joined cluster never saw "
                + "(mixed-wipe / down -v hazard). Recovery is NOT attempted (RC2).",
                 self,
                 persisted,
                 clusterReported);
        onBootFutureHistory.accept(persisted.value(), clusterReported.value());
    }

    /// Install the Wave-1 §6.4 boot future-history listener (invoked with
    /// `(persistedPhaseValue, clusterReportedPhaseValue)` when [#detectBootFutureHistory] trips).
    /// Diagnostic-only; default no-op. A `null` argument resets to the no-op.
    @Contract
    public void onBootFutureHistory(BiConsumer<Long, Long> listener) {
        this.onBootFutureHistory = listener == null
                                   ? (persisted, cluster) -> {}
                                   : listener;
    }

    /// Register a post-restore listener, invoked after EVERY successful sync restore once the
    /// restored state has been applied, the engine re-activated, AND the §5.8 notification replay
    /// has fired. Restore can run more than once per process (initial join sync, later re-syncs),
    /// so listeners must be idempotent. Fires on the thread `restoreState` completes on (the
    /// consensus apply executor); listeners must be cheap and thread-safe. Listeners ACCUMULATE and
    /// run in registration order. A `null` argument clears all registered listeners.
    ///
    /// SEAM RETAINED (Wave 8 M5 rework, 2026-06-11): the §5.8 amendment removed the production
    /// listeners (gossip-key `replayFromStore` and the action-log drain) — KV-derived boot state
    /// now arrives via the engine-level replay on the normal subscription, not via this hook. The
    /// seam stays as a general post-restore lifecycle notification (exercised by `RabiaEngineTest`
    /// and available for non-KV restore-completion needs); it is NOT narrowed because it is a
    /// legitimate engine lifecycle event independent of the KV notification path.
    @Contract
    public void onStateRestored(Runnable listener) {
        if (listener == null) {
            onStateRestored.clear();

            return;
        }

        onStateRestored.add(listener);
    }

    /// Invoke the post-restore listeners in registration order. Runs on the consensus apply thread.
    @Contract
    private void notifyStateRestored() {
        onStateRestored.forEach(Runnable::run);
    }

    /// A snapshot that skips local slots may already include any old pending request.
    /// Only the source's still-pending batches can safely survive that gap. An equal-frontier
    /// restore has no skipped decisions, so it preserves local requests absent from the source.
    private void reconcileSnapshotPending(Phase nextSlot, List<Batch<C>> restoredPending) {
        if (nextSlot.compareTo(currentPhase.get()) > 0) {
            discardAmbiguousPending(nextSlot, restoredPending);
        }

        restoredPending.forEach(this::learnProposedBatch);
        metrics.updatePendingBatches(self, pendingBatches.size());
    }

    private void discardAmbiguousPending(Phase nextSlot, List<Batch<C>> restoredPending) {
        var retained = restoredPending.stream().map(Batch::id).collect(java.util.stream.Collectors.toSet());
        var cause = new ConsensusError.SnapshotOutcomeUnknown(self, nextSlot.value());

        for (var batch : List.copyOf(pendingBatches.values())) {
            if (!retained.contains(batch.id())) {
                pendingBatches.remove(batch.id());
                failPendingCorrelations(batch, cause);
            }
        }
    }

    private void failPendingCorrelations(Batch<C> batch, Cause cause) {
        for (var correlationId : batch.correlationIds()) {
            Option.option(correlationMap.remove(correlationId)).onPresent(promise -> promise.fail(cause));
        }
    }

    private void applyRestoredState(SavedState<C> state) {
        stateTransferFailure = Option.none();
        reconcileSnapshotPending(state.lastCommittedPhase(), state.pendingBatches());
        // Advance-only: never regress currentPhase below where it already is. A live Decision
        // applied during the Stopped/Syncing window (now buffered via `handleDecision`'s state
        // guard) could have advanced the counter past the candidate snapshot's phase; an
        // unconditional `set` would drop the rejoiner back behind the cluster and cause
        // `commitDecision` to ignore subsequent same-phase Decisions as duplicates.
        currentPhase.updateAndGet(existing -> existing.compareTo(state.lastCommittedPhase()) >= 0
                                              ? existing
                                              : state.lastCommittedPhase());
        state.configuration().onPresent(this::adoptSyncedConfiguration);
        persistRestoredState();
        log.info("Node {} restored state from persistence. Current phase {}", self, currentPhase.get());
    }

    /// Activate node and adjust phase, if necessary.
    /// In observer mode, transitions to Observing state instead of Idle and does not start phases.
    /// #1212 — durably record that this node has participated, BEFORE it leaves `Syncing`.
    ///
    /// Ordering is the whole point: a node cannot vote before it activates, so recording here is
    /// strictly stronger than recording on the vote path, and it has ONE choke point instead of
    /// several. Deliberately ahead of the `observerMode` branch — whether an observer can ever vote
    /// is not a property this gate should depend on, and recording for it costs only conservatism.
    ///
    /// Returns false when the node must NOT activate. That happens only when this node currently
    /// claims never to have participated and the marker could not record otherwise: activating there
    /// would let it vote and then present itself as new on its next boot, which is the exact property
    /// #667 and #1212 exist to prevent. The retry tick re-enters `doSynchronize`, so a transient
    /// write failure resolves itself rather than wedging the node permanently.
    private boolean recordParticipation() {
        return participationMarker.recordParticipation()
                                  .onFailure(cause -> log.error("Node {} refusing to activate: could not durably record "
                                                               + "consensus participation ({}). This node claims to have "
                                                               + "never participated, and activating without recording "
                                                               + "would let it vote and later rejoin presenting itself "
                                                               + "as new (#1212).",
                                                                self,
                                                                cause))
                                  .isSuccess();
    }

    private void activate() {
        if (authorityFailure.isPresent()) {
            return;
        }

        if (!observerMode && !isVoter(self)) {
            log.warn("Node {} cannot activate outside the core electorate", self);

            return;
        }

        if (!recordParticipation()) {
            return;
        }

        if (observerMode) {
            activateAsObserver();

            return;
        }

        var oldState = engineState.getAndSet(new EngineState.Idle());

        exitState(oldState);
        armCleanupTask();
        notifyConsensusStateTransition();
        startPromise.get().succeed(Unit.unit());
        syncResponses.clear();
        syncRounds.set(0);
        metrics.recordSyncAttempt(self, true);
        log.info("Node {} activated in phase {}", self, currentPhase.get());
        // Drain any Decisions that were buffered while the engine was Stopped/Syncing.
        // Must happen AFTER engineState=Idle so that `handleDecision`'s state guard accepts
        // re-applied Decisions, and AFTER the Idle-restore so phase-filter math is correct.
        drainBufferedDecisions();
        safeExecute(this::startPhase);
    }

    private void activateAsObserver() {
        var oldState = engineState.getAndSet(new EngineState.Observing());

        exitState(oldState);
        armCleanupTask();
        notifyConsensusStateTransition();
        startPromise.get().succeed(Unit.unit());
        syncResponses.clear();
        syncRounds.set(0);
        metrics.recordSyncAttempt(self, true);
        log.info("Node {} activated in observer mode at phase {}", self, currentPhase.get());
        drainBufferedDecisions();
    }

    /// Cancels any timers owned by the old state during a transition.
    private void exitState(EngineState oldState) {
        switch (oldState) {
            case EngineState.InPhase(var stallDetector) -> stallDetector.cancel(false);
            case EngineState.Syncing(var syncTask) -> syncTask.cancel(false);
            default -> {}
        }
    }

    /// Re-evaluates whether the engine is currently in an `Active` phase (`Idle | InPhase`)
    /// and emits a `ConsensusEvent` ONLY on edge transitions. Idempotent within an
    /// active-/passive-phase window: the same edge will not fire twice. Called from every
    /// state-mutation site.
    ///
    /// E2 Phase 2c.0 (2026-05-28): `RabiaEngine` is the authoritative source for "consensus
    /// engine is genuinely operational" semantics. The bridge translates these into
    /// `ClusterStateNotification.ACTIVE` / `PASSIVE` on the cluster `MessageRouter`.
    @Contract
    private void notifyConsensusStateTransition() {
        var nowActive = engineState.get().isActive();
        var prev = lastPublishedActive.get();

        if (nowActive == prev) {
            return;
        }

        if (!lastPublishedActive.compareAndSet(prev, nowActive)) {
            return;
        }

        if (nowActive) {
            // Single authority for downstream ClusterStateNotification: TopologyObserver feeds the
            // quorum edge to this engine privately, and this emission (via ConsensusBridge) is now
            // the sole shared-bus producer of Rabia's active status. CAS-latch above stays as
            // belt-and-suspenders: notifyConsensusStateTransition runs off several state-mutation
            // sites that are not all on the single apply executor, so the edge guard is load-bearing.
            log.info("Node {}: emitting ConsensusActive (cluster active)", self);
            consensusEventListener.accept(new ConsensusActive(self));
        } else {
            log.info("Node {}: emitting ConsensusPassive (cluster passive)", self);
            consensusEventListener.accept(new ConsensusPassive(self));
        }
    }

    /// Creates a periodic stall detector that re-broadcasts the held proposal set and this
    /// node's own votes when a phase hasn't advanced within the configured interval.
    private ScheduledFuture<?> createStallDetector() {
        return SharedScheduler.scheduleAtFixedRate(() -> safeExecute(this::checkPhaseStall), phaseStallCheck);
    }

    /// Checks if the current phase is stalled and re-broadcasts protocol messages so peers that
    /// missed the first send (transient QUIC reconnect) or that joined the phase after the
    /// original contributors died can collect them.
    ///
    /// Re-broadcasts, while short of quorum/majority:
    ///   - the FULL proposal SET this node holds (not just its own) — see #258 below;
    ///   - this node's own Round 1 / Round 2 votes.
    ///
    /// Why re-broadcast votes: on a 5-way simultaneous restart, peerLinks flap during the QUIC
    /// handshake storm. `QuicClusterNetwork.broadcast` only sends to peers currently in peerLinks,
    /// so a message sent while a peer is in re-dial gets dropped. Votes are idempotent at the
    /// receiver (registerRound1Vote/registerRound2Vote are keyed on (phase, sender)), so
    /// re-broadcasting is safe.
    ///
    /// Why re-broadcast the whole proposal set (#258): the detector used to re-broadcast only the
    /// node's OWN proposal. If the voters that contributed the quorum proposals die mid-phase,
    /// surviving/fresh voters can never satisfy `hasQuorumProposals` and can never vote — an
    /// unrecoverable phase deadlock. The holder of a dead node's proposal now re-broadcasts it
    /// (under the original sender's id; `registerProposal` is idempotent and keyed on sender), so
    /// a fresh voter synced to the stalled phase can reach quorum proposals and make progress.
    private void checkPhaseStall() {
        if (! (engineState.get() instanceof EngineState.InPhase)) {
            return;
        }

        var phase = currentPhase.get();

        Option.option(phases.get(phase)).onPresent(phaseData -> checkPhaseStallFor(phase, phaseData));
    }

    private void checkPhaseStallFor(Phase phase, PhaseData<C> phaseData) {
        var quorumSize = voterQuorum();

        if (phaseData.proposalCount() > 0) {
            log.debug("Node {} stall detected in phase {}: {}/{} proposals, re-broadcasting full proposal set",
                      self,
                      phase,
                      phaseData.proposalCount(),
                      quorumSize);
            rebroadcastProposalSet(phase, phaseData);
        }

        if (phaseData.hasVotedRound1(self)) {
            Option.option(phaseData.getRound1Vote(self)).onPresent(value -> rebroadcastRound1Stall(phase, value));
        }

        if (phaseData.hasVotedRound2(self)) {
            Option.option(phaseData.getRound2Vote(self)).onPresent(value -> rebroadcastRound2Stall(phase, value));
        }
    }

    /// Re-broadcasts every proposal this node holds for the stalled phase, each under its
    /// original contributing node's id. Idempotent at the receiver; bounded by the periodic
    /// stall-check cadence and by the `!hasQuorumProposals` guard at the call site.
    private void rebroadcastProposalSet(Phase phase, PhaseData<C> phaseData) {
        phaseData.proposals()
                 .forEach((proposer, batch) -> broadcastVoters(new Propose<>(proposer,
                                                                             voterEpoch(),
                                                                             phase,
                                                                             batch,
                                                                             phaseData.configuration(proposer))));
    }

    private void rebroadcastRound1Stall(Phase phase, StateValue value) {
        log.debug("Node {} stall detected in phase {}: round1 votes short of quorum, re-broadcasting own R1 vote",
                  self,
                  phase);
        broadcastVoters(new VoteRound1(self, voterEpoch(), phase, getOrCreatePhaseData(phase).round(), value));
    }

    private void rebroadcastRound2Stall(Phase phase, StateValue value) {
        log.debug("Node {} stall detected in phase {}: round2 votes short of quorum, re-broadcasting own R2 vote",
                  self,
                  phase);
        broadcastVoters(new VoteRound2(self, voterEpoch(), phase, getOrCreatePhaseData(phase).round(), value));
    }

    /// Handles a synchronization request from another node.
    @Contract
    @MessageReceiver
    public void handleSyncRequest(SyncRequest request) {
        safeExecute(() -> doHandleSyncRequest(request));
    }

    private void doHandleSyncRequest(SyncRequest request) {
        // Full consensus state is only for admitted CORE identities, including staged candidates.
        if (!topologyManager.isStateTransferPeer(request.sender())) {
            return;
        }
        // Observer snapshots are not evidence from the consensus electorate.
        if (observerMode || !isVoter(self)) {
            return;
        }

        var state = engineState.get();
        // A Paused responder retains its full in-memory protocol state (stateMachine,
        // currentPhase, pendingBatches) so it can serve the SAME live-equivalent payload an
        // active/observing node would. Only genuinely stateless engines (Stopped/Syncing) fall
        // back to the persisted/empty snapshot, which omits pendingBatches and would otherwise
        // leave a joiner unable to re-propose an in-flight batch.
        if (state.isActive() || state.isObserving() || state.isPaused()) {
            stateMachine.makeSnapshot()
                        .map(snapshot -> new SyncResponse<>(self,
                                                            new SavedState<C>(snapshot,
                                                                              currentPhase.get(),
                                                                              List.copyOf(pendingBatches.values()),
                                                                              voters),
                                                            ResponderState.LIVE))
                        .onSuccess(response -> sendSyncResponse(request.sender(),
                                                                response))
                        .onFailure(cause -> log.error("Node {} failed to create snapshot: {}", self, cause));
        } else {
            log.trace("Node {} is inactive, trying to share saved (or empty) state for request: {}", self, request);
            var response = new SyncResponse<>(self,
                                              persistence.load()
                                                         .map(RabiaEngine::withoutConfiguration)
                                                         .or(SavedState.empty()),
                                              ResponderState.COLD);

            sendSyncResponse(request.sender(), response);
        }
    }

    /// Peer sync responses required before this node will adopt cluster state.
    ///
    /// The gate MUST be a majority of the CLUSTER, never a majority of whoever this node currently
    /// happens to reach. It once computed `min(connectedNodeCount, clusterSize) / 2 + 1`, which
    /// evaluates to **1** at connectivity 0 or 1 — so a node that could reach exactly one peer would
    /// `restoreState` from a SINGLE response, adopting consensus state on the word of one other node,
    /// precisely when it is least likely to be talking to the majority side of a partition. The old
    /// docstring justified this as "adapts to actual connectivity", which is the defect stated as a
    /// feature: connectivity is what a partition manipulates, so deriving a safety threshold from it
    /// means the threshold collapses exactly when it is needed. Same class as #557, where cluster-start
    /// quorum was declared from discovery rather than reachability. The threshold is therefore derived
    /// from `clusterSize` ALONE — never from live, connected, or reachable counts.
    ///
    /// #660: the replacement then counted self twice over. Sync responses arrive only from PEERS
    /// (`broadcastPayload` iterates `peers`; self is never a peer), so demanding `clusterSize / 2 + 1`
    /// RESPONSES silently required `quorum + 1` live nodes. A bare-majority cold start — 3 of 5, or a
    /// 3-node cluster with one node down — sat in `Syncing` forever: consensus never reached ACTIVE, so
    /// no `QuorumEstablished` dispatched, no leader was elected and no reconciler ran, while every link
    /// and every SWIM view stayed healthy. Quorum ESTABLISHMENT counts self, so adoption must count it
    /// exactly once too: `clusterSize / 2` peer responses, and self completes the majority.
    ///
    /// This is safe ONLY because self carries weight in the adoption decision — see [#ownStateFloor].
    /// The responses by themselves are a minority, so the intersection property that makes adoption safe
    /// rests on self's own history being able to REFUSE a response set that is behind it. **The two must
    /// change together**; relaxing this threshold while adopting the best response unconditionally would
    /// trade this deadlock for silent state loss.
    ///
    /// `clusterSize <= 1` yields 0: a single-node cluster has no peers and self alone is its majority.
    /// The previous `1` was unsatisfiable there — a one-node cluster could never leave `Syncing` either.
    private int syncPeerResponsesRequired() {
        return voterCount() / 2;
    }

    /// #667 round 2: the adoption decision, computed ONCE from a single read of the response map and
    /// a single read of the installed voter count. `Option.none()` means "keep collecting"; a present value is
    /// the exact set adoption may choose its candidate from.
    ///
    /// The first cut of #667 thresholded on LIVE responders (`clusterSize / 2 + 1` of them) and a live
    /// minority waited. That is a quantity the waiting nodes cannot increase: the moment one node
    /// activates it answers LIVE, every remaining joiner sees a live responder, switches to the
    /// stricter bound, and they answer each other COLD. The only nodes that could raise the live count
    /// are precisely the ones blocked, and nothing times out of `Syncing` — [#warnIfSyncStuck] only
    /// WARNs. A cluster that had half-started could never finish. Cold start was never the defective
    /// arm: at t=0 every responder is COLD and #660's rule is reached.
    ///
    /// So the threshold is on RESPONSES, and liveness only chooses the SOURCE:
    ///
    /// - any LIVE responder, and `clusterSize / 2 + 1` RESPONSES of any mix: adopt the maximum over
    ///   the LIVE responders. The response quorum is what carries the safety argument — responders
    ///   alone are a majority, so they intersect every majority that could have committed anything,
    ///   without leaning on self's history (the #667 hole was exactly a self whose in-memory
    ///   persistence left it at phase 0, unable to refuse). [#ownStateFloor] stays as the belt.
    /// - no LIVE responder: #660's cold rule, unchanged — `clusterSize / 2` responses with self as the
    ///   floor. Nothing durable answered live, so this is the full-cluster cold bootstrap.
    ///
    /// A response minority still waits, which is the arm the live bound existed to protect: a stale
    /// LIVE responder in a minority partition cannot by itself authorize adoption.
    ///
    /// `UNKNOWN` (an ordinal this node cannot name, #964) counts as COLD when choosing the source, and
    /// counts as a response toward the quorum like any other answer: it can never become the state this
    /// node installs, and it never lowers the number of answers required.
    ///
    /// The single read matters: the previous split between `adoptionThresholdMet()` and
    /// `candidateResponses()` re-read both the response map and the voter count, so a topology change
    /// between the two could pass the gate on one rule and build the candidate set under the other.
    private Option<List<SyncResponse<C>>> adoptionCandidates() {
        var clusterSize = voterCount();
        // Only installed voter authority supplies this denominator. Configuration intent and
        // discovery membership cannot lower the synchronization quorum.
        if (clusterSize < 1) {
            return Option.none();
        }

        var newestEpoch = syncResponses.values()
                                       .stream()
                                       .mapToLong(RabiaEngine::responseEpoch)
                                       .max()
                                       .orElse(voterEpoch());
        var responses = syncResponses.values()
                                     .stream()
                                     .filter(response -> responseEpoch(response) == newestEpoch)
                                     .toList();

        if (newestEpoch > voterEpoch()) {
            return newerEpochCandidates(responses);
        }
        // An observer is outside the electorate: its own state cannot supply the missing
        // member of a response majority, even on a cold start or with durable history.
        if (observerMode || !isVoter(self)) {
            return responses.size() >= clusterSize / 2 + 1
                   ? Option.some(responses)
                   : Option.none();
        }

        var liveResponses = responses.stream().filter(response -> response.responder() == ResponderState.LIVE).toList();

        if (liveResponses.isEmpty()) {
            return responses.size() >= clusterSize / 2
                   ? Option.some(responses)
                   : Option.none();
        }

        if (responses.size() < responsesRequiredWithALiveResponder(clusterSize)) {
            return Option.none();
        }
        // The LIVE filter is licensed by the intersection argument only when the LIVE responders are
        // THEMSELVES a majority. A response quorum intersects every commit quorum, but the intersecting
        // member may be COLD, and filtering it out is how a joiner adopts a state behind a commit that
        // was sitting in its own response set. So: the live maximum when live is a majority, otherwise
        // the maximum over everything that answered.
        //
        // This branch is NOT dead, but it is narrow, and saying so is the point (#667 round 2).
        // Adoption normally fires on the arrival that first meets the requirement, so the collected set
        // is exactly the requirement and "a live majority among them" reduces to "all of them are
        // LIVE", where filtering removes nothing. The filter only SELECTS when the collected set is
        // LARGER than the requirement, for example when an applied reconfiguration changes
        // the installed electorate while responses are collected. Desired core counts cannot
        // directly change this denominator.
        return Option.some(liveResponses.size() >= clusterSize / 2 + 1
                           ? liveResponses
                           : responses);
    }

    private static long responseEpoch(SyncResponse<?> response) {
        return response.state()
                       .configuration()
                       .map(VoterConfiguration::epoch)
                       .or(0L);
    }

    /// Adoption of a NEWER voter epoch (#1526, Rabia §4 design point 3): ONE live responder that is a
    /// member of the configuration it claims suffices; COLD responders never authorize it.
    ///
    /// Why this does not weaken safety under crash faults. The same-epoch arms below need a response
    /// majority because the adopting replica may hold ballots of its own that a staler state would
    /// contradict (#667's amnesiac self), or may be the only witness of a commit. Neither applies here:
    /// - A live responder's claimed configuration is one it applied from the log, and its state is its
    ///   own applied prefix — a genuine prefix of the one log. Crash faults do not fabricate either.
    /// - A replica still at an OLDER epoch has not applied the change's slot R, and slots are strictly
    ///   sequential, so it has never cast a ballot in any slot at or after R+1. The adopted frontier is
    ///   at least R+1, so it joins every later slot as a fresh participant — which Weak-MVC tolerates
    ///   exactly like a slow replica that missed earlier rounds. It has no ballot to contradict.
    /// - A responder that is itself behind the cluster only makes this replica behind as well: it
    ///   catches up through decision relay and slot repair. Staleness costs liveness, never safety.
    ///
    /// Requiring a response quorum here instead would wedge precisely the case §4 exists to handle: a
    /// replacement voter whose only live peer in the new roster is the one that decided R (#1526's
    /// {A,B,C} → {A,B,D} with B lost after R). Responses whose claimed configurations disagree are
    /// refused — under crash faults that cannot happen, so it is reported as an inconsistency.
    ///
    /// [limit: amnesiac-same-id-excluded-by-boot-token] Safe only because a same-NodeId restart is refused
    /// at transport (#1528/#1545); a fresh ULID has never balloted. The second bullet above assumes the
    /// adopting process is the one that held this NodeId's ballots, or a new identity. A process restarted
    /// under a NodeId that DID ballot past R, with its memory lost, breaks that assumption: it can adopt
    /// from one responder and vote again (`V1554AmnesiaProbeTest` demonstrates it at this layer). Peers
    /// keep the original process's boot token and drop everything from a different process under that id
    /// before it reaches this engine, which is what excludes the case. A responder-quorum fence here would
    /// recreate the #1526 wedge for a replacement that must adopt a snapshot.
    private Option<List<SyncResponse<C>>> newerEpochCandidates(List<SyncResponse<C>> responses) {
        var live = responses.stream().filter(response -> response.responder() == ResponderState.LIVE).toList();
        var claimed = live.stream().map(response -> response.state()
                                                            .configuration()).distinct().count();

        if (claimed > 1) {
            log.warn("Node {} refuses newer-epoch sync state: live responders claim {} different configurations at one epoch",
                     self,
                     claimed);
        }

        return live.isEmpty() || claimed != 1
               ? Option.none()
               : Option.some(live);
    }

    /// Responses required once any responder is live.
    ///
    /// The set that must intersect every commit quorum is `{responders} ∪ {self}`, so self may be
    /// counted — but ONLY when it brings history of its own. An amnesiac self (in-memory persistence,
    /// or a wiped disk) sits inside that majority contributing nothing, and that is #667's hole
    /// exactly: its floor is `Phase.ZERO`, it can refuse nothing, and two responders that never
    /// witnessed the latest commit are enough to pull it forward. Excluded from its own count, the
    /// responders must be a majority alone.
    ///
    /// **Owner ruling, session 20.** At n=3 with one node down at most ONE responder exists, and one is
    /// never a majority of three — so in a degraded 3-node cluster #667's safety property and joiner
    /// liveness are incompatible, and the owner chose liveness. The property being spent is one the
    /// system does not in fact hold: #660's cold rule, shipping today and untouched by #667, already
    /// activates a self whose durable snapshot is STALE relative to a commit it witnessed — verified
    /// against rc4 `4af02125c`, where 2 of 5 minority responses activate and install the stale state
    /// while a 1-of-5 control stays inactive. This makes the live arm consistent with the cold arm
    /// rather than introducing a new exposure.
    ///
    /// The residual risk, stated precisely because it is narrower than "self is stale": adoption can
    /// discard a commit only when self was in a commit quorum whose every OTHER member is currently
    /// unreachable AND self lost its own record of it. A genuinely new node was never in a prior
    /// quorum, so for it that branch is unreachable.
    ///
    /// **#1212 — the second way to reach the cold bound.** The sentence above ("a genuinely new node
    /// was never in a prior quorum, so for it that branch is unreachable") is an argument this rule
    /// could not previously ACT on, because nothing durable recorded that a node had never
    /// participated. [ParticipationMarker] supplies exactly that observation, and nothing else: a
    /// node that provably never voted was in no commit quorum, so every commit quorum intersecting
    /// `{responders} ∪ {self}` intersects at a RESPONDER that holds the commit. Admitting it on
    /// `clusterSize / 2` spends nothing.
    ///
    /// The two disjuncts are independent and neither subsumes the other: a returning node with
    /// durable state vouches for its own history, a brand-new node has no history TO vouch for. Both
    /// reach the cold bound, for opposite reasons.
    private int responsesRequiredWithALiveResponder(int clusterSize) {
        return selfCanVouchForItsOwnHistory() || selfProvablyNeverVoted()
               ? clusterSize / 2
               : clusterSize / 2 + 1;
    }

    /// #1212 — whether this node's durable marker proves it has never participated in consensus, and
    /// therefore never voted. UNKNOWN and PARTICIPATED both answer false; only a marker written
    /// BEFORE the node first participated can answer true.
    private boolean selfProvablyNeverVoted() {
        return participationMarker.resolve()
                                  .provablyNeverVoted();
    }

    /// Whether self brings history of its own to the adoption majority — the same quantity
    /// [#ownStateFloor] uses to refuse a response set that is behind this node, so the two cannot
    /// disagree about what self knows.
    private boolean selfCanVouchForItsOwnHistory() {
        return ownStateFloor(persistence.load()).compareTo(Phase.ZERO) > 0;
    }

    /// Adopts when the collected responses already satisfy the rule, reporting whether it did so the
    /// callers can log the "still waiting" case without evaluating the decision a second time.
    private boolean adoptIfThresholdMet() {
        var candidates = adoptionCandidates();

        candidates.onPresent(this::adoptCollectedState);

        return candidates.isPresent();
    }

    private long liveResponseCount() {
        return syncResponses.values()
                            .stream()
                            .filter(response -> response.responder() == ResponderState.LIVE)
                            .count();
    }

    /// Cleans up old phase data to prevent memory leaks.
    private void cleanupOldPhases() {
        safeExecute(this::doCleanupOldPhases);
    }

    /// Test-only view of the #714 arming state. Package-private deliberately: the arming point is
    /// the contract this ticket changed, and it is not observable any other way — `SharedScheduler`
    /// exposes no introspection, so without this a test could only assert the arming indirectly
    /// through timing, which would pin nothing.
    boolean cleanupArmed() {
        return cleanupTask.get() != null;
    }

    /// Idempotent arm for the old-phase sweep (#714). Activation is reached repeatedly — every
    /// re-activation after a reconfigure or a quorum-loss pause runs through it — so this must arm
    /// exactly once per running engine. The CAS is the guard; a caller that loses the race cancels
    /// the future it just created rather than leaking it, which is the same leak class this ticket
    /// is about and would be an embarrassing way to reintroduce it.
    private void armCleanupTask() {
        if (cleanupTask.get() != null) {
            return;
        }

        var task = SharedScheduler.scheduleAtFixedRate(this::cleanupOldPhases, config.cleanupInterval());

        if (!cleanupTask.compareAndSet(null, task)) {
            task.cancel(false);
        }
    }

    private void doCleanupOldPhases() {
        var state = engineState.get();

        if (!state.isActive() && !state.isObserving()) {
            return;
        }

        var current = currentPhase.get();

        phases.keySet().removeIf(phase -> isExpiredPhase(phase, current));
    }

    private boolean isExpiredPhase(Phase phase, Phase current) {
        return phase.compareTo(current) < 0 && current.value() - phase.value() > config.removeOlderThanPhases();
    }

    /// Handles a Propose message from another node.
    /// NOTE: All nodes MUST process proposals regardless of active/dormant state.
    /// Rabia is leaderless — every node participates in every round.
    private void handlePropose(Propose<C> propose) {
        if (deferIfNewerEpoch(propose.sender(), propose.epoch(), propose)) {
            return;
        }

        recordCatchUp(propose.sender(), propose.epoch(), propose.phase());
        var currentPhaseValue = currentPhase.get();

        if (isPastPhase(propose.phase(), currentPhaseValue)) {
            repairPastSlot(propose.sender(), propose.phase());

            return;
        }

        if (!acceptsBallot(propose.sender(), propose.epoch())) {
            return;
        }

        if (propose.reconfiguration().isPresent() && propose.value().isNotEmpty()) {
            return;
        }

        log.trace("Node {} received proposal from {} for phase {}", self, propose.sender(), propose.phase());
        observeClusterPhase(propose.phase());
        if (isFarFuturePhase(propose.phase(), currentPhaseValue)) {
            log.warn("Node {} behind by {} phases (current: {}, received: {}). Triggering resync.",
                     self,
                     propose.phase().value() - currentPhaseValue.value(),
                     currentPhaseValue,
                     propose.phase());
            triggerResync();

            return;
        }
        // Proposals also repair missed NewBatch dissemination. Without this, divergent queue
        // heads survive every V0 slot and fair ballot delivery cannot make application progress.
        // The past-slot guard above prevents delayed proposals from resurrecting committed work.
        if (propose.value().isNotEmpty()) {
            learnProposedBatch(propose.value());
        }

        var phaseData = getOrCreatePhaseData(propose.phase());

        propose.reconfiguration().onPresent(this::adoptRequested);
        enterPhaseIfNeeded(propose.phase(), currentPhaseValue, phaseData);
        if (engineState.get().isInPhase() && !phaseData.hasProposal(self)) {
            broadcastOwnProposalIfNeeded();
        }

        registerProposal(propose, phaseData);
        driveBinaryRound(phaseData);
    }

    private static final long MAX_PHASE_AHEAD = 100;

    private boolean isFarFuturePhase(Phase proposalPhase, Phase current) {
        return proposalPhase.value() - current.value() > MAX_PHASE_AHEAD;
    }

    /// Triggers a resync when the node detects it's significantly behind.
    /// No-op when already syncing — doSynchronize() handles retries internally.
    private void triggerResync() {
        if (engineState.get() instanceof EngineState.Syncing) {
            return;
        }

        doClusterConnected();
    }

    private boolean isPastPhase(Phase proposalPhase, Phase current) {
        return proposalPhase.compareTo(current) < 0;
    }

    private void enterPhaseIfNeeded(Phase proposalPhase, Phase currentPhaseValue, PhaseData<C> phaseData) {
        if (!proposalPhase.equals(currentPhaseValue) || engineState.get().isInPhase()) {
            return;
        }

        log.trace("Node {} entering phase {} triggered by external proposal", self, proposalPhase);
        var stallDetector = createStallDetector();
        var current = engineState.get();

        if (! (current instanceof EngineState.Idle) || !engineState.compareAndSet(current,
                                                                                  new EngineState.InPhase(stallDetector))) {
            stallDetector.cancel(false);

            return;
        }

        notifyConsensusStateTransition();
        broadcastOwnProposal(proposalPhase, phaseData, nextProposal());
        driveBinaryRound(phaseData);
    }

    private void broadcastOwnProposal(Phase phase, PhaseData<C> phaseData, Batch<C> batch) {
        var proposedBatch = requestedConfiguration.isPresent()
                            ? Batch.<C> emptyBatch()
                            : batch;

        if (!phaseData.hasProposal(self)) {
            phaseData.registerProposal(self, proposedBatch, requestedConfiguration);
        }

        broadcastVoters(new Propose<>(self,
                                      voterEpoch(),
                                      phase,
                                      phaseData.getProposal(self),
                                      phaseData.configuration(self)));
    }

    private void registerProposal(Propose<C> propose, PhaseData<C> phaseData) {
        phaseData.registerProposal(propose.sender(), propose.value(), propose.reconfiguration());
        metrics.recordProposal(propose.sender(), propose.phase());
    }

    private void tryBroadcastRound1Vote(Phase phase, PhaseData<C> phaseData) {
        var quorumSize = voterQuorum();

        if (canVoteRound1(phase, phaseData, quorumSize)) {
            broadcastRound1Vote(phase, phaseData, quorumSize);
        } else {
            logRound1VoteConditionsNotMet(phase, phaseData, quorumSize);
        }
    }

    private boolean canVoteRound1(Phase phase, PhaseData<C> phaseData, int quorumSize) {
        return engineState.get()
                          .isInPhase()
               && currentPhase.get()
                              .equals(phase)
               && phaseData.round() == 0
               && !phaseData.hasVotedRound1(self)
               && phaseData.hasQuorumProposals(quorumSize);
    }

    private void broadcastRound1Vote(Phase phase, PhaseData<C> phaseData, int quorumSize) {
        var vote = phaseData.evaluateInitialVote(self, quorumSize);

        log.trace("Node {} broadcasting R1 vote {} for phase {} after collecting quorum proposals", self, vote, phase);
        if (broadcastVoters(vote)) {
            phaseData.registerRound1Vote(self, vote.stateValue());
        }
    }

    private void logRound1VoteConditionsNotMet(Phase phase, PhaseData<C> phaseData, int quorumSize) {
        log.trace("Node {} conditions not met to vote R1 for phase {}. InPhase: {}, CurrentPhase: {}, HasVotedR1: {}, ProposalCount: {}/{}",
                  self,
                  phase,
                  engineState.get().isInPhase(),
                  currentPhase.get(),
                  phaseData.hasVotedRound1(self),
                  phaseData.proposalCount(),
                  quorumSize);
    }

    /// Handles a round 1 vote from another node.
    private void handleVoteRound1(VoteRound1 vote) {
        if (deferIfNewerEpoch(vote.sender(), vote.epoch(), vote)) {
            return;
        }

        recordCatchUp(vote.sender(), vote.epoch(), vote.phase());
        if (isPastPhase(vote.phase(), currentPhase.get())) {
            repairPastSlot(vote.sender(), vote.phase());

            return;
        }

        if (!acceptsBallot(vote.sender(), vote.epoch())) {
            return;
        }

        log.trace("Node {} received round 1 vote from {} for phase {} with value {}",
                  self,
                  vote.sender(),
                  vote.phase(),
                  vote.stateValue());
        observeClusterPhase(vote.phase());
        if (vote.round() < 0) {
            return;
        }

        if (vote.stateValue() == StateValue.VQUESTION) {
            return;
        }

        var phaseData = getOrCreatePhaseData(vote.phase());

        if (vote.round() < phaseData.round()) {
            return;
        }

        if (vote.round() > phaseData.round()) {
            network.send(vote.sender(),
                         new RoundRequest(self, voterEpoch(), vote.phase(), phaseData.round()));
        }

        if (vote.round() - phaseData.round() > MAX_PHASE_AHEAD) {
            return;
        }

        registerRound1Vote(vote, phaseData);
        driveBinaryRound(phaseData);
    }

    private void registerRound1Vote(VoteRound1 vote, PhaseData<C> phaseData) {
        phaseData.registerRound1Vote(vote.sender(), vote.round(), vote.stateValue());
        metrics.recordVoteRound1(vote.sender(), vote.phase(), vote.stateValue());
    }

    private void driveBinaryRound(PhaseData<C> phaseData) {
        tryBroadcastRound1Vote(phaseData.phase(), phaseData);
        if (canVoteRound2(phaseData.phase(), phaseData, voterQuorum())) {
            broadcastRound2Vote(phaseData.phase(), phaseData, voterQuorum());
        }

        tryMakeDecision(phaseData.phase(), phaseData);
    }

    @Contract
    @MessageReceiver
    public void handleRoundRequest(RoundRequest request) {
        safeExecute(() -> doHandleRoundRequest(request));
    }

    private void doHandleRoundRequest(RoundRequest request) {
        if (request.round() < 0) {
            return;
        }

        if (isPastPhase(request.phase(), currentPhase.get())) {
            repairPastSlot(request.sender(), request.phase());

            return;
        }

        if (!acceptsBallot(request.sender(), request.epoch())) {
            return;
        }

        Option.option(phases.get(request.phase()))
              .filter(data -> request.round() <= data.round())
              .onPresent(data -> replayRoundTo(request.sender(),
                                               data,
                                               request.round()));
    }

    private void replayCompletedSlot(NodeId peer, Phase phase) {
        Option.option(phases.get(phase))
              .flatMap(PhaseData::completedDecision)
              .map(decision -> new Decision<C>(self,
                                               decision.epoch(),
                                               decision.phase(),
                                               decision.stateValue(),
                                               decision.value(),
                                               decision.reconfiguration()))
              .onPresent(decision -> network.send(peer, decision))
              .onEmpty(() -> doHandleSyncRequest(new SyncRequest(peer)));
    }

    private void replayRoundTo(NodeId peer, PhaseData<C> phaseData, long round) {
        phaseData.round1Vote(self, round)
                 .onPresent(value -> network.send(peer,
                                                  new VoteRound1(self,
                                                                 voterEpoch(),
                                                                 phaseData.phase(),
                                                                 round,
                                                                 value)));
        phaseData.round2Vote(self, round)
                 .onPresent(value -> network.send(peer,
                                                  new VoteRound2(self,
                                                                 voterEpoch(),
                                                                 phaseData.phase(),
                                                                 round,
                                                                 value)));
    }

    private boolean canVoteRound2(Phase phase, PhaseData<C> phaseData, int quorumSize) {
        return engineState.get()
                          .isInPhase()
               && currentPhase.get()
                              .equals(phase)
               && phaseData.hasVotedRound1(self)
               && !phaseData.hasVotedRound2(self)
               && phaseData.hasRound1MajorityVotes(quorumSize);
    }

    private void broadcastRound2Vote(Phase phase, PhaseData<C> phaseData, int quorumSize) {
        var round2Vote = phaseData.evaluateRound2Vote(quorumSize);

        log.trace("Node {} votes in round 2 {}", self, round2Vote);
        if (broadcastVoters(new VoteRound2(self, voterEpoch(), phase, phaseData.round(), round2Vote))) {
            phaseData.registerRound2Vote(self, round2Vote);
        }
    }

    /// Handles a round 2 vote from another node.
    private void handleVoteRound2(VoteRound2 vote) {
        if (deferIfNewerEpoch(vote.sender(), vote.epoch(), vote)) {
            return;
        }

        recordCatchUp(vote.sender(), vote.epoch(), vote.phase());
        if (isPastPhase(vote.phase(), currentPhase.get())) {
            repairPastSlot(vote.sender(), vote.phase());

            return;
        }

        if (!acceptsBallot(vote.sender(), vote.epoch())) {
            return;
        }

        log.trace("Node {} received round 2 vote from {} for phase {} with value {}",
                  self,
                  vote.sender(),
                  vote.phase(),
                  vote.stateValue());
        observeClusterPhase(vote.phase());
        if (vote.round() < 0) {
            return;
        }

        var phaseData = getOrCreatePhaseData(vote.phase());

        if (vote.round() < phaseData.round()) {
            return;
        }

        if (vote.round() > phaseData.round()) {
            network.send(vote.sender(),
                         new RoundRequest(self, voterEpoch(), vote.phase(), phaseData.round()));
        }

        if (vote.round() - phaseData.round() > MAX_PHASE_AHEAD) {
            return;
        }

        registerRound2Vote(vote, phaseData);
        driveBinaryRound(phaseData);
    }

    private void registerRound2Vote(VoteRound2 vote, PhaseData<C> phaseData) {
        phaseData.registerRound2Vote(vote.sender(), vote.round(), vote.stateValue());
        metrics.recordVoteRound2(vote.sender(), vote.phase(), vote.stateValue());
    }

    private void tryMakeDecision(Phase phase, PhaseData<C> phaseData) {
        var quorumSize = voterQuorum();

        if (canMakeDecision(phase, phaseData, quorumSize)) {
            makeAndBroadcastDecision(phaseData, quorumSize);
        }
    }

    private boolean canMakeDecision(Phase phase, PhaseData<C> phaseData, int quorumSize) {
        return engineState.get()
                          .isInPhase()
               && currentPhase.get()
                              .equals(phase)
               && !phaseData.isDecided()
               && phaseData.hasVotedRound2(self)
               && phaseData.hasRound2MajorityVotes(quorumSize);
    }

    private void makeAndBroadcastDecision(PhaseData<C> phaseData, int quorumSize) {
        var outcome = phaseData.processRound2Completion(self, voterFPlusOne(), quorumSize);

        switch (outcome) {
            case Round2Outcome.AwaitingProposal<C> ignored -> rebroadcastProposalSet(phaseData.phase(), phaseData);
            case Round2Outcome.Decided<C> decided -> {
                broadcastCoreObservers(decided.decision());
                processDecision(decided.decision());
            }
            case Round2Outcome.CarryForward<C> carryForward -> {
                phaseData.advanceRound(self, carryForward.value());
                broadcastVoters(new VoteRound1(self,
                                               voterEpoch(),
                                               phaseData.phase(),
                                               phaseData.round(),
                                               carryForward.value()));
                safeExecute(() -> driveBinaryRound(phaseData));
            }
        }
    }

    private void commitDecision(PhaseData<C> phaseData, Decision<C> decision) {
        if (phaseData.tryMarkDecided()) {
            metrics.recordDecision(self, phaseData.phase(), decision.stateValue(), 0L);
            // Apply commands to state machine ONLY if it was a V1 decision with a non-empty batch
            if (decision.stateValue() == StateValue.V1 && !decision.value().commands().isEmpty()) {
                commitChanges(phaseData, decision);
            }

            phaseData.completedDecision(decision);
            if (decision.stateValue() == StateValue.V1) {
                decision.reconfiguration().onPresent(command -> applyReconfiguration(phaseData.phase(), command));
            }

            advancePhase(phaseData.phase());
        }
    }

    @SuppressWarnings("unchecked")
    private void commitChanges(PhaseData<C> phaseData, Decision<C> decision) {
        log.trace("Node {} applies decision {}", self, decision);
        var results = stateMachine.processCommitted(decision.value(),
                                                    decision.phase().successor().value());
        // Get the batch from pendingBatches BEFORE removing - this has all merged correlationIds.
        // The decision.value() may have partial IDs if the proposer hadn't received all batches yet.
        var localBatch = Option.option(pendingBatches.remove(decision.value().id()));

        metrics.updatePendingBatches(self, pendingBatches.size());
        // Use correlationIds from our local pendingBatches (fully merged) rather than
        // from decision.value() (which may have partial IDs from early proposals)
        var correlationIds = localBatch.map(Batch::correlationIds).or(() -> decision.value()
                                                                                    .correlationIds());

        for (var correlationId : correlationIds) {
            Option.option(correlationMap.remove(correlationId)).onPresent(promise -> promise.succeed(results));
        }
    }

    /// Handles a decision message from another node.
    /// Observers also process decisions to keep their state machine in sync.
    ///
    /// Engine-state guard: Decisions delivered while the engine is `Stopped` / `Syncing` are
    /// buffered for replay after `activate()`. Applying them eagerly causes asymmetric apply
    /// — the Decision mutates state via `commitDecision → advancePhase`, then the imminent
    /// `stateMachine.restoreSnapshot` wipes the mutation and `applyRestoredState` regresses
    /// `currentPhase`. Live KV writes from the cluster's current phase silently disappear
    /// from the rejoiner's local state machine, leaving it stuck (its FSM proposes against a
    /// phantom phase counter and never commits because the cluster is at a different phase).
    ///
    /// Membership-architecture-spec §4.5: while `Paused` (transient quorum loss), Decisions
    /// MUST be applied directly so the engine catches up transparently when quorum returns.
    /// Buffering on Paused would defeat the purpose — state would silently drift and require
    /// a full sync round on resume, which the new design explicitly avoids.
    private void handleDecision(Decision<C> decision) {
        if (authorityFailure.isPresent() || !isTrustedPeer(decision.sender())) {
            return;
        }

        log.trace("Node {} received decision {}", self, decision);
        if ((decision.stateValue() != StateValue.V0 && decision.stateValue() != StateValue.V1) || (decision.stateValue() == StateValue.V1 && decision.value()
                                                                                                                                                     .commands()
                                                                                                                                                     .isEmpty() && decision.reconfiguration()
                                                                                                                                                                           .isEmpty())) {
            return;
        }

        observeClusterPhase(decision.phase());
        var state = engineState.get();

        if (state instanceof EngineState.Stopped || state instanceof EngineState.Syncing) {
            bufferDecisionForReplay(decision);

            return;
        }

        var comparison = decision.phase().compareTo(currentPhase.get());

        if (comparison < 0) {
            return;
        }

        if (comparison > 0) {
            bufferDecisionForReplay(decision);
            // The log cannot apply across a missing slot. Snapshot repair establishes
            // the complete applied prefix before replaying this buffered decision.
            // Quorum loss defers the request until resume drains this same buffer.
            if (!state.isPaused()) {
                triggerResync();
            }

            return;
        }
        // The epoch governing a slot is a function of the applied prefix, so a correct Decision for the
        // current slot carries this replica's epoch. Anything else is refused, never applied.
        if (decision.epoch() != voterEpoch()) {
            log.warn("Node {} refused a decision for slot {} at epoch {}; this replica governs the slot at epoch {}",
                     self,
                     decision.phase(),
                     decision.epoch(),
                     voterEpoch());

            return;
        }

        commitDecision(getOrCreatePhaseData(decision.phase()), decision);
    }

    private void bufferDecisionForReplay(Decision<C> decision) {
        bufferedDecisions.offer(decision);
        if (bufferedDecisionCount.incrementAndGet() > MAX_BUFFERED_DECISIONS) {
            bufferedDecisions.pollFirst();
            bufferedDecisionCount.decrementAndGet();
        }
    }

    /// Drains the buffered Decisions queue after `activate()` has transitioned the engine
    /// to an accepting state. Replay uses the same ordering guard as live delivery: old
    /// phases are ignored independently of phase-cache retention, the current phase is
    /// applied, and an unresolved gap triggers synchronization. Runs on the executor thread.
    private void drainBufferedDecisions() {
        if (bufferedDecisions.isEmpty()) {
            return;
        }

        var sorted = bufferedDecisions.stream().sorted(java.util.Comparator.comparing(Decision::phase)).toList();

        bufferedDecisions.clear();
        bufferedDecisionCount.set(0);
        for (var decision : sorted) {
            handleDecision(decision);
        }
    }

    /// Completes one log slot. Binary carry-forward never calls this method.
    private void advancePhase(Phase fromPhase) {
        var nextPhase = fromPhase.successor();

        this.currentPhase.updateAndGet(p -> p.compareTo(nextPhase) >= 0
                                            ? p
                                            : nextPhase);
        if (observerMode) {
            advancePhaseAsObserver(nextPhase);

            return;
        }
        // Only transition InPhase → Idle. Preserve Syncing/Stopped states so that
        // live Decisions received during synchronization don't cancel the sync task.
        var oldState = engineState.get();

        if (oldState instanceof EngineState.InPhase) {
            engineState.set(new EngineState.Idle());
            exitState(oldState);
            notifyConsensusStateTransition();
        }

        if (oldState.isObserving()) {
            promoteAddedVoter();
        }

        if (!pendingBatches.isEmpty() || requestedConfiguration.isPresent()) {
            safeExecute(this::startPhase);
        }
    }

    /// An observer that applied a change adding it starts voting from the change's R+1, through the
    /// same #1212 participation gate as any activation. A refused record leaves it observing; the next
    /// applied slot retries.
    private void promoteAddedVoter() {
        if (!activationAuthorized || !recordParticipation()) {
            return;
        }

        engineState.set(new EngineState.Idle());
        armCleanupTask();
        notifyConsensusStateTransition();
        startPromise.get().succeed(Unit.unit());
        log.info("Node {} joined the voter roster at phase {}", self, currentPhase.get());
        safeExecute(this::openSlotAfterChange);
    }

    private void advancePhaseAsObserver(Phase nextPhase) {
        var oldState = engineState.getAndSet(new EngineState.Observing());

        exitState(oldState);
        notifyConsensusStateTransition();
        log.trace("Node {} (observer) advancing to phase {}", self, nextPhase);
    }

    /// Gets or creates phase data for a specific phase. Data for a current or future slot created under
    /// an epoch the slot no longer belongs to is replaced, so every ballot, decision and coin flip of a
    /// slot at or after a change's R+1 is evaluated under the epoch that governs it. Completed slots keep
    /// their data, which replay needs verbatim.
    private PhaseData<C> getOrCreatePhaseData(Phase phase) {
        var epoch = voterEpoch();
        var frontier = currentPhase.get();

        return phases.compute(phase,
                              (slot, existing) -> currentPhaseData(slot, Option.option(existing), epoch, frontier));
    }

    private PhaseData<C> currentPhaseData(Phase slot, Option<PhaseData<C>> existing, long epoch, Phase frontier) {
        return existing.filter(data -> data.epoch() == epoch || slot.compareTo(frontier) < 0)
                       .or(() -> new PhaseData<>(slot, epoch));
    }
}
