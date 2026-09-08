// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Codec;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


@Codec
@SuppressWarnings("JBCT-SEQ-01")
public enum SliceState {
    LOAD,
    LOADING(timeSpan(2).minutes()),
    LOADED,
    ACTIVATE,
    ACTIVATING(timeSpan(90).seconds()),
    ROUTING(timeSpan(30).seconds()),
    ACTIVE,
    DEACTIVATE,
    DEACTIVATING(timeSpan(30).seconds()),
    FAILED,
    UNLOAD,
    UNLOADING(timeSpan(2).minutes()),
    /// Wire sentinel (#964): a state ordinal this node cannot name decodes here instead of throwing.
    /// It is INERT by construction -- no timeout, so it is not transitional and the stuck-transitional
    /// remediator will not force-unload a slice whose state was authored by a newer node; no valid
    /// transitions, so nothing can be driven out of it; and it loses every merge in
    /// `DeploymentMap.higherState`, which special-cases it rather than trusting ordinal order.
    /// Deliberately absent from `STRING_TO_STATE`, so `sliceState("UNKNOWN")` fails: this is a decode
    /// artifact, not a state an operator or a config file may ask for.
    /// Must stay LAST -- a new constant appended after it, or inserted before it, is read as UNKNOWN
    /// by an older node either way.
    UNKNOWN;
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
    public boolean isInProgress() {
        return switch (this) {
            case LOAD, LOADING, ACTIVATE, ACTIVATING, ROUTING, DEACTIVATE, DEACTIVATING, UNLOAD, UNLOADING -> true;
            case LOADED, ACTIVE, FAILED, UNKNOWN -> false;
        };
    }
    public Set<SliceState> validTransitions() {
        return switch (this) {
            case LOAD -> Set.of(LOADING);
            case LOADING, DEACTIVATING -> Set.of(LOADED, FAILED);
            case LOADED -> Set.of(ACTIVATE, UNLOAD);
            case ACTIVATE -> Set.of(ACTIVATING);
            case ACTIVATING -> Set.of(ROUTING, ACTIVE, FAILED);
            case ROUTING -> Set.of(ACTIVE, FAILED);
            case ACTIVE -> Set.of(DEACTIVATE);
            case DEACTIVATE -> Set.of(DEACTIVATING);
            case FAILED -> Set.of(UNLOAD);
            case UNLOAD -> Set.of(UNLOADING);
            case UNLOADING -> Set.of();
            // No transition out of an unreadable state: this node cannot know what the peer meant.
            case UNKNOWN -> Set.of();
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
            case ACTIVATING -> success(ROUTING);
            case ROUTING -> success(ACTIVE);
            case ACTIVE -> success(DEACTIVATE);
            case DEACTIVATE -> success(DEACTIVATING);
            case FAILED -> success(UNLOAD);
            case UNLOAD -> success(UNLOADING);
            case UNLOADING -> TERMINAL_STATE_ERROR.result();
            case UNKNOWN -> UNREADABLE_STATE_ERROR.result();
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
        map.put("ROUTING", ROUTING);
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
    private static final Fn1<Cause, String> UNKNOWN_STATE = Causes.forOneValue("Unknown slice state [%s]");
    private static final Cause TERMINAL_STATE_ERROR = Causes.cause("Cannot transition from UNLOADING terminal state");
    private static final Cause UNREADABLE_STATE_ERROR = Causes.cause("Cannot advance from UNKNOWN: the state was written by a node running a newer SliceState (#964)");
}
