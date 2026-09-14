// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.artifact;

import org.pragmatica.aether.artifact.Version;

import java.util.Comparator;
import java.util.Locale;

/// Orders versions for `maven-metadata.xml`'s `<latest>`/`<release>` (#281): numeric
/// `major.minor.patch` first, then the qualifier in Maven's canonical order of the known
/// tokens — `alpha`/`a` < `beta`/`b` < `milestone`/`m` < `rc`/`cr` < `snapshot` < no qualifier
/// (`ga`/`final`/`release`) < `sp` — with an unknown qualifier placed between `rc` and `snapshot`.
/// Equal tokens order by their trailing number (`rc1` < `rc4` < `rc10`), then lexically. This is
/// NOT Maven's full `ComparableVersion` algorithm (no dotted qualifier lists, no `-1` build numbers).
enum VersionOrder implements Comparator<Version> {
    INSTANCE;

    private static final int UNKNOWN_RANK = 4;
    private static final int SNAPSHOT_RANK = 5;
    private static final int RELEASE_RANK = 6;

    static boolean isSnapshot(Version version) {
        return version.qualifier().toLowerCase(Locale.ROOT).contains("snapshot");
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

        return byNumber != 0
               ? byNumber
               : qa.rest().compareTo(qb.rest());
    }

    private record Qualifier(int rank, long number, String rest) {
        static Qualifier parse(String qualifier) {
            var lower = qualifier.toLowerCase(Locale.ROOT);
            var tokenEnd = 0;

            while (tokenEnd < lower.length() && Character.isLetter(lower.charAt(tokenEnd))) {
                tokenEnd++;
            }

            var token = lower.substring(0, tokenEnd);
            var rest = lower.substring(tokenEnd).replaceFirst("^[-._]+", "");
            var numberEnd = 0;

            while (numberEnd < rest.length() && Character.isDigit(rest.charAt(numberEnd))) {
                numberEnd++;
            }

            // A digit run past `long` (a 30-digit stamp) saturates rather than throws; ties there
            // fall to the lexical `rest` comparison.
            var number = numberEnd == 0
                         ? -1L
                         : numberEnd > 18
                           ? Long.MAX_VALUE
                           : Long.parseLong(rest.substring(0, numberEnd));

            var rankedRest = numberEnd > 18
                             ? rest
                             : rest.substring(numberEnd);

            return new Qualifier(rankOf(token, lower), number, rankedRest);
        }

        private static int rankOf(String token, String whole) {
            if (whole.contains("snapshot")) return SNAPSHOT_RANK;

            return switch (token) {
                case "alpha", "a" -> 0;
                case "beta", "b" -> 1;
                case "milestone", "m" -> 2;
                case "rc", "cr" -> 3;
                case "", "ga", "final", "release" -> RELEASE_RANK;
                case "sp" -> 7;
                default -> UNKNOWN_RANK;
            };
        }
    }
}
