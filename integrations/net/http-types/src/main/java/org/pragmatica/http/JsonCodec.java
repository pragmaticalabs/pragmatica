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

package org.pragmatica.http;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

/// JSON serialization seam.
///
/// Defined over `byte[]` so the base stays free of any transport concern (no `ByteBuf`).
/// Transport adapters convert request content to `byte[]` before [#deserialize] and write
/// the `byte[]` produced by [#serialize] directly to their response sink.
public interface JsonCodec {
    Result<byte[]> serialize(Object value);

    <T> Result<T> deserialize(byte[] bytes, TypeToken<T> token);
}
