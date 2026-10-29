// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaEntries;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaHeartbeat;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaRefused;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotChunk;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotRequest;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSubscribe;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.SubscribeMode;
import org.pragmatica.cluster.state.kvstore.CommitRing.RefusalReason;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.ReplicaKeyFilter;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cluster.replica.ClientFixtures.incarnation;
import static org.pragmatica.cluster.replica.FeedFixtures.Key;
import static org.pragmatica.cluster.replica.FeedFixtures.put;

/// The relay (W-OBS W4, spec 2.3, 2.9, 3.3): two cores, workers that each hold a replica, follow a source and can serve as the community's governor,
/// over a manual network and clock. A worker follows the announced governor when it may, a core otherwise; a governor follows a core and serves its
/// members once its [ReplicaServeGate] opens. Each test names the NT-O scenario it carries and the mutation that turns it red.
class ReplicaFeedClientRelayTest {
    private static final long MS = 1_000_000L;

    private record Sent(long atMillis, NodeId from, NodeId to, ProtocolMessage message) {}

    /// A worker: a store, the real client over it and the real server over it (it serves only through its gate).
    private static final class Worker {
        final NodeId id;
        final KVStore<StructuredKey, Object> store;
        final AtomicBoolean up = new AtomicBoolean(true);
        final AtomicBoolean governor = new AtomicBoolean();
        final AtomicBoolean holdInbound = new AtomicBoolean();
        final List<ProtocolMessage> held = new ArrayList<>();
        final ReplicaApplier<StructuredKey, Object> applier;
        final ReplicaReadiness readiness;
        final ReplicaFeedClient<StructuredKey, Object> client;
        final ReplicaServeGate gate;
        final AtomicInteger installs = new AtomicInteger();
        ReplicaFeedServer<StructuredKey, Object> server;

        Worker(Mesh mesh, String name, ReplicaFeedLimits serverLimits) {
            id = NodeId.nodeId(name).unwrap();
            store = ClientFixtures.store();
            applier = ReplicaApplier.replicaApplier(store, ReplicaClientLimits.DEFAULT.bufferBytes());
            readiness = ReplicaReadiness.replicaReadiness(mesh.nanos::get, applier::position, () -> true, () -> true, _ -> {}, ReplicaClientLimits.DEFAULT);
            client = ReplicaFeedClient.replicaFeedClient(id,
                                                         applier,
                                                         readiness,
                                                         (target, message) -> mesh.deliver(id, target, message),
                                                         () -> mesh.coreIds,
                                                         mesh.queue::add,
                                                         mesh.nanos::get,
                                                         ReplicaClientLimits.DEFAULT,
                                                         installs::incrementAndGet,
                                                         new ReplicaFeedClient.GovernorArm(governor::get, () -> mesh.announced.get()));
            gate = ReplicaServeGate.replicaServeGate(governor::get, client::followsCore, readiness::servable);
            server = ReplicaFeedServer.replicaFeedServer(id,
                                                         store,
                                                         ReplicaKeyFilter.includeAll(),
                                                         (peer, message) -> mesh.deliver(id, peer, message),
                                                         gate::mayServeMembers,
                                                         peer -> mesh.community.contains(peer),
                                                         peer -> mesh.workers.get(peer).up.get(),
                                                         Runnable::run,
                                                         mesh.nanos::get,
                                                         serverLimits);
        }

        String valueOf(String key) {
            return store.get(new Key(key)).map(String::valueOf).or("<absent>");
        }
    }

    private static final class Core {
        final NodeId id;
        final KVStore<StructuredKey, Object> store = ClientFixtures.store();
        final AtomicBoolean up = new AtomicBoolean(true);
        ReplicaFeedServer<StructuredKey, Object> server;

        Core(Mesh mesh, String name, ReplicaFeedLimits limits) {
            id = NodeId.nodeId(name).unwrap();
            server = ReplicaFeedServer.replicaFeedServer(id,
                                                         store,
                                                         ReplicaKeyFilter.includeAll(),
                                                         (peer, message) -> mesh.deliver(id, peer, message),
                                                         () -> true,
                                                         _ -> true,
                                                         _ -> true,
                                                         Runnable::run,
                                                         mesh.nanos::get,
                                                         limits);
        }
    }

