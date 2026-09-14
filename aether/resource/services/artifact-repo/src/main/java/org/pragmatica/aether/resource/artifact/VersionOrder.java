// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.artifact;

import org.pragmatica.aether.artifact.Version;

import java.util.Comparator;
import java.util.Locale;

/// Orders versions for `maven-metadata.xml`'s `<latest>`/`<release>` (#281): numeric
/// `major.minor.patch` first, then the qualifier as Maven's `ComparableVersion` ranks it —
/// `alpha` < `beta` < `milestone` < `rc`/`cr` < `snapshot` < no qualifier (`ga`/`final`/`release`)
/// < `sp` < any unknown qualifier (unknowns lexically among themselves). `a`/`b`/`m` alias
/// `alpha`/`beta`/`milestone` only when a number follows (`a1`); bare they are unknown, as in
/// Maven. Equal tokens order by their number (`rc1` < `rc4` < `rc10`), then lexically; a
/// `<token>-SNAPSHOT` sorts directly below its own `<token>` (`rc4-SNAPSHOT` < `rc4`).
/// Checked against `ComparableVersion` 3.9 on the rows of `VersionOrderTest`; this is NOT the full
/// algorithm (no dotted qualifier lists, no digit/letter splitting inside a token).
enum VersionOrder implements Comparator<Version> {
    INSTANCE;

    private static final int SNAPSHOT_RANK = 5;
    private static final int RELEASE_RANK = 6;
    private static final int UNKNOWN_RANK = 8;
    private static final String SNAPSHOT = "snapshot";

    static boolean isSnapshot(Version version) {
        return version.qualifier().toLowerCase(Locale.ROOT).contains(SNAPSHOT);
    }

    @Override
    public int compare(Version a, Version b) {
        var numeric = Comparator.comparingInt(Version::major)
                                .thenComparingInt(Version::minor)
                                .thenComparingInt(Version::patch)
                                .compare(a, b);

        return numeric != 0
               ? numeric
               : compareQualifiers(a.qualifier(), b.qualifier());
    }

    private static int compareQualifiers(String a, String b) {
        var qa = Qualifier.parse(a);
        var qb = Qualifier.parse(b);
        var byRank = Integer.compare(qa.rank(), qb.rank());

        if (byRank != 0) return byRank;

        var byNumber = Long.compare(qa.number(), qb.number());

        if (byNumber != 0) return byNumber;

        var byRest = qa.rest().compareTo(qb.rest());

        return byRest != 0
               ? byRest
               : Boolean.compare(qb.snapshotOf(), qa.snapshotOf());
    }

    /// `snapshotOf` marks `<token>-SNAPSHOT`: the same rank/number/rest as `<token>`, ordered below it.
    private record Qualifier(int rank, long number, String rest, boolean snapshotOf) {
        static Qualifier parse(String qualifier) {
            var lower = qualifier.toLowerCase(Locale.ROOT);

            if (lower.equals(SNAPSHOT)) return new Qualifier(SNAPSHOT_RANK, -1L, "", false);

            var snapshotOf = lower.endsWith("-" + SNAPSHOT);
            var body = snapshotOf
                       ? lower.substring(0, lower.length() - SNAPSHOT.length() - 1)
                       : lower;
            var tokenEnd = 0;

            while (tokenEnd < body.length() && Character.isLetter(body.charAt(tokenEnd))) {
                tokenEnd++;
            }

            var token = body.substring(0, tokenEnd);
            var afterToken = body.substring(tokenEnd).replaceFirst("^[-._]+", "");
            var numberEnd = 0;

            while (numberEnd < afterToken.length() && Character.isDigit(afterToken.charAt(numberEnd))) {
                numberEnd++;
            }

            var rank = rankOf(token, numberEnd > 0);

            if (rank == UNKNOWN_RANK) return new Qualifier(rank, -1L, body, snapshotOf);

            // A digit run past `long` (a 30-digit stamp) saturates rather than throws; ties there
            // fall to the lexical `rest` comparison.
            var number = numberEnd == 0
                         ? -1L
                         : numberEnd > 18
                           ? Long.MAX_VALUE
                           : Long.parseLong(afterToken.substring(0, numberEnd));
            var rest = numberEnd > 18
                       ? afterToken
                       : afterToken.substring(numberEnd);

            return new Qualifier(rank, number, rest, snapshotOf);
        }

        private static int rankOf(String token, boolean numbered) {
            return switch (token) {
                case "alpha" -> 0;
                case "a" -> numbered ? 0 : UNKNOWN_RANK;
                case "beta" -> 1;
                case "b" -> numbered ? 1 : UNKNOWN_RANK;
                case "milestone" -> 2;
                case "m" -> numbered ? 2 : UNKNOWN_RANK;
                case "rc", "cr" -> 3;
                case SNAPSHOT -> SNAPSHOT_RANK;
                case "", "ga", "final", "release" -> RELEASE_RANK;
                case "sp" -> 7;
                default -> UNKNOWN_RANK;
            };
        }
    }
}
