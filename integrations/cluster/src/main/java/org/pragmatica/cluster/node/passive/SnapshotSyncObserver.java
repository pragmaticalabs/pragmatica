package org.pragmatica.cluster.node.passive;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// Operator-facing signal for a passive node's initial KV snapshot (#2033).
///
/// `stalled` fires ONCE per stall (flood guard: the retry timer keeps running, the signal does
/// not repeat) when no snapshot has been applied within the policy's stall bound. `recovered`
/// fires once, only after a `stalled`, when a snapshot is finally applied. A passive node has no
/// event bus of its own, so the embedder decides what an event is; the default logs.
public interface SnapshotSyncObserver {
    Unit stalled(NodeId self, int attempts, long elapsedMs);
    Unit recovered(NodeId self, int attempts, long elapsedMs);

    static SnapshotSyncObserver logging() {
        return LoggingObserver.INSTANCE;
    }

    enum LoggingObserver implements SnapshotSyncObserver {
        INSTANCE;
        private static final Logger log = LoggerFactory.getLogger(SnapshotSyncObserver.class);
        @Override
        public Unit stalled(NodeId self, int attempts, long elapsedMs) {
            log.warn("Passive node {} has no KV snapshot after {} ms and {} request(s); still retrying",
                     self,
                     elapsedMs,
                     attempts);

            return unit();
        }
        @Override
        public Unit recovered(NodeId self, int attempts, long elapsedMs) {
            log.info("Passive node {} applied its KV snapshot after {} ms and {} request(s)", self, elapsedMs, attempts);

            return unit();
        }
    }
}
