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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/// Pins the range ledger `wire-tag-ranges.txt` (#2822) against [SystemTags]: reserve first, then allocate
/// inside the range. The ledger says who MAY take a number; [SystemTags] stays the authority for which type
/// HAS it. Two parallel lanes picking the same free number is the collision this exists to prevent, and
/// without a reservation it is found only by hand-checking every open PR.
class SystemTagsLedgerTest {
    private static final String LEDGER = "wire-tag-ranges.txt";

    /// One ledger line: `<from>..<to> <owner> <date> <note>`.
    record Reservation(int from, int to, String owner, LocalDate date, String note, int line) {
        static Reservation parse(int line, String text) {
            var fields = text.trim()
                             .split("\\s+", 4);

            assertTrue(fields.length >= 3, "%s:%d needs '<from>..<to> <owner> <date> <note>', got: %s".formatted(LEDGER, line, text));

            var bounds = fields[0].split("\\.\\.");

            assertTrue(bounds.length == 2, "%s:%d range must read <from>..<to>, got: %s".formatted(LEDGER, line, fields[0]));

            return new Reservation(Integer.parseInt(bounds[0]),
                                   Integer.parseInt(bounds[1]),
                                   fields[1],
                                   LocalDate.parse(fields[2]),
                                   fields.length > 3
                                   ? fields[3]
                                   : "",
                                   line);
        }

        boolean contains(int tag) {
            return tag >= from && tag <= to;
        }

        String describe() {
            return "%d..%d %s (%s:%d)".formatted(from, to, owner, LEDGER, line);
        }
    }

    private static List<Reservation> ledger() throws Exception {
        var path = Path.of(SystemTags.class.getResource(LEDGER)
                                           .toURI());
        var lines = Files.readAllLines(path);

        return IntStream.range(0, lines.size())
                        .filter(index -> isReservation(lines.get(index)))
                        .mapToObj(index -> Reservation.parse(index + 1,
                                                             lines.get(index)))
                        .toList();
    }

    private static boolean isReservation(String line) {
        return !line.isBlank() && !line.stripLeading()
                                       .startsWith("#");
    }

    /// A reservation outside the system range, or backwards, would reserve nothing or reserve the user
    /// range that [SliceCodec#deterministicTag] owns.
    @Test
    void everyReservation_isAWellFormedRangeInsideTheSystemRange() throws Exception {
        var reservations = ledger();

        assertFalse(reservations.isEmpty(), LEDGER + " has no reservations");
        reservations.forEach(SystemTagsLedgerTest::assertWellFormed);
    }

    private static void assertWellFormed(Reservation reservation) {
        assertTrue(reservation.from() >= 0 && reservation.from() <= reservation.to() && reservation.to() <= SliceCodec.SYSTEM_TAG_MAX,
                   "%s must satisfy 0 <= from <= to <= %d".formatted(reservation.describe(), SliceCodec.SYSTEM_TAG_MAX));
    }

    /// Two lanes holding one number is the whole failure. Sorted by start, an overlap shows as a
    /// reservation that begins at or before the end of its predecessor.
    @Test
    void noTwoReservations_overlap() throws Exception {
        var sorted = ledger().stream()
                             .sorted(Comparator.comparingInt(Reservation::from))
                             .toList();

        IntStream.range(1, sorted.size())
                 .forEach(index -> assertNoOverlap(sorted.get(index - 1),
                                                   sorted.get(index)));
    }

    private static void assertNoOverlap(Reservation earlier, Reservation later) {
        assertTrue(later.from() > earlier.to(), "Reservations overlap: %s and %s".formatted(earlier.describe(), later.describe()));
    }

    /// The rule itself: a pin with no reservation was allocated without reserving first. The fix is a ledger
    /// line committed BEFORE the pin, not a widened range for a pin that is already written.
    @Test
    void everyPinnedTag_liesInsideAReservedRange() throws Exception {
        var reservations = ledger();

        SystemTags.TAGS.forEach((name, tag) -> assertReserved(reservations, name, tag));
    }

    private static void assertReserved(List<Reservation> reservations, String name, int tag) {
        assertTrue(reservations.stream()
                               .anyMatch(reservation -> reservation.contains(tag)),
                   "%s is pinned to %d, which no reservation in %s covers: reserve a range first, then allocate inside it".formatted(name,
                                                                                                                                      tag,
                                                                                                                                      LEDGER));
    }
}
