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

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/**
 * Static utility methods for input/output.
 *
 * @author Antti Laisi
 */
public class IO {

    public static String getCString(ByteBuffer buffer, Charset charset) {
        int start = buffer.position();
        while (buffer.get() != 0) {}
        int length = buffer.position() - start - 1;
        if (length == 0) {
            return "";
        }
        byte[] bytes = new byte[length];
        buffer.position(start);
        buffer.get(bytes);
        buffer.get(); // consume null terminator
        return new String(bytes, charset);
    }

    public static void putCString(ByteBuffer buffer, String value, Charset charset) {
        if (!value.isEmpty()) {
            buffer.put(value.getBytes(charset));
        }
        buffer.put((byte) 0);
    }

}
