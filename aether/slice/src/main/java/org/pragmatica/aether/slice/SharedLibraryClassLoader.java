// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.io.IOException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.dependency.ArtifactDependency;
import org.pragmatica.aether.slice.dependency.CompatibilityResult;
import org.pragmatica.aether.slice.dependency.VersionPattern;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;


@SuppressWarnings("JBCT-SEQ-01")
public class SharedLibraryClassLoader extends UncachedResourceClassLoader {
    private static final Logger log = LoggerFactory.getLogger(SharedLibraryClassLoader.class);

    private final Map<String, Version> loadedArtifacts = new ConcurrentHashMap<>();
    private final Map<String, String> loadedBy = new ConcurrentHashMap<>();

    public SharedLibraryClassLoader(ClassLoader parent) {
        super(new URL[0], parent);
    }

    public Option<CompatibilityResult> checkCompatibility(ArtifactDependency dependency) {
        return checkCompatibility(dependency.groupId(), dependency.artifactId(), dependency.versionPattern());
    }

    public Option<CompatibilityResult> checkCompatibility(String groupId, String artifactId, VersionPattern required) {
        var key = artifactKey(groupId, artifactId);

        return option(loadedArtifacts.get(key)).map(loadedVersion -> CompatibilityResult.check(loadedVersion, required));
    }

    /// #1184 — a second request for an already-held `groupId:artifactId` is a no-op only when it
    /// asks for the SAME version; a different version is refused with a cause naming both versions
    /// and both requesters. The held version never changes (first version wins), so the loader's
    /// state is the same as before #1184 — what changed is that the caller now learns about it
    /// instead of reading a WARN-and-success. This guard is reachable on the production path even
    /// after `checkCompatibility`, because the locate between check and add is asynchronous.
    public synchronized Result<Unit> addArtifact(String groupId,
                                                 String artifactId,
                                                 Version version,
                                                 URL jarUrl,
                                                 String requester) {
        var key = artifactKey(groupId, artifactId);
        var loaded = loadedArtifacts.get(key);

        if (loaded != null) {
            return refuseUnlessSameVersion(key, loaded, version, requester);
        }

        addURL(jarUrl);
        loadedArtifacts.put(key, version);
        loadedBy.put(key, requester);
        log.debug("Added shared artifact {}:{} from {} for {}", key, version.withQualifier(), jarUrl, requester);

        return Result.unitResult();
    }

    private Result<Unit> refuseUnlessSameVersion(String key, Version loaded, Version version, String requester) {
        if (loaded.equals(version)) {
            log.debug("Artifact {}:{} already loaded, nothing to add for {}", key, version.withQualifier(), requester);

            return Result.unitResult();
        }

        var conflict = new SliceLoadingFailure.Fatal.SharedLoaderVersionConflict(requester,
                                                                                 key + ":" + version.withQualifier(),
                                                                                 key + ":" + loaded.withQualifier(),
                                                                                 loadedBy.get(key));

        log.error(conflict.message());

        return conflict.result();
    }

    public synchronized Result<Unit> registerRuntimeProvided(String groupId,
                                                             String artifactId,
                                                             Version version,
                                                             String requester) {
        var key = artifactKey(groupId, artifactId);

        if (!loadedArtifacts.containsKey(key)) {
            loadedArtifacts.put(key, version);
            loadedBy.put(key, requester);
            log.debug("Registered runtime-provided artifact {}:{} for {}", key, version.withQualifier(), requester);
        }

        return Result.unitResult();
    }

    public boolean isLoaded(String groupId, String artifactId) {
        return loadedArtifacts.containsKey(artifactKey(groupId, artifactId));
    }

    public Option<Version> getLoadedVersion(String groupId, String artifactId) {
        return option(loadedArtifacts.get(artifactKey(groupId, artifactId)));
    }

    /// Who first loaded (or registered) the artifact — the requester a later conflict is reported
    /// against (#1184).
    public Option<String> loadedBy(String groupId, String artifactId) {
        return option(loadedBy.get(artifactKey(groupId, artifactId)));
    }

    public Map<String, Version> getLoadedArtifacts() {
        return Map.copyOf(loadedArtifacts);
    }

    @SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
    @Override
    public void close() throws IOException {
        loadedArtifacts.clear();
        loadedBy.clear();
        super.close();
    }

    private static String artifactKey(String groupId, String artifactId) {
        return groupId + ":" + artifactId;
    }
}
