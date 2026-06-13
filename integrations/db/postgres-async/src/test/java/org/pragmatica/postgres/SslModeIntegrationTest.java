/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.pragmatica.postgres;

import org.pragmatica.postgres.net.SqlException;
import org.pragmatica.postgres.net.SslConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;

import static org.pragmatica.postgres.DatabaseExtension.block;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Integration tests for SslConfig modes against a stock testcontainers Postgres
/// (which does not have SSL enabled). These cover:
/// - DISABLE works (no TLS attempted)
/// - PREFER falls back to plain when server doesn't support TLS
/// - REQUIRE fails fast when server doesn't support TLS
/// - VERIFY_CA / VERIFY_FULL: factory accepts root cert path; runtime fails if no SSL.
///
/// Tests requiring SSL-enabled PG (channel binding round-trip, hostname verification
/// against a real server cert) are tracked as follow-up — they require either a custom
/// container with generated certs or a libpq sidecar, which is out of scope here.
@Tag("Slow")
public class SslModeIntegrationTest {

    @RegisterExtension
    static final DatabaseExtension dbr = DatabaseExtension.defaultConfiguration();

    @Test
    public void disabledModeConnects() {
        var pool = dbr.builder.ssl(SslConfig.disabled()).pool();
        try {
            var rs = block(pool.completeQuery("SELECT 1"));
            assertEquals(1L, (long) rs.index(0).getInt(0));
        } finally {
            block(pool.close());
        }
    }

    @Test
    public void preferModeFallsBackToPlain() {
        // Stock testcontainers PG doesn't have SSL enabled → server replies 'N' to SSLRequest.
        // PREFER should silently fall back to plain text and connect successfully.
        var pool = dbr.builder.ssl(SslConfig.prefer()).pool();
        try {
            var rs = block(pool.completeQuery("SELECT 1"));
            assertEquals(1L, (long) rs.index(0).getInt(0));
        } finally {
            block(pool.close());
        }
    }

    @Test
    public void requireModeFailsWhenServerDoesNotSupportSsl() {
        var pool = dbr.builder.ssl(SslConfig.require()).pool();
        try {
            assertThrows(SqlException.class, () -> block(pool.completeQuery("SELECT 1")));
        } finally {
            block(pool.close());
        }
    }

    @Test
    public void verifyFullFactoryAcceptsRootCert() {
        // Factory only validates that a root cert path is provided. Actual TLS handshake
        // would fail against this PG without SSL — which is the expected REQUIRE-class behavior.
        var pool = dbr.builder.ssl(SslConfig.verifyFull(Path.of("/nonexistent/root.pem"))).pool();
        try {
            assertThrows(SqlException.class, () -> block(pool.completeQuery("SELECT 1")));
        } finally {
            block(pool.close());
        }
    }
}