    private static final class Mesh {
        final AtomicLong nanos = new AtomicLong(1_000_000_000L);
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final List<Core> cores = new ArrayList<>();
        final List<NodeId> coreIds = new ArrayList<>();
        final Map<NodeId, Worker> workers = new LinkedHashMap<>();
        final Set<NodeId> community = new HashSet<>();
        final AtomicReference<Option<NodeId>> announced = new AtomicReference<>(Option.none());
        final List<Sent> log = new ArrayList<>();
        final ReplicaFeedLimits workerLimits;
        long nextSlot;

        Mesh(ReplicaFeedLimits coreLimits, ReplicaFeedLimits workerLimits) {
            this.workerLimits = workerLimits;
            for (var name : List.of("core-1", "core-2")) {
                var core = new Core(this, name, coreLimits);

                cores.add(core);
                coreIds.add(core.id);
            }
        }

        Mesh() {
            this(ReplicaFeedLimits.DEFAULT, ReplicaFeedLimits.DEFAULT);
        }

        Worker worker(String name) {
            var worker = new Worker(this, name, workerLimits);

            workers.put(worker.id, worker);
            community.add(worker.id);

            return worker;
        }

        Core core(int index) {
            return cores.get(index);
        }

        void deliver(NodeId from, NodeId to, ProtocolMessage message) {
            log.add(new Sent((nanos.get() - 1_000_000_000L) / MS, from, to, message));
            if (!isUp(from) || !isUp(to)) {
                return;
            }

            var target = workers.get(to);

            if (target != null && target.holdInbound.get() && !(message instanceof ReplicaSubscribe) && !(message instanceof ReplicaSnapshotRequest)) {
                target.held.add(message);

                return;
            }

            dispatch(to, message);
        }

        void dispatch(NodeId to, ProtocolMessage message) {
            var worker = workers.get(to);
            var server = worker != null
                         ? worker.server
                         : cores.stream().filter(core -> core.id.equals(to)).findFirst().orElseThrow().server;

            switch (message) {
                case ReplicaSubscribe subscribe -> server.onSubscribe(subscribe);
                case ReplicaSnapshotRequest request -> server.onSnapshotRequest(request);
                case ReplicaEntries entries -> worker.client.onEntries(entries);
                case ReplicaHeartbeat beat -> worker.client.onHeartbeat(beat);
                case ReplicaRefused refused -> worker.client.onRefused(refused);
                case ReplicaSnapshotChunk chunk -> worker.client.onSnapshotChunk(chunk);
                default -> throw new IllegalStateException("unexpected " + message);
            }
        }

        private boolean isUp(NodeId id) {
            var worker = workers.get(id);

            return worker != null
                   ? worker.up.get()
                   : cores.stream().anyMatch(core -> core.id.equals(id) && core.up.get());
        }

        /// Hands the messages a slow worker held back to its client, in order.
        void release(Worker worker) {
            worker.holdInbound.set(false);
            List.copyOf(worker.held).forEach(message -> dispatch(worker.id, message));
            worker.held.clear();
            pump();
        }

        void pump() {
            while (!queue.isEmpty()) {
                queue.poll().run();
            }
        }

        /// One round: every serving node drains and flushes one heartbeat, then every client ticks, then the answers are drained.
        void step() {
            serveRound();
            pump();
            workers.values().stream().filter(worker -> worker.up.get()).forEach(worker -> worker.client.tick());
            pump();
            serveRound();
            pump();
        }

        private void serveRound() {
            cores.stream().filter(core -> core.up.get()).forEach(core -> flush(core.server));
            workers.values().stream().filter(worker -> worker.up.get()).forEach(worker -> flush(worker.server));
        }

        private static void flush(ReplicaFeedServer<StructuredKey, Object> server) {
            server.tick();
            server.heartbeat();
            server.tick();
        }

        void advance(long millis) {
            nanos.addAndGet(millis * MS);
        }

        /// `rounds` rounds of 300 ms.
        void run(int rounds) {
            for (var i = 0; i < rounds; i++) {
                advance(300);
                step();
            }
        }

        /// Commits one batch at the next slot on every core, the way the engine does (one shared batch, so every core records the same batch id).
        @SafeVarargs
        final void commit(KVCommand<StructuredKey>... commands) {
            var slot = nextSlot++;
            var batch = cores.get(0).store.createBatch(List.of(commands));

            cores.forEach(core -> {
                core.store.processCommitted(batch, slot + 1);
                core.store.onPhaseCompleted(slot + 1);
            });
        }

