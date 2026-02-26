package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Codec;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

// Note: Enum timeouts are default values. SliceActionConfig is the single source of truth
// for actual timeout configuration and can override these defaults.
@Codec
@SuppressWarnings("JBCT-SEQ-01")
public enum SliceState {
    LOAD,
    LOADING(timeSpan(2).minutes()),
    LOADED,
    ACTIVATE,
    ACTIVATING(timeSpan(1).minutes()),
    ACTIVE,
    DEACTIVATE,
    DEACTIVATING(
    timeSpan(30).seconds()),
    FAILED,
    UNLOAD,
    UNLOADING(timeSpan(2).minutes());
    private final Option<TimeSpan> timeout;
    SliceState() {
        this(Option.none());
    }
    SliceState(TimeSpan timeout) {
        this(Option.some(timeout));
    }
    SliceState(Option<TimeSpan> timeout) {
        this.timeout = timeout;
    }
    public Option<TimeSpan> timeout() {
        return timeout;
    }
    public boolean hasTimeout() {
        return timeout.isPresent();
    }
    public boolean isTransitional() {
        return hasTimeout();
    }
    /// Returns true if this state indicates an operation is in progress.
    /// Includes both transitional states (with timeouts) and command states (triggers).
    /// Used by ControlLoop to block auto-scaling during deployment.
    public boolean isInProgress() {
        return switch (this) {
            case LOAD, LOADING, ACTIVATE, ACTIVATING, DEACTIVATE, DEACTIVATING, UNLOAD, UNLOADING -> true;
            case LOADED, ACTIVE, FAILED -> false;
        };
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
    public Result<SliceState> nextState() {
        return switch (this) {
            case LOAD -> success(LOADING);
            case LOADING, DEACTIVATING -> success(LOADED);
            case LOADED -> success(ACTIVATE);
            case ACTIVATE -> success(ACTIVATING);
            case ACTIVATING -> success(ACTIVE);
            case ACTIVE -> success(DEACTIVATE);
            case DEACTIVATE -> success(DEACTIVATING);
            case FAILED -> success(UNLOAD);
            case UNLOAD -> success(UNLOADING);
            case UNLOADING -> TERMINAL_STATE_ERROR.result();
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
        return option(STRING_TO_STATE.get(stateString.toUpperCase())).toResult(UNKNOWN_STATE.apply(stateString));
    }
    private static final Fn1<Cause, String> UNKNOWN_STATE = Causes.forOneValue("Unknown slice state [{}]");
    private static final Cause TERMINAL_STATE_ERROR = Causes.cause("Cannot transition from UNLOADING terminal state");
}
