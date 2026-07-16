// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
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
public class SharedLibraryClassLoader extends URLClassLoader {
    private static final Logger log = LoggerFactory.getLogger(SharedLibraryClassLoader.class);

    private final Map<String, Version> loadedArtifacts = new ConcurrentHashMap<>();

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

    public synchronized Result<Unit> addArtifact(String groupId, String artifactId, Version version, URL jarUrl) {
        var key = artifactKey(groupId, artifactId);

        if (loadedArtifacts.containsKey(key)) {
            log.warn("Artifact {} already loaded with version {}, ignoring request to load version {}",
                     key,
                     loadedArtifacts.get(key).withQualifier(),
                     version.withQualifier());

            return Result.unitResult();
        }

        addURL(jarUrl);
        loadedArtifacts.put(key, version);
        log.debug("Added shared artifact {}:{} from {}", key, version.withQualifier(), jarUrl);

        return Result.unitResult();
    }

    public synchronized Result<Unit> registerRuntimeProvided(String groupId, String artifactId, Version version) {
        var key = artifactKey(groupId, artifactId);

        if (!loadedArtifacts.containsKey(key)) {
            loadedArtifacts.put(key, version);
            log.debug("Registered runtime-provided artifact {}:{}", key, version.withQualifier());
        }

        return Result.unitResult();
    }

    public boolean isLoaded(String groupId, String artifactId) {
        return loadedArtifacts.containsKey(artifactKey(groupId, artifactId));
    }

    public Option<Version> getLoadedVersion(String groupId, String artifactId) {
        return option(loadedArtifacts.get(artifactKey(groupId, artifactId)));
    }

    public Map<String, Version> getLoadedArtifacts() {
        return Map.copyOf(loadedArtifacts);
    }

    @SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
    @Override
    public void close() throws IOException {
        loadedArtifacts.clear();
        super.close();
    }

    private static String artifactKey(String groupId, String artifactId) {
        return groupId + ":" + artifactId;
    }
}
