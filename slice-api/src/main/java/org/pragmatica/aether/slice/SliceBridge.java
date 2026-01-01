package org.pragmatica.aether.slice;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

/**
 * Bridge interface for Node-Slice communication across classloader boundaries.
 * <p>
 * This interface defines the contract between the Node (Application ClassLoader)
 * and Slices (isolated SliceClassLoader hierarchy). It uses byte arrays for
 * serialized data to maintain complete classloader isolation.
 * <p>
 * <b>ClassLoader Hierarchy:</b>
 * <pre>
 * Bootstrap (JDK)
 *     ↑
 * Application (Node code)
 *     │
 *     ├── Node uses its own framework copy
 *     │
 * FrameworkClassLoader (pragmatica-lite, slice-api)
 *     ↑
 * SharedLibraryClassLoader ([shared] deps)
 *     ↑
 * SliceClassLoader (slice JAR)
 * </pre>
 * <p>
 * The SliceBridge is implemented by SliceBridgeImpl in the slice module,
 * loaded via FrameworkClassLoader. This allows the Node to communicate with
 * slices without sharing classes across classloader boundaries.
 * <p>
 * <b>Wire Format:</b>
 * <ul>
 *   <li>Input/output bytes use Fury serialization</li>
 *   <li>Serialization/deserialization happens within the slice's classloader</li>
 *   <li>Only primitive byte arrays cross the boundary</li>
 * </ul>
 *
 * @see Slice
 */
public interface SliceBridge {
    /**
     * Invoke a method on the slice with serialized input.
     *
     * @param methodName Name of the method to invoke
     * @param input      Serialized input parameter (Fury format)
     * @return Promise resolving to serialized response (Fury format)
     */
    Promise<byte[] > invoke(String methodName, byte[] input);

    /**
     * Start the slice lifecycle.
     *
     * @return Promise resolving when slice is started
     */
    Promise<Unit> start();

    /**
     * Stop the slice lifecycle.
     *
     * @return Promise resolving when slice is stopped
     */
    Promise<Unit> stop();

    /**
     * Get the list of method names exposed by this slice.
     *
     * @return List of method names
     */
    List<String> methodNames();
}
