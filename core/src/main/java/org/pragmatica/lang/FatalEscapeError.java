/*
 *  Copyright (c) 2025-2026 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.pragmatica.lang;

/// Failures of [Promise#installFatalEscapeHandler(FatalEscapeHandler)] and
/// [FatalEscapeHandler.Registration#uninstall()].
public enum FatalEscapeError implements Cause {
    /// A handler is already installed in this JVM. The slot is never overwritten: the holder keeps it until it
    /// uninstalls, so a second component cannot silently replace the first one's handler.
    ALREADY_INSTALLED("A fatal-escape handler is already installed in this JVM"),
    /// The registration does not hold the slot: it was already uninstalled, so it cannot free a slot that is now
    /// empty or held by another registration.
    NOT_INSTALLED("This fatal-escape handler registration does not hold the slot");
    private final String message;
    FatalEscapeError(String message) {
        this.message = message;
    }
    @Override
    public String message() {
        return message;
    }
}
