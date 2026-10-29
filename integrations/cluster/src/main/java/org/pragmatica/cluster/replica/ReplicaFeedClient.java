// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pragmatica.cluster.replica.ReplicaApplier.Step;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaEntries;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaHeartbeat;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaRefused;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotChunk;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotRequest;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSubscribe;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.SubscribeMode;
import org.pragmatica.cluster.state.kvstore.AnchorPair;
import org.pragmatica.cluster.state.kvstore.IncarnationTag;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// The worker side of the replica feed (spec 3.2): follows ONE source at a time chosen by [UpstreamSelector], joins subscribe-first (live entries
/// buffer while the filtered snapshot transfers in pulled chunks, then drain), resumes a held anchored position with `FROM_ANCHOR`, and falls back
/// to a snapshot on any refusal that makes the position unusable.
///
/// One request id names one subscription AND its snapshot transfer. A refusal is TERMINAL for its request id: the session ends there and every
/// later message carrying that id (a grant acknowledgement or tagged entries that overtook or followed the refusal around an incarnation bump)
/// is discarded (G-1). Messages from any sender but the session's source are discarded too.
///
/// The governor arm (relay, spec 2.3/2.9/3.3): a member whose community has an announced governor follows it instead of a core, through the same
/// [UpstreamSelector]. A governor that refuses, goes silent or ends its session is not retried for 10 s (`GOVERNOR_RETRY_MILLIS`), so a governor that cannot
/// serve costs a member one refused subscribe per interval, not a flap. A node that BECOMES the governor re-points to a core at once (hand-off), and its
/// [#followsCore] is one of the three conditions of [ReplicaServeGate].
///
/// All state is confined to `serial`, a single-thread executor: inbound messages and [#tick] hop onto it, so the network thread never applies
/// entries or installs a snapshot. Readiness is published by [ReplicaReadiness#refresh] from here and nowhere else.
public final class ReplicaFeedClient<K extends StructuredKey, V> {
    private static final Logger log = LoggerFactory.getLogger(ReplicaFeedClient.class);
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long RETRY_AFTER_FAILURE_MILLIS = BusyBackoff.MIN_DELAY_MILLIS;
    private static final int LOOP_AFTER_ABANDONS = 3;
    private static final long LOOP_BACKOFF_MILLIS = 30_000L;
    /// How long a governor that failed a session is left alone `[guess]`.
    private static final long GOVERNOR_RETRY_MILLIS = 10_000L;

    private enum Phase {
        AWAIT_GRANT,
        SNAPSHOT,
        LIVE
    }

    /// What the governor arm needs from the node: whether this node is itself the governor, and the announced governor of its community.
    public record GovernorArm(BooleanSupplier selfIsGovernor, Supplier<Option<NodeId>> announced) {}

    /// The snapshot being assembled from chunks; `bytes` is sized by the first chunk's `total`.
    private static final class Transfer {
        final IncarnationTag tag;
        final long revision;
        final String hash;
        final List<AnchorPair> anchor;
        final byte[] bytes;
        int received;

        Transfer(IncarnationTag tag, long revision, String hash, List<AnchorPair> anchor, int total) {
            this.tag = tag;
            this.revision = revision;
            this.hash = hash;
            this.anchor = anchor;
            this.bytes = new byte[total];
        }
    }

    private static final class Session {
        final long id;
        final NodeId source;
        final SubscribeMode mode;
        Phase phase = Phase.AWAIT_GRANT;
        long lastHeardNanos;
        long latest;
        Transfer transfer;

        Session(long id, NodeId source, SubscribeMode mode, long nowNanos) {
            this.id = id;
            this.source = source;
            this.mode = mode;
            this.lastHeardNanos = nowNanos;
        }
    }

    private final NodeId self;
    private final ReplicaApplier<K, V> applier;
    private final ReplicaReadiness readiness;
    private final BiConsumer<NodeId, ProtocolMessage> send;
    private final Supplier<List<NodeId>> uplinks;
    private final Executor serial;
    private final LongSupplier nanos;
    private final ReplicaClientLimits limits;
    private final Runnable onSnapshotInstalled;
    private final GovernorArm governorArm;
    private final Set<NodeId> avoided = new HashSet<>();
    private final Map<NodeId, Long> governorRetryAt = new HashMap<>();
    private final AtomicLong subscribed = new AtomicLong();
    private final AtomicLong snapshotsInstalled = new AtomicLong();
    private final AtomicLong refusals = new AtomicLong();
    private final AtomicLong silenceSwitches = new AtomicLong();
    private final AtomicLong gaps = new AtomicLong();
    private final AtomicLong bufferOverflows = new AtomicLong();
    private final AtomicLong snapshotLoops = new AtomicLong();
    private final AtomicLong droppedMessages = new AtomicLong();
    private volatile boolean followsCore;
    private boolean wasGovernor;
    private Session session;
    private volatile Option<NodeId> currentSource = Option.none();
    private long requestCounter;
    private long retryAtNanos;
    private int consecutiveRefusals;
    private int consecutiveOverflows;

