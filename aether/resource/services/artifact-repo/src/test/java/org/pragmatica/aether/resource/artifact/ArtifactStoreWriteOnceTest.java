// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.artifact;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.resource.artifact.ArtifactStore.ArchivePolicy;
import org.pragmatica.aether.resource.artifact.ArtifactStore.ArtifactStoreError;
import org.pragmatica.aether.resource.artifact.ArtifactStore.DeployResult;
import org.pragmatica.dht.DHTError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1778: the built-in store is write-once and monotonic. Every test names the acceptance item it pins; the
/// mutation each one was shown RED under is recorded in the PR.
class ArtifactStoreWriteOnceTest {
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final ArchivePolicy SEVEN_DAYS = ArchivePolicy.archivePolicy(timeSpan(7).days());
    private static final byte[] CONTENT = "content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER = "other content".getBytes(StandardCharsets.UTF_8);
    private static final String VERSIONS_KEY = "artifacts/org.example/lib/versions";

    private final Artifact v1 = Artifact.artifact("org.example:lib:1.0.0").unwrap();
    private final Artifact v2 = Artifact.artifact("org.example:lib:2.0.0").unwrap();

    private ReplicatedTestDht dht;
    private AtomicLong now;
    private StorageInstance storage;
    private ArtifactStore store;

    @BeforeEach
    void setup() {
        dht = ReplicatedTestDht.single();
        now = new AtomicLong(1_000_000L);
        storage = StorageInstance.storageInstance("test-artifacts", List.of(MemoryTier.memoryTier(64 * 1024 * 1024)));
        store = newStore(dht);
    }

    private ArtifactStore newStore(ReplicatedTestDht backing) {
        return new ArtifactStoreImpl(backing, storage, SEVEN_DAYS, now::get);
    }

    private DeployResult deploy(Artifact artifact, byte[] content) {
        return store.deploy(artifact, content).await().onFailureRun(Assertions::fail).unwrap();
    }

    private static Cause failureOf(Promise<?> promise) {
        var failure = new AtomicReference<Cause>();

        promise.await().onSuccessRun(Assertions::fail).onFailure(failure::set);

        return failure.get();
    }

    private List<String> listedVersions() {
        return store.versions(v1.groupId(), v1.artifactId())
                    .await()
                    .onFailureRun(Assertions::fail)
                    .unwrap()
                    .stream()
                    .map(v -> v.withQualifier())
                    .sorted()
                    .toList();
    }

    @Nested
    class WriteOnceContent {
        @Test
        void deploy_refusesWithBothDigests_whenContentDiffers() {
            var first = deploy(v1, CONTENT);

            var cause = failureOf(store.deploy(v1, OTHER));

            assertThat(cause).isInstanceOf(ArtifactStoreError.ContentConflict.class);

            var conflict = (ArtifactStoreError.ContentConflict) cause;

            assertThat(conflict.storedSha1()).isEqualTo(first.sha1());
            assertThat(conflict.offeredSha1()).isNotEqualTo(first.sha1()).hasSize(40);
            assertThat(conflict.message()).contains(conflict.storedSha1()).contains(conflict.offeredSha1());
        }

        @Test
        void deploy_keepsTheStoredBytesAndMetadata_whenDifferentContentIsRefused() {
            deploy(v1, CONTENT);
            var metaBefore = dht.union().get("artifacts/org.example/lib/1.0.0/jar/meta").clone();

            failureOf(store.deploy(v1, OTHER));

            assertThat(dht.union().get("artifacts/org.example/lib/1.0.0/jar/meta")).isEqualTo(metaBefore);
            assertThat(store.resolve(v1).await().onFailureRun(Assertions::fail).unwrap()).isEqualTo(CONTENT);
        }

        @Test
        void deploy_isIdempotent_whenContentIsIdentical() {
            var first = deploy(v1, CONTENT);
            var metaBefore = dht.union().get("artifacts/org.example/lib/1.0.0/jar/meta").clone();
            now.addAndGet(DAY);

            var again = deploy(v1, CONTENT);

            assertThat(first.alreadyPresent()).isFalse();
            assertThat(again.alreadyPresent()).isTrue();
            assertThat(again.sha1()).isEqualTo(first.sha1());
            assertThat(dht.union().get("artifacts/org.example/lib/1.0.0/jar/meta")).as("not rewritten")
                                                                                   .isEqualTo(metaBefore);
            assertThat(listedVersions()).containsExactly("1.0.0");
        }

