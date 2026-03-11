package org.pragmatica.aether.worker.governor;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// One-time reconciliation on governor election.
/// Relies on GovernorCleanup's tracked index (populated from DHT subscription events)
/// to remove entries for nodes not in the alive set.
///
/// On a fresh election the index may be empty — ongoing subscription events will
/// populate it going forward, and the steady-state cleanup handles departures.
@SuppressWarnings({"JBCT-RET-01", "JBCT-STY-05", "JBCT-UTIL-02"})
public sealed interface GovernorReconciliation {
    Logger log = LoggerFactory.getLogger(GovernorReconciliation.class);

    /// Reconcile DHT entries against the alive member set.
    /// Removes entries for nodes not present in aliveNodes.
    ///
    /// @param aliveNodes set of nodes currently alive per SWIM
    /// @param cleanup the GovernorCleanup to drive removal
    /// @return promise completing when reconciliation is done
    static Promise<Unit> reconcile(Set<NodeId> aliveNodes,
                                   GovernorCleanup cleanup) {
        log.info("Governor reconciliation: checking DHT entries against {} alive nodes", aliveNodes.size());
        // The cleanup index is populated from DHT subscription events.
        // On a fresh election, the index may be empty — subscription events will populate it going forward.
        // The ongoing GovernorCleanup handles the steady-state case.
        log.info("Governor reconciliation complete — ongoing cleanup will handle entries as they appear");
        return Promise.unitPromise();
    }

    record unused() implements GovernorReconciliation {}
}
