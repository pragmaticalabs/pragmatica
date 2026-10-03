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
package org.pragmatica.dht;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;
import java.util.zip.CRC32;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTMessage.Readiness;
import org.pragmatica.dht.storage.StorageEngine;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Local DHT node that handles storage operations.
/// Provides local data access and can be integrated with MessageRouter
/// for handling remote requests.
public final class DHTNode {
    private static final Logger log = LoggerFactory.getLogger(DHTNode.class);

    private final NodeId nodeId;
    private final StorageEngine storage;
    private final ConsistentHashRing<NodeId> ring;
    /// The replication this node places keys and sizes quorums with. Live (#1777 track 1): the cluster's
    /// committed `[replication]` factors arrive after boot and may change at runtime ([#resolveReplication]).
    private final AtomicReference<DHTConfig> config;
    /// Whether [#config] holds the committed factors yet. Until it does, every partition answers
    /// [Readiness#CATCHING_UP] and clients refuse quorum operations ([DHTError#REPLICATION_UNRESOLVED]).
    private final AtomicBoolean replicationResolved;
    /// Serializes the two mutations that change replica sets — a ring change and a replication change — so each
    /// diffs against the state the other left.
    private final Object placementLock = new Object();
    private final HlcClock hlcClock;
    private final CatchUpState catchUp = CatchUpState.catchUpState();
    /// The ring members when [#beginCatchUp] ran: the joins observed since boot are the current members
    /// outside this set, and they bound the boot walk ([#previousHolders]).
    private final AtomicReference<Set<NodeId>> bootMembers = new AtomicReference<>(Set.of());
    /// Ring members this node has heard from over the DHT since it booted: a digest request or answer from
    /// them. The rest are unconfirmed and lengthen the boot walk ([#previousHolders]).
    private final Set<NodeId> heardFrom = ConcurrentHashMap.newKeySet();
    private final AtomicLong belowHighWaterCopies = new AtomicLong();
    /// How long a tombstone is kept (#1777 track 3): the cluster's committed `[replication] tombstone_retention`.
    private final AtomicReference<TimeSpan> tombstoneRetention = new AtomicReference<>(DEFAULT_TOMBSTONE_RETENTION);
    /// Wall-clock time in milliseconds, comparable with the HLC physical time a tombstone is stamped with.
    private final AtomicReference<LongSupplier> wallClock = new AtomicReference<>(System::currentTimeMillis);
    /// When this node stopped replicating each partition it still holds copies of (#1777 track 3): a stray copy
    /// is dropped once it is older than the stray horizon, before any tombstone that could supersede it may go.
    private final ConcurrentHashMap<Integer, Long> lostAt = new ConcurrentHashMap<>();
    /// When a node last LEFT each partition's replica set in this node's view (#1777 track 3): a tombstone is not
    /// collected until every holder displaced by a ring change has dropped its stray copy.
    private final ConcurrentHashMap<Integer, Long> holderLeftAt = new ConcurrentHashMap<>();
    /// When each partition last had a full agreement round: every co-replica SERVING with an equal digest.
    private final ConcurrentHashMap<Integer, Long> agreedAt = new ConcurrentHashMap<>();
    private final AtomicLong collectedTombstones = new AtomicLong();
    private final AtomicLong purgedStrayPartitions = new AtomicLong();

    /// The default tombstone retention (owner ruling 2026-10-03, #1777): one hour.
    public static final TimeSpan DEFAULT_TOMBSTONE_RETENTION = TimeSpan.timeSpan(1).hours();

    private DHTNode(NodeId nodeId,
                    StorageEngine storage,
                    ConsistentHashRing<NodeId> ring,
                    DHTConfig config,
                    boolean replicationResolved,
                    HlcClock hlcClock) {
        this.nodeId = nodeId;
        this.storage = storage;
        this.ring = ring;
        this.config = new AtomicReference<>(config);
        this.replicationResolved = new AtomicBoolean(replicationResolved);
        this.hlcClock = hlcClock;
    }

    /// Create a new DHT node with an externally provided HLC clock.
    ///
    /// @param nodeId   this node's identifier
    /// @param storage  storage engine for local data
    /// @param ring     consistent hash ring for routing
    /// @param config   DHT configuration
    /// @param hlcClock hybrid logical clock for version tracking
    public static DHTNode dhtNode(NodeId nodeId,
                                  StorageEngine storage,
                                  ConsistentHashRing<NodeId> ring,
                                  DHTConfig config,
                                  HlcClock hlcClock) {
        return new DHTNode(nodeId, storage, ring, config, true, hlcClock);
    }