        @Test
        void deploy_comparesEachFileOfAVersionOnItsOwn() {
            var pom = ArtifactFile.artifactFile(v1, "", "pom");

            deploy(v1, CONTENT);

            assertThat(store.deploy(pom, OTHER).await().onFailureRun(Assertions::fail).unwrap().alreadyPresent())
                .as("the pom is a different file, so different bytes are not a conflict").isFalse();
        }

        @Test
        void deploy_failsWithoutWriting_whenTheExistenceCheckFailsTransiently() {
            dht.getFailure = key -> key.endsWith("/jar/meta")
                                    ? Option.<Cause> some(DHTError.quorumNotReached(2, 1))
                                    : Option.none();

            var cause = failureOf(store.deploy(v1, CONTENT));

            assertThat(cause.isTransient()).as("answered as retryable, never as absent").isTrue();
            assertThat(dht.puts).as("a failed check must not overwrite stored content (#1795)").isEmpty();
        }

        @Test
        void deploy_reassertsRegistration_whenAnIdenticalRePutFollowsAPartialFirstDeploy() {
            dht.putFailure = key -> key.equals(VERSIONS_KEY)
                                    ? Option.<Cause> some(DHTError.NO_AVAILABLE_NODES)
                                    : Option.none();
            failureOf(store.deploy(v1, CONTENT));
            dht.putFailure = _ -> Option.none();

            assertThat(listedVersions()).as("the first deploy died before listing the version").isEmpty();

            var retry = deploy(v1, CONTENT);

            assertThat(retry.alreadyPresent()).isTrue();
            assertThat(listedVersions()).as("the identical re-put completes the registration").containsExactly("1.0.0");
        }
    }

    @Nested
    class NoSnapshot {
        @Test
        void deploy_refusesSnapshotVersions_withoutTouchingTheDht() {
            for (var coordinates : List.of("org.example:lib:1.0.0-SNAPSHOT",
                                           "org.example:lib:1.0.0-rc4-SNAPSHOT",
                                           "org.example:lib:2.0.0-snapshot")) {
                var cause = failureOf(store.deploy(Artifact.artifact(coordinates).unwrap(), CONTENT));

                assertThat(cause).as(coordinates).isInstanceOf(ArtifactStoreError.SnapshotRefused.class);
                assertThat(cause.message()).contains("SNAPSHOT").contains("Local repository");
            }

            assertThat(dht.puts).as("refused before any write").isEmpty();
            assertThat(dht.union()).isEmpty();
        }

        @Test
        void deploy_acceptsAQualifierThatOnlyLooksLikeAReleaseCandidate() {
            deploy(Artifact.artifact("org.example:lib:1.0.0-rc4").unwrap(), CONTENT);

            assertThat(listedVersions()).containsExactly("1.0.0-rc4");
        }
    }

    @Nested
    class Archive {
        @Test
        void archive_refusesAVersionYoungerThanTheRetention() {
            deploy(v1, CONTENT);
            now.addAndGet(7 * DAY - 1);

            var cause = failureOf(store.archive(v1));

            assertThat(cause).isInstanceOf(ArtifactStoreError.RetentionNotElapsed.class);
            assertThat(store.resolve(v1).await().isSuccess()).as("still resolves").isTrue();
        }

        @Test
        void archive_honoursANonDefaultRetention() {
            var oneHour = new ArtifactStoreImpl(dht, storage, ArchivePolicy.archivePolicy(timeSpan(1).hours()), now::get);
            oneHour.deploy(v1, CONTENT).await().onFailureRun(Assertions::fail);
            now.addAndGet(60L * 60 * 1000 - 1);

            assertThat(failureOf(oneHour.archive(v1))).as("one millisecond short of the configured hour")
                                                      .isInstanceOf(ArtifactStoreError.RetentionNotElapsed.class);

            now.addAndGet(1);

            oneHour.archive(v1).await().onFailureRun(Assertions::fail);

            assertThat(failureOf(oneHour.resolve(v1))).isInstanceOf(ArtifactStoreError.Archived.class);
        }