        void genesis() {
            commit(incarnation(1, "A"));
        }

        List<Sent> sentBy(Worker worker, Class<? extends ProtocolMessage> type) {
            return log.stream().filter(sent -> sent.from().equals(worker.id) && type.isInstance(sent.message())).toList();
        }

        List<NodeId> subscribeTargets(Worker worker) {
            return sentBy(worker, ReplicaSubscribe.class).stream().map(Sent::to).toList();
        }

        Option<ReplicaSubscribe> lastSubscribe(Worker worker) {
            var all = sentBy(worker, ReplicaSubscribe.class);

            return all.isEmpty()
                   ? Option.none()
                   : Option.some((ReplicaSubscribe) all.getLast().message());
        }

        boolean isCore(NodeId id) {
            return coreIds.contains(id);
        }

        /// A worker joined and caught up from the cores, became the governor and serves; only then is it announced, after its ring recorded its
        /// first effect batch (its ring starts empty at its snapshot install and can verify no anchor below its floor; in a live cluster the governor's
        /// own announcement is that batch).
        Worker governorReady(String name) {
            var gov = governorServing(name);

            commit(put("announcement", "1"));
            run(3);
            announced.set(Option.some(gov.id));
            run(2);

            return gov;
        }

        /// The same, announced at once: its ring has recorded no effect batch since its snapshot install (a quiet cluster).
        Worker governorReadyQuiet(String name) {
            var gov = governorServing(name);

            announced.set(Option.some(gov.id));
            run(2);

            return gov;
        }

        private Worker governorServing(String name) {
            var gov = worker(name);

            run(4);
            gov.governor.set(true);
            run(4);
            assertThat(gov.gate.mayServeMembers()).as("precondition: the governor serves").isTrue();

            return gov;
        }
    }

    private static Mesh last;

    @org.junit.jupiter.api.AfterEach
    void dumpWhenAsked() {
        if (java.nio.file.Files.exists(java.nio.file.Path.of("/tmp/kw4-dump")) && last != null) {
            last.log.stream()
                    .filter(sent -> !(sent.message() instanceof ReplicaHeartbeat))
                    .forEach(sent -> System.out.println("KW4 " + sent.atMillis() + " " + sent.from().id() + "->" + sent.to().id() + " " + describe(sent.message())));
        }
    }

    private static String describe(ProtocolMessage message) {
        return switch (message) {
            case ReplicaSubscribe subscribe -> "SUB " + subscribe.mode() + " applied=" + subscribe.applied() + " anchor=" + subscribe.anchor().size();
            case ReplicaRefused refused -> "REFUSED " + refused.reason();
            case ReplicaEntries entries -> "ENTRIES subSeq=" + entries.subSeq() + " n=" + entries.entries().size() + " latest=" + entries.latest();
            case ReplicaSnapshotRequest request -> "SNAPREQ off=" + request.offset();
            case ReplicaSnapshotChunk chunk -> "CHUNK off=" + chunk.offset() + "/" + chunk.total();
            default -> message.toString();
        };
    }

    private static Mesh meshWithData() {
        var mesh = new Mesh();

        last = mesh;

        mesh.genesis();
        mesh.commit(put("a", "1"));
        mesh.commit(put("b", "2"));

        return mesh;
    }

    /// The governor-first rule (the second test of the W4 gate): once an announced governor serves, a joining member subscribes to it and to no core.
    /// Mutation: drop the governor argument at `openIfDue`'s `select` call.
    @Test
    void aMemberWithAnAnnouncedServingGovernor_neverSubscribesToACore() {
        var mesh = meshWithData();
        var gov = mesh.governorReady("w-gov");
        var member = mesh.worker("w-1");

        mesh.run(8);

        assertThat(mesh.subscribeTargets(member)).containsExactly(gov.id);
        assertThat(member.valueOf("b")).isEqualTo("2");
        assertThat(member.readiness.ready()).isTrue();
    }