    /// Create a DHT node whose replication factors are not known yet (#1777 track 1): `placeholder` is used for
    /// the boot catch-up walk and for its timeout and retry policy only. The node answers every partition
    /// [Readiness#CATCHING_UP], and quorum clients refuse, until [#resolveReplication] supplies the cluster's
    /// committed factors.
    public static DHTNode dhtNodeAwaitingReplication(NodeId nodeId,
                                                     StorageEngine storage,
                                                     ConsistentHashRing<NodeId> ring,
                                                     DHTConfig placeholder,
                                                     HlcClock hlcClock) {
        return new DHTNode(nodeId, storage, ring, placeholder, false, hlcClock);
    }

    /// Create a new DHT node with an internally created HLC clock.
    ///
    /// @param nodeId  this node's identifier
    /// @param storage storage engine for local data
    /// @param ring    consistent hash ring for routing
    /// @param config  DHT configuration
    public static DHTNode dhtNode(NodeId nodeId,
                                  StorageEngine storage,
                                  ConsistentHashRing<NodeId> ring,
                                  DHTConfig config) {
        var clock = HlcClock.hlcClock(nodeId);

        return new DHTNode(nodeId, storage, ring, config, true, clock);
    }

    /// Get the node's identifier.
    public NodeId nodeId() {
        return nodeId;
    }

    /// Get the storage engine (for migration and anti-entropy operations).
    public StorageEngine storage() {
        return storage;
    }

    /// The replication this node currently places keys with (see [#resolveReplication]).
    public DHTConfig config() {
        return config.get();
    }

    /// Whether the committed replication factors have been applied ([#dhtNodeAwaitingReplication]).
    public boolean replicationResolved() {
        return replicationResolved.get();
    }

    /// Apply the cluster's committed replication (#1777 track 1) — the first time, and on every later change.
    /// Like a ring change, a replication change moves replica sets: every partition this node GAINS by it starts
    /// catching up, with the previous replica set recorded as its sources, and a pending partition it loses is
    /// forgotten. So a raised replication factor is re-placed through the same catch-up gate as a join, and a read
    /// in the window refuses rather than answers "absent". FULL replication has no placement to diff.
    @Contract
    public void resolveReplication(DHTConfig resolved) {
        synchronized (placementLock) {
            if (resolved.isFullReplication() || config.get().isFullReplication()) {
                config.set(resolved);
            } else {
                var before = replicaSets();

                config.set(resolved);
                var after = replicaSets();

                markGained(before, after);
                forgetLost(after);
                recordPlacementChange(before, after);
            }

            replicationResolved.set(true);
        }
    }

    /// Get the consistent hash ring.
    public ConsistentHashRing<NodeId> ring() {
        return ring;
    }

    /// Get the HLC clock.
    public HlcClock hlcClock() {
        return hlcClock;
    }

    /// Apply a ring change and mark catching up every partition this node GAINED by it (#1777 track 2),
    /// recording the partition's previous replica set as catch-up sources: those holders may still be
    /// ring members holding the data. Every ring mutation that can make this node a replica goes through
    /// here; [ConsistentHashRing] itself stays ownership-agnostic. FULL replication has no catch-up: every
    /// node is a replica of everything, and anti-entropy does not run in that mode.
    @Contract
    public void changeRing(Consumer<ConsistentHashRing<NodeId>> change) {
        synchronized (placementLock) {
            if (config.get().isFullReplication()) {
                change.accept(ring);

                return;
            }

            var before = replicaSets();

            change.accept(ring);
            var after = replicaSets();

            markGained(before, after);
            forgetLost(after);
            recordPlacementChange(before, after);
        }
    }

    /// Mark every partition this node currently owns catching up — the boot state of a node whose store
    /// starts empty (#1777 track 2). A restarted node therefore never answers "absent" for data its
    /// co-replicas hold; at genesis, or after a whole-cluster cold restart, every replica is empty and
    /// anti-entropy's anchorless rule makes them serving after one round.
    ///
    /// At boot there is no ring change to diff, so the previous holders are not known exactly. They are
    /// bounded instead, and walked LIVE ([#previousHolders]) rather than recorded here: the ring a node
    /// boots with is its statically configured cores, while the holders it must find are members of the
    /// ring the cluster actually has, which this node only learns as `NodeJoined` decisions arrive.
    @Contract
    public void beginCatchUp() {
        if (config.get().isFullReplication()) {
            return;
        }

        var replicationFactor = config.get().effectiveReplicationFactor(ring.nodeCount());

        bootMembers.set(Set.copyOf(ring.nodes()));
        IntStream.range(0, Partition.MAX_PARTITIONS)
                 .mapToObj(Partition::at)
                 .filter(partition -> ring.nodesFor(partition, replicationFactor)
                                          .contains(nodeId))
                 .forEach(catchUp::markCatchingUpSinceBoot);
    }

