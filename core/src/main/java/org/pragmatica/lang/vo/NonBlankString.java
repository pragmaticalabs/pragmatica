/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
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
 */

package org.pragmatica.lang.vo;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.utils.Causes.cause;

/// Trimmed, guaranteed non-empty string value.
public record NonBlankString(String value) {
    private static final Cause BLANK_OR_NULL = cause("String must not be null, empty, or blank");

    /// Parse and validate a non-blank string.
    ///
    /// @param raw the raw string to validate
    /// @return Result containing trimmed NonBlankString or validation failure
    public static Result<NonBlankString> nonBlankString(String raw) {
        return Verify.ensure(raw, Verify.Is::present, BLANK_OR_NULL)
                     .map(String::trim)
                     .map(NonBlankString::new);
    }

    @Override
    public String toString() {
        return value;
    }
}