    /// A member that joined from a core moves to the governor once it is announced and serving, from its anchor, without a second snapshot.
    /// Mutation: drop `rebalance` from `tickNow`.
    @Test
    void aMemberOnACore_movesToTheGovernorWhenItServes_withoutASecondSnapshot() {
        var mesh = meshWithData();
        var member = mesh.worker("w-1");

        mesh.run(4);
        assertThat(mesh.isCore(mesh.subscribeTargets(member).getFirst())).isTrue();
        var gov = mesh.governorReady("w-gov");

        mesh.run(4);

        assertThat(mesh.subscribeTargets(member).getLast()).isEqualTo(gov.id);
        assertThat(mesh.lastSubscribe(member).unwrap().mode()).isEqualTo(SubscribeMode.FROM_ANCHOR);
        assertThat(member.installs.get()).as("no second snapshot").isEqualTo(1);
        assertThat(member.readiness.ready()).isTrue();
    }

    /// NT-O21, governor dies mid-relay: the members' last counted heartbeat keeps them ready while they wait out the 3 s silence, then each resubscribes to
    /// a core from its anchor (no snapshot) and receives what the cores committed meanwhile. Mutation: drop the silence check, clear readiness on a
    /// source change (`sourceChanged` on a resume), or resubscribe LIVE_ONLY.
    @Test
    void whenTheGovernorDies_theMembersStayReady_andReparentToACoreFromTheirAnchor() {
        var mesh = meshWithData();
        var gov = mesh.governorReady("w-gov");
        var one = mesh.worker("w-1");
        var two = mesh.worker("w-2");

        mesh.run(6);
        assertThat(mesh.subscribeTargets(one)).containsExactly(gov.id);

        gov.up.set(false);
        mesh.commit(put("c", "3"));
        for (var i = 0; i < 30; i++) {
            mesh.advance(300);
            mesh.step();
            assertThat(one.readiness.ready() && two.readiness.ready()).as("round %d: members stay ready", i).isTrue();
        }

        assertThat(mesh.isCore(mesh.subscribeTargets(one).getLast())).isTrue();
        assertThat(mesh.lastSubscribe(one).unwrap().mode()).isEqualTo(SubscribeMode.FROM_ANCHOR);
        assertThat(one.installs.get()).as("no second snapshot").isEqualTo(1);
        assertThat(one.valueOf("c")).isEqualTo("3");
        assertThat(two.valueOf("c")).isEqualTo("3");
    }

    /// NT-O23, core dies mid-feed: the governor resubscribes to the other core from its anchor and keeps its members; they never leave it and their
    /// freshness does not degrade. Mutation: close the serve gate while the governor re-parents (require a live session in `followsCore`), or drop the
    /// silence check.
    @Test
    void whenTheGovernorsCoreDies_theGovernorReparents_andKeepsItsMembers() {
        var mesh = meshWithData();
        var gov = mesh.governorReady("w-gov");
        var member = mesh.worker("w-1");

        mesh.run(6);
        var source = mesh.subscribeTargets(gov).getLast();

        mesh.core(0).up.set(!source.equals(mesh.core(0).id));
        mesh.core(1).up.set(!source.equals(mesh.core(1).id));
        mesh.commit(put("c", "3"));
        for (var i = 0; i < 30; i++) {
            mesh.advance(300);
            mesh.step();
            assertThat(member.readiness.ready()).as("round %d", i).isTrue();
        }

        assertThat(mesh.subscribeTargets(gov).getLast()).as("the governor moved to the other core").isNotEqualTo(source);
        assertThat(mesh.lastSubscribe(gov).unwrap().mode()).isEqualTo(SubscribeMode.FROM_ANCHOR);
        assertThat(mesh.subscribeTargets(member)).as("the member never left its governor").containsExactly(gov.id);
        assertThat(member.valueOf("c")).isEqualTo("3");
    }