    /// Whether this node's answers for `partition` are authoritative. Before the committed replication is known no
    /// answer is: the node cannot tell which partitions it replicates, and an empty store is no evidence of absence.
    public Readiness readiness(Partition partition) {
        return replicationResolved.get()
               ? catchUp.readiness(partition)
               : Readiness.CATCHING_UP;
    }

    /// Whether this node's answers for the partition holding `key` are authoritative.
    public Readiness readinessFor(byte[] key) {
        return readiness(ring.partitionFor(key));
    }

    List<Partition> pendingPartitions() {
        return catchUp.pendingPartitions();
    }

    /// The current pending spell of `partition` (0 when serving): a round completes only the spell it began in.
    long catchUpGeneration(Partition partition) {
        return catchUp.generation(partition);
    }

    /// The previous holders a ring change recorded exactly — without the boot walk.
    Set<NodeId> recordedPreviousHolders(Partition partition) {
        return catchUp.previousHolders(partition);
    }

    /// The catch-up sources beyond the current co-replicas: the previous replica set a ring change recorded
    /// and, for a partition pending since boot, the first RF + J nodes of the partition's walk on the CURRENT
    /// ring, where J counts the members that joined since boot (#1777 M2). Each node inserted into the ring
    /// displaces an existing holder by at most one walk position, and a removal only moves holders earlier,
    /// so every surviving old holder lies within RF + J. A fixed 2·RF walk missed every old holder in
    /// 0.3–3.2% of partitions at 5–8 joins (v1820's sim); RF + J missed none, removals included. Walking the
    /// current ring (not the boot-time one) finds holders that are cores this node's configuration does not
    /// list.
    ///
    /// The boot ring is the STATIC configured-core list, so a scale-up core that joined before this node
    /// booted is counted as a join too. That overcounts J, which only lengthens the walk: safe for reach,
    /// at the cost of asking a few more sources. Walk nodes are sources, never anchors: an empty non-owner on
    /// the walk answers SERVING, so it may not authorize a timed-out decision (see [#recordedPreviousHolders]).
    ///
    /// The same static list can name cores that left before this node booted (#1777 Q1). This node's ring
    /// keeps such a phantom until the membership prunes it (#1830), and J does not count it, so each phantom
    /// would take one walk slot from a real holder. The walk therefore adds U: the ring members this node has
    /// not yet heard from over the DHT since boot. A phantom can never be heard from, so U counts every
    /// phantom, and each one displaces a holder by at most one position, the same argument as for joins.
    /// U also counts real members not heard from yet (every one, before the first round's answers) and a
    /// joiner already in J; both only lengthen the walk. The source is DHT contact rather than SWIM because
    /// the DHT's liveness view (`DHTNetwork.livePeers`) reports a seeded phantom as a live member.
    Set<NodeId> previousHolders(Partition partition) {
        var recorded = catchUp.previousHolders(partition);

        if (!catchUp.pendingSinceBoot(partition)) {
            return recorded;
        }

        var holders = new HashSet<>(recorded);

        holders.addAll(ring.nodesFor(partition, bootWalkLength()));

        return Set.copyOf(holders);
    }

    private int bootWalkLength() {
        var boot = bootMembers.get();
        var members = ring.nodes();
        var joinedSinceBoot = (int) members.stream().filter(member -> !boot.contains(member)).count();
        var unconfirmed = (int) members.stream().filter(this::unconfirmed).count();

        return config.get()
                     .effectiveReplicationFactor(ring.nodeCount()) + joinedSinceBoot + unconfirmed;
    }

    private boolean unconfirmed(NodeId member) {
        return ! member.equals(nodeId) && !heardFrom.contains(member);
    }

    /// Record that `peer` has reached this node over the DHT since boot (#1777 Q1).
    @Contract
    void noteHeardFrom(NodeId peer) {
        heardFrom.add(peer);
    }

    @Contract
    void markServing(Partition partition) {
        catchUp.markServing(partition);
    }

    int noteCatchUpRound(Partition partition) {
        return catchUp.noteRound(partition);
    }

