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


public interface HttpError extends Cause, HttpStatusAware {
    HttpStatus status();

    @Override
    default HttpStatus httpStatus() {
        return status();
    }

    /// The text safe to put in a client-facing body: the status text and nothing below it. The default is
    /// deliberately the narrowest text, so an implementer that carries an origin chain cannot leak it by
    /// omission; an implementer widens it to its top cause's message on purpose. [#message()] may walk the
    /// origin chain and is for server-side logs only.
    default String clientMessage() {
        return status().message();
    }

    /// The one renderer of a [Cause] into client-facing text (#2101). Every client-body producer calls this and
    /// never `Cause::message` directly: an [HttpError] gives its [#clientMessage()], a composite joins its members'
    /// client text (recursively), any other cause gives its own top-level message. The origin chain is left to the
    /// server log.
    static String clientMessage(Cause cause) {
        return switch (cause) {
            case HttpError error -> error.clientMessage();
            case Causes.CompositeCause composite -> compositeClientMessage(composite);
            default -> cause.message();
        };
    }

    private static String compositeClientMessage(Causes.CompositeCause composite) {
        var builder = new StringBuilder("Composite:");

        composite.stream().forEach(member -> builder.append("\n  ")
                                                    .append(clientMessage(member)));

        return builder.toString();
    }

    /// The full origin chain as one line, for server-side logs only (#2101): every link joined by `<-`. An
    /// [HttpError] link contributes its status alone (its origin is the next link), so nothing is repeated.
    /// Never put this in a client body; [#clientMessage(Cause)] is the client-facing renderer.
    static String causeChain(Cause cause) {
        var chain = new StringBuilder();

        cause.iterate(link -> chain.append(chain.isEmpty()
                                           ? ""
                                           : " <- ")
                                   .append(chainLinkText(link)));

        return chain.toString();
    }

    private static String chainLinkText(Cause link) {
        return link instanceof HttpError error
               ? error.status()
                      .message()
               : link.message();
    }

    static HttpError httpError(HttpStatus status, Cause source) {
        record httpError(HttpStatus status, Cause origin) implements HttpError {
            @Override
            public String clientMessage() {
                return status().message() + ": " + HttpError.clientMessage(origin());
            }

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
