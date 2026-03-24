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

package org.pragmatica.consensus.net.quic;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class QuicTlsProviderTest {

    @Nested
    class ServerContext {
        @Test
        void serverContext_noConfig_autoGeneratesSelfSigned() {
            var result = QuicTlsProvider.serverContext(Option.empty());

            result.onFailure(_ -> fail("Expected successful self-signed context generation"))
                  .onSuccess(ctx -> assertThat(ctx).isNotNull());
        }

        @Test
        void serverContext_withSelfSignedConfig_usesConfig() {
            var tlsConfig = TlsConfig.selfSignedServer();
            var result = QuicTlsProvider.serverContext(Option.some(tlsConfig));

            result.onFailure(_ -> fail("Expected successful context creation from config"))
                  .onSuccess(ctx -> assertThat(ctx).isNotNull());
        }
    }
}
