// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.resource.artifact.ArtifactFile;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// Shared fixtures for the artifact-resolution tests (#1927): the causes a repository answers with, and an artifact
/// store that answers `resolve` with a fixed outcome.
public final class ResolutionStubs {
    private ResolutionStubs() {}

    /// What a repository answers when it ANSWERED and the artifact is not there (the real repositories type it so).
    public record NotHere(String where) implements org.pragmatica.aether.slice.repository.Repository.Absent {
        @Override
        public String message() {
            return "not here: " + where;
        }
    }

    /// A repository that could not answer.
    public static Cause unreachable() {
        return new SliceLoadingFailure.Intermittent.Timeout("repository locate", () -> "remote repository unreachable");
    }

    /// A location whose URL cannot be read: locating succeeded, fetching the bytes will not.
    public static Location unreadableLocation(Artifact artifact) {
        try {
            return new Location(artifact, URI.create("file:///nonexistent/resolution-stub.jar").toURL());
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ArtifactStore artifactStoreAnswering(Promise<byte[]> resolveAnswer) {
        return new ArtifactStore() {
            @Override public Promise<DeployResult> deploy(ArtifactFile file, byte[] content) {
                return new NotHere("store").promise();
            }

            @Override public Promise<byte[]> resolve(ArtifactFile file) {
                return resolveAnswer;
            }

            @Override public Promise<ResolvedArtifact> resolveWithMetadata(ArtifactFile file) {
                return new NotHere("store").promise();
            }

            @Override public Promise<Boolean> exists(ArtifactFile file) {
                return Promise.success(false);
            }

            @Override public Promise<Option<ArtifactMetadata>> metadata(ArtifactFile file) {
                return Promise.success(Option.none());
            }

            @Override public Promise<List<Version>> versions(GroupId groupId, ArtifactId artifactId) {
                return Promise.success(List.of());
            }

            @Override public Promise<Unit> archive(Artifact artifact) {
                return Promise.unitPromise();
            }

            @Override public Metrics metrics() {
                return new Metrics(0, 0, 0L);
            }
        };
    }
}
