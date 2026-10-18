// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.management.route;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.http.HttpMethod;


/// Maven repository paths (`/repository/**`) are read by their coordinate, never by segment count (#1986).
///
/// A group id spans one or more segments (`org`, `org/example`, ...), so the number of segments in a repository
/// path says nothing about which operation it is. The routes declare a fixed number of parameters, so before
/// matching, the group segments are folded into one segment (`org/example`) and the remaining coordinate tail
/// keeps its fixed shape: artifact files and DELETE read `<group>/<artifactId>/<version>[/<file>]`, GET
/// `/repository/info/` reads `<group>/<artifactId>/<version>`, and `maven-metadata.xml*` at artifact level reads
/// `<group>/<artifactId>/<file>`. Anything too short to carry a group is left untouched and matches nothing.
///
/// A metadata PUT/POST has no version, but its only routes are `ARTIFACT_PUT`/`ARTIFACT_POST`, so the version slot
/// is filled with an empty value: the request must resolve to the same route, and the same forwarding target, as
/// the artifact files pushed beside it.
final class MavenRepositoryPath {
    private static final String REPOSITORY = "repository";
    private static final String INFO = "info";
    private static final String ARTIFACTS = "artifacts";
    private static final String METADATA_FILE = "maven-metadata.xml";
    private static final int FILE_TAIL = 3;
    private static final int METADATA_TAIL = 2;
    private static final int VERSION_TAIL = 2;

    private MavenRepositoryPath() {}

    static List<String> foldGroup(HttpMethod method, List<String> segments) {
        if (segments.size() < 3 || !REPOSITORY.equals(segments.getFirst()) || ARTIFACTS.equals(segments.get(1))) {
            return segments;
        }

        if (method == HttpMethod.GET && INFO.equals(segments.get(1))) {
            return fold(segments, 2, VERSION_TAIL, false);
        }

        return switch (method) {
            case DELETE -> fold(segments, 1, VERSION_TAIL, false);
            case GET -> fold(segments, 1, fileTail(segments), false);
            case PUT, POST -> fold(segments, 1, fileTail(segments), true);
            default -> segments;
        };
    }

    private static int fileTail(List<String> segments) {
        return isArtifactLevelMetadata(segments)
               ? METADATA_TAIL
               : FILE_TAIL;
    }

    private static boolean isArtifactLevelMetadata(List<String> segments) {
        var size = segments.size();

        return segments.get(size - 1)
                       .startsWith(METADATA_FILE) && !startsWithDigit(segments.get(size - 2));
    }

    private static boolean startsWithDigit(String segment) {
        return ! segment.isEmpty() && Character.isDigit(segment.charAt(0));
    }

    private static List<String> fold(List<String> segments, int groupStart, int tail, boolean padVersion) {
        var groupEnd = segments.size() - tail;

        if (groupEnd - groupStart < 1) {
            return segments;
        }

        var folded = new ArrayList<>(segments.subList(0, groupStart));

        folded.add(String.join("/", segments.subList(groupStart, groupEnd)));
        folded.addAll(segments.subList(groupEnd, segments.size()));
        if (padVersion && tail == METADATA_TAIL) {
            folded.add(folded.size() - 1, "");
        }

        return folded;
    }
}