    private ReplicaFeedClient(NodeId self,
                              ReplicaApplier<K, V> applier,
                              ReplicaReadiness readiness,
                              BiConsumer<NodeId, ProtocolMessage> send,
                              Supplier<List<NodeId>> uplinks,
                              Executor serial,
                              LongSupplier nanos,
                              ReplicaClientLimits limits,
                              Runnable onSnapshotInstalled,
                              GovernorArm governorArm) {
        this.self = self;
        this.applier = applier;
        this.readiness = readiness;
        this.send = send;
        this.uplinks = uplinks;
        this.serial = serial;
        this.nanos = nanos;
        this.limits = limits;
        this.onSnapshotInstalled = onSnapshotInstalled;
        this.governorArm = governorArm;
        this.requestCounter = nanos.getAsLong() ^ ((long) self.id().hashCode() << 32);
    }

    /// `send` hands a message to the transport (non-blocking); `uplinks` are the node's deterministic core uplinks, in order; `serial` is a
    /// single-thread executor; `onSnapshotInstalled` is the post-restore seam (`completePassiveRestore`), run after each snapshot install;
    /// `governorArm` is the node's view of the relay (a node that never follows a governor passes `() -> false` and `Option::none`).
    public static <K extends StructuredKey, V> ReplicaFeedClient<K, V> replicaFeedClient(NodeId self,
                                                                                         ReplicaApplier<K, V> applier,
                                                                                         ReplicaReadiness readiness,
                                                                                         BiConsumer<NodeId, ProtocolMessage> send,
                                                                                         Supplier<List<NodeId>> uplinks,
                                                                                         Executor serial,
                                                                                         LongSupplier nanos,
                                                                                         ReplicaClientLimits limits,
                                                                                         Runnable onSnapshotInstalled,
                                                                                         GovernorArm governorArm) {
        return new ReplicaFeedClient<>(self,
                                       applier,
                                       readiness,
                                       send,
                                       uplinks,
                                       serial,
                                       nanos,
                                       limits,
                                       onSnapshotInstalled,
                                       governorArm);
    }

    // ---- inbound (network thread: hop at once) ----
    @Contract
    public void onEntries(ReplicaEntries message) {
        serial.execute(() -> handle(message.sender(), message.requestId(), session -> entries(session, message)));
    }

    @Contract
    public void onHeartbeat(ReplicaHeartbeat message) {
        serial.execute(() -> handle(message.sender(), message.requestId(), session -> heartbeat(session, message)));
    }

    @Contract
    public void onRefused(ReplicaRefused message) {
        serial.execute(() -> handle(message.sender(), message.requestId(), session -> refused(session, message)));
    }

    @Contract
    public void onSnapshotChunk(ReplicaSnapshotChunk message) {
        serial.execute(() -> handle(message.sender(), message.requestId(), session -> chunk(session, message)));
    }

    /// Checks silence, opens a session when there is none and its retry time has come, and refreshes readiness. Called by a scheduler.
    @Contract
    public void tick() {
        serial.execute(this::tickNow);
    }

    /// True iff the source this node last chose is a core uplink: one of the three conditions of [ReplicaServeGate]. It holds across a re-parent from
    /// one core to the other (the replica is still as current as its last counted heartbeat says, and `servable` checks that), so a governor whose core
    /// died keeps its members; it is false while the node still follows a governor (it has just become one) or has chosen nothing.
    public boolean followsCore() {
        return followsCore;
    }

    /// The source this node last chose (a governor or a core uplink), for diagnostics and tests.
    public Option<NodeId> source() {
        return currentSource;
    }

    public ReplicaPosition position() {
        return applier.position();
    }

    public Map<String, Long> counters() {
        return Map.of("replica_subscribed_total",
                      subscribed.get(),
                      "replica_snapshots_installed_total",
                      snapshotsInstalled.get(),
                      "replica_refused_total",
                      refusals.get(),
                      "replica_silence_switch_total",
                      silenceSwitches.get(),
                      "replica_gap_total",
                      gaps.get(),
                      "replica_buffer_overflow_total",
                      bufferOverflows.get(),
                      "replica_snapshot_loop_total",
                      snapshotLoops.get(),
                      "replica_dropped_total",
                      droppedMessages.get());
    }

