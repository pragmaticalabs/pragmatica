// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.dependency;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.SliceClassLoader;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.StreamError;
import org.pragmatica.lang.io.StreamOps;
import org.pragmatica.lang.utils.Causes;


import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-LAM-01", "JBCT-LAM-02", "JBCT-UTIL-02", "JBCT-PAT-01", "JBCT-RET-05", "JBCT-NAM-01"})
public record DependencyFile(List<ArtifactDependency> shared,
                             List<ArtifactDependency> infra,
                             List<ArtifactDependency> slices) {
    private enum Section {
        NONE,
        SHARED,
        INFRA,
        SLICES
    }

    public static Result<DependencyFile> dependencyFile(String content) {
        var shared = new ArrayList<ArtifactDependency>();
        var infra = new ArrayList<ArtifactDependency>();
        var slices = new ArrayList<ArtifactDependency>();
        var currentSection = Section.NONE;
        var lines = content.split("\n");

        for (var line : lines) {
            var trimmed = line.trim();

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            if (trimmed.equals("[shared]")) {
                currentSection = Section.SHARED;
                continue;
            }

            if (trimmed.equals("[infra]")) {
                currentSection = Section.INFRA;
                continue;
            }

            if (trimmed.equals("[slices]")) {
                currentSection = Section.SLICES;
                continue;
            }

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                return UNKNOWN_SECTION.apply(trimmed).result();
            }

            var parseResult = ArtifactDependency.artifactDependency(trimmed);
            final var sectionRef = currentSection;
            var errorHolder = new AtomicReference<Cause>();
            var skipFlag = new AtomicBoolean(false);

            parseResult.onSuccess(dependency -> {
                                      switch (sectionRef) {
                case SHARED -> shared.add(dependency);
                case INFRA -> infra.add(dependency);
                case SLICES, NONE -> slices.add(dependency);
            }
                                  })
                       .onFailure(cause -> {
                                      if (cause == ArtifactDependency.EMPTY_LINE || cause == ArtifactDependency.COMMENT_LINE || cause == ArtifactDependency.SECTION_HEADER) {
                                      skipFlag.set(true);
                                  } else {
                                      errorHolder.set(cause);
                                  }
                                  });
            if (skipFlag.get()) {
                continue;
            }

            if (errorHolder.get() != null) {
                return errorHolder.get()
                                  .result();
            }
        }

        var result = new DependencyFile(List.copyOf(shared), List.copyOf(infra), List.copyOf(slices));

        return result.validateNoFrameworkDependencies();
    }

    private Result<DependencyFile> validateNoFrameworkDependencies() {
        return findFrameworkDependency().fold(() -> success(this),
                                              dep -> FRAMEWORK_DEPENDENCY_ERROR.apply(dep).result());
    }

    private Option<String> findFrameworkDependency() {
        for (var dep : shared) {
            if (isFrameworkArtifact(dep)) {
                return some("[shared] " + dep.asString());
            }
        }

        for (var dep : infra) {
            if (isFrameworkArtifact(dep)) {
                return some("[infra] " + dep.asString());
            }
        }

        return none();
    }

    private static boolean isFrameworkArtifact(ArtifactDependency dep) {
        return AETHER_GROUP.equals(dep.groupId()) && FRAMEWORK_ARTIFACTS.contains(dep.artifactId());
    }

    private static final String AETHER_GROUP = "org.pragmatica-lite.aether";

    private static final Set<String> FRAMEWORK_ARTIFACTS = Set.of("slice-api", "infra-api", "slice-annotations");

    private static final String DEPENDENCIES_DIR = "META-INF/dependencies/";
    private static final DependencyFile EMPTY = new DependencyFile(List.of(), List.of(), List.of());

    private static final Fn1<Cause, String> FRAMEWORK_DEPENDENCY_ERROR = Causes.forOneValue("Slice incorrectly packaged: framework dependency declared in %s. "
                                                                                           + "slice-api, infra-api, and slice-annotations are provided by the runtime and must not be declared as dependencies");

    public static Result<DependencyFile> dependencyFile(InputStream inputStream) {
        return StreamOps.readString(inputStream).flatMap(DependencyFile::dependencyFile);
    }

    /// Read the slice's dependency file through `classLoader`. An ABSENT file is the dependency-free slice
    /// and answers [#EMPTY]. A file that is PRESENT but cannot be read or parsed REFUSES the load with
    /// [DependencyFileError.Unreadable] naming the jar and the underlying error (#1372): loading the slice
    /// as dependency-free would start it without its declared dependencies and fail further from the cause.
    public static Result<DependencyFile> load(String sliceClassName, ClassLoader classLoader) {
        var resource = DEPENDENCIES_DIR + sliceClassName;

        return StreamOps.readResource(classLoader, resource)
                        .flatMap(DependencyFile::dependencyFile)
                        .fold(cause -> emptyOnlyIfAbsent(resource, classLoader, cause), Result::success);
    }

    private static Result<DependencyFile> emptyOnlyIfAbsent(String resource, ClassLoader classLoader, Cause cause) {
        return cause instanceof StreamError.ResourceNotFound
               ? success(EMPTY)
               : new DependencyFileError.Unreadable(resource, jarOf(classLoader), cause).result();
    }

    public sealed interface DependencyFileError extends Cause {
        /// The dependency file exists in the jar but could not be read or parsed; `origin` is the read or
        /// parse error, so the message names the offending line.
        record Unreadable(String resource, String jar, Cause origin) implements DependencyFileError, Cause.Wrapped {
            @Override
            public String message() {
                return "Dependency file " + resource + " in " + jar
                     + " is present but cannot be read; refusing to load the slice: " + origin.message();
            }
        }
    }

    private static String jarOf(ClassLoader classLoader) {
        return classLoader instanceof SliceClassLoader slice
               ? slice.sliceJarUrl()
                      .map(URL::toString)
                      .or("<loader without a jar url>")
               : classLoader.toString();
    }

    /// Read the dependency file straight from the slice jar at `jarUrl`, through a throwaway
    /// [SliceClassLoader] over that jar alone that is CLOSED before this returns (#1357). The loader
    /// serves exactly this one resource read; left open, it holds the jar's file handle until it is
    /// garbage-collected. The resource is read fully inside [#load], so nothing outlives the close.
    public static Result<DependencyFile> loadFromJar(String sliceClassName, URL jarUrl, ClassLoader parent) {
        return loadClosing(sliceClassName, new SliceClassLoader(new URL[]{jarUrl}, parent));
    }

    /// The closing half of [#loadFromJar], separable so a test can hand in a loader that records its
    /// own close. A failed close is a failed load.
    static Result<DependencyFile> loadClosing(String sliceClassName, SliceClassLoader loader) {
        return Result.lift(Causes::fromThrowable, () -> loadAndClose(sliceClassName, loader)).flatMap(Fn1.id());
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<DependencyFile> loadAndClose(String sliceClassName, SliceClassLoader loader) throws IOException {
        try (loader) {
            return load(sliceClassName, loader);
        }
    }

    public boolean hasSharedDependencies() {
        return ! shared.isEmpty();
    }

    public boolean hasInfraDependencies() {
        return ! infra.isEmpty();
    }

    public boolean hasSliceDependencies() {
        return ! slices.isEmpty();
    }

    public boolean isEmpty() {
        return shared.isEmpty()
               && infra.isEmpty()
               && slices.isEmpty();
    }

    private static final Fn1<Cause, String> UNKNOWN_SECTION = Causes.forOneValue("Unknown section in dependency file: %s. Valid sections: [shared], [infra], [slices]");
}
