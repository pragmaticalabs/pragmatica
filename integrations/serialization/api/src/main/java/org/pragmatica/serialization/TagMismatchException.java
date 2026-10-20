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
package org.pragmatica.serialization;

/// A generated record reader found a field discriminator other than the one its compile-time shape expects (#1459).
///
/// Every generated record writer puts a type tag in front of each field. The reader used to consume that tag without
/// comparing it, so a frame written with a different field shape (a retyped, added, removed or reordered field, or a
/// corrupt frame) ran the wrong reader and produced garbage with no signal. The tag was already on the wire; this is the
/// check that uses it.
///
/// Distinct from [UnknownTypeTagException], which says the sender's TYPE is unknown to this node: here the type is known
/// and its FIELD layout disagrees. Subclasses `IllegalArgumentException` so boundaries that contain decode failures keep
/// containing it.
public final class TagMismatchException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private final String subject;
    private final int expected;
    private final int actual;

    TagMismatchException(String subject, int expected, int actual) {
        super("Wire tag mismatch for " + subject
             + ": expected tag " + expected
             + " but the frame carries " + actual
             + ". The sender wrote a different field layout (or the frame is corrupt); the message cannot be decoded.");
        this.subject = subject;
        this.expected = expected;
        this.actual = actual;
    }

    /// The generated reader position that failed, as `Type.field`.
    public String subject() {
        return subject;
    }

    /// The tag the reader's compile-time shape requires.
    public int expected() {
        return expected;
    }

    /// The tag found on the wire.
    public int actual() {
        return actual;
    }
}
