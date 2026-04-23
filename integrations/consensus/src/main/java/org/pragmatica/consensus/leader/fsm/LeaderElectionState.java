/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.consensus.leader.fsm;

/// Explicit states of the leader-election state machine. Every runtime configuration of the
/// election process maps to exactly one of these values — there is no implicit state derived
/// from combinations of atomic flags.
public enum LeaderElectionState {
    /// No quorum yet, and the machine has never elected a leader.
    DORMANT,
    /// Quorum established; waiting for the external `ConsensusReady` signal (consensus sync).
    QUORUM_WAITING,
    /// Initial election in progress. `hasEverHadLeader` is false; only the lowest-ranked candidate
    /// submits proposals. Entry schedules the staggered first tick.
    ELECTING,
    /// A leader is committed and present in the current topology. Stable.
    LED,
    /// The cluster previously had a leader and has lost it while quorum still holds. All survivors
    /// may propose (consensus deduplicates).
    RE_ELECTING,
    /// Quorum has disappeared. Leader invalidated; machine waits for quorum to re-establish.
    QUORUM_LOST,
    /// Terminal state after shutdown.
    STOPPED
}