    /// Partitions still catching up after [DHTAntiEntropy#STUCK_AFTER_ROUNDS] catch-up rounds (#1777) — a
    /// gauge for a partition that cannot complete, e.g. because every source that may hold it is silent.
    public int stuckCatchUpPartitions() {
        return catchUp.stuck(DHTAntiEntropy.STUCK_AFTER_ROUNDS);
    }

    private List<List<NodeId>> replicaSets() {
        var replicationFactor = config.get().effectiveReplicationFactor(ring.nodeCount());

        return IntStream.range(0, Partition.MAX_PARTITIONS)
                        .mapToObj(index -> ring.nodesFor(Partition.at(index),
                                                         replicationFactor))
                        .toList();
    }

    private void markGained(List<List<NodeId>> before, List<List<NodeId>> after) {
        IntStream.range(0, Partition.MAX_PARTITIONS)
                 .filter(index -> gained(before.get(index),
                                         after.get(index)))
                 .forEach(index -> catchUp.markCatchingUp(Partition.at(index),
                                                          before.get(index)));
    }

    /// A pending partition this node no longer owns leaves the set: it was never authoritative for it, and
    /// leaving it pending would answer CATCHING_UP for it forever, since rounds run only for owned ones.
    private void forgetLost(List<List<NodeId>> after) {
        catchUp.pendingPartitions()
               .stream()
               .filter(partition -> !after.get(partition.value())
                                          .contains(nodeId))
               .forEach(catchUp::markServing);
    }

    private boolean gained(List<NodeId> before, List<NodeId> after) {
        return after.contains(nodeId) && !before.contains(nodeId);
    }

    /// Record, per partition, a stray copy this node now holds (it stopped replicating the partition) and a holder
    /// that left the replica set — the two facts the tombstone horizon is ordered against (#1777 track 3).
    private void recordPlacementChange(List<List<NodeId>> before, List<List<NodeId>> after) {
        var now = nowMillis();

        IntStream.range(0, Partition.MAX_PARTITIONS)
                 .forEach(index -> recordPlacementChange(index, before.get(index), after.get(index), now));
    }

    private void recordPlacementChange(int index, List<NodeId> before, List<NodeId> after, long now) {
        if (after.contains(nodeId)) {
            lostAt.remove(index);
        } else if (before.contains(nodeId)) {
            lostAt.put(index, now);
        }

        if (!after.containsAll(before)) {
            holderLeftAt.put(index, now);
        }
    }

    /// Get a value from local storage.
    public Promise<Option<byte[]>> getLocal(byte[] key) {
        return storage.get(key);
    }

    /// Put a value to local storage.
    public Promise<Unit> putLocal(byte[] key, byte[] value) {
        return storage.put(key, value);
    }

    /// Put a value to local storage with version tracking at the unfenced epoch floor.
    public Promise<Boolean> putLocalVersioned(byte[] key, byte[] value, long version) {
        return storage.putVersioned(key, value, version);
    }

    /// Put a value to local storage with version tracking and the writer's owner epoch as the
    /// fencing token (#345 piece 1c).
    public Promise<Boolean> putLocalVersioned(byte[] key,
                                              byte[] value,
                                              long version,
                                              long epochIncarnation,
                                              long epochTerm,
                                              long epochCounter) {
        return storage.putVersioned(key, value, version, epochIncarnation, epochTerm, epochCounter);
    }

    /// Remove a value from local storage.
    public Promise<Boolean> removeLocal(byte[] key) {
        return storage.remove(key);
    }

    /// Check if key exists in local storage.
    public Promise<Boolean> existsLocal(byte[] key) {
        return storage.exists(key);
    }

    /// Get the partition for a key.
    public Partition partitionFor(byte[] key) {
        return ring.partitionFor(key);
    }

    /// Check if this node is responsible for a key (as primary or replica).
    public boolean isResponsibleFor(byte[] key) {
        return ring.nodesFor(key,
                             config.get().replicationFactor())
                   .contains(nodeId);
    }

    /// Check if this node is the primary for a key.
    public boolean isPrimaryFor(byte[] key) {
        return ring.primaryFor(key)
                   .map(nodeId::equals)
                   .or(false);
    }

    /// Get the local storage size.
    public long localSize() {
        return storage.size();
    }

    /// Clear local storage.
    public Promise<Unit> clearLocal() {
        return storage.clear();
    }

    /// Shutdown the node and release resources.
    public Promise<Unit> shutdown() {
        return storage.shutdown();
    }

