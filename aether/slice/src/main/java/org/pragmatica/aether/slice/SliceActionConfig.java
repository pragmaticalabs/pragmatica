package org.pragmatica.aether.slice;

import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.aether.slice.serialization.SerializerFactoryProvider;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Path;
import java.util.List;

import static org.pragmatica.aether.slice.repository.maven.LocalRepository.localRepository;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Configuration for slice loading and lifecycle management.
///
/// @param loadingTimeout      Timeout for slice loading
/// @param activatingTimeout   Timeout for slice activation
/// @param deactivatingTimeout Timeout for slice deactivation
/// @param unloadingTimeout    Timeout for slice unloading
/// @param startStopTimeout    Timeout for start/stop operations
/// @param repositories        List of repositories to search for slices
/// @param serializerProvider  Provider for serialization (Fury or Kryo)
/// @param frameworkJarsPath   Optional path to framework JARs for classloader isolation.
///                            If provided, creates a FrameworkClassLoader with isolated
///                            pragmatica-lite, slice-api, and serialization classes.
///                            If empty, uses Application ClassLoader (no isolation).
@SuppressWarnings("JBCT-SEQ-01")
public record SliceActionConfig(TimeSpan loadingTimeout,
                                TimeSpan activatingTimeout,
                                TimeSpan deactivatingTimeout,
                                TimeSpan unloadingTimeout,
                                TimeSpan startStopTimeout,
                                List<Repository> repositories,
                                SerializerFactoryProvider serializerProvider,
                                Option<Path> frameworkJarsPath) {
    @SuppressWarnings("JBCT-RET-03")
    public static SliceActionConfig sliceActionConfig() {
        return sliceActionConfig((SerializerFactoryProvider) null);
    }

    public static SliceActionConfig sliceActionConfig(SerializerFactoryProvider serializerProvider) {
        return new SliceActionConfig(timeSpan(2).minutes(),
                                     timeSpan(1).minutes(),
                                     timeSpan(30).seconds(),
                                     timeSpan(2).minutes(),
                                     timeSpan(5).seconds(),
                                     List.of(localRepository()),
                                     serializerProvider,
                                     Option.empty());
    }

    /// Create configuration with framework isolation enabled.
    ///
    /// @param serializerProvider Provider for serialization
    /// @param frameworkJarsPath  Path to directory containing framework JARs
    /// @return Configuration with isolation enabled
    public static SliceActionConfig sliceActionConfig(SerializerFactoryProvider serializerProvider,
                                                      Path frameworkJarsPath) {
        return new SliceActionConfig(timeSpan(2).minutes(),
                                     timeSpan(1).minutes(),
                                     timeSpan(30).seconds(),
                                     timeSpan(2).minutes(),
                                     timeSpan(5).seconds(),
                                     List.of(localRepository()),
                                     serializerProvider,
                                     option(frameworkJarsPath));
    }

    public Result<TimeSpan> timeoutFor(SliceState state) {
        return switch (state) {
            case SliceState.LOADING -> success(loadingTimeout);
            case SliceState.ACTIVATING -> success(activatingTimeout);
            case SliceState.DEACTIVATING -> success(deactivatingTimeout);
            case SliceState.UNLOADING -> success(unloadingTimeout);
            default -> NO_TIMEOUT_CONFIGURED.apply(state)
                                            .result();
        };
    }

    private static final Fn1<Cause, SliceState> NO_TIMEOUT_CONFIGURED = Causes.forOneValue("No timeout configured for state: %s");
}
