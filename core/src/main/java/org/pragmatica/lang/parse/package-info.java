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
/// Exception-safe parsing: functional wrappers over JDK parsing APIs returning `Result<T>`
/// instead of throwing.
///
/// **Never call throwing JDK parse methods (`Integer.parseInt`, `LocalDate.parse`,
/// `UUID.fromString`, ...) in business code — use these wrappers.** Each failure carries a
/// descriptive `Cause` suitable for direct propagation through a validation chain.
///
/// | Class | Covers |
/// |-------|--------|
/// | [Number][org.pragmatica.lang.parse.Number] | JDK number parsing (`parseInt`, `parseLong`, `parseDouble`, ...) |
/// | [DateTime][org.pragmatica.lang.parse.DateTime] | `java.time` parsing (`parseLocalDate`, `parseOffsetDateTime`, ...) |
/// | [Network][org.pragmatica.lang.parse.Network] | network and identifier parsing (`parseURI`, `parseUUID`, ...) |
/// | [Text][org.pragmatica.lang.parse.Text] | JDK text processing APIs |
/// | [I18n][org.pragmatica.lang.parse.I18n] | JDK internationalization APIs |
/// | [DataSize][org.pragmatica.lang.parse.DataSize] | human-friendly data sizes ("10MB") |
/// | [TimeSpan][org.pragmatica.lang.parse.TimeSpan] | human-friendly durations ("5s") |
///
/// For validated domain wrappers built on these, see `org.pragmatica.lang.vo`; for predicate
/// validation, see [org.pragmatica.lang.Verify].
package org.pragmatica.lang.parse;