    /// Handle a get request (for message routing integration). The reply carries this node's
    /// [Readiness] for the key's partition, so the reader can discount an absent answer from a replica
    /// that is still catching up (#1777 track 2).
    ///
    /// The reply carries the stamp of the entry held — a value or a tombstone (#1777 track 3) — so the reader keeps
    /// the newest answer. A store that cannot be read answers "no entry", which votes only from a SERVING replica,
    /// exactly as before.
    @Contract
    public void handleGetRequest(DHTMessage.GetRequest request, Consumer<DHTMessage.GetResponse> responseHandler) {
        var readiness = readinessFor(request.key());

        storage.getEntry(request.key())
               .onSuccess(entry -> responseHandler.accept(getResponse(request.requestId(), entry, readiness)))
               .onFailure(_ -> responseHandler.accept(new DHTMessage.GetResponse(request.requestId(),
                                                                                 nodeId,
                                                                                 Option.none(),
                                                                                 readiness)));
    }

    private DHTMessage.GetResponse getResponse(String requestId, Option<DHTMessage.KeyValue> entry, Readiness readiness) {
        return entry.map(kv -> new DHTMessage.GetResponse(requestId,
                                                          nodeId,
                                                          kv.tombstone()
                                                          ? Option.none()
                                                          : Option.some(kv.value()),
                                                          readiness,
                                                          kv.tombstone(),
                                                          kv.version(),
                                                          kv.epochIncarnation(),
                                                          kv.epochTerm(),
                                                          kv.epochCounter()))
                    .or(() -> new DHTMessage.GetResponse(requestId, nodeId, Option.none(), readiness));
    }

    /// Handle a put request (for message routing integration).
    ///
    /// Threads the put's owner epoch (`epochIncarnation`/`epochTerm`/`epochCounter`) into the versioned store so THIS
    /// replica enforces the fence against its own per-partition high-water (#345 piece 1c). A
    /// stale-epoch reject surfaces as a failed `putVersioned` promise → `PutResponse(success=false,
    /// superseded=false)`, exactly the deposed-owner rejection the client re-resolves against.
    @Contract
    public void handlePutRequest(DHTMessage.PutRequest request, Consumer<DHTMessage.PutResponse> responseHandler) {
        storage.putVersioned(request.key(),
                             request.value(),
                             request.version(),
                             request.epochIncarnation(),
                             request.epochTerm(),
                             request.epochCounter())
               .onSuccess(written -> responseHandler.accept(new DHTMessage.PutResponse(request.requestId(),
                                                                                       nodeId,
                                                                                       true,
                                                                                       !written,
                                                                                       false)))
               .onFailure(cause -> responseHandler.accept(new DHTMessage.PutResponse(request.requestId(),
                                                                                     nodeId,
                                                                                     false,
                                                                                     false,
                                                                                     cause instanceof DHTError.StaleEpochWrite)));
    }

    /// Handle a remove request: store a tombstone stamped with the remover's version and owner epoch, fenced like a
    /// put (#1777 track 3). A fence refusal is reported as such, so a remove that loses its quorum to fences is
    /// indeterminate, as a put is.
    @Contract
    public void handleRemoveRequest(DHTMessage.RemoveRequest request,
                                    Consumer<DHTMessage.RemoveResponse> responseHandler) {
        storage.removeVersioned(request.key(),
                                request.version(),
                                request.epochIncarnation(),
                                request.epochTerm(),
                                request.epochCounter())
               .onSuccess(found -> responseHandler.accept(new DHTMessage.RemoveResponse(request.requestId(),
                                                                                        nodeId,
                                                                                        found)))
               .onFailure(cause -> responseHandler.accept(new DHTMessage.RemoveResponse(request.requestId(),
                                                                                    nodeId,
                                                                                    false,
                                                                                    cause instanceof DHTError.StaleEpochWrite)));
    }

    /// Handle an exists request (for message routing integration), carrying this node's [Readiness] for
    /// the key's partition like [#handleGetRequest].
    @Contract
    public void handleExistsRequest(DHTMessage.ExistsRequest request,
                                    Consumer<DHTMessage.ExistsResponse> responseHandler) {
        var readiness = readinessFor(request.key());

        storage.getEntry(request.key())
               .onSuccess(entry -> responseHandler.accept(existsResponse(request.requestId(), entry, readiness)))
               .onFailure(_ -> responseHandler.accept(new DHTMessage.ExistsResponse(request.requestId(),
                                                                                    nodeId,
                                                                                    false,
                                                                                    readiness)));
    }

