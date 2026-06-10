// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.delegation;

import org.pragmatica.lang.Cause;


public sealed interface TaskAssignmentError extends Cause {
    static NotAssigned notAssigned(TaskGroup group) {
        return new NotAssigned(group);
    }

    record NotAssigned(TaskGroup group) implements TaskAssignmentError {
        @Override
        public String message() {
            return "Task group " + group + " has no current owner assignment";
        }
    }
}
