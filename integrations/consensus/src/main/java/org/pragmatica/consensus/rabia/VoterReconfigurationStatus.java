// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.consensus.rabia;

import java.util.List;

import org.pragmatica.lang.Option;

/// Immutable operational observation, never a source of quorum authority.
/// Epoch and barrier slot are absent when unavailable. Witness counts describe certified evidence;
/// unpersisted acknowledgements are deliberately excluded. Failure is empty when none is recorded.
public record VoterReconfigurationStatus(String stage,
                                        Option<Long> installedEpoch,
                                        List<String> installedVoters,
                                        List<String> targetVoters,
                                        Option<Long> barrierSlot,
                                        int certifiedCheckpointWitnesses,
                                        int certifiedInstallationWitnesses,
                                        String failure) {
    public static VoterReconfigurationStatus unavailable() {
        return new VoterReconfigurationStatus("UNAVAILABLE", Option.none(), List.of(), List.of(), Option.none(), 0, 0, "");
    }

    public VoterReconfigurationStatus {
        installedVoters = List.copyOf(installedVoters);
        targetVoters = List.copyOf(targetVoters);
    }
}