    // ---- session routing ----
    private void handle(NodeId sender, long requestId, Consumer<Session> action) {
        Option.option(session)
              .filter(open -> open.id == requestId && open.source.equals(sender))
              .onPresent(open -> touch(open, action))
              .onEmpty(droppedMessages::incrementAndGet);
        publish();
    }

    private void touch(Session open, Consumer<Session> action) {
        open.lastHeardNanos = nanos.getAsLong();
        action.accept(open);
    }

    // ---- tick ----
    private void tickNow() {
        handOffOnGovernorEdge();
        Option.option(session).onPresent(this::checkSilence).onPresent(this::rebalance).onEmpty(this::openIfDue);
        publish();
    }

    private void publish() {
        followsCore = currentSource.filter(uplinks.get()::contains).isPresent();
        readiness.refresh();
    }

    /// The election hand-off (spec 3.3): when this node becomes the governor it stops following a governor (possibly a deposed one) and
    /// re-points to a core uplink, resuming from its anchor. Becoming a member again needs nothing here: [#rebalance] finds the new governor.
    private void handOffOnGovernorEdge() {
        var governor = governorArm.selfIsGovernor().getAsBoolean();

        if (governor && !wasGovernor) {
            Option.option(session).filter(open -> !uplinks.get()
                                                          .contains(open.source)).onPresent(this::endDeliberately);
        }

        wasGovernor = governor;
    }

    /// A live session re-asks the selector: a governor that appeared (or went) moves the member there (or back to a core) without waiting for a failure.
    private void rebalance(Session open) {
        if (session == open && open.phase == Phase.LIVE) {
            UpstreamSelector.select(uplinks.get(),
                                    governorCandidate(),
                                    Option.some(open.source),
                                    avoided)
                            .filter(chosen -> !chosen.equals(open.source))
                            .onPresent(_ -> endDeliberately(open));
        }
    }

    /// The announced governor of this community when this node may follow it: not while it is the governor itself, not itself, not within the
    /// retry delay.
    private Option<NodeId> governorCandidate() {
        return governorArm.selfIsGovernor()
                          .getAsBoolean()
               ? Option.none()
               : followableGovernor();
    }

    private Option<NodeId> followableGovernor() {
        var now = nanos.getAsLong();

        return governorArm.announced()
                          .get()
                          .filter(candidate -> !candidate.equals(self))
                          .filter(candidate -> now >= governorRetryAt.getOrDefault(candidate, 0L));
    }

    private void checkSilence(Session open) {
        if (nanos.getAsLong() - open.lastHeardNanos > limits.silenceMillis() * NANOS_PER_MILLI) {
            silenceSwitches.incrementAndGet();
            avoided.add(open.source);
            end(open, RETRY_AFTER_FAILURE_MILLIS);
        }
    }

    private void openIfDue() {
        if (nanos.getAsLong() >= retryAtNanos) {
            UpstreamSelector.select(uplinks.get(),
                                    governorCandidate(),
                                    currentSource,
                                    avoided)
                            .onPresent(this::subscribeTo)
                            .onEmpty(this::everyUplinkAvoided);
        }
    }

    /// Both uplinks refused or went silent: forget the verdicts and wait the full-jitter backoff before asking again.
    private void everyUplinkAvoided() {
        avoided.clear();
        retryAtNanos = nanos.getAsLong() + BusyBackoff.delayMillis(self.id(), Math.max(1, consecutiveRefusals)) * NANOS_PER_MILLI;
    }

    // ---- subscribe ----
    private void subscribeTo(NodeId source) {
        var held = applier.position();
        var resume = held.isResumable();
        var mode = resume
                   ? SubscribeMode.FROM_ANCHOR
                   : SubscribeMode.LIVE_ONLY;

        session = new Session(++requestCounter, source, mode, nanos.getAsLong());
        currentSource = Option.some(source);
        if (resume) {
            readiness.carryBeatsFor(limits.silenceMillis() * NANOS_PER_MILLI);
            applier.beginLive();
        } else {
            readiness.sourceChanged();
            applier.beginBuffering();
        }

        subscribed.incrementAndGet();
        send.accept(source, subscribeMessage(session, held, resume));
    }

    private ReplicaSubscribe subscribeMessage(Session open, ReplicaPosition held, boolean resume) {
        return resume
               ? new ReplicaSubscribe(self, open.id, held.tag(), held.applied(), held.anchor(), open.mode)
               : new ReplicaSubscribe(self, open.id, IncarnationTag.NONE, 0L, List.of(), open.mode);
    }

