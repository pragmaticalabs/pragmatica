// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.consensus.rabia;

import java.util.List;

import org.pragmatica.lang.Option;


/// Immutable operational observation, never a source of quorum authority.
///
/// `effectiveSlot` is the first slot the installed epoch governs (R+1 for a change agreed at R); it is
/// empty for the genesis epoch and when this node adopted the epoch from a snapshot instead of applying
/// the change itself. `awaitingCatchUp` lists members added by the last applied change from which this
/// node has not yet accepted a ballot past R. Failure is empty when none is recorded.
public record VoterReconfigurationStatus(String stage,
                                         Option<Long> installedEpoch,
                                         List<String> installedVoters,
                                         List<String> targetVoters,
                                         Option<Long> effectiveSlot,
                                         List<String> awaitingCatchUp,
                                         String failure) {
    public static VoterReconfigurationStatus unavailable() {
        return new VoterReconfigurationStatus("UNAVAILABLE",
                                              Option.none(),
                                              List.of(),
                                              List.of(),
                                              Option.none(),
                                              List.of(),
                                              "");
    }

    public VoterReconfigurationStatus {
        installedVoters = List.copyOf(installedVoters);
        targetVoters = List.copyOf(targetVoters);
        awaitingCatchUp = List.copyOf(awaitingCatchUp);
    }
}
