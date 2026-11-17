package conduit.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;


/// The one clock and the one wire format (B-48): truncated to milliseconds BEFORE storage, so the value returned at
/// write time is the value read back later, and rendered as ISO-8601 UTC with exactly three fractional digits.
public sealed interface Timestamps {
    DateTimeFormatter WIRE = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    static String wire(Instant instant) {
        return WIRE.format(instant);
    }

    record unused() implements Timestamps {}
}