    // ---- entries ----
    private void entries(Session open, ReplicaEntries message) {
        var step = applier.accept(message);

        if (step == Step.APPLIED || step == Step.BUFFERED) {
            progressed(open, message);
        } else {
            failed(open, step);
        }
    }

    private void progressed(Session open, ReplicaEntries message) {
        if (open.phase == Phase.AWAIT_GRANT && message.subSeq() == 0L) {
            granted(open, message);
        } else if (open.phase == Phase.LIVE && message.subSeq() == 0L) {
            open.latest = Math.max(open.latest, message.latest());
        }

        checkCaughtUp(open);
    }

    private void granted(Session open, ReplicaEntries message) {
        open.latest = message.latest();
        if (open.mode == SubscribeMode.LIVE_ONLY) {
            open.phase = Phase.SNAPSHOT;
            send.accept(open.source, new ReplicaSnapshotRequest(self, open.id, IncarnationTag.NONE, 0L, "", 0L));
        } else {
            open.phase = Phase.LIVE;
        }
    }

    /// A resumed session is caught up when it has applied everything the source held at the grant; a snapshot session when its install drained.
    private void checkCaughtUp(Session open) {
        if (open.phase == Phase.LIVE && applier.position().applied() >= open.latest) {
            readiness.caughtUp(true);
            avoided.clear();
            consecutiveRefusals = 0;
            consecutiveOverflows = 0;
        }
    }

    // ---- heartbeat ----
    private void heartbeat(Session open, ReplicaHeartbeat beat) {
        if (applier.sequence(beat.subSeq()) != Step.APPLIED) {
            failed(open, Step.GAP);
        } else if (!beat.serving()) {
            avoided.add(open.source);
            end(open, RETRY_AFTER_FAILURE_MILLIS);
        } else if (readiness.isAhead(beat.tag(), beat.nextSlot())) {
            behindOrAhead(open);
        } else {
            readiness.onHeartbeat(beat.tag(), beat.nextSlot(), true);
        }
    }

    // ---- refusal ----
    private void refused(Session open, ReplicaRefused message) {
        refusals.incrementAndGet();
        if (isGovernor(open)) {
            fellBackFromGovernor(open);

            return;
        }

        switch (message.reason()) {
            case BUSY, NOT_SERVING -> turnedAway(open);
            default -> positionUnusable(open);
        }
    }

    private boolean isGovernor(Session open) {
        return ! uplinks.get()
                        .contains(open.source);
    }

    /// A governor can refuse a position it cannot verify (`EVICTED`: its ring starts empty at its own snapshot install and holds no pair at or above
    /// its floor until the cluster commits an effect batch past the member's newest pair, which in a quiet cluster can take long; `AHEAD`: it is behind
    /// this node), but only a core decides whether a position is usable. So a governor's refusal, of any reason, keeps the position and falls back to
    /// a core (which resumes it from the anchor, or refuses it itself); the governor is asked again after its retry delay. The cost while the governor's
    /// ring cannot verify is one refused subscribe per member per interval, never a snapshot.
    private void fellBackFromGovernor(Session open) {
        avoided.add(open.source);
        end(open, 0L);
    }

    /// The source's heartbeat claims a frontier behind this replica's. A core being behind makes the position unusable; a governor being behind just
    /// sends the member back to a core.
    private void behindOrAhead(Session open) {
        if (isGovernor(open)) {
            fellBackFromGovernor(open);
        } else {
            failed(open, Step.DISCARD_POSITION);
        }
    }

    /// `BUSY` and `NOT_SERVING` are advisory: the other uplink first; both refusing waits the backoff (see [#everyUplinkAvoided]).
    private void turnedAway(Session open) {
        consecutiveRefusals++;
        avoided.add(open.source);
        end(open, 0L);
    }

    /// `EVICTED`, `INCARNATION_MISMATCH`, `AHEAD`, anything unknown: discard the position and take a snapshot.
    private void positionUnusable(Session open) {
        applier.discardPosition();
        end(open, RETRY_AFTER_FAILURE_MILLIS);
    }

    // ---- snapshot ----
    private void chunk(Session open, ReplicaSnapshotChunk message) {
        if (open.phase != Phase.SNAPSHOT) {
            droppedMessages.incrementAndGet();

            return;
        }

        assemble(open, message).onSuccess(transfer -> advanceTransfer(open, transfer))
                .onFailure(cause -> abandonTransfer(open,
                                                    cause.message()));
    }