        @Test
        void archive_failsForAVersionNeverWritten() {
            assertThat(failureOf(store.archive(v1))).isInstanceOf(ArtifactStoreError.VersionNotFound.class);
            assertThat(dht.puts).isEmpty();
        }

        @Test
        void archive_makesTheVersionUnavailableButKeepsEveryKey() {
            deploy(v1, CONTENT);
            store.deploy(ArtifactFile.artifactFile(v1, "", "pom"), OTHER).await().onFailureRun(Assertions::fail);
            var keysBefore = new ArrayList<>(dht.union().keySet());
            now.addAndGet(7 * DAY);

            store.archive(v1).await().onFailureRun(Assertions::fail);

            assertThat(failureOf(store.resolve(v1))).isInstanceOf(ArtifactStoreError.Archived.class);
            assertThat(failureOf(store.resolve(ArtifactFile.artifactFile(v1, "", "pom"))))
                .as("archive is version-wide").isInstanceOf(ArtifactStoreError.Archived.class);
            assertThat(store.exists(v1).await().unwrap()).isFalse();
            assertThat(listedVersions()).as("delisted").isEmpty();
            assertThat(dht.union().keySet()).as("every key is kept; absent still means never written")
                                            .containsAll(keysBefore)
                                            .contains("artifacts/org.example/lib/1.0.0/archived");
            assertThat(store.metadata(v1).await().unwrap().isPresent()).as("the key itself is kept").isTrue();
        }

        @Test
        void archive_leavesOtherVersionsAlone() {
            deploy(v1, CONTENT);
            deploy(v2, CONTENT);
            now.addAndGet(7 * DAY);

            store.archive(v1).await().onFailureRun(Assertions::fail);

            assertThat(listedVersions()).containsExactly("2.0.0");
            assertThat(store.resolve(v2).await().isSuccess()).isTrue();
        }

        @Test
        void archive_isIdempotent_andWritesTheMarkerOnce() {
            deploy(v1, CONTENT);
            now.addAndGet(7 * DAY);
            store.archive(v1).await().onFailureRun(Assertions::fail);
            var marker = dht.union().get("artifacts/org.example/lib/1.0.0/archived").clone();
            now.addAndGet(DAY);

            store.archive(v1).await().onFailureRun(Assertions::fail);

            assertThat(dht.union().get("artifacts/org.example/lib/1.0.0/archived")).as("never rewritten")
                                                                                   .isEqualTo(marker);
            assertThat(listedVersions()).isEmpty();
        }

        @Test
        void archive_completesAnInterruptedArchive_onRerun() {
            deploy(v1, CONTENT);
            now.addAndGet(7 * DAY);
            dht.putFailure = key -> key.equals(VERSIONS_KEY)
                                    ? Option.<Cause> some(DHTError.NO_AVAILABLE_NODES)
                                    : Option.none();
            failureOf(store.archive(v1));
            dht.putFailure = _ -> Option.none();

            assertThat(failureOf(store.resolve(v1))).as("the marker landed first, so reads already refuse")
                                                    .isInstanceOf(ArtifactStoreError.Archived.class);
            assertThat(listedVersions()).as("only the listing flag is missing").containsExactly("1.0.0");

            store.archive(v1).await().onFailureRun(Assertions::fail);

            assertThat(listedVersions()).isEmpty();
        }
    }

    @Nested
    class NeverResurrected {
        @BeforeEach
        void archiveV1() {
            deploy(v1, CONTENT);
            now.addAndGet(7 * DAY);
            store.archive(v1).await().onFailureRun(Assertions::fail);
        }

        @Test
        void deploy_refusesAnArchivedVersion_whetherTheContentIsIdenticalOrNot() {
            assertThat(failureOf(store.deploy(v1, CONTENT))).as("an identical re-put is not a way back")
                                                            .isInstanceOf(ArtifactStoreError.Archived.class);
            assertThat(failureOf(store.deploy(v1, OTHER))).isInstanceOf(ArtifactStoreError.Archived.class);
            assertThat(failureOf(store.deploy(ArtifactFile.artifactFile(v1, "sources", "jar"), CONTENT)))
                .as("nor does an archived version take new files").isInstanceOf(ArtifactStoreError.Archived.class);
            assertThat(listedVersions()).isEmpty();
        }

