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

/// An enum ordinal arrived that this node's copy of the enum cannot name, and the enum has no
/// `UNKNOWN` sentinel to surface it as (#964).
///
/// This is the fallback for enums that did NOT opt into the sentinel — slice-generated application
/// enums. Framework `@Codec` enums are required by `CodecProcessor` to declare `UNKNOWN`, so they
/// never reach here; they surface the sentinel and the rest of the message survives.
///
/// What this replaces is the point of it. The generated read was `values()[readCompact(buf)]`, an
/// unchecked array index, so an unknown ordinal produced `ArrayIndexOutOfBoundsException: Index 3 out
/// of bounds for length 3` — a message that names neither the enum nor the wire, from a stack inside
/// generated code, caught by a boundary that logs "failed to deserialize". This names the enum, the
/// ordinal, how many constants this node knows, and what to do about it.
///
/// Subclasses `IllegalArgumentException` because the bytes are, from this node's perspective,
/// not a valid encoding of this type.
public final class UnknownEnumOrdinalException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private final String enumType;
    private final int ordinal;
    private final int knownConstantCount;

    UnknownEnumOrdinalException(String enumType, int ordinal, int knownConstantCount) {
        super("Enum %s received ordinal %d but this node knows only %d constant(s) [0, %d)."
              .formatted(enumType, ordinal, knownConstantCount, knownConstantCount)
              + " The sender is running a version of this enum with constants this node does not have,"
              + " so the message is dropped. Give the enum a last constant named UNKNOWN to have"
              + " unrecognised values decode to it instead, leaving the rest of the message usable.");
        this.enumType = enumType;
        this.ordinal = ordinal;
        this.knownConstantCount = knownConstantCount;
    }

    public String enumType() {
        return enumType;
    }

    public int ordinal() {
        return ordinal;
    }

    public int knownConstantCount() {
        return knownConstantCount;
    }
}
