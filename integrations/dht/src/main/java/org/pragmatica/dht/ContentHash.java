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
package org.pragmatica.dht;

import java.security.MessageDigest;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// The content check of a content-addressed value (A6): its bytes hash to the key's SHA-256.
public sealed interface ContentHash {
    /// Whether `value` hashes to `sha256`. A digest that cannot be computed never matches.
    static boolean matches(byte[] value, byte[] sha256) {
        return sha256Of(value).map(digest -> MessageDigest.isEqual(digest, sha256))
                       .or(false);
    }

    private static Result<byte[]> sha256Of(byte[] value) {
        return Result.lift(Causes::fromThrowable,
                           () -> MessageDigest.getInstance("SHA-256").digest(value));
    }

    record unused() implements ContentHash {}
}
