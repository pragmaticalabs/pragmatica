/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
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

package org.pragmatica.lang.vo;

import java.net.URI;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Network;

import static org.pragmatica.lang.utils.Causes.cause;

/// Validated URL with accessible components. Requires scheme and host.
public record Url(URI uri) {
    private static final Cause MISSING_SCHEME = cause("URL must have a scheme");
    private static final Cause MISSING_HOST = cause("URL must have a host");

    /// Parse and validate a URL string.
    ///
    /// @param raw the raw URL string to parse
    /// @return Result containing validated Url or parsing failure
    public static Result<Url> url(String raw) {
        return Network.parseURI(raw)
                      .filter(MISSING_SCHEME, u -> u.getScheme() != null)
                      .filter(MISSING_HOST, u -> u.getHost() != null)
                      .map(Url::new);
    }

    /// URL scheme (e.g. "https", "ftp").
    public String scheme() {
        return uri.getScheme();
    }

    /// URL host.
    public String host() {
        return uri.getHost();
    }

    /// URL port, or -1 if not specified.
    public int port() {
        return uri.getPort();
    }

    /// URL path, or null if not present.
    public String path() {
        return uri.getPath();
    }

    /// URL query string, or null if not present.
    public String query() {
        return uri.getQuery();
    }

    @Override
    public String toString() {
        return uri.toString();
    }
}
