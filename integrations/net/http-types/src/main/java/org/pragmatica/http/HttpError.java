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


public interface HttpError extends Cause, HttpStatusAware {
    HttpStatus status();

    @Override
    default HttpStatus httpStatus() {
        return status();
    }

    static HttpError httpError(HttpStatus status, Cause source) {
        record httpError(HttpStatus status, Cause origin) implements HttpError {
            @Override
            public String message() {
                var builder = new StringBuilder().append(status().message()).append(": ").append(origin().message());
                var cause = origin().source();

                while (cause.isPresent()) {
                    cause.onPresent(c -> builder.append("\n\t")
                                                .append(c.message()));
                    cause = cause.flatMap(Cause::source);
                }

                return builder.toString();
            }

            @Override
            public Option<Cause> source() {
                return Option.some(origin);
            }
        }

        return new httpError(status, source);
    }
}
