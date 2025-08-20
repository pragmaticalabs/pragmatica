package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

// TODO: rework timeout handling, perhaps use existing values as defaults
public enum SliceState {
    LOAD,
    LOADING(timeSpan(2).minutes()),
    LOADED,
    ACTIVATE,
    ACTIVATING(timeSpan(1).minutes()),
    ACTIVE,
    DEACTIVATE,
    DEACTIVATING(timeSpan(30).seconds()),
    FAILED,
    UNLOAD,
    UNLOADING(timeSpan(2).minutes());

    private final TimeSpan timeout;

    SliceState() {
        this(null);
    }

    SliceState(TimeSpan timeout) {
        this.timeout = timeout;
    }

    public TimeSpan timeout() {
        return timeout;
    }

    public boolean hasTimeout() {
        return timeout != null;
    }

    public boolean isTransitional() {
        return hasTimeout();
    }

    public Set<SliceState> validTransitions() {
        return switch (this) {
            case LOAD -> Set.of(LOADING);
            case LOADING, DEACTIVATING -> Set.of(LOADED, FAILED);
            case LOADED -> Set.of(ACTIVATE, UNLOAD);
            case ACTIVATE -> Set.of(ACTIVATING);
            case ACTIVATING -> Set.of(ACTIVE, FAILED);
            case ACTIVE -> Set.of(DEACTIVATE);
            case DEACTIVATE -> Set.of(DEACTIVATING);
            case FAILED -> Set.of(UNLOAD);
            case UNLOAD -> Set.of(UNLOADING);
            case UNLOADING -> Set.of();
        };
    }

    public boolean canTransitionTo(SliceState target) {
        return validTransitions().contains(target);
    }

    public SliceState nextState() {
        return switch (this) {
            case LOAD -> LOADING;
            case LOADING, DEACTIVATING -> LOADED;
            case LOADED -> ACTIVATE;
            case ACTIVATE -> ACTIVATING;
            case ACTIVATING -> ACTIVE;
            case ACTIVE -> DEACTIVATE;
            case DEACTIVATE -> DEACTIVATING;
            case FAILED -> UNLOAD;
            case UNLOAD -> UNLOADING;
            // Throw exception here as this is an unrecoverable error, we can't expect transition from
            // terminal state.
            case UNLOADING -> throw new IllegalStateException("UNLOADING is terminal state");
        };
    }

    private static final Map<String, SliceState> STRING_TO_STATE;

    static {
        var map = new HashMap<String, SliceState>();
        map.put("LOAD", LOAD);
        map.put("LOADING", LOADING);
        map.put("LOADED", LOADED);
        map.put("ACTIVATE", ACTIVATE);
        map.put("ACTIVATING", ACTIVATING);
        map.put("ACTIVE", ACTIVE);
        map.put("DEACTIVATE", DEACTIVATE);
        map.put("DEACTIVATING", DEACTIVATING);
        map.put("FAILED", FAILED);
        map.put("UNLOAD", UNLOAD);
        map.put("UNLOADING", UNLOADING);
        STRING_TO_STATE = Map.copyOf(map);
    }

    public static Result<SliceState> sliceState(String stateString) {
        var state = STRING_TO_STATE.get(stateString.toUpperCase());

        return state == null
                ? UNKNOWN_STATE.apply(stateString).result()
                : Result.success(state);

    }

    private static final Fn1<Cause, String> UNKNOWN_STATE = Causes.forValue("Unknown slice state [{0}]");
}