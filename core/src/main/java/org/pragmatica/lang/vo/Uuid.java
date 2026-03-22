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

import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Network;

/// Validated UUID value object.
public record Uuid(java.util.UUID value) {

    /// Parse a UUID from its string representation.
    ///
    /// @param raw the raw UUID string to parse
    /// @return Result containing parsed Uuid or parsing failure
    public static Result<Uuid> uuid(String raw) {
        return Network.parseUUID(raw).map(Uuid::new);
    }

    /// Wrap an existing UUID instance.
    ///
    /// @param value the UUID to wrap
    /// @return Uuid wrapping the provided value
    public static Uuid uuid(java.util.UUID value) {
        return new Uuid(value);
    }

    /// Generate a random UUID.
    ///
    /// @return Uuid containing a new random UUID
    public static Uuid randomUuid() {
        return new Uuid(java.util.UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
