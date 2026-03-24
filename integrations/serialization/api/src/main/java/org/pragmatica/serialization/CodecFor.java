package org.pragmatica.serialization;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Declares that codecs are needed for external types that cannot be annotated with @Codec directly.
///
/// Use on any class in the module (typically the *Codecs registry class) to declare
/// which external types need codec support. The annotation processor:
/// 1. Generates codecs automatically for enums and records
/// 2. Suppresses compile-time "no codec" errors for listed types
/// 3. Adds listed types to the runtime validation checklist
///
/// For types that need custom serialization (e.g., InetSocketAddress), a manual codec
/// must be registered. The runtime validation catches missing implementations at startup.
///
/// Example:
/// ```java
/// @CodecFor({TimeSpan.class, InetSocketAddress.class})
/// public interface NodeCodecs { ... }
/// ```
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface CodecFor {
    /// External types that need codec support in this module.
    Class<?>[] value();
}
