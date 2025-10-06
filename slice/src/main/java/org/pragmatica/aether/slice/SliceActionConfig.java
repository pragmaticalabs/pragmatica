package org.pragmatica.aether.slice;

import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.io.TimeSpan;

import java.util.List;

import static org.pragmatica.aether.slice.repository.maven.LocalRepository.localRepository;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

public record SliceActionConfig(
        TimeSpan loadingTimeout,
        TimeSpan activatingTimeout,
        TimeSpan deactivatingTimeout,
        TimeSpan unloadingTimeout,
        List<Repository> repositories
) {
    public static SliceActionConfig defaultConfiguration() {
        return new SliceActionConfig(
                timeSpan(2).minutes(),
                timeSpan(1).minutes(),
                timeSpan(30).seconds(),
                timeSpan(2).minutes(),
                List.of(localRepository())
        );
    }

    public TimeSpan timeoutFor(SliceState state) {
        return switch (state) {
            case SliceState.LOADING -> loadingTimeout;
            case SliceState.ACTIVATING -> activatingTimeout;
            case SliceState.DEACTIVATING -> deactivatingTimeout;
            case SliceState.UNLOADING -> unloadingTimeout;
            default -> throw new IllegalArgumentException("No timeout configured for state: " + state);
        };
    }
}
