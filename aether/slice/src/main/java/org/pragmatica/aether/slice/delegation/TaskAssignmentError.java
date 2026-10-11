// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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