    /// NT-O29, two governors briefly and a deposed one still relaying: the announcement moves from one to the other; the member re-parents from its
    /// anchor with no snapshot, the deposed governor refuses a late subscriber (`NOT_SERVING`), and both histories are prefixes of the same one.
    /// Mutation: serve without `governor` in the gate (the deposed one keeps serving), or drop the community test in `eligible`.
    @Test
    void aDeposedGovernor_refusesMembers_andTheMemberFollowsTheNewOneWithoutASnapshot() {
        var mesh = meshWithData();
        var old = mesh.governorReady("w-old");
        var member = mesh.worker("w-1");
        var late = mesh.worker("w-2");

        mesh.run(6);
        assertThat(mesh.subscribeTargets(member)).containsExactly(old.id);

        var next = mesh.worker("w-new");

        mesh.run(4);
        next.governor.set(true);
        mesh.run(4);
        assertThat(next.gate.mayServeMembers()).isTrue();
        mesh.commit(put("handover", "1"));
        mesh.run(3);
        mesh.announced.set(Option.some(next.id));
        old.governor.set(false);
        mesh.commit(put("c", "3"));
        mesh.run(6);

        assertThat(mesh.subscribeTargets(member).getLast()).isEqualTo(next.id);
        assertThat(member.installs.get()).as("no snapshot to change governor").isEqualTo(1);
        assertThat(member.valueOf("c")).isEqualTo("3");
        assertThat(old.gate.mayServeMembers()).isFalse();

        var before = mesh.log.size();

        mesh.deliver(late.id, old.id, new ReplicaSubscribe(late.id, 99L, org.pragmatica.cluster.state.kvstore.IncarnationTag.NONE, 0L, List.of(), SubscribeMode.LIVE_ONLY));

        assertThat(mesh.log.subList(before, mesh.log.size()).stream().map(Sent::message).filter(ReplicaRefused.class::isInstance).map(ReplicaRefused.class::cast))
            .extracting(ReplicaRefused::reason)
            .containsExactly(RefusalReason.NOT_SERVING);
    }

    /// NT-O30, a slow member: a burst fills its queue at the governor beyond the byte bound, so the governor closes that subscription and tells it
    /// (`EVICTED`) instead of blocking its own apply; the member discards its position, takes a snapshot from a core (the governor that closed it is left
    /// alone for the retry interval) and converges. Mutation: remove the queue bound, or let the overflow stop the governor's apply.
    @Test
    void aSlowMember_isClosedByTheGovernor_andConvergesFromACore() {
        var mesh = new Mesh(ReplicaFeedLimits.DEFAULT, new ReplicaFeedLimits(64 * 1024, 2, 5_000, 2, 150, 8, 30_000, 256, 256));

        mesh.genesis();
        mesh.commit(put("a", "1"));
        var gov = mesh.governorReady("w-gov");
        var slow = mesh.worker("w-1");

        mesh.run(8);
        assertThat(mesh.subscribeTargets(slow)).containsExactly(gov.id);
        assertThat(gov.server.counters().get("replica_subscribers")).isEqualTo(1L);

        mesh.commit(put("b", "2"));
        mesh.commit(put("c", "3"));
        mesh.commit(put("d", "4"));
        mesh.commit(put("e", "5"));
        mesh.advance(300);
        mesh.step();

        assertThat(mesh.sentBy(gov, ReplicaRefused.class)).extracting(sent -> ((ReplicaRefused) sent.message()).reason()).contains(RefusalReason.EVICTED);
        assertThat(gov.valueOf("e")).as("the governor's own apply was not stopped").isEqualTo("5");

        mesh.run(20);

        assertThat(slow.valueOf("e")).isEqualTo("5");
        assertThat(slow.readiness.ready()).isTrue();
        assertThat(mesh.isCore(mesh.subscribeTargets(slow).getLast())).isTrue();
    }

    /// NT-O31 (a), governor restarted empty while members are attached: the members' next subscribe meets a store with no incarnation, they take no
    /// data from it, and they converge on a core. Mutation: let a governor with tag `NONE` serve (drop the `NONE` rule), or let a member keep the
    /// restarted governor.
    @Test
    void aGovernorRestartedEmpty_isNeverFollowedForData_andTheMemberConvergesOnACore() {
        var mesh = meshWithData();
        var gov = mesh.governorReady("w-gov");
        var member = mesh.worker("w-1");

        mesh.run(6);
        assertThat(mesh.subscribeTargets(member)).containsExactly(gov.id);

        mesh.workers.remove(gov.id);
        mesh.community.remove(gov.id);
        var reborn = mesh.worker("w-gov");

        reborn.governor.set(true);
        reborn.holdInbound.set(true);
        mesh.commit(put("c", "3"));
        mesh.run(30);

        assertThat(reborn.gate.mayServeMembers()).as("it never caught up: it holds no incarnation").isFalse();
        assertThat(member.valueOf("a")).as("the member never lost what it held").isEqualTo("1");
        assertThat(member.valueOf("c")).isEqualTo("3");
        assertThat(mesh.isCore(mesh.subscribeTargets(member).getLast())).isTrue();
        assertThat(member.readiness.ready()).isTrue();
    }

