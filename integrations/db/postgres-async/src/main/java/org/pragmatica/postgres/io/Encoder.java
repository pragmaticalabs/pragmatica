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

package org.pragmatica.postgres.io;

import org.pragmatica.postgres.message.Message;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/**
 * Encoder writes messages to byte buffer.
 *
 * @author Antti Laisi
 */
public interface Encoder<T extends Message> {

    /**
     * @return Message class
     */
    Class<T> getMessageType();

    /**
     * @param msg Protocol message
     * @param buffer Target buffer
     */
    void write(T msg, ByteBuffer buffer, Charset encoding);

    /**
     * Estimate the buffer size needed to encode the message.
     * Default returns 256 bytes. Encoders should override for accuracy.
     */
    default int estimateSize(T msg, Charset encoding) {
        return 256;
    }

}
