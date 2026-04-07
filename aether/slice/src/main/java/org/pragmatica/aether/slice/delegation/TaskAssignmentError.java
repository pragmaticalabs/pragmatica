/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package org.pragmatica.aether.slice.delegation;

import org.pragmatica.lang.Cause;


/// Errors raised by task-group assignment lookups.
///
/// Defined in `aether-slice` (rather than `aether-management-api`) because
/// `aether-management-api` already depends on `aether-slice`. Placing the
/// error here keeps the dependency arrow one-way and lets `ManagementRouteError`
/// in the management-api module compose this error when forwarding fails.
public sealed interface TaskAssignmentError extends Cause {
    static NotAssigned notAssigned(TaskGroup group) {
        return new NotAssigned(group);
    }

    record NotAssigned(TaskGroup group) implements TaskAssignmentError {
        @Override public String message() {
            return "Task group " + group + " has no current owner assignment";
        }
    }
}
