// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.Arrays;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// Static cost class of a source (`[source.<name>] cost_class`), a ranking input for source selection
/// (rc6 spec §8.1): `reserved` < `ondemand` < `spot`, `free` is declared by the operator for sources that cost nothing.
public enum CostClass {
    ONDEMAND("ondemand"),
    SPOT("spot"),
    RESERVED("reserved"),
    FREE("free");
    private static final Cause INVALID_COST_CLASS = Causes.cause("Invalid cost class: must be 'ondemand', 'spot', 'reserved', or 'free'");
    private final String value;
    CostClass(String value) {
        this.value = value;
    }
    public String value() {
        return value;
    }
    public static Result<CostClass> costClass(String raw) {
        return Arrays.stream(values())
                     .filter(costClass -> costClass.value.equals(raw))
                     .findFirst()
                     .map(Result::success)
                     .orElseGet(INVALID_COST_CLASS::result);
    }
}
