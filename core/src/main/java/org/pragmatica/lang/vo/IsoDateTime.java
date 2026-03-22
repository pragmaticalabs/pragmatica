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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.DateTime;

/// ISO 8601 datetime with offset. Wraps OffsetDateTime.
public record IsoDateTime(OffsetDateTime value) {

    /// Parse an ISO 8601 datetime string with offset.
    ///
    /// @param raw the raw datetime string to parse
    /// @return Result containing parsed IsoDateTime or parsing failure
    public static Result<IsoDateTime> isoDateTime(String raw) {
        return DateTime.parseOffsetDateTime(raw).map(IsoDateTime::new);
    }

    /// Wrap an existing OffsetDateTime instance.
    ///
    /// @param value the OffsetDateTime to wrap
    /// @return IsoDateTime wrapping the provided value
    public static IsoDateTime isoDateTime(OffsetDateTime value) {
        return new IsoDateTime(value);
    }

    /// Current timestamp in the system default offset.
    ///
    /// @return IsoDateTime representing the current moment
    public static IsoDateTime now() {
        return new IsoDateTime(OffsetDateTime.now());
    }

    /// Current timestamp in UTC.
    ///
    /// @return IsoDateTime representing the current moment in UTC
    public static IsoDateTime nowUtc() {
        return new IsoDateTime(OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Override
    public String toString() {
        return value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
