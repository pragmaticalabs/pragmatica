/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pragmatica.postgres.net;

import java.nio.charset.Charset;

import org.pragmatica.postgres.Oid;


/// A value received from PostgreSQL in either text or binary wire format.
///
/// PostgreSQL columns may be returned in either format depending on the column type's
/// `Oid.supportsBinary()` capability and the format codes negotiated for the result row.
/// Custom `Converter<T>` implementations receive `PgValue` and decide how to extract
/// the typed value:
///
/// - Pattern-match on the sealed subtype to handle each format explicitly.
/// - Use `asBytes()` for the raw wire bytes (works for either format).
/// - Use `asString(...)` on `Text` for the decoded character form.
public sealed interface PgValue {
    /// PostgreSQL type OID of this value.
    Oid type();
    /// Raw wire bytes. For `Text`, these are the encoded characters; for `Binary`, the
    /// PostgreSQL binary representation of the value.
    byte[] asBytes();
    /// Whether this value carries binary-format wire bytes.
    boolean isBinary();

    /// Text-format value: raw bytes plus the charset used for decoding.
    record Text(Oid type, byte[] raw, Charset encoding) implements PgValue {
        @Override
        public byte[] asBytes() {
            return raw;
        }

        @Override
        public boolean isBinary() {
            return false;
        }

        /// Decode the raw bytes to a String. Allocates a fresh String each call —
        /// converters that need the value multiple times should cache locally.
        public String asString() {
            return new String(raw, encoding);
        }
    }

    /// Binary-format value: PostgreSQL binary representation of the value.
    record Binary(Oid type, byte[] raw) implements PgValue {
        @Override
        public byte[] asBytes() {
            return raw;
        }

        @Override
        public boolean isBinary() {
            return true;
        }
    }
}
