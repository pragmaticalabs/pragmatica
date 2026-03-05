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

package org.pragmatica.postgres.io.backend;

import org.pragmatica.postgres.io.Decoder;
import org.pragmatica.postgres.message.backend.DataRow;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/**
 * See <a href="https://www.postgresql.org/docs/11/protocol-message-formats.html">Postgres message formats</a>
 *
 * <pre>
 * DataRow (B)
 *  Byte1('D')
 *      Identifies the message as a data row.
 *  Int32
 *      Length of message contents in bytes, including self.
 *  Int16
 *      The number of column values that follow (possibly zero).
 *  Next, the following pair of fields appear for each column:
 *  Int32
 *      The length of the column value, in bytes (this count does not include itself). Can be zero. As a special case, -1 indicates a NULL column value. No value bytes follow in the NULL case.
 *  Byten
 *      The value of the column, in the format indicated by the associated format code. n is the above length.
 * </pre>
 *
 * @author Antti Laisi
 */
public class DataRowDecoder implements Decoder<DataRow> {
    @Override
    public byte getMessageId() {
        return 'D';
    }

    @Override
    public DataRow read(ByteBuffer buffer, int contentLength, Charset encoding) {
        int columnCount = buffer.getShort() & 0xFFFF;
        int dataBytes = contentLength - 2 - (columnCount * 4);
        byte[] data = new byte[Math.max(dataBytes, 0)];
        int[] offsets = new int[columnCount];
        int[] lengths = new int[columnCount];
        int offset = 0;

        for (int i = 0; i < columnCount; i++) {
            int length = buffer.getInt();
            offsets[i] = offset;
            lengths[i] = length;
            if (length != -1) {
                buffer.get(data, offset, length);
                offset += length;
            }
        }
        return new DataRow(data, offsets, lengths);
    }

}
