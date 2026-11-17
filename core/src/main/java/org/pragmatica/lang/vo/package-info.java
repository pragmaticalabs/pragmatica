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
/// Production-ready value objects for common types.
///
/// **Check this package before writing a custom value object.** Hand-rolling a value object that
/// duplicates one of these is a JBCT violation — these are validated, normalized, and tested.
/// Custom VOs are for domain-specific types (`OrderId`, `Username`, `ReferralCode`), not for
/// re-validating emails and URLs.
///
/// | Value Object | Factory | Validation / normalization |
/// |--------------|---------|---------------------------|
/// | [Email][org.pragmatica.lang.vo.Email] | `Email.email(String)` | RFC 5321: trims, ≤254 chars, exactly one `@`, local-part structure rules, domain lowercased with label checks; components `localPart()`/`domain()`/`address()` |
/// | [Url][org.pragmatica.lang.vo.Url] | `Url.url(String)` | URI parse + requires scheme and host; accessors `scheme()`/`host()`/`port()`/`path()`/`query()` |
/// | [Uuid][org.pragmatica.lang.vo.Uuid] | `Uuid.uuid(String)`, `Uuid.uuid(UUID)`, `Uuid.randomUuid()` | UUID parse, wrap, or generate |
/// | [NonBlankString][org.pragmatica.lang.vo.NonBlankString] | `NonBlankString.nonBlankString(String)` | Rejects null/empty/blank, trims |
/// | [Currency][org.pragmatica.lang.vo.Currency] | `Currency.currency(String)`, `Currency.currency(java.util.Currency)` | ISO 4217 code over `java.util.Currency`: trims, upper-cases, unknown code gives a typed `CurrencyError.UnknownCode`; accessors `code()`/`fractionDigits()` |
/// | [Password][org.pragmatica.lang.vo.Password] | `Password.password(String, Policy)`, `Password.presented(String)`, `Password.presented(String, Policy)` | Register/change enforces a data `Policy` (code-point bounds, UTF-8 byte cap, optional character classes and normalisation, both off by default); login checks non-blank only (`presented(String, Policy)` also applies the policy's normalisation); `toString` redacted, constant-time `equals`, no hashing |
/// | [IsoDateTime][org.pragmatica.lang.vo.IsoDateTime] | `IsoDateTime.isoDateTime(String)`, `isoDateTime(OffsetDateTime)`, `now()`, `nowUtc()` | ISO 8601 datetime with offset |
///
/// Not a validated value: [ValueMapping][org.pragmatica.lang.vo.ValueMapping] (`ValueMapping.valueMapping(lower, lift)`) is the
/// boundary-neutral descriptor a custom value object exposes as `static ValueMapping<Self, P> valueMapping()` so
/// persistence and transport generators bind it to a single primitive `P`.
///
/// All factories follow the JBCT naming convention (`TypeName.typeName(...)`) and return
/// `Result<T>` — failures carry descriptive `Cause`s suitable for direct propagation.
///
/// For parsing raw values into JDK types without a value-object wrapper, see
/// `org.pragmatica.lang.parse` (`Number`, `DateTime`, `Network`, `Text`, `DataSize`, `TimeSpan`).
///
/// @see org.pragmatica.lang.Verify
package org.pragmatica.lang.vo;
