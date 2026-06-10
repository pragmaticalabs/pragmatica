// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.stream;

import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.lang.Option;

import java.util.regex.Matcher;
import java.util.regex.Pattern;


/// Helpers that map the JSON envelopes returned by [org.pragmatica.aether.cli.AetherCli] HTTP
/// helpers (`{"error":"HTTP <code>: <body>"}`) to spec event-stream-namespaces §8.6 exit codes.
///
/// The base CLI HTTP helpers don't expose the wire-level status code — they pre-format every
/// non-2xx as a JSON envelope. Rather than refactor every caller, the stream surface scrapes the
/// `HTTP <code>` prefix back out. Failures that don't carry that shape (network, malformed JSON)
/// bucket into [StreamExitCode#ERROR].
public sealed interface StreamHttpResponse {
    Pattern HTTP_STATUS = Pattern.compile("^HTTP\\s+(\\d{3})\\b");

    /// `null` and `""` short-circuit to absent — the CLI HTTP layer never returns either,
    /// but defensive against synthetic test input.
    static Option<Integer> extractHttpStatus(String responseJson) {
        if (responseJson == null || responseJson.isEmpty()) {
            return Option.none();
        }

        if (!OutputFormatter.isErrorResponse(responseJson)) {
            return Option.none();
        }

        var message = OutputFormatter.extractErrorMessage(responseJson);
        Matcher matcher = HTTP_STATUS.matcher(message);

        if (!matcher.find()) {
            return Option.none();
        }

        try {
            return Option.some(Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException _) {
            return Option.none();
        }
    }

    /// Map an HTTP error response to a spec §8.6 exit code. 404→4, 409→5, 410→6; any other
    /// non-2xx → generic ERROR. Successful responses are not handled here — caller invokes
    /// the table renderer instead.
    static int mapErrorExitCode(String responseJson) {
        return extractHttpStatus(responseJson).map(StreamHttpResponse::statusToExitCode)
                                .or(StreamExitCode.ERROR);
    }

    private static int statusToExitCode(int status) {
        return switch (status) {
            case 404 -> StreamExitCode.NOT_FOUND;
            case 409 -> StreamExitCode.CONFLICT;
            case 410 -> StreamExitCode.GONE;
            default -> StreamExitCode.ERROR;
        };
    }

    record unused() implements StreamHttpResponse {}
}
