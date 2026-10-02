// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.artifact;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactContentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactVersionsValue;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// The coordinate index of the built-in store (#1778): the versions of each artifact, as a grow-only set whose
/// entries carry the archived flag, and the first content digest bound to each file. The artifact bytes and per-version metadata live in the DHT; this index lives wherever writers can be
/// ordered. In a cluster that is the consensus KV plane, where the applier folds concurrent writers
/// ([ArtifactVersionsValue#mergeInto]), so concurrent publishes never lose a version. Both operations are
/// idempotent and only move an entry up `absent < present < archived`; there is no remove.
public interface ArtifactVersionIndex {
    /// Adds the artifact's version, leaving an archived entry archived.
    Promise<Unit> publish(Artifact artifact);
    /// Flags the artifact's version archived, adding it if absent.
    Promise<Unit> archive(Artifact artifact);
    /// Binds `digest` to the file's coordinate unless a digest is already bound, and answers the digest that IS bound:
    /// the FIRST one proposed, decided once for every node. Never rewritten or removed. A caller whose digest is not
    /// the answer lost the race for this coordinate and must not write its content.
    Promise<ArtifactContentValue> bindContent(ArtifactFile file, ArtifactContentValue digest);
    /// The versions that are present and not archived.
    Promise<List<Version>> versions(GroupId groupId, ArtifactId artifactId);
    /// Fails with [ArtifactStore.ArtifactStoreError.VersionLimitReached] when the artifact's version is NEW and the
    /// artifact already holds its maximum of present (not archived) versions; succeeds otherwise. An advisory check
    /// made before anything is uploaded; [#publish] enforces the bound itself and fails the same way.
    Promise<Unit> requireCapacity(Artifact artifact);
    /// Whether the artifact's version is flagged archived. In a cluster this is the consensus-committed flag, the
    /// authority a resolve obeys even when the DHT archive marker cannot be read from the replicas that answer.
    Promise<Boolean> isArchived(Artifact artifact);

    /// A process-local index with the cluster index's merge semantics. For single-process use and tests; a
    /// cluster node wires the consensus-backed one.
    static ArtifactVersionIndex inMemory() {
        return inMemory(ArtifactVersionsValue.DEFAULT_MAX_LIVE);
    }

    /// As [#inMemory()] with a bound on the present versions of one artifact.
    static ArtifactVersionIndex inMemory(int maxLive) {
        var sets = new ConcurrentHashMap<ArtifactBase, ArtifactVersionsValue>();
        var bindings = new ConcurrentHashMap<String, ArtifactContentValue>();

        return new ArtifactVersionIndex() {
            @Override
            public Promise<Unit> publish(Artifact artifact) {
                return write(sets,
                             artifact,
                             ArtifactVersionsValue.added(artifact.version().withQualifier(),
                                                         maxLive)).flatMap(_ -> requireRegistered(sets,
                                                                                                  artifact,
                                                                                                  maxLive));
            }

            @Override
            public Promise<Unit> requireCapacity(Artifact artifact) {
                var committed = sets.getOrDefault(ArtifactBase.artifactBase(artifact), ArtifactVersionsValue.empty());

                return committed.contains(artifact.version().withQualifier()) || committed.hasRoom(maxLive)
                       ? Promise.unitPromise()
                       : new ArtifactStore.ArtifactStoreError.VersionLimitReached(artifact, maxLive).promise();
            }

            @Override
            public Promise<Unit> archive(Artifact artifact) {
                return write(sets,
                             artifact,
                             ArtifactVersionsValue.archived(artifact.version().withQualifier()));
            }

            @Override
            public Promise<ArtifactContentValue> bindContent(ArtifactFile file, ArtifactContentValue digest) {
                return Promise.success(bindings.computeIfAbsent(file.asString(), _ -> digest));
            }

            @Override
            public Promise<List<Version>> versions(GroupId groupId, ArtifactId artifactId) {
                return Promise.success(ArtifactVersionIndex.parseVersions(sets.getOrDefault(new ArtifactBase(groupId,
                                                                                                             artifactId),
                                                                                            ArtifactVersionsValue.empty())
                                                                              .live()));
            }

            @Override
            public Promise<Boolean> isArchived(Artifact artifact) {
                return Promise.success(sets.getOrDefault(ArtifactBase.artifactBase(artifact),
                                                         ArtifactVersionsValue.empty())
                                           .isArchived(artifact.version().withQualifier()));
            }
        };
    }

    private static Promise<Unit> write(ConcurrentHashMap<ArtifactBase, ArtifactVersionsValue> sets,
                                       Artifact artifact,
                                       ArtifactVersionsValue incoming) {
        sets.merge(ArtifactBase.artifactBase(artifact), incoming, (committed, added) -> added.mergeInto(committed));

        return Promise.unitPromise();
    }

    private static Promise<Unit> requireRegistered(ConcurrentHashMap<ArtifactBase, ArtifactVersionsValue> sets,
                                                   Artifact artifact,
                                                   int maxLive) {
        return sets.get(ArtifactBase.artifactBase(artifact))
                   .contains(artifact.version().withQualifier())
               ? Promise.unitPromise()
               : new ArtifactStore.ArtifactStoreError.VersionLimitReached(artifact, maxLive).promise();
    }

    /// Version strings that no longer parse are skipped, as they always were.
    static List<Version> parseVersions(List<String> names) {
        return names.stream()
                    .map(Version::version)
                    .flatMap(result -> result.fold(_ -> java.util.stream.Stream.<Version> empty(),
                                                   java.util.stream.Stream::of))
                    .toList();
    }
}