    /// NT-O31 (b), a member ahead of the new governor: the governor is behind the member's applied frontier, so it answers `AHEAD`; only a core decides
    /// whether the position is usable, so the member keeps it, resumes from a core's ring with no snapshot, and ends equal to the cores. Mutation: treat a
    /// governor's refusal like a core's (discard the position).
    @Test
    void aMemberAheadOfTheNewGovernor_isRefusedAhead_andResumesFromACoreWithoutASnapshot() {
        var mesh = meshWithData();
        var behind = mesh.worker("w-behind");

        mesh.run(6);
        behind.up.set(false);
        mesh.commit(put("c", "3"));
        mesh.commit(put("d", "4"));
        var member = mesh.worker("w-1");

        mesh.run(6);
        assertThat(member.valueOf("d")).isEqualTo("4");
        var installs = member.installs.get();

        behind.up.set(true);
        behind.governor.set(true);
        mesh.announced.set(Option.some(behind.id));
        mesh.run(2);

        assertThat(mesh.sentBy(behind, ReplicaRefused.class)).extracting(sent -> ((ReplicaRefused) sent.message()).reason()).contains(RefusalReason.AHEAD);

        mesh.run(20);

        assertThat(member.valueOf("d")).isEqualTo("4");
        assertThat(member.readiness.ready()).isTrue();
        assertThat(member.installs.get()).as("no snapshot: the position was kept").isEqualTo(installs);
        assertThat(mesh.isCore(mesh.subscribeTargets(member).getLast())).isTrue();
    }

    /// A governor whose heartbeat claims a frontier behind the member's (under the same tag) is not a reason to discard the member's position: it sends
    /// the member back to a core, which resumes it with no snapshot. Mutation: discard the position on such a heartbeat from a governor (drop `behindOrAhead`'s
    /// governor branch).
    @Test
    void aGovernorsHeartbeatBehindTheMember_sendsItBackToACore_keepingItsPosition() {
        var mesh = meshWithData();
        var gov = mesh.governorReady("w-gov");
        var member = mesh.worker("w-1");

        mesh.run(8);
        assertThat(mesh.subscribeTargets(member)).containsExactly(gov.id);
        var installs = member.installs.get();
        var beat = (ReplicaHeartbeat) mesh.log.stream()
                                             .filter(sent -> sent.to().equals(member.id) && sent.message() instanceof ReplicaHeartbeat)
                                             .reduce((first, second) -> second)
                                             .orElseThrow()
                                             .message();

        member.client.onHeartbeat(new ReplicaHeartbeat(gov.id, beat.requestId(), beat.subSeq() + 1, beat.tag(), 0L, true, 0L));
        mesh.pump();
        mesh.run(6);

        assertThat(mesh.isCore(mesh.subscribeTargets(member).getLast())).isTrue();
        assertThat(mesh.lastSubscribe(member).unwrap().mode()).isEqualTo(SubscribeMode.FROM_ANCHOR);
        assertThat(member.installs.get()).as("the position was kept").isEqualTo(installs);
        assertThat(member.readiness.ready()).isTrue();
    }

    /// The governor-ring window: a governor whose ring has recorded no effect batch since its snapshot install can verify no anchor and refuses an anchored
    /// member `EVICTED`. The member's fallback is a core: it keeps its position, resumes there with no snapshot, and asks the governor again only after
    /// the retry interval, so the window costs one refused subscribe per interval and never a snapshot, however long the cluster stays quiet.
    /// Mutation: discard the position on a governor's refusal (a snapshot per interval).
    @Test
    void aGovernorThatCannotVerifyAnAnchor_sendsTheMemberBackToACore_withoutASnapshot() {
        var mesh = meshWithData();
        var member = mesh.worker("w-1");

        mesh.run(4);
        var gov = mesh.governorReadyQuiet("w-gov");
        var installs = member.installs.get();

        mesh.run(60);

        assertThat(mesh.sentBy(gov, ReplicaRefused.class)).extracting(sent -> ((ReplicaRefused) sent.message()).reason()).contains(RefusalReason.EVICTED);
        assertThat(mesh.subscribeTargets(member).stream().filter(gov.id::equals).count()).as("asked again after each interval").isGreaterThan(1L);
        assertThat(member.installs.get()).as("never a snapshot").isEqualTo(installs);
        assertThat(member.readiness.ready()).isTrue();
        assertThat(mesh.isCore(mesh.subscribeTargets(member).getLast()) || member.client.source().equals(Option.some(gov.id))).isTrue();
    }