    private Result<Transfer> assemble(Session open, ReplicaSnapshotChunk message) {
        if (message.offset() == 0L) {
            return startTransfer(open, message);
        }

        var transfer = open.transfer;

        return transfer != null && transfer.received == message.offset() && transfer.hash.equals(message.hash())
               ? append(transfer, message)
               : Causes.cause("snapshot chunk out of order at offset " + message.offset()).result();
    }

    private Result<Transfer> startTransfer(Session open, ReplicaSnapshotChunk message) {
        if (message.tag().isNone() || message.total() <= 0L || message.total() > Integer.MAX_VALUE) {
            return Causes.cause("snapshot refused: no usable tag or length").result();
        }

        open.transfer = new Transfer(message.tag(),
                                     message.revision(),
                                     message.hash(),
                                     message.anchor(),
                                     (int) message.total());

        return append(open.transfer, message);
    }

    private static Result<Transfer> append(Transfer transfer, ReplicaSnapshotChunk message) {
        var bytes = message.bytes();

        if (transfer.received + bytes.length > transfer.bytes.length) {
            return Causes.cause("snapshot chunk overruns the announced length").result();
        }

        System.arraycopy(bytes, 0, transfer.bytes, transfer.received, bytes.length);
        transfer.received += bytes.length;

        return Result.success(transfer);
    }

    private void advanceTransfer(Session open, Transfer transfer) {
        if (transfer.received < transfer.bytes.length) {
            send.accept(open.source,
                        new ReplicaSnapshotRequest(self,
                                                   open.id,
                                                   transfer.tag,
                                                   transfer.revision,
                                                   transfer.hash,
                                                   transfer.received));
        } else {
            install(open, transfer);
        }
    }

    private void install(Session open, Transfer transfer) {
        sha256Hex(transfer.bytes).filter(Causes.cause("snapshot hash mismatch"),
                                         hash -> hash.equals(transfer.hash))
                 .flatMap(_ -> applier.install(transfer.bytes, transfer.tag, transfer.revision, transfer.anchor))
                 .onSuccess(step -> installed(open, step))
                 .onFailure(cause -> abandonTransfer(open,
                                                     cause.message()));
    }

    private void installed(Session open, Step drained) {
        open.transfer = null;
        snapshotsInstalled.incrementAndGet();
        onSnapshotInstalled.run();
        if (drained == Step.APPLIED) {
            open.phase = Phase.LIVE;
            checkCaughtUp(open);
        } else {
            failed(open, drained);
        }
    }

    private void abandonTransfer(Session open, String reason) {
        log.warn("Replica snapshot from {} abandoned: {}", open.source, reason);
        applier.discardPosition();
        end(open, RETRY_AFTER_FAILURE_MILLIS);
    }

    private static Result<String> sha256Hex(byte[] bytes) {
        return Result.lift(Causes::fromThrowable,
                           () -> HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    // ---- failure and end ----
    private void failed(Session open, Step step) {
        switch (step) {
            case GAP -> {
                gaps.incrementAndGet();
                end(open, RETRY_AFTER_FAILURE_MILLIS);
            }
            case OVERFLOW -> overflowed(open);
            default -> positionUnusable(open);
        }
    }

    /// The snapshot was too slow for the receiver buffer: abandon and start over. Three in a row raise the loop signal and back off (W7's cue).
    private void overflowed(Session open) {
        bufferOverflows.incrementAndGet();
        applier.discardPosition();
        if (++consecutiveOverflows >= LOOP_AFTER_ABANDONS) {
            snapshotLoops.incrementAndGet();
            consecutiveOverflows = 0;
            end(open, LOOP_BACKOFF_MILLIS);
        } else {
            end(open, RETRY_AFTER_FAILURE_MILLIS);
        }
    }

    /// Retires a session that failed: its request id answers nothing from here on, and a governor that failed is left alone for a while.
    private void end(Session open, long retryAfterMillis) {
        if (!uplinks.get().contains(open.source)) {
            governorRetryAt.put(open.source, nanos.getAsLong() + GOVERNOR_RETRY_MILLIS * NANOS_PER_MILLI);
        }

        retire(open, retryAfterMillis);
    }

    /// Retires a session because the selector (or the hand-off) chose another source: the old one did nothing wrong and is not penalized.
    private void endDeliberately(Session open) {
        retire(open, 0L);
    }

    /// The replica keeps its place across a re-parent: readiness (e) is lost only when the position was discarded and a snapshot must follow.
    private void retire(Session open, long retryAfterMillis) {
        session = null;
        retryAtNanos = nanos.getAsLong() + retryAfterMillis * NANOS_PER_MILLI;
        if (!applier.position().isResumable()) {
            readiness.caughtUp(false);
        }
    }
}
