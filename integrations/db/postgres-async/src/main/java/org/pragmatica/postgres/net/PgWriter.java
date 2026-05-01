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

import org.pragmatica.lang.Contract;

/// Sink that a `Converter<T>` writes a parameter value into during BIND.
///
/// A converter must call exactly one of `writeText` / `writeBinary` per invocation.
/// Calling neither, or calling more than once, leaves the writer in an invalid
/// state and the framework will reject the bind.
public interface PgWriter {

    /// Write the parameter as text-format wire bytes.
    @Contract void writeText(String value);

    /// Write the parameter as binary-format wire bytes.
    @Contract void writeBinary(byte[] value);
}
