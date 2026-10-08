package org.pragmatica.postgres.conversion;

import java.time.Instant;
import java.time.LocalDate;

import org.pragmatica.postgres.net.SqlException;


/// Binary `date` / `timestamp` / `timestamptz` values to Java temporals, in one place.
///
/// PostgreSQL encodes `infinity` and `-infinity` as the extreme integers (`int32` for `date`, `int64` for the
/// timestamps). Decoded as ordinary offsets they become dates millions of years away that look valid, so they are refused
/// by name instead. Arithmetic is exact: an offset that does not fit a Java `Instant` fails rather than wrapping.
final class PgTemporal {
    private static final LocalDate PG_EPOCH = LocalDate.of(2000, 1, 1);

    private PgTemporal() {}

    static LocalDate date(int days) {
        if (days == Integer.MAX_VALUE || days == Integer.MIN_VALUE) {
            throw new SqlException("PostgreSQL date '" + infinityName(days == Integer.MAX_VALUE) + "' has no LocalDate representation");
        }

        return PG_EPOCH.plusDays(days);
    }

    static Instant timestamp(long pgMicros) {
        if (pgMicros == Long.MAX_VALUE || pgMicros == Long.MIN_VALUE) {
            throw new SqlException("PostgreSQL timestamp '" + infinityName(pgMicros == Long.MAX_VALUE) + "' has no Instant representation");
        }

        try {
            var epochMicros = Math.addExact(pgMicros, BinaryCodec.PG_EPOCH_MICROS_OFFSET);

            return Instant.ofEpochSecond(Math.floorDiv(epochMicros, 1_000_000L), Math.floorMod(epochMicros, 1_000_000L) * 1000L);
        } catch (ArithmeticException | java.time.DateTimeException outOfRange) {
            throw new SqlException("PostgreSQL timestamp " + pgMicros + " is outside the range of Instant");
        }
    }

    private static String infinityName(boolean positive) {
        return positive
               ? "infinity"
               : "-infinity";
    }
}
