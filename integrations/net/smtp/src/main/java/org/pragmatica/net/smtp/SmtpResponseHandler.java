/*
 *  Copyright (c) 2022-2025 Sergiy Yevtushenko.
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

package org.pragmatica.net.smtp;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Netty handler that parses SMTP multi-line responses and delegates to SmtpSession.
///
/// SMTP responses consist of a 3-digit code followed by either a dash (continuation)
/// or a space (final line). Multi-line responses accumulate text until the final line.
class SmtpResponseHandler extends SimpleChannelInboundHandler<String> {
    private static final Logger log = LoggerFactory.getLogger(SmtpResponseHandler.class);
    private static final int MIN_RESPONSE_LENGTH = 3;
    private static final int CODE_SEPARATOR_INDEX = 3;

    private final SmtpSession session;
    private final StringBuilder accumulated = new StringBuilder();
    private int currentCode = -1;

    SmtpResponseHandler(SmtpSession session) {
        this.session = session;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String line) {
        log.trace("SMTP <<< {}", line);

        if (line.length() < MIN_RESPONSE_LENGTH) {
            session.onException(new IllegalStateException("Malformed SMTP response: " + line));
            return;
        }

        var parsedCode = parseResponseCode(line);
        if (parsedCode < 0) {
            session.onException(new IllegalStateException("Invalid SMTP response code: " + line));
            return;
        }

        currentCode = parsedCode;
        var text = extractText(line);

        if (isContinuation(line)) {
            accumulated.append(text).append("\n");
            return;
        }

        // Final line of response
        accumulated.append(text);
        var fullText = accumulated.toString();
        accumulated.setLength(0);
        session.handleResponse(currentCode, fullText);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        session.onDisconnect();
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("SMTP channel exception", cause);
        session.onException(cause);
        ctx.close();
    }

    private static int parseResponseCode(String line) {
        var codeStr = line.substring(0, MIN_RESPONSE_LENGTH);
        return parseIntSafe(codeStr);
    }

    private static String extractText(String line) {
        if (line.length() > CODE_SEPARATOR_INDEX + 1) {
            return line.substring(CODE_SEPARATOR_INDEX + 1);
        }
        return "";
    }

    private static boolean isContinuation(String line) {
        return line.length() > CODE_SEPARATOR_INDEX && line.charAt(CODE_SEPARATOR_INDEX) == '-';
    }

    private static int parseIntSafe(String value) {
        var result = 0;
        for (int i = 0; i < value.length(); i++) {
            var ch = value.charAt(i);
            if (ch < '0' || ch > '9') {
                return -1;
            }
            result = result * 10 + (ch - '0');
        }
        return result;
    }
}
