/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.statemachine;

/// Two-part identification for FSM instances. Split so that observers/metrics emit them as
/// separate tags, avoiding high-cardinality explosion on `kind` when `instance` (e.g. NodeId)
/// varies across node churn.
public record FsmTags(String kind, String instance) {
    public static FsmTags fsmTags(String kind, String instance) {
        return new FsmTags(kind, instance);
    }

    /// For callers that have only a legacy single name. Parses `"kind-instance"` by splitting on
    /// the first `-`; if no `-`, treats the whole thing as `kind` with empty instance.
    public static FsmTags fromLegacyName(String name) {
        var idx = name.indexOf('-');
        if (idx < 0) {
            return new FsmTags(name, "");
        }
        return new FsmTags(name.substring(0, idx), name.substring(idx + 1));
    }

    public String displayName() {
        return instance.isEmpty() ? kind : kind + "-" + instance;
    }
}
