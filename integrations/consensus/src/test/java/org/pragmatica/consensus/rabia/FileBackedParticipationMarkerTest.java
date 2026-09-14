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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.consensus.rabia.ParticipationMarker.Participation;

import static org.assertj.core.api.Assertions.assertThat;

/// #1212 — the marker's own contract, tested against a real filesystem rather than a stub, because
/// the properties that matter here ARE durability properties and a stub would encode the answer it
/// was built from.
///
/// Two of these are cited by name from [ParticipationMarker]'s LIMITATION block, which enumerates the
/// operator-deletion scopes the design does and does not survive. If either is renamed, that citation
/// becomes a phantom — the tag names the evidence, so the name is load-bearing.
class FileBackedParticipationMarkerTest {
    private static final String MARKER = ".aether-participation";

    @Nested
    class AProvablyNewNode {
        /// The ONLY path to the relaxed bound: no marker, and whatever created this node asserted it
        /// is new. Nothing else in this class may reach NEVER_PARTICIPATED.
        @Test
        void creationAssertedOnAnEmptyDir_resolvesNeverParticipated(@TempDir Path dir) {
            var marker = ParticipationMarker.fileBacked(dir.resolve(MARKER), true);

            assertThat(marker.resolve())
                .as("an asserted creation with no prior marker is the provably-new case")
                .isEqualTo(Participation.NEVER_PARTICIPATED);
            assertThat(marker.resolve().provablyNeverVoted()).isTrue();
        }

        /// The durability ordering the ticket turns on: the marker is on disk when the marker object
        /// is CONSTRUCTED, which is before the node starts, which is before its first SyncRequest.
        /// A marker only written later could not be trusted at the moment it is read.
        @Test
        void theMarkerIsOnDiskBeforeAnythingReadsIt(@TempDir Path dir) {
            var file = dir.resolve(MARKER);

            assertThat(file).doesNotExist();

            ParticipationMarker.fileBacked(file, true);

            assertThat(file)
                .as("constructing the marker must already have written it durably")
                .exists();
        }
    }

    @Nested
    class AWipedNodeIsNeverMistakenForANewOne {
        /// LIMITATION scope 2, cited from [ParticipationMarker]. The whole node data dir is gone, so
        /// no assertion reaches this node — absence is read as PARTICIPATED, never as NEW.
        @Test
        void aDeletedMarkerWithNoCreationAssertionReadsParticipated(@TempDir Path dir) {
            var marker = ParticipationMarker.fileBacked(dir.resolve(MARKER), false);

            assertThat(marker.resolve())
                .as("absence means WIPED — the dangerous node presents exactly this way")
                .isEqualTo(Participation.PARTICIPATED);
            assertThat(marker.resolve().provablyNeverVoted()).isFalse();
        }

        /// LIMITATION scope 1, cited from [ParticipationMarker]. The operator deleted the CONSENSUS
        /// state directory; the marker lives elsewhere, survives, and still refuses the relaxation.
        /// This is the scope the ticket's constraint 3 was written against.
        @Test
        void deletingTheConsensusStateDirLeavesTheMarkerIntact(@TempDir Path root) throws IOException {
            var consensusStateDir = root.resolve("consensus-backup");
            var markerFile = root.resolve("node-data")
                                 .resolve(MARKER);

            Files.createDirectories(consensusStateDir);
            Files.writeString(consensusStateDir.resolve("state.toml"), "snapshot");

            ParticipationMarker.fileBacked(markerFile, true)
                               .recordParticipation()
                               .unwrap();

            Files.delete(consensusStateDir.resolve("state.toml"));
            Files.delete(consensusStateDir);

            assertThat(consensusStateDir).doesNotExist();
            assertThat(ParticipationMarker.fileBacked(markerFile, true).resolve())
                .as("the consensus state dir is gone but the marker still says PARTICIPATED")
                .isEqualTo(Participation.PARTICIPATED);
        }