    private DHTMessage.ExistsResponse existsResponse(String requestId,
                                                     Option<DHTMessage.KeyValue> entry,
                                                     Readiness readiness) {
        return entry.map(kv -> new DHTMessage.ExistsResponse(requestId,
                                                             nodeId,
                                                             !kv.tombstone(),
                                                             readiness,
                                                             kv.tombstone(),
                                                             kv.version(),
                                                             kv.epochIncarnation(),
                                                             kv.epochTerm(),
                                                             kv.epochCounter()))
                    .or(() -> new DHTMessage.ExistsResponse(requestId, nodeId, false, readiness));
    }

    /// Handle a digest request: compute digest for the requested partition range and respond, with this
    /// node's [Readiness] for the partition so a catching-up requester can tell an authoritative source
    /// from another catching-up replica (#1777 track 2). A digest that could not be computed is reported
    /// [Readiness#UNKNOWN] — never as an authoritative empty partition.
    @Contract
    public void handleDigestRequest(DHTMessage.DigestRequest request,
                                    Consumer<DHTMessage.DigestResponse> responseHandler) {
        noteHeardFrom(request.sender());
        var partition = Partition.at(request.partitionStart());
        var readiness = readiness(partition);

        storage.entriesForPartition(ring, partition)
               .onSuccess(entries -> responseHandler.accept(new DHTMessage.DigestResponse(request.requestId(),
                                                                                          nodeId,
                                                                                          digestOf(entries),
                                                                                          readiness)))
               .onFailure(_ -> responseHandler.accept(new DHTMessage.DigestResponse(request.requestId(),
                                                                                    nodeId,
                                                                                    new byte[0],
                                                                                    Readiness.UNKNOWN)));
    }

    /// Handle a migration data request: return the partition's entries, but ONLY to a node that this
    /// node — the HOLDER of the data — considers an owner of that partition (issue #420).
    ///
    /// Ownership is settled here rather than by the requester because on a PARTIAL ring the requester
    /// cannot refuse anything. `AetherNode` creates a joiner's ring empty and
    /// `MembershipDeltaProjector.emitJoin` fills it one `NodeJoined` at a time, so during the join
    /// burst the joiner sees a ring on which it is a replica of every partition; asked against its own
    /// view it would pull the whole keyspace, and no path in this module ever releases an unowned copy.
    /// The holder's ring is the view that HAS the data, so it is the view that answers the question.
    ///
    /// A holder whose own ring is stale refuses a legitimate owner: the requester then keeps nothing
    /// and the next anti-entropy round repeats the exchange, so a wrong refusal costs a delay, never a
    /// copy. Refusing to hand out is the only safe correction available — DELETING an unowned copy
    /// against a ring that may be partial could drop the last one. The refusal is EXPLICIT
    /// (`refused=true`, #1777), as is a holder that could not read its own store: an empty entry list
    /// must only ever mean "the holder has nothing here", or a catching-up requester would take a
    /// refusal for a completed catch-up.
    @Contract
    public void handleMigrationDataRequest(DHTMessage.MigrationDataRequest request,
                                           Consumer<DHTMessage.MigrationDataResponse> responseHandler) {
        var partition = Partition.at(request.partitionStart());

        if (!isReplicaOf(request.sender(), partition)) {
            // One per partition the requester over-asked for: DEBUG, because a join burst produces
            // hundreds of these by design and a louder level would be a log storm, not a signal.
            log.debug("Refusing migration of partition {} to {}: not a replica in this node's ring",
                      request.partitionStart(),
                      request.sender().id());
            responseHandler.accept(new DHTMessage.MigrationDataResponse(request.requestId(),
                                                                        nodeId,
                                                                        java.util.List.of(),
                                                                        false,
                                                                        true));

            return;
        }

        storage.entriesForPartition(ring, partition)
               .onSuccess(entries -> responseHandler.accept(new DHTMessage.MigrationDataResponse(request.requestId(),
                                                                                                 nodeId,
                                                                                                 entries,
                                                                                                 false,
                                                                                                 false)))
               .onFailure(_ -> responseHandler.accept(new DHTMessage.MigrationDataResponse(request.requestId(),
                                                                                           nodeId,
                                                                                           java.util.List.of(),
                                                                                           false,
                                                                                           true)));
    }

