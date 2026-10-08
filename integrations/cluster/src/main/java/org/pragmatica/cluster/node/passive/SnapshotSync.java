package org.pragmatica.cluster.node.passive;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkMessage.KVSyncRequest;
import org.pragmatica.consensus.net.NetworkMessage.KVSyncResponse;
import org.pragmatica.consensus.net.NetworkServiceMessage.Send;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter.DelegateRouter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;


/// Drives a passive node's initial KV snapshot until one is applied (#2033).
///
/// The request used to be one-shot: sent on the first connection and never again, so a lost request,
/// a lost response or a failed restore left the node without a snapshot forever. Now a request goes
/// out on every connection to a node other than the last one asked, and a backoff timer re-asks the
/// most recently connected node until a restore succeeds. After that nothing is sent, and a late
/// duplicate response is ignored: restoring it would roll the store back over decisions applied
/// since.
final class SnapshotSync {
    private static final Logger log = LoggerFactory.getLogger(SnapshotSync.class);

    private final NodeId selfId;
    private final DelegateRouter router;
    private final Function<byte[], Result<Unit>> restorer;
    private final SnapshotSyncPolicy policy;
    private final AtomicBoolean applied = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stalledReported = new AtomicBoolean(false);
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicLong backoffMs = new AtomicLong();
    private final AtomicLong startedAt = new AtomicLong();
    private final AtomicReference<Option<NodeId>> latestPeer = new AtomicReference<>(none());
    private final AtomicReference<Option<NodeId>> lastAsked = new AtomicReference<>(none());

    SnapshotSync(NodeId selfId,
                 DelegateRouter router,
                 Function<byte[], Result<Unit>> restorer,
                 SnapshotSyncPolicy policy) {
        this.selfId = selfId;
        this.router = router;
        this.restorer = restorer;
        this.policy = policy;
    }

    /// Idempotent: arms the retry timer. Called on start and on the first connection, whichever is first.
    Unit start() {
        if (running.compareAndSet(false, true)) {
            startedAt.set(policy.clock().getAsLong());
            backoffMs.set(policy.initialBackoffMs());
            scheduleNext();
        }

        return unit();
    }

    Unit stop() {
        running.set(false);

        return unit();
    }

    Unit onConnected(NodeId peer) {
        latestPeer.set(option(peer));
        start();
        if (!applied.get() && !lastAsked.get().equals(option(peer))) {
            request(peer);
        }

        return unit();
    }

    Unit onResponse(KVSyncResponse response) {
        if (applied.get()) {
            log.debug("Ignoring KV snapshot from {}: one is already applied", response.target());

            return unit();
        }

        restorer.apply(response.snapshot())
                .onSuccess(_ -> markApplied(response.target()))
                .onFailure(cause -> onRestoreFailed(response.target(),
                                                    cause));

        return unit();
    }

    private void markApplied(NodeId source) {
        if (!applied.compareAndSet(false, true)) {
            return;
        }

        log.info("KV-Store snapshot restored from {}", source);
        if (stalledReported.get()) {
            policy.observer().recovered(selfId, attempts.get(), elapsedMs());
        }
    }

    private void onRestoreFailed(NodeId source, Cause cause) {
        log.error("Failed to restore KV snapshot from {}: {}; will re-request", source, cause);
        // Forget who was asked so the next connection to the same node may ask again too.
        lastAsked.set(none());
    }

    private void request(NodeId peer) {
        lastAsked.set(option(peer));
        attempts.incrementAndGet();
        log.info("Requesting KV-Store snapshot from {}", peer);
        router.route(new Send(peer, new KVSyncRequest(selfId)));
    }

    private void tick() {
        if (!running.get() || applied.get()) {
            return;
        }

        reportStallOnce();
        latestPeer.get().onPresent(this::request);
        backoffMs.set(Math.min(backoffMs.get() * 2, policy.maxBackoffMs()));
        scheduleNext();
    }

    private void reportStallOnce() {
        if (elapsedMs() >= policy.stallBoundMs() && stalledReported.compareAndSet(false, true)) {
            policy.observer().stalled(selfId, attempts.get(), elapsedMs());
        }
    }

    private void scheduleNext() {
        policy.ticker().schedule(this::tick, backoffMs.get());
    }

    private long elapsedMs() {
        return policy.clock()
                     .getAsLong() - startedAt.get();
    }
}
