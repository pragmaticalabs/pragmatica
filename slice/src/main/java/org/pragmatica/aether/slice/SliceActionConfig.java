package org.pragmatica.aether.slice;

import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.aether.slice.serialization.SerializerFactoryProvider;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import java.util.List;

import static org.pragmatica.aether.slice.repository.maven.LocalRepository.localRepository;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

public record SliceActionConfig(
        TimeSpan loadingTimeout,
        TimeSpan activatingTimeout,
        TimeSpan deactivatingTimeout,
        TimeSpan unloadingTimeout,
        TimeSpan startStopTimeout,
        List<Repository> repositories,
        SerializerFactoryProvider serializerProvider
) {
    public static SliceActionConfig defaultConfiguration() {
        return defaultConfiguration(null);
    }

    public static SliceActionConfig defaultConfiguration(SerializerFactoryProvider serializerProvider) {
        return new SliceActionConfig(
                timeSpan(2).minutes(),
                timeSpan(1).minutes(),
                timeSpan(30).seconds(),
                timeSpan(2).minutes(),
                timeSpan(5).seconds(),
                List.of(localRepository()),
                serializerProvider
        );
    }

    public Result<TimeSpan> timeoutFor(SliceState state) {
        return switch (state) {
            case SliceState.LOADING -> Result.success(loadingTimeout);
            case SliceState.ACTIVATING -> Result.success(activatingTimeout);
            case SliceState.DEACTIVATING -> Result.success(deactivatingTimeout);
            case SliceState.UNLOADING -> Result.success(unloadingTimeout);
            default -> NO_TIMEOUT_CONFIGURED.apply(state).result();
        };
    }

    private static final Fn1<Cause, SliceState> NO_TIMEOUT_CONFIGURED =
        Causes.forValue("No timeout configured for state: %s");
}
