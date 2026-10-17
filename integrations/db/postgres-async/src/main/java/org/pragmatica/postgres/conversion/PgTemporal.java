package org.pragmatica.postgres.conversion;

import java.time.Instant;
import java.time.LocalDate;

import org.pragmatica.postgres.net.SqlException;


/// Binary `date` / `timestamp` / `timestamptz` values to Java temporals, in one place.
///
/// PostgreSQL encodes `infinity` and `-infinity` as the extreme integers (`int32` for `date`, `int64` for the
/// timestamps). Decoded as ordinary offsets they become dates millions of years away that look valid, so they are refused
/// by name instead. The epoch shift is applied in seconds so the whole valid PostgreSQL range decodes without overflow.
final class PgTemporal {
    private static final LocalDate PG_EPOCH = LocalDate.of(2000, 1, 1);
    /// Seconds from 1970-01-01 to 2000-01-01. Added to SECONDS, not to microseconds: the micros sum overflows `long` for the
    /// latest valid PostgreSQL timestamps (year 294276), which then wrapped to a wrong instant.
    private static final long PG_EPOCH_SECONDS = 946_684_800L;

    private PgTemporal() {}

    @SuppressWarnings("JBCT-EX-01")
    static LocalDate date(int days) {
        if (days == Integer.MAX_VALUE || days == Integer.MIN_VALUE) {
            throw new SqlException("PostgreSQL date '" + infinityName(days == Integer.MAX_VALUE)
                                  + "' has no LocalDate representation");
        }

        return PG_EPOCH.plusDays(days);
    }

    @SuppressWarnings("JBCT-EX-01")
    static Instant timestamp(long pgMicros) {
        if (pgMicros == Long.MAX_VALUE || pgMicros == Long.MIN_VALUE) {
            throw new SqlException("PostgreSQL timestamp '" + infinityName(pgMicros == Long.MAX_VALUE)
                                  + "' has no Instant representation");
        }

        return Instant.ofEpochSecond(Math.floorDiv(pgMicros, 1_000_000L) + PG_EPOCH_SECONDS,
                                     Math.floorMod(pgMicros, 1_000_000L) * 1000L);
    }

    private static String infinityName(boolean positive) {
        return positive
               ? "infinity"
               : "-infinity";
    }
}