        @Test
        void store_neverRemovesOrRewritesAKey_acrossDeployArchiveAndRedeploy() {
            failureOf(store.deploy(v1, CONTENT));
            store.archive(v1).await().onFailureRun(Assertions::fail);

            assertThat(dht.removes).as("archive replaces delete: no key is ever removed").isEmpty();
            assertThat(dht.puts.stream().filter(key -> key.endsWith("/archived")).count())
                .as("the marker is written exactly once").isEqualTo(1);
            assertThat(dht.puts.stream().filter(key -> key.endsWith("/jar/meta")).count())
                .as("the metadata is written exactly once").isEqualTo(1);
        }

        @Test
        void resolve_staysArchived_whenOneReplicaMissedTheArchiveWrite() {
            // Three replicas, read quorum two. The archive marker never reached replica 2.
            var replicated = new ReplicatedTestDht(3, 2);
            var local = new ArtifactStoreImpl(replicated, storage, SEVEN_DAYS, now::get);

            local.deploy(v2, CONTENT).await().onFailureRun(Assertions::fail);
            now.addAndGet(7 * DAY);
            replicated.writeUnreachable = List.of(2);
            local.archive(v2).await().onFailureRun(Assertions::fail);

            assertThat(replicated.replicas.get(2)).as("replica 2 missed the archive")
                                                  .doesNotContainKey("artifacts/org.example/lib/2.0.0/archived");

            for (var order : List.of(List.of(2, 0, 1), List.of(2, 1, 0), List.of(0, 2, 1), List.of(1, 2, 0))) {
                replicated.readOrder = order;

                assertThat(failureOf(local.resolve(v2))).as("read order " + order)
                                                        .isInstanceOf(ArtifactStoreError.Archived.class);
            }
        }

        @Test
        void resolve_staysArchived_whenARepairReplaysAStaleVersionsList() {
            // A repair copies a pre-archive replica value back: the versions list loses its archived flag.
            dht.replicas.getFirst().put(VERSIONS_KEY, "1.0.0".getBytes(StandardCharsets.UTF_8));

            assertThat(failureOf(store.resolve(v1))).as("reads obey the marker, not the list flag")
                                                    .isInstanceOf(ArtifactStoreError.Archived.class);
            assertThat(failureOf(store.deploy(v1, CONTENT))).isInstanceOf(ArtifactStoreError.Archived.class);

            store.archive(v1).await().onFailureRun(Assertions::fail);

            assertThat(listedVersions()).as("re-running the archive restores the listing flag").isEmpty();
        }

        @Test
        void publishingAnotherVersion_doesNotUnflagTheArchivedOne() {
            deploy(v2, CONTENT);

            assertThat(listedVersions()).containsExactly("2.0.0");
            assertThat(new String(dht.union().get(VERSIONS_KEY), StandardCharsets.UTF_8)).isEqualTo("1.0.0!,2.0.0");
        }
    }

    @Nested
    class GrowOnlyVersions {
        @Test
        void versions_keepsEveryVersion_whenPublishedConcurrentlyThroughOneNode() {
            // The first read of the versions key is held back, so without per-key sequencing a second
            // publish would read the same (empty) list and one of the two writes would erase the other.
            dht.delayFirstGetOf = key -> key.equals(VERSIONS_KEY);
            dht.delayMillis = 300;

            var first = store.deploy(v1, CONTENT);
            var second = store.deploy(v2, CONTENT);

            first.await().onFailureRun(Assertions::fail);
            second.await().onFailureRun(Assertions::fail);

            assertThat(listedVersions()).containsExactly("1.0.0", "2.0.0");
        }

        @Test
        void versions_keepsEveryVersion_whenManyArePublishedAtOnce() {
            var publishes = new ArrayList<Promise<DeployResult>>();

            for (var i = 1; i <= 12; i++) {
                publishes.add(store.deploy(Artifact.artifact("org.example:lib:" + i + ".0.0").unwrap(), CONTENT));
            }

            publishes.forEach(p -> p.await().onFailureRun(Assertions::fail));

            assertThat(listedVersions()).hasSize(12);
        }