    /// Whether `candidate` is one of the partition's replicas in THIS node's ring — the same single
    /// placement function every other caller uses (issue #420).
    private boolean isReplicaOf(NodeId candidate, Partition partition) {
        return ring.nodesFor(partition,
                             config.get().effectiveReplicationFactor(ring.nodeCount()))
                   .contains(candidate);
    }

    /// Apply migration data by merging received entries into local storage as replica copies,
    /// preserving each entry's owner epoch (#345 piece 1c) so the fencing token survives transfer. A
    /// copy bypasses the owner-epoch high-water but keeps the per-key ordering (issue #1818, see
    /// [StorageEngine#putReplica]).
    ///
    /// @return `true` when every entry was stored or was already superseded by a newer stored entry;
    ///         `false` when any entry failed to store — the outcome an honest ack reports (issue #1818).
    public Promise<Boolean> applyMigrationData(java.util.List<DHTMessage.KeyValue> entries) {
        return Promise.allOf(entries.stream().map(this::applyReplica).toList()).map(outcomes -> outcomes.stream()
                                                                                                        .allMatch(Result::isSuccess));
    }

    /// A tombstone copy is applied like a value copy (#1777 track 3), except that an EXPIRED one never creates an
    /// entry: it still supersedes a stale value it meets, but its holders may already have collected it, and
    /// re-creating it here would bounce it between replicas forever.
    private Promise<Boolean> applyReplica(DHTMessage.KeyValue kv) {
        var belowHighWater = storage.belowHighWater(kv.key(), kv.epochIncarnation(), kv.epochTerm(), kv.epochCounter());

        return storage.putReplica(kv, !expiredTombstone(kv))
                      .onSuccess(written -> noteBelowHighWater(kv, written && belowHighWater));
    }

    /// Whether `kv` is a tombstone older than the tombstone retention, by its own stamp's HLC physical time.
    public boolean expiredTombstone(DHTMessage.KeyValue kv) {
        return kv.tombstone() && HlcTimestamp.physicalMillis(kv.version()) <= expiryCutoffMillis(nowMillis());
    }

    /// The HLC physical time at or before which a tombstone stamped is expired as of `atMillis`.
    long expiryCutoffMillis(long atMillis) {
        return atMillis - tombstoneRetention.get()
                                            .millis();
    }

    /// The digest this node compares a partition by: every live entry and every tombstone not yet expired by its
    /// own stamp (#1777 track 3). Expiry is read from the stamp, not from whether this node has collected the
    /// tombstone, so a replica that has collected it and one that has not compute the same digest.
    public byte[] digestOf(List<DHTMessage.KeyValue> entries) {
        var cutoff = expiryCutoffMillis(nowMillis());

        return computeDigest(entries.stream()
                                    .filter(kv -> !kv.tombstone() || HlcTimestamp.physicalMillis(kv.version()) > cutoff)
                                    .toList());
    }

    /// The cluster's committed tombstone retention (#1777 track 3), applied live.
    @Contract
    public void resolveTombstoneRetention(TimeSpan retention) {
        tombstoneRetention.set(retention);
    }

    public TimeSpan tombstoneRetention() {
        return tombstoneRetention.get();
    }

    long nowMillis() {
        return wallClock.get()
                        .getAsLong();
    }

    /// Test seam: the wall clock tombstone ages and horizons are measured with.
    @Contract
    void useWallClock(LongSupplier clock) {
        wallClock.set(clock);
    }

    /// Partitions this node stopped replicating at or before `cutoffMillis` and still holds copies of.
    List<Partition> strayPartitionsSince(long cutoffMillis) {
        return lostAt.entrySet()
                     .stream()
                     .filter(e -> e.getValue() <= cutoffMillis)
                     .map(e -> Partition.at(e.getKey()))
                     .toList();
    }

    /// Drop a stray partition's copies, values and tombstones alike (#1777 track 3, the stray horizon).
    Promise<Integer> dropStray(Partition partition) {
        lostAt.remove(partition.value());

        return storage.dropPartition(ring, partition)
                      .onSuccess(dropped -> notePurgedStray(partition, dropped));
    }

    @Contract
    private void notePurgedStray(Partition partition, int dropped) {
        purgedStrayPartitions.incrementAndGet();
        log.info("Dropped {} stray entries of partition {}: this node stopped replicating it more than the stray "
                 + "horizon ago",
                 dropped,
                 partition.value());
    }

    /// Whether no holder has left `partition`'s replica set since `cutoffMillis` in this node's view.
    boolean holderSetStableSince(Partition partition, long cutoffMillis) {
        return Option.option(holderLeftAt.get(partition.value()))
                     .filter(leftAt -> leftAt > cutoffMillis)
                     .isEmpty();
    }

