// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.node;

import java.security.SecureRandom;
import java.util.stream.LongStream;


/// Per-process random BOOT TOKEN (owner ruling, session 28 — terminal removal is the identity model).
///
/// Drawn once per node assembly and carried by every piece of process evidence: SWIM ANNOUNCE and
/// self-ALIVE, metric observations, cluster-sync pongs, community health and worker admission.
/// Tokens are compared by EQUALITY only, never ordered: the same token is the same process (a
/// partitioned-but-live process heals), a different token for a known NodeId is a different process
/// and is refused. Nothing is persisted, so a restarted process always carries a new token.
///
/// Type: a 63-bit positive `long` from [SecureRandom]. It rides the existing `long incarnation` wire
/// fields unchanged, stays compatible with the `incarnation < 0` validity checks at the ingress
/// sites, and reserves `0` for "no token". 63 random bits make a collision between two processes of
/// one NodeId negligible (~2^-63 per pair).
public sealed interface BootToken {
    SecureRandom RANDOM = new SecureRandom();

    /// A fresh positive, non-zero token.
    static long bootToken() {
        return LongStream.generate(RANDOM::nextLong)
                         .map(value -> value & Long.MAX_VALUE)
                         .filter(value -> value != 0L)
                         .findFirst()
                         .getAsLong();
    }

    record unused() implements BootToken {}
}
