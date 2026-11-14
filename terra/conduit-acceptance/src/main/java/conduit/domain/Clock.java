package conduit.domain;

import java.time.Instant;


/// The source of "now" for writes and token minting; tests pin it.
@FunctionalInterface
public interface Clock {
    Instant now();

    static Clock clock() {
        return Timestamps::now;
    }
}
