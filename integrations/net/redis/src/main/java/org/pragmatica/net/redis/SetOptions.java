/*
 *  Copyright (c) 2022-2025 Sergiy Yevtushenko.
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
package org.pragmatica.net.redis;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.net.redis.RedisCommands.utf8;


/// Modifiers of SET: an expiry (`EX`/`PX`) and `NX` (set only when the key is absent).
public record SetOptions(Option<Expiry> expiry, boolean onlyIfAbsent) {
    /// Unit of an [Expiry], carrying the SET keyword that selects it.
    public enum Precision {
        SECONDS("EX"),
        MILLIS("PX");
        private final String keyword;
        Precision(String keyword) {
            this.keyword = keyword;
        }
    }

    /// A positive time to live.
    public record Expiry(Precision precision, long amount) {
        public static Result<Expiry> expiry(Precision precision, long amount) {
            return Verify.ensure(amount, Verify.Is::positive).map(positive -> new Expiry(precision, positive));
        }

        public static Result<Expiry> seconds(long amount) {
            return expiry(Precision.SECONDS, amount);
        }

        public static Result<Expiry> millis(long amount) {
            return expiry(Precision.MILLIS, amount);
        }
    }

    /// No expiry, key overwritten unconditionally.
    public static SetOptions setOptions() {
        return new SetOptions(none(), false);
    }

    public SetOptions withExpiry(Expiry ttl) {
        return new SetOptions(some(ttl), onlyIfAbsent);
    }

    public SetOptions ifAbsent() {
        return new SetOptions(expiry, true);
    }

    /// The SET arguments after `key value`.
    List<byte[]> arguments() {
        return Stream.concat(expiry.stream().flatMap(SetOptions::expiryArguments),
                             onlyIfAbsent
                             ? Stream.of(utf8("NX"))
                             : Stream.<byte[]> empty())
                     .toList();
    }

    private static Stream<byte[]> expiryArguments(Expiry ttl) {
        return Stream.of(utf8(ttl.precision().keyword),
                         utf8(Long.toString(ttl.amount())));
    }
}
