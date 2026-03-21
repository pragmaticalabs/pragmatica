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

import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// SMTP client configuration.
public record SmtpConfig(String host,
                         int port,
                         SmtpTlsMode tlsMode,
                         Option<SmtpAuth> auth,
                         TimeSpan connectTimeout,
                         TimeSpan commandTimeout) {

    static final int DEFAULT_PORT = 587;
    static final SmtpTlsMode DEFAULT_TLS_MODE = SmtpTlsMode.STARTTLS;
    static final TimeSpan DEFAULT_CONNECT_TIMEOUT = timeSpan(10).seconds();
    static final TimeSpan DEFAULT_COMMAND_TIMEOUT = timeSpan(30).seconds();

    /// Create SMTP configuration with defaults: port=587, STARTTLS, no auth.
    public static SmtpConfig smtpConfig(String host) {
        return new SmtpConfig(host, DEFAULT_PORT, DEFAULT_TLS_MODE, none(), DEFAULT_CONNECT_TIMEOUT, DEFAULT_COMMAND_TIMEOUT);
    }

    /// Create SMTP configuration with host and port, using defaults for the rest.
    public static SmtpConfig smtpConfig(String host, int port) {
        return new SmtpConfig(host, port, DEFAULT_TLS_MODE, none(), DEFAULT_CONNECT_TIMEOUT, DEFAULT_COMMAND_TIMEOUT);
    }

    /// Create SMTP configuration with host, port, TLS mode, and authentication.
    public static SmtpConfig smtpConfig(String host, int port, SmtpTlsMode tlsMode, SmtpAuth auth) {
        return new SmtpConfig(host, port, tlsMode, some(auth), DEFAULT_CONNECT_TIMEOUT, DEFAULT_COMMAND_TIMEOUT);
    }

    /// Return a copy with the given TLS mode.
    public SmtpConfig withTlsMode(SmtpTlsMode tlsMode) {
        return new SmtpConfig(host, port, tlsMode, auth, connectTimeout, commandTimeout);
    }

    /// Return a copy with the given authentication credentials.
    public SmtpConfig withAuth(SmtpAuth auth) {
        return new SmtpConfig(host, port, tlsMode, some(auth), connectTimeout, commandTimeout);
    }

    /// Return a copy with the given connect timeout.
    public SmtpConfig withConnectTimeout(TimeSpan connectTimeout) {
        return new SmtpConfig(host, port, tlsMode, auth, connectTimeout, commandTimeout);
    }

    /// Return a copy with the given command timeout.
    public SmtpConfig withCommandTimeout(TimeSpan commandTimeout) {
        return new SmtpConfig(host, port, tlsMode, auth, connectTimeout, commandTimeout);
    }
}
