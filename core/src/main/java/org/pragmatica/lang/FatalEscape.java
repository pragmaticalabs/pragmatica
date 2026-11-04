/*
 *  Copyright (c) 2025-2026 Sergiy Yevtushenko.
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
 *
 */
package org.pragmatica.lang;

/// One [Error] that escaped a [Promise] continuation, as handed to a [FatalEscapeHandler].
///
/// @param continuation the kind of continuation the error escaped: `onResult` (every `onResult`, `onSuccess` and
///                     `onFailure` handler), `fold` (`flatMap` and the other promise-returning transformations),
///                     `replaceResult` (`map` and the other result-returning transformations) or `async` (the
///                     consumer given to `async` or `Promise.promise(consumer)`)
/// @param error        the error itself; a [VirtualMachineError] is still rethrown at the continuation boundary
///                     after the handler returns
/// @param origin       the first stack frame that belongs to neither the JDK nor this library: the origin the
///                     ERROR log line names
/// @param threadName   the name of the thread the continuation ran on
public record FatalEscape(String continuation, Error error, String origin, String threadName) {}