        @Test
        void archive_keepsItsFlag_whenAnotherVersionIsPublishedConcurrently() {
            deploy(v1, CONTENT);
            now.addAndGet(7 * DAY);
            dht.delayFirstGetOf = key -> key.equals(VERSIONS_KEY);
            dht.delayMillis = 300;

            var archive = store.archive(v1);
            var publish = store.deploy(v2, CONTENT);

            archive.await().onFailureRun(Assertions::fail);
            publish.await().onFailureRun(Assertions::fail);

            assertThat(listedVersions()).as("v1 stays archived, v2 stays listed").containsExactly("2.0.0");
        }

        /// KNOWN LIMIT, pinned as an ENABLED tripwire (#1778). Two NODES rewriting one versions list at once
        /// can overwrite each other: the DHT has no conditional put, so the sequencing above is per node.
        /// When the DHT gains one this test goes RED on purpose — delete it and enable the pair below.
        @Test
        void versions_losesAVersion_whenTwoNodesPublishAtOnce_KNOWN_LIMIT() {
            var shared = ReplicatedTestDht.single();
            shared.rendezvousOn = key -> key.equals(VERSIONS_KEY);
            shared.rendezvousParties = 2;
            var nodeA = newStore(shared);
            var nodeB = newStore(shared);

            var a = nodeA.deploy(v1, CONTENT);
            var b = nodeB.deploy(v2, CONTENT);

            a.await().onFailureRun(Assertions::fail);
            b.await().onFailureRun(Assertions::fail);

            assertThat(nodeA.versions(v1.groupId(), v1.artifactId()).await().unwrap())
                .as("the DHT now keeps both: delete this tripwire and enable the Disabled pair")
                .hasSize(1);
        }

        @Disabled("needs a DHT conditional put or server-side merge (#1778 follow-up); see the tripwire above")
        @Test
        void versions_keepsEveryVersion_whenTwoNodesPublishAtOnce() {
            var shared = ReplicatedTestDht.single();
            shared.rendezvousOn = key -> key.equals(VERSIONS_KEY);
            shared.rendezvousParties = 2;
            var nodeA = newStore(shared);
            var nodeB = newStore(shared);

            var a = nodeA.deploy(v1, CONTENT);
            var b = nodeB.deploy(v2, CONTENT);

            a.await().onFailureRun(Assertions::fail);
            b.await().onFailureRun(Assertions::fail);

            assertThat(nodeA.versions(v1.groupId(), v1.artifactId()).await().unwrap()).hasSize(2);
        }
    }

    @Nested
    class GrowOnlySetTests {
        private final GrowOnlySet live = GrowOnlySet.empty().add("1.0.0").add("2.0.0");
        private final GrowOnlySet archived = GrowOnlySet.empty().archive("1.0.0").add("3.0.0");

        @Test
        void merge_takesTheHigherStatePerEntry_inEitherOrder() {
            var expected = "1.0.0!,2.0.0,3.0.0";

            assertThat(new String(live.merge(archived).toBytes(), StandardCharsets.UTF_8)).isEqualTo(expected);
            assertThat(new String(archived.merge(live).toBytes(), StandardCharsets.UTF_8)).isEqualTo(expected);
        }

        @Test
        void merge_isIdempotentAndAssociative() {
            var other = GrowOnlySet.empty().add("4.0.0").archive("2.0.0");

            assertThat(live.merge(live)).isEqualTo(live);
            assertThat(live.merge(archived).merge(other)).isEqualTo(live.merge(archived.merge(other)));
        }

        @Test
        void add_neverClearsTheArchivedFlag() {
            assertThat(archived.add("1.0.0").isArchived("1.0.0")).isTrue();
            assertThat(archived.merge(GrowOnlySet.empty().add("1.0.0")).isArchived("1.0.0")).isTrue();
        }

        @Test
        void parse_roundTripsTheSerializedForm_andIgnoresEmptyTokens() {
            var set = live.merge(archived);

            assertThat(GrowOnlySet.growOnlySet(set.toBytes())).isEqualTo(set);
            assertThat(GrowOnlySet.growOnlySet(",1.0.0,,".getBytes(StandardCharsets.UTF_8)).live()).containsExactly("1.0.0");
            assertThat(set.live()).containsExactly("2.0.0", "3.0.0");
        }
    }
}