    @Contract
    void noteAgreement(Partition partition, long atMillis) {
        agreedAt.put(partition.value(), atMillis);
    }

    /// Collect `partition`'s tombstones stamped at or before `cutoffMillis` (#1777 track 3).
    Promise<Integer> collectTombstones(Partition partition, long cutoffMillis) {
        return storage.collectTombstones(ring, partition, cutoffMillis)
                      .onSuccess(collectedTombstones::addAndGet);
    }

    /// Tombstones held now (#1777 track 3, a gauge).
    public long tombstoneCount() {
        return storage.tombstoneCount();
    }

    /// Tombstones collected since start (#1777 track 3).
    public long collectedTombstoneCount() {
        return collectedTombstones.get();
    }

    /// Stray partitions dropped since start (#1777 track 3).
    public long purgedStrayPartitionCount() {
        return purgedStrayPartitions.get();
    }

    /// Partitions this node replicates that have not had a full agreement round — every co-replica SERVING with an
    /// equal digest — within `window` (#1777 track 3). A partition that cannot agree cannot collect its tombstones:
    /// a co-replica is silent, diverged or catching up.
    public int unagreedPartitions(TimeSpan window) {
        var cutoff = nowMillis() - window.millis();
        var replicationFactor = config.get()
                                      .effectiveReplicationFactor(ring.nodeCount());

        return (int) IntStream.range(0, Partition.MAX_PARTITIONS)
                              .filter(index -> ring.nodesFor(Partition.at(index), replicationFactor)
                                                   .contains(nodeId))
                              .filter(index -> Option.option(agreedAt.get(index))
                                                     .filter(at -> at > cutoff)
                                                     .isEmpty())
                              .count();
    }

    /// This node was REMOVED from the cluster while alive — a committed removal it did not ask for, e.g. after a
    /// pause or a partition (#1777 track 3, owner ruling 2026-10-03). Its store is not current with any tombstone
    /// issued since it was cut off, and a node that rejoins holding it could resurrect removed values, so the store
    /// is dropped: the node rejoins empty, exactly as a restarted one does, and catches up through the gate.
    @Contract
    public void discardStoreAfterSelfRemoval() {
        synchronized (placementLock) {
            var dropped = storage.size();
            var _ = storage.clear();

            lostAt.clear();
            catchUp.pendingPartitions()
                   .forEach(catchUp::markServing);
            log.warn("This node was removed from the DHT ring while running: dropped its {} stored entries; it "
                     + "rejoins empty and catches up",
                     dropped);
        }
    }

    /// Copies applied below this node's owner-epoch high-water since start (#1818, the owner's fence ruling):
    /// each is a write the fence would have refused as fresh. Most are pre-rewrite data being re-replicated,
    /// which is what the bypass exists for; a deposed owner's write spreading would also land here.
    public long belowHighWaterCopyCount() {
        return belowHighWaterCopies.get();
    }

    @Contract
    private void noteBelowHighWater(DHTMessage.KeyValue kv, boolean appliedBelowHighWater) {
        if (!appliedBelowHighWater) {
            return;
        }

        belowHighWaterCopies.incrementAndGet();
        log.info("Applied a copy below this node's owner-epoch high-water: partition {}, epoch {}:{}:{}",
                 ring.partitionFor(kv.key()).value(),
                 kv.epochIncarnation(),
                 kv.epochTerm(),
                 kv.epochCounter());
    }

    /// Compute a CRC32 digest over sorted key-value entries.
    static byte[] computeDigest(java.util.List<DHTMessage.KeyValue> entries) {
        var crc = new CRC32();

        entries.stream().sorted((a, b) -> Arrays.compare(a.key(), b.key())).forEach(kv -> updateCrc(crc, kv));

        return longToBytes(crc.getValue());
    }

    /// A kind byte separates a tombstone from a live entry with an empty value (#1777 track 3).
    private static void updateCrc(CRC32 crc, DHTMessage.KeyValue kv) {
        crc.update(kv.key());
        crc.update(kv.tombstone()
                   ? 1
                   : 0);
        crc.update(kv.value());
    }

    private static byte[] longToBytes(long value) {
        return new byte[]{(byte)(value >>> 56), (byte)(value >>> 48), (byte)(value >>> 40), (byte)(value >>> 32), (byte)(value >>> 24), (byte)(value >>> 16), (byte)(value >>> 8), (byte) value};
    }
}