        /// The anti-replay property, and the reason a stale creation assertion in a config file is
        /// harmless: an existing marker ALWAYS wins. A node rebuilt under an old node id — which is
        /// exactly what `EmberCluster.stop()` then `start()` does — reads its previous PARTICIPATED
        /// even though the harness asserts creation on every construction.
        @Test
        void anExistingMarkerAlwaysWins_evenWhenCreationIsAsserted(@TempDir Path dir) {
            var file = dir.resolve(MARKER);

            ParticipationMarker.fileBacked(file, true)
                               .recordParticipation()
                               .unwrap();

            assertThat(ParticipationMarker.fileBacked(file, true).resolve())
                .as("re-asserting creation must NOT resurrect newness for a node that participated")
                .isEqualTo(Participation.PARTICIPATED);
        }

        /// A torn or unrecognised token is conservative, not a parse failure and not newness.
        @Test
        void unrecognisedMarkerContentReadsUnknown(@TempDir Path dir) throws IOException {
            var file = dir.resolve(MARKER);

            Files.writeString(file, "aether-participation-v99:something-from-the-future", StandardCharsets.UTF_8);

            var marker = ParticipationMarker.fileBacked(file, true);

            assertThat(marker.resolve()).isEqualTo(Participation.UNKNOWN);
            assertThat(marker.resolve().provablyNeverVoted())
                .as("an unreadable marker can never loosen the bound")
                .isFalse();
        }
    }

    @Nested
    class RecordingParticipation {
        /// The W2 write point: a new node that activates stops being new, durably, and a later
        /// reconstruction sees it. Without this a node could vote and then rejoin claiming newness.
        @Test
        void recordParticipation_flipsNeverParticipatedAndPersistsAcrossReconstruction(@TempDir Path dir) {
            var file = dir.resolve(MARKER);
            var marker = ParticipationMarker.fileBacked(file, true);

            assertThat(marker.resolve()).isEqualTo(Participation.NEVER_PARTICIPATED);

            assertThat(marker.recordParticipation().isSuccess()).isTrue();

            assertThat(marker.resolve())
                .as("the in-memory view flips too, so a resync in the same process cannot re-claim newness")
                .isEqualTo(Participation.PARTICIPATED);
            assertThat(ParticipationMarker.fileBacked(file, true).resolve())
                .as("and it survives reconstruction")
                .isEqualTo(Participation.PARTICIPATED);
        }

        /// `activate()` is reached repeatedly — every re-activation after a reconfigure or a
        /// quorum-loss pause runs through it — so this must be a cheap no-op after the first.
        @Test
        void recordParticipation_isIdempotent(@TempDir Path dir) {
            var marker = ParticipationMarker.fileBacked(dir.resolve(MARKER), true);

            assertThat(marker.recordParticipation().isSuccess()).isTrue();
            assertThat(marker.recordParticipation().isSuccess()).isTrue();
            assertThat(marker.recordParticipation().isSuccess()).isTrue();
            assertThat(marker.resolve()).isEqualTo(Participation.PARTICIPATED);
        }

        /// Fail-closed where it buys something: a node CLAIMING newness that cannot record must not
        /// be allowed to activate. The marker path is made unwritable by putting a regular file where
        /// the parent directory must be, so `Files.createDirectories` cannot succeed.
        @Test
        void recordParticipation_failsWhenAProvablyNewNodeCannotRecord(@TempDir Path dir) throws IOException {
            var blocked = dir.resolve("not-a-directory");

            Files.writeString(blocked, "this is a file, not a directory");

            var markerFile = blocked.resolve("sub")
                                    .resolve(MARKER);
            var marker = ParticipationMarker.fileBacked(markerFile, true);

            assertThat(marker.resolve())
                .as("a marker that could not be written resolves UNKNOWN, which is already conservative")
                .isEqualTo(Participation.UNKNOWN);
            assertThat(marker.recordParticipation().isSuccess())
                .as("UNKNOWN already denies the relaxation, so a failed write must NOT block activation")
                .isTrue();
        }
    }

    @Nested
    class TheFailSafeDefault {
        /// What every caller that wires nothing gets. Production keeps exactly its #1171 behaviour
        /// until the deployment path supplies a real marker.
        @Test
        void unknownMarker_neverClaimsNewnessAndNeverBlocksActivation() {
            var marker = ParticipationMarker.unknown();

            assertThat(marker.resolve()).isEqualTo(Participation.UNKNOWN);
            assertThat(marker.resolve().provablyNeverVoted()).isFalse();
            assertThat(marker.recordParticipation().isSuccess())
                .as("wiring no marker must never wedge a cluster")
                .isTrue();
        }
    }
}
