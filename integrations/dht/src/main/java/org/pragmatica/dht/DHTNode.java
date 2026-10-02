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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.zip.CRC32;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTMessage.Readiness;
import org.pragmatica.dht.storage.StorageEngine;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

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
    private final DHTConfig config;
    private final HlcClock hlcClock;
    private final CatchUpState catchUp = CatchUpState.catchUpState();
    /// The ring members when [#beginCatchUp] ran: the joins observed since boot are the current members
    /// outside this set, and they bound the boot walk ([#previousHolders]).
    private final AtomicReference<Set<NodeId>> bootMembers = new AtomicReference<>(Set.of());
    /// Ring members this node has heard from over the DHT since it booted: a digest request or answer from
    /// them. The rest are unconfirmed and lengthen the boot walk ([#previousHolders]).
    private final Set<NodeId> heardFrom = ConcurrentHashMap.newKeySet();

    private DHTNode(NodeId nodeId,
                    StorageEngine storage,
                    ConsistentHashRing<NodeId> ring,
                    DHTConfig config,
                    HlcClock hlcClock) {
        this.nodeId = nodeId;
        this.storage = storage;
        this.ring = ring;
        this.config = config;
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
        return new DHTNode(nodeId, storage, ring, config, hlcClock);
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

        return new DHTNode(nodeId, storage, ring, config, clock);
    }

    /// Get the node's identifier.
    public NodeId nodeId() {
        return nodeId;
    }

    /// Get the storage engine (for migration and anti-entropy operations).
    public StorageEngine storage() {
        return storage;
    }

    /// Get the configuration.
    public DHTConfig config() {
        return config;
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
        if (config.isFullReplication()) {
            change.accept(ring);

            return;
        }

        var before = replicaSets();

        change.accept(ring);
        var after = replicaSets();

        markGained(before, after);
        forgetLost(after);
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
        if (config.isFullReplication()) {
            return;
        }

        var replicationFactor = config.effectiveReplicationFactor(ring.nodeCount());

        bootMembers.set(Set.copyOf(ring.nodes()));
        IntStream.range(0, Partition.MAX_PARTITIONS)
                 .mapToObj(Partition::at)
                 .filter(partition -> ring.nodesFor(partition, replicationFactor)
                                          .contains(nodeId))
                 .forEach(catchUp::markCatchingUpSinceBoot);
    }

    /// Whether this node's answers for `partition` are authoritative.
    public Readiness readiness(Partition partition) {
        return catchUp.readiness(partition);
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

        return config.effectiveReplicationFactor(ring.nodeCount()) + joinedSinceBoot + unconfirmed;
    }

    private boolean unconfirmed(NodeId member) {
        return !member.equals(nodeId) && !heardFrom.contains(member);
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
        var replicationFactor = config.effectiveReplicationFactor(ring.nodeCount());

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
                             config.replicationFactor())
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
    @Contract
    public void handleGetRequest(DHTMessage.GetRequest request, Consumer<DHTMessage.GetResponse> responseHandler) {
        var readiness = readinessFor(request.key());

        storage.get(request.key())
               .onSuccess(value -> responseHandler.accept(new DHTMessage.GetResponse(request.requestId(),
                                                                                     nodeId,
                                                                                     value,
                                                                                     readiness)))
               .onFailure(_ -> responseHandler.accept(new DHTMessage.GetResponse(request.requestId(),
                                                                                 nodeId,
                                                                                 Option.none(),
                                                                                 readiness)));
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
                                                                                       !written)))
               .onFailure(_ -> responseHandler.accept(new DHTMessage.PutResponse(request.requestId(),
                                                                                 nodeId,
                                                                                 false,
                                                                                 false)));
    }

    /// Handle a remove request (for message routing integration).
    @Contract
    public void handleRemoveRequest(DHTMessage.RemoveRequest request,
                                    Consumer<DHTMessage.RemoveResponse> responseHandler) {
        storage.remove(request.key())
               .onSuccess(found -> responseHandler.accept(new DHTMessage.RemoveResponse(request.requestId(),
                                                                                        nodeId,
                                                                                        found)))
               .onFailure(_ -> responseHandler.accept(new DHTMessage.RemoveResponse(request.requestId(),
                                                                                    nodeId,
                                                                                    false)));
    }

    /// Handle an exists request (for message routing integration), carrying this node's [Readiness] for
    /// the key's partition like [#handleGetRequest].
    @Contract
    public void handleExistsRequest(DHTMessage.ExistsRequest request,
                                    Consumer<DHTMessage.ExistsResponse> responseHandler) {
        var readiness = readinessFor(request.key());

        storage.exists(request.key())
               .onSuccess(exists -> responseHandler.accept(new DHTMessage.ExistsResponse(request.requestId(),
                                                                                         nodeId,
                                                                                         exists,
                                                                                         readiness)))
               .onFailure(_ -> responseHandler.accept(new DHTMessage.ExistsResponse(request.requestId(),
                                                                                    nodeId,
                                                                                    false,
                                                                                    readiness)));
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
                                                                                          computeDigest(entries),
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
                             config.effectiveReplicationFactor(ring.nodeCount()))
                   .contains(candidate);
    }

    /// Apply migration data by merging received entries into local storage using versioned puts,
    /// preserving each entry's owner epoch (#345 piece 1c) so the fencing token survives transfer.
    @Contract
    public void applyMigrationData(java.util.List<DHTMessage.KeyValue> entries) {
        entries.forEach(kv -> storage.putVersioned(kv.key(),
                                                   kv.value(),
                                                   kv.version(),
                                                   kv.epochIncarnation(),
                                                   kv.epochTerm(),
                                                   kv.epochCounter()));
    }

    /// Compute a CRC32 digest over sorted key-value entries.
    static byte[] computeDigest(java.util.List<DHTMessage.KeyValue> entries) {
        var crc = new CRC32();

        entries.stream().sorted((a, b) -> Arrays.compare(a.key(), b.key())).forEach(kv -> updateCrc(crc, kv));

        return longToBytes(crc.getValue());
    }

    private static void updateCrc(CRC32 crc, DHTMessage.KeyValue kv) {
        crc.update(kv.key());
        crc.update(kv.value());
    }

    private static byte[] longToBytes(long value) {
        return new byte[]{(byte)(value >>> 56), (byte)(value >>> 48), (byte)(value >>> 40), (byte)(value >>> 32), (byte)(value >>> 24), (byte)(value >>> 16), (byte)(value >>> 8), (byte) value};
    }
}
