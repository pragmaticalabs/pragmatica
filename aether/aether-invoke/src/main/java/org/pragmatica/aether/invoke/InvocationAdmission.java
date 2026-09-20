// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.function.BooleanSupplier;

import org.pragmatica.lang.Contract;


/// Admission and lifetime accounting for actual local execution, shared by remote and local calls.
public interface InvocationAdmission {
    boolean tryEnter();

    @Contract
    void exit();

    /// Independent admission conditions compose with the lifetime counter; they never reopen
    /// a tracker already closed by drain. Calls admitted before closure retain their accounting.
    static InvocationAdmission gated(InvocationAdmission delegate, BooleanSupplier isCurrent) {
        record gated(InvocationAdmission delegate, BooleanSupplier isCurrent) implements InvocationAdmission {
            @Override
            public boolean tryEnter() {
                return isCurrent.getAsBoolean() && delegate.tryEnter();
            }

            @Override
            @Contract
            public void exit() {
                delegate.exit();
            }
        }

        return new gated(delegate, isCurrent);
    }

    static InvocationAdmission open() {
        record open() implements InvocationAdmission {
            @Override
            public boolean tryEnter() {
                return true;
            }

            @Override
            @Contract
            public void exit() {}
        }

        return new open();
    }
}