    /// Spec 2.8 (b) through the relay: with both cores gone for good the governor stops serving within its freshness bound plus the silence interval, and
    /// its member stops being ready within the same bound plus its own silence interval, not a bound per level. The governor's counted heartbeat and the
    /// member's are carried across a re-parent for one silence interval only. Mutation: carry them for the whole freshness bound (drop `carryBeatsFor`).
    @Test
    void whenBothCoresAreGone_theGovernorAndItsMemberLeaveReadinessWithinOneBoundAndTwoSilences() {
        var mesh = meshWithData();
        var gov = mesh.governorReady("w-gov");
        var member = mesh.worker("w-1");

        mesh.run(8);
        assertThat(mesh.subscribeTargets(member)).containsExactly(gov.id);
        mesh.cores.forEach(core -> core.up.set(false));
        mesh.run(7);

        assertThat(member.readiness.ready()).as("7 s: the silence interval is still being waited out").isTrue();

        mesh.run(50);

        assertThat(gov.gate.mayServeMembers()).as("17 s after the cores died: the governor serves nobody").isFalse();
        assertThat(member.readiness.ready()).as("17 s after the cores died: the member is not ready (spec bound 30 s from the last heartbeat, not 60 s)").isFalse();
    }

    /// The hand-off acts only on a session that follows a governor: a node that becomes the governor while already on a core keeps its session.
    /// Mutation: end the session whatever its source (drop the uplink filter in `handOffOnGovernorEdge`).
    @Test
    void aMemberOnACore_thatBecomesTheGovernor_keepsItsCoreSession() {
        var mesh = meshWithData();
        var member = mesh.worker("w-1");

        mesh.run(6);
        var subscribes = mesh.subscribeTargets(member).size();

        member.governor.set(true);
        mesh.run(4);

        assertThat(mesh.subscribeTargets(member)).hasSize(subscribes);
        assertThat(member.gate.mayServeMembers()).isTrue();
    }

    /// A rebalance moves a session only once it is live: a join in progress (granted nothing yet) is not interrupted when a governor appears.
    /// Mutation: drop the `Phase.LIVE` test in `rebalance`.
    @Test
    void aJoinInProgress_isNotInterruptedByAGovernorAppearing() {
        var mesh = meshWithData();
        var gov = mesh.governorReady("w-gov");
        var member = mesh.worker("w-1");

        mesh.announced.set(Option.none());
        member.holdInbound.set(true);
        mesh.run(4);
        var subscribes = mesh.subscribeTargets(member).size();

        mesh.announced.set(Option.some(gov.id));
        mesh.run(3);

        assertThat(mesh.subscribeTargets(member)).as("still the same join").hasSize(subscribes);

        mesh.release(member);
        mesh.run(8);

        assertThat(member.readiness.ready()).isTrue();
    }

