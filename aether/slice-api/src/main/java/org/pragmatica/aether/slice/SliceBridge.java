package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;


/// Bridge interface for Node-Slice communication across classloader boundaries.
///
/// This interface defines the contract between the Node (Application ClassLoader)
/// and Slices (isolated SliceClassLoader hierarchy). It uses byte arrays for
/// serialized data to maintain complete classloader isolation.
///
/// **ClassLoader Hierarchy:**
/// ```
/// Bootstrap (JDK)
///     ^
/// Application (Node code)
///     |
///     +-- Node uses its own framework copy
///     |
/// FrameworkClassLoader (pragmatica-lite, slice-api)
///     ^
/// SharedLibraryClassLoader ([shared] deps)
///     ^
/// SliceClassLoader (slice JAR)
/// ```
///
/// The SliceBridge is implemented by DefaultSliceBridge in the slice module,
/// loaded via FrameworkClassLoader. This allows the Node to communicate with
/// slices without sharing classes across classloader boundaries.
///
/// **Wire Format:**
///
///   - Input/output bytes use SliceCodec serialization
///   - Serialization/deserialization happens within the slice's classloader
///   - Only primitive byte arrays cross the boundary
///
///
/// @see Slice
public interface SliceBridge {
    Promise<byte[]> invoke(String methodName, byte[] input);
    Promise<Unit> start();
    Promise<Unit> stop();

    default Promise<byte[]> encode(Object input) {
        return BridgeError.ENCODE_NOT_SUPPORTED.promise();
    }

    default Promise<Object> decode(byte[] bytes) {
        return BridgeError.DECODE_NOT_SUPPORTED.promise();
    }

    ClassLoader classLoader();
    List<String> methodNames();

    enum BridgeError implements Cause {
        ENCODE_NOT_SUPPORTED("Encode not supported by this bridge"),
        DECODE_NOT_SUPPORTED("Decode not supported by this bridge");
        private final String message;
        BridgeError(String message) {
            this.message = message;
        }
        @Override public String message() {
            return message;
        }
    }
}
