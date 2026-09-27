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

import java.nio.file.Path;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// #1212 — the durable first-boot marker that lets a PROVABLY NEW node adopt on the cold bound.
///
/// ## Why this exists
///
/// #667 raises the response bound for an amnesiac joiner (see
/// [RabiaEngine#responsesRequiredWithALiveResponder]). A node that lost its durable state is
/// indistinguishable from one that never had any, so a genuinely new node pays the amnesiac's price:
/// at `emberCluster(3, ...)` with a third node held back the second response can never arrive and a
/// fresh cluster cannot form. Pre-#667 the rule was `clusterSize / 2` unconditionally, so this is a
/// regression #667 introduces rather than a pre-existing defect.
///
/// The argument that licenses the carve-out is the same intersection argument as everything else
/// here: **if a joiner provably never voted, no commit quorum ever contained it.** So any commit
/// quorum intersecting `{responders} ∪ {self}` must intersect at a RESPONDER, and that responder
/// holds the commit. Admitting a provably-new node on `clusterSize / 2` responses spends nothing.
/// What was missing was never the reasoning — it was the OBSERVATION, which is what this provides.
///
/// ## Why absence is PARTICIPATED and never NEVER_PARTICIPATED
///
/// The dangerous node is the one that lost its state, and it presents as absence. So absence is read
/// as [Participation#UNKNOWN] and treated exactly as [Participation#PARTICIPATED] by the adoption
/// rule. Newness is never inferred from emptiness; it is only ever a POSITIVE durable assertion
/// written before the node participates in anything. Getting this backwards reopens #667 wider than
/// it is today.
///
/// ## LOAD-BEARING: this must NOT move into `RabiaPersistence`
///
/// The obvious simplification for a later reader is to fold this into [RabiaPersistence], which
/// already persists consensus state. **Do not.** `AetherNode.resolvePersistence` falls back to
/// `RabiaPersistence.inMemory()` whenever no `BackupConfig` path is set, and a marker living there
/// dies with the process. Such a node would re-assert newness on EVERY restart — that is precisely
/// the amnesiac-returning-node case #667 exists to refuse, so the fix would reopen the hole it was
/// written to close, and would do so silently. The marker needs a durable medium of its own,
/// independent of whether consensus persistence is configured.
///
/// ## LIMITATION — what this does and does not survive (owner ruling, session 20)
///
/// The property "a node that has voted cannot present itself as new" is **always relative to what
/// the operator deleted**. There is no scope-free version of it, because a wipe is defined by
/// removing exactly the evidence that would distinguish a new node from a wiped one. The three
/// scopes, stated so the next reader knows the boundary rather than inferring a stronger guarantee:
///
/// - `[verified: FileBackedParticipationMarkerTest.deletingTheConsensusStateDirLeavesTheMarkerIntact]`
///   deleting the consensus backup dir — the marker lives elsewhere and still reads PARTICIPATED.
///   **HOLDS.**
/// - `[verified: FileBackedParticipationMarkerTest.aDeletedMarkerWithNoCreationAssertionReadsParticipated]`
///   deleting the whole node data dir — the marker is absent, absence is conservative. **HOLDS.**
/// - `[unverified: out of scope, accepted by owner ruling session 20]` deleting the node data dir
///   **AND** re-running node creation so the creation assertion is supplied again: the node presents
///   as new having voted. **FAILS.** A node cannot refuse an identity its own operator asserts, and
///   no node-local mechanism can. Peer attestation does not rescue it either — a sole responder that
///   was partitioned while the joiner voted attests "new" wrongly.
///
/// `[unverified: rc4 baseline comparison]` rc4 today has #667's hole open for EVERY node, wiped or
/// not. The #1171 + #1212 pair closes it for everything but the delete-and-recreate case, so the
/// pair is a strict improvement over rc4 carrying a named limit.
public interface ParticipationMarker {
    /// This node's participation history. Resolved ONCE, at engine start, BEFORE the first
    /// `SyncRequest` reaches the wire — a marker read after first participation cannot be trusted at
    /// the moment it is read. Implementations cache, so repeated calls are cheap and stable, and a
    /// call after [#recordParticipation] reports [Participation#PARTICIPATED].
    Participation resolve();
    /// Durably records that this node has participated. Called from `RabiaEngine.activate()` BEFORE
    /// the engine transitions out of `Syncing`, because a node cannot vote before it activates.
    ///
    /// **Fail-closed:** a failure here means the node MUST NOT activate. A node that activates
    /// without recording is one that can vote and then, after losing its state, present itself as
    /// new — the exact property this ticket exists to prevent. Idempotent: after the first success
    /// this is a no-op and performs no I/O.
    Result<Unit> recordParticipation();

    /// What the marker says about this node's history.
    enum Participation {
        /// Durably recorded, before any participation, that this node was being created. The ONLY
        /// state that relaxes the adoption bound.
        NEVER_PARTICIPATED,
        /// This node has activated at least once, so it may have voted.
        PARTICIPATED,
        /// No marker, or it could not be read or written. Treated exactly as [#PARTICIPATED] by the
        /// adoption rule — absence means WIPED, never NEW.
        UNKNOWN;
        /// Whether this node provably never voted, and may therefore adopt on `clusterSize / 2`.
        public boolean provablyNeverVoted() {
            return this == NEVER_PARTICIPATED;
        }
    }

    /// The fail-safe default, and what every caller that wires nothing gets: this node's history is
    /// unknown, so it is held to the amnesiac's bound. Production keeps exactly its #1171 behaviour
    /// until the deployment path supplies a real marker (follow-up ticket).
    ///
    /// [#recordParticipation] SUCCEEDS here rather than failing. The marker may only ever RELAX the
    /// bound; a node without one must still be able to activate, or wiring nothing would wedge every
    /// cluster.
    static ParticipationMarker unknown() {
        record unknown() implements ParticipationMarker {
            @Override
            public Participation resolve() {
                return Participation.UNKNOWN;
            }

            @Override
            public Result<Unit> recordParticipation() {
                return Result.success(Unit.unit());
            }
        }

        return new unknown();
    }

    /// A marker held in a single file, written atomically and fsynced before it is relied upon.
    ///
    /// `creationAsserted` is the one piece of evidence that cannot come from the node itself: only
    /// whatever CREATES a node knows it is new. It is consulted ONLY when no marker file exists —
    /// **an existing marker always wins** — so a stale assertion left in a config file is harmless
    /// across restarts, and matters only in the delete-and-recreate case named in the LIMITATION
    /// block above.
    static ParticipationMarker fileBacked(Path markerFile, boolean creationAsserted) {
        return FileBackedParticipationMarker.create(markerFile, creationAsserted);
    }
}