    /// NT-O34, join storm: six members join at once into a governor whose serve cap is 2 (and cores with the same cap, chunks of a few bytes so the
    /// transfers overlap). `BUSY` is advisory: every member ends ready, none of the sources ever serves more than 2 transfers, and some asker was
    /// turned away. Mutation: drop the serve cap, or treat `BUSY` as terminal.
    @Test
    void aJoinStorm_isServedUnderTheCap_andEveryMemberEndsReady() {
        var limits = new ReplicaFeedLimits(4, 2, 5_000, 2, 8 << 20, 512, 30_000, 256, 256);
        var mesh = new Mesh(limits, limits);

        mesh.genesis();
        mesh.commit(put("a", "1"));
        var gov = mesh.governorReady("w-gov");
        var members = new ArrayList<Worker>();

        for (var i = 1; i <= 6; i++) {
            members.add(mesh.worker("w-" + i));
        }

        var maxTransfers = 0L;

        for (var round = 0; round < 200 && !members.stream().allMatch(member -> member.readiness.ready()); round++) {
            mesh.advance(300);
            mesh.step();
            maxTransfers = Math.max(maxTransfers, Math.max(gov.server.counters().get("replica_transfers"), mesh.core(0).server.counters().get("replica_transfers")));
        }

        assertThat(members).allSatisfy(member -> assertThat(member.readiness.ready()).as(member.id.id()).isTrue());
        assertThat(members).allSatisfy(member -> assertThat(member.valueOf("a")).isEqualTo("1"));
        assertThat(maxTransfers).isLessThanOrEqualTo(2L);
        assertThat(gov.server.counters().get("replica_busy_total") + mesh.core(0).server.counters().get("replica_busy_total") + mesh.core(1).server.counters().get("replica_busy_total"))
            .as("somebody was turned away").isPositive();
    }

    /// A governor that cannot serve is asked once per retry interval, not every tick. Mutation: drop `governorRetryAt` (a refused governor is asked again at
    /// once), or never retry it.
    @Test
    void aGovernorThatRefused_isLeftAloneForTheRetryInterval_thenAskedAgain() {
        var mesh = meshWithData();
        var notServing = mesh.worker("w-gov");

        mesh.run(4);
        mesh.announced.set(Option.some(notServing.id));
        var member = mesh.worker("w-1");

        mesh.run(4);
        var asked = mesh.subscribeTargets(member).stream().filter(target -> target.equals(notServing.id)).count();

        assertThat(asked).as("asked once, refused NOT_SERVING (it is not the governor)").isEqualTo(1L);
        assertThat(mesh.isCore(mesh.subscribeTargets(member).getLast())).isTrue();

        mesh.run(20);
        assertThat(mesh.subscribeTargets(member).stream().filter(target -> target.equals(notServing.id)).count()).as("within the interval: not again").isEqualTo(1L);

        mesh.run(20);
        assertThat(mesh.subscribeTargets(member).stream().filter(target -> target.equals(notServing.id)).count()).as("after the interval: again").isGreaterThan(1L);
        assertThat(member.readiness.ready()).as("a refused governor never costs the member its readiness").isTrue();
    }

    /// The election hand-off (spec 3.3): a member that becomes the governor re-points to a core at once, even while its session with the old governor is
    /// still waiting for a grant; its members get `NOT_SERVING` until it is current against the core. Mutation: drop `handOffOnGovernorEdge`.
    @Test
    void aMemberThatBecomesTheGovernor_leavesItsGovernorAtOnce_andOpensItsGateOnlyOnceCurrent() {
        var mesh = meshWithData();
        var oldGovernor = mesh.governorReady("w-old");
        var member = mesh.worker("w-1");

        mesh.run(6);
        assertThat(mesh.subscribeTargets(member)).containsExactly(oldGovernor.id);

        oldGovernor.up.set(false);
        mesh.advance(300);
        mesh.step();
        var before = mesh.subscribeTargets(member).size();

        member.governor.set(true);
        assertThat(member.gate.mayServeMembers()).as("a governor still following a governor does not serve").isFalse();
        mesh.advance(300);
        mesh.step();

        assertThat(mesh.subscribeTargets(member)).hasSize(before + 1);
        assertThat(mesh.isCore(mesh.subscribeTargets(member).getLast())).isTrue();
        assertThat(mesh.lastSubscribe(member).unwrap().mode()).isEqualTo(SubscribeMode.FROM_ANCHOR);

        mesh.run(4);

        assertThat(member.gate.mayServeMembers()).isTrue();
    }

    /// A node that is announced as the governor but is not (yet) eligible, so `isGovernor` is false, must not follow itself. Mutation: drop the
    /// self test in `followableGovernor`.
    @Test
    void aNodeThatIsAnnouncedButNotTheGovernor_neverFollowsItself() {
        var mesh = meshWithData();
        var node = mesh.worker("w-1");

        mesh.run(4);
        mesh.announced.set(Option.some(node.id));
        mesh.run(6);

        assertThat(mesh.subscribeTargets(node)).doesNotContain(node.id);
        assertThat(node.readiness.ready()).isTrue();
    }
}
