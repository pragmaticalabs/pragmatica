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

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

public sealed interface CodecError extends Cause {
    record SerializationFailed(String message, Option<Cause> source) implements CodecError {}

    record DeserializationFailed(String message, Option<Cause> source) implements CodecError {}

    static CodecError serializationFailed(String message, Cause source) {
        return new SerializationFailed(message, Option.option(source));
    }

    static CodecError deserializationFailed(String message, Cause source) {
        return new DeserializationFailed(message, Option.option(source));
    }

    static CodecError fromSerializationThrowable(Throwable t) {
        return new SerializationFailed("Serialization failed: " + t.getMessage(),
                                       Option.option(Causes.fromThrowable(t)));
    }

    static CodecError fromDeserializationThrowable(Throwable t) {
        return new DeserializationFailed("Deserialization failed: " + t.getMessage(),
                                         Option.option(Causes.fromThrowable(t)));
    }
}
